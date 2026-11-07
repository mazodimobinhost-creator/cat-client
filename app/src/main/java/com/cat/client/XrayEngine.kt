// Xray engine process (beta223): runs libxray.so (official Xray-core) and protects its sockets from the VPN.
package com.cat.client

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Runs the official Xray-core (XTLS/Xray-core, MPL-2.0 — built unmodified from source in CI by
 * `scripts/build-xray-core.sh` together with Cat's tiny runner `scripts/xray-runner/main.go`) as a
 * child process, packaged as `lib/<abi>/libxray.so`.
 *
 * VPN loop: Xray's outbound sockets would otherwise be routed back into our own TUN. The runner
 * registers an Xray dialer controller that passes every new socket fd over a private unix socket
 * (in the app's no-backup dir, same UID only) to [XrayProtectServer], which calls
 * `VpnService.protect(fd)` and answers with one byte. This is the same pattern other Android
 * proxy clients use for helper processes.
 *
 * The runner also exits when its stdin closes, so a killed app process cannot leave Xray running.
 */
internal object XrayEngine {
    const val BINARY_NAME = "libxray.so"
    private const val READY_TIMEOUT_MS = 10_000L
    private const val LOG_LINES = 80
    private const val PID_FILE = "xray-engine.pids"
    private const val PREFS = "cat_xray_engine"
    private const val KEY_ENABLED = "enabled"

    sealed class StartResult {
        data class Ready(val ports: List<Int>) : StartResult()
        data class Failed(val reason: String, val log: List<String>) : StartResult()
    }

    private class Running(val process: Process, val protectServer: XrayProtectServer?, val ports: List<Int>)

    @Volatile
    private var running: Running? = null
    private val log = ArrayDeque<String>()

    fun binary(context: Context): File? =
        runCatching { File(context.applicationInfo.nativeLibraryDir, BINARY_NAME) }
            .getOrNull()
            ?.takeIf { it.isFile && it.length() > 0L }

    fun isInstalled(context: Context): Boolean = binary(context) != null

    /** The user can switch the engine off (then F&F proxies go back to the ByeByeDPI path). Default on. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isRunning(): Boolean = running?.process?.isAliveCompat() == true

    fun lastLog(): List<String> = synchronized(log) { log.toList() }

    /** [count] distinct free loopback ports (held open together so they cannot repeat). */
    fun allocatePorts(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val sockets = mutableListOf<ServerSocket>()
        return try {
            repeat(count) {
                sockets += ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            }
            sockets.map { it.localPort }
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Starts Xray with [configJson] and waits until every inbound in [ports] accepts connections.
     * Blocking — call from an IO thread. [protect] is `VpnService.protect(int)`.
     */
    fun start(context: Context, configJson: String, ports: List<Int>, protect: (Int) -> Boolean): StartResult {
        stop(context)
        val bin = binary(context) ?: return StartResult.Failed("libxray.so is not packaged in this build", emptyList())
        killOrphans(context)
        val dir = File(context.noBackupFilesDir, "xray").apply { mkdirs() }
        val configFile = File(dir, "config.json")
        runCatching {
            configFile.writeText(configJson)
            configFile.setReadable(false, false)
            configFile.setReadable(true, true)
        }.onFailure { return StartResult.Failed("cannot write the Xray config: ${it.message}", emptyList()) }

        val socketFile = File(dir, "protect.sock")
        val protectServer = runCatching { XrayProtectServer(socketFile, protect).also { it.start() } }
            .onFailure { DiagnosticLogger.warn(context, "xray.protect.unavailable", error = it) }
            .getOrNull()
        val args = mutableListOf(bin.absolutePath, "-config", configFile.absolutePath, "-watch-stdin")
        if (protectServer != null) args += listOf("-protect", socketFile.absolutePath)

        synchronized(log) { log.clear() }
        val process = runCatching {
            ProcessBuilder(args)
                .directory(dir)
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment()["HOME"] = context.filesDir.absolutePath
                    builder.environment()["XRAY_LOCATION_ASSET"] = dir.absolutePath
                }
                .start()
        }.getOrElse { error ->
            protectServer?.stop()
            return StartResult.Failed("cannot launch Xray: ${error.message}", emptyList())
        }
        running = Running(process, protectServer, ports)
        rememberPid(context, process)
        Thread({
            runCatching {
                var logged = 0
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val clean = line.trim()
                        if (clean.isEmpty()) return@forEach
                        synchronized(log) {
                            log.addLast(clean.take(240))
                            while (log.size > LOG_LINES) log.removeFirst()
                        }
                        if (logged++ < 60) DiagnosticLogger.info(context, "xray.engine", clean.take(240))
                    }
                }
            }
        }, "xray-log").apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAliveCompat()) break
            if (ports.all(::isListening)) {
                DiagnosticLogger.info(
                    context,
                    "xray.engine.ready",
                    "inbounds=${ports.size} protect=${protectServer != null}",
                )
                return StartResult.Ready(ports)
            }
            Thread.sleep(100)
        }
        val tail = lastLog()
        stop(context)
        return StartResult.Failed(
            if (tail.isEmpty()) "Xray did not open its local ports" else tail.last(),
            tail,
        )
    }

    fun stop(context: Context? = null) {
        val entry = running ?: return
        running = null
        runCatching { entry.process.outputStream.close() }
        runCatching { entry.process.destroy() }
        runCatching { Thread.sleep(80) }
        if (entry.process.isAliveCompat()) runCatching { entry.process.destroyForcibly() }
        entry.protectServer?.stop()
        context?.let { runCatching { File(it.filesDir, PID_FILE).delete() } }
    }

    private fun isListening(port: Int): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 200); true }
    }.getOrDefault(false)

    private fun rememberPid(context: Context, process: Process) {
        val pid = pidOf(process) ?: return
        runCatching { File(context.filesDir, PID_FILE).writeText("$pid\n") }
    }

    /** Kills an Xray left over from a previous app process (same UID, so allowed). */
    private fun killOrphans(context: Context) {
        val file = File(context.filesDir, PID_FILE)
        if (!file.isFile) return
        file.readLines().mapNotNull { it.trim().toIntOrNull() }.forEach { pid ->
            val cmd = runCatching { File("/proc/$pid/cmdline").readText() }.getOrDefault("")
            if (cmd.contains(BINARY_NAME)) runCatching { android.os.Process.killProcess(pid) }
        }
        runCatching { file.delete() }
    }

    private fun pidOf(process: Process): Int? = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(process)
    }.getOrNull()

    private fun Process.isAliveCompat(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)
}

/**
 * Receives socket fds from the Xray runner (SCM_RIGHTS over a filesystem unix socket in the app's
 * private dir), protects them from the VPN and answers `1` (protected) or `0` (refused).
 */
internal class XrayProtectServer(
    private val socketFile: File,
    private val protect: (Int) -> Boolean,
) {
    @Volatile
    private var active = false
    private var bound: LocalSocket? = null
    private var server: LocalServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        socketFile.delete()
        val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
        socket.bind(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        val listening = LocalServerSocket(socket.fileDescriptor)
        bound = socket
        server = listening
        active = true
        thread = Thread({ acceptLoop(listening) }, "xray-protect").apply { isDaemon = true; start() }
    }

    private fun acceptLoop(listening: LocalServerSocket) {
        val myUid = android.os.Process.myUid()
        while (active) {
            val client = runCatching { listening.accept() }.getOrNull() ?: if (active) continue else return
            runCatching { handle(client, myUid) }
            runCatching { client.close() }
        }
    }

    private fun handle(client: LocalSocket, myUid: Int) {
        client.soTimeout = 2_000
        if (client.peerCredentials.uid != myUid) return
        if (client.inputStream.read() < 0) return
        val fds: Array<FileDescriptor>? = client.ancillaryFileDescriptors
        val protected = fds?.firstOrNull()?.let(::protectFd) ?: false
        fds?.forEach { fd -> runCatching { Os.close(fd) } }
        client.outputStream.write(if (protected) 1 else 0)
        client.outputStream.flush()
    }

    private fun protectFd(fd: FileDescriptor): Boolean = runCatching {
        ParcelFileDescriptor.dup(fd).use { copy -> protect(copy.fd) }
    }.getOrDefault(false)

    fun stop() {
        if (!active) return
        active = false
        // accept() does not return on close() on every Android version; wake it with a dummy client.
        runCatching {
            LocalSocket().use { it.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)) }
        }
        runCatching { server?.close() }
        runCatching { bound?.close() }
        server = null
        bound = null
        thread = null
        runCatching { socketFile.delete() }
    }
}

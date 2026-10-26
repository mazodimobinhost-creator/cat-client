package com.cat.client

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Real DNS-tunnel engine.
 *
 * Earlier builds "connected" DNS-tunnel configs (dnstt / NoizDNS / VayDNS / SlipNet) by opening a
 * local SOCKS5 port that silently dialled the destination **directly** — the traffic was never
 * tunnelled, so blocked sites did not open. This engine runs the official open-source SlipNet CLI
 * (`anonvector/SlipNet/cli`, AGPL-3.0, built from source in CI and packaged as
 * `lib/<abi>/libslipnet.so`), which implements the real Noise/KCP-over-DNS protocols of
 * dnstt, NoizDNS and VayDNS (plus SlipNet SSH / SOCKS5 / VLESS / DoH profiles) and exposes a
 * local SOCKS5 proxy that mihomo uses as the outbound.
 *
 * Tunnel types without an open, implementable client (CottenDNS, StormDNS, MasterDNS, Soar,
 * QS/QQ/Spoof tunnels, S3 bypass, Phantun without root, …) are reported as unsupported instead of
 * being faked.
 */
internal object SlipNetCliEngine {
    const val BINARY_NAME = "libslipnet.so"
    private const val READY_TIMEOUT_MS = 20_000L
    private const val LOG_LINES = 80
    private const val PID_FILE = "slipnet-engine.pids"

    data class Running(val key: String, val port: Int, val process: Process, val startedAt: Long)

    sealed class StartResult {
        data class Ready(val port: Int) : StartResult()
        data class Unsupported(val reasonFa: String, val reasonEn: String) : StartResult()
        data class Failed(val reasonFa: String, val reasonEn: String, val log: List<String>) : StartResult()
    }

    private val running = ConcurrentHashMap<String, Running>()
    private val logs = ConcurrentHashMap<String, ArrayDeque<String>>()

    @Volatile
    var appContext: Context? = null
        private set

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun binary(context: Context): File? =
        runCatching { File(context.applicationInfo.nativeLibraryDir, BINARY_NAME) }
            .getOrNull()
            ?.takeIf { it.isFile && it.length() > 0L }

    fun isInstalled(context: Context): Boolean = binary(context) != null

    fun isAnyRunning(): Boolean = running.values.any { it.process.isAliveCompat() }

    fun lastLog(key: String): List<String> = logs[key]?.toList().orEmpty()

    fun keyOf(parsed: WhiteDnsSmartScanner.WhiteDnsParsedTunnelUri): String =
        "${parsed.scheme}|${parsed.resolverIp}:${parsed.resolverPort}|${parsed.tunnelDomain}|${parsed.publicKeyHex.take(16)}"

    /** Whether this tunnel family has a real, open-source client that this engine can run. */
    fun supports(parsed: WhiteDnsSmartScanner.WhiteDnsParsedTunnelUri): Boolean = cliUriFor(parsed) != null

    /** Human explanation for tunnel types that cannot be run for real. */
    fun unsupportedReason(parsed: WhiteDnsSmartScanner.WhiteDnsParsedTunnelUri): Pair<String, String> = when {
        parsed.scheme == "slipnet-enc" || parsed.scheme == "slipnet-bundle-enc" ->
            "کانفیگ رمزگذاری‌شدهٔ SlipNet فقط با کلید خصوصی خود اپ SlipNet باز می‌شود؛ نسخهٔ معمولی slipnet:// را از سازنده بگیرید." to
                "Encrypted SlipNet configs can only be opened with SlipNet's private key; ask for a plain slipnet:// export."
        parsed.engine == WhiteDnsSmartScanner.WhiteDnsTunnelEngine.SLIPSTREAM_QUIC ->
            "Slipstream (QUIC روی DNS) کلاینت اندرویدی متن‌باز قابل اجرا ندارد." to
                "Slipstream (QUIC-over-DNS) has no runnable open-source Android client."
        parsed.publicKeyHex.isBlank() &&
            parsed.engine in setOf(
                WhiteDnsSmartScanner.WhiteDnsTunnelEngine.DNSTT_CLASSIC,
                WhiteDnsSmartScanner.WhiteDnsTunnelEngine.DNST_STANDARD,
                WhiteDnsSmartScanner.WhiteDnsTunnelEngine.NOIZ_DNS,
                WhiteDnsSmartScanner.WhiteDnsTunnelEngine.VAY_DNS,
            ) ->
            "کلید عمومی سرور (pubkey) در این کانفیگ نیست؛ بدون آن تانل DNS ساخته نمی‌شود." to
                "The server public key (pubkey) is missing; a DNS tunnel cannot be built without it."
        else ->
            "برای «${parsed.engine.titleFa}» کلاینت واقعی و متن‌باز وجود ندارد؛ این کانفیگ قبلاً فقط وانمود به اتصال می‌کرد و مستقیم وصل می‌شد." to
                "\"${parsed.engine.titleEn}\" has no real open-source client; earlier builds only pretended and connected directly."
    }

    /**
     * Builds the `slipnet://` URI understood by the SlipNet CLI. Native slipnet:// profiles are
     * passed through; dnstt / dnst / NoizDNS / VayDNS URIs are converted into an equivalent
     * minimal v18 pipe profile (fields 0..11).
     */
    fun cliUriFor(parsed: WhiteDnsSmartScanner.WhiteDnsParsedTunnelUri): String? {
        if (parsed.scheme == "slipnet") {
            val payload = parsed.rawUri.substringAfter("://").substringBefore('#').substringBefore('?').trim()
            val decoded = runCatching {
                String(Base64.decode(payload, Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull() ?: runCatching {
                String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull() ?: return null
            val fields = decoded.split('|')
            if (fields.size < 12) return null
            val type = fields[1].trim().lowercase()
            if (type !in CLI_TUNNEL_TYPES) return null
            val needsKey = type.startsWith("dnstt") || type.startsWith("sayedns") || type.startsWith("vaydns")
            if (needsKey && fields[11].isBlank()) return null
            // Re-encode with standard Base64 (the CLI rejects URL-safe alphabets).
            return "slipnet://" + Base64.encodeToString(decoded.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        val type = when (parsed.engine) {
            WhiteDnsSmartScanner.WhiteDnsTunnelEngine.DNSTT_CLASSIC,
            WhiteDnsSmartScanner.WhiteDnsTunnelEngine.DNST_STANDARD -> "dnstt"
            WhiteDnsSmartScanner.WhiteDnsTunnelEngine.NOIZ_DNS -> "sayedns"
            WhiteDnsSmartScanner.WhiteDnsTunnelEngine.VAY_DNS -> "vaydns"
            else -> return null
        }
        if (parsed.scheme !in setOf("dnstt", "dnst", "noizdns", "vaydns")) return null
        val key = parsed.publicKeyHex.trim()
        if (!PUBKEY_HEX.matches(key)) return null
        val domain = parsed.tunnelDomain.trim()
        if (domain.isBlank() || domain == "t.example.com" || '.' !in domain) return null
        val resolver = parsed.resolverIp.trim().ifBlank { return null }
        val fields = listOf(
            "18", type, parsed.remark.replace('|', ' ').take(60), domain,
            "$resolver:${parsed.resolverPort}:0", "0", "", "", "", "127.0.0.1", "0", key.lowercase(),
        )
        return "slipnet://" + Base64.encodeToString(fields.joinToString("|").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /**
     * Starts (or reuses) the real engine for [parsed] on `127.0.0.1:[preferredPort]` and waits until
     * its SOCKS5 port accepts connections. Blocking — call from an IO thread.
     */
    fun start(
        context: Context,
        parsed: WhiteDnsSmartScanner.WhiteDnsParsedTunnelUri,
        preferredPort: Int,
    ): StartResult {
        attach(context)
        val cliUri = cliUriFor(parsed) ?: run {
            val (fa, en) = unsupportedReason(parsed)
            return StartResult.Unsupported(fa, en)
        }
        val bin = binary(context) ?: return StartResult.Failed(
            "موتور تانل DNS داخل این نسخه بسته‌بندی نشده است (libslipnet.so). نسخهٔ کامل را از ریلیز نصب کنید.",
            "The DNS-tunnel engine (libslipnet.so) is not packaged in this build.",
            emptyList(),
        )
        val key = keyOf(parsed)
        running[key]?.let { existing ->
            if (existing.process.isAliveCompat() && isListening(existing.port)) return StartResult.Ready(existing.port)
            stop(key)
        }
        killOrphans(context)
        val port = if (preferredPort in 1..65535 && !isListening(preferredPort)) preferredPort else freePort()
        val resolver = parsed.resolverIp.trim()
        val args = mutableListOf(bin.absolutePath)
        if (resolver.isNotBlank()) {
            args += listOf("--dns", if (resolver.contains(':') && !resolver.contains('.')) "[$resolver]:${parsed.resolverPort}" else "$resolver:${parsed.resolverPort}")
        }
        args += listOf("--host", "127.0.0.1", "--port", port.toString(), cliUri)
        val buffer = ArrayDeque<String>()
        logs[key] = buffer
        val process = runCatching {
            ProcessBuilder(args)
                .directory(context.cacheDir)
                .redirectErrorStream(true)
                .also { it.environment()["HOME"] = context.filesDir.absolutePath }
                .start()
        }.getOrElse { error ->
            return StartResult.Failed(
                "اجرای موتور تانل DNS ممکن نشد: ${error.message}",
                "Could not launch the DNS-tunnel engine: ${error.message}",
                emptyList(),
            )
        }
        runCatching { process.outputStream.close() }
        running[key] = Running(key, port, process, System.currentTimeMillis())
        rememberPid(context, process)
        Thread({
            runCatching {
                var logged = 0
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val clean = line.trim()
                        if (clean.isEmpty()) return@forEach
                        synchronized(buffer) {
                            buffer.addLast(clean.take(240))
                            while (buffer.size > LOG_LINES) buffer.removeFirst()
                        }
                        if (logged++ < 60) DiagnosticLogger.info(context, "slipnet.engine", clean.take(240))
                    }
                }
            }
        }, "slipnet-log-$port").apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAliveCompat()) break
            if (isListening(port)) {
                DiagnosticLogger.info(context, "slipnet.engine.ready", "port=$port type=${parsed.engine.name}")
                return StartResult.Ready(port)
            }
            Thread.sleep(150)
        }
        val tail = synchronized(buffer) { buffer.toList() }
        stop(key)
        return StartResult.Failed(
            "موتور تانل DNS بالا نیامد. معمولاً یعنی ریزالور جواب نمی‌دهد یا دامنه/کلید سرور اشتباه است.",
            "The DNS-tunnel engine did not come up (resolver unreachable or wrong domain/key).",
            tail,
        )
    }

    fun stop(key: String) {
        running.remove(key)?.let { entry ->
            runCatching { entry.process.destroy() }
            runCatching { Thread.sleep(80) }
            if (entry.process.isAliveCompat()) runCatching { entry.process.destroyForcibly() }
        }
    }

    fun stopAll() {
        running.keys.toList().forEach(::stop)
        appContext?.let { ctx -> runCatching { File(ctx.filesDir, PID_FILE).delete() } }
    }

    fun isListening(port: Int): Boolean = runCatching {
        Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 250); true }
    }.getOrDefault(false)

    private fun freePort(): Int = runCatching {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }.getOrDefault(10899)

    private fun rememberPid(context: Context, process: Process) {
        val pid = pidOf(process) ?: return
        runCatching { File(context.filesDir, PID_FILE).appendText("$pid\n") }
    }

    /** Kills engines left over from a previous app process (same UID, so allowed). */
    private fun killOrphans(context: Context) {
        val file = File(context.filesDir, PID_FILE)
        if (!file.isFile) return
        val ours = running.values.mapNotNull { pidOf(it.process) }.toSet()
        val keep = mutableListOf<Int>()
        file.readLines().mapNotNull { it.trim().toIntOrNull() }.forEach { pid ->
            if (pid in ours) {
                keep += pid
            } else {
                val cmd = runCatching { File("/proc/$pid/cmdline").readText() }.getOrDefault("")
                if (cmd.contains(BINARY_NAME)) runCatching { android.os.Process.killProcess(pid) }
            }
        }
        runCatching { file.writeText(keep.joinToString("") { "$it\n" }) }
    }

    private fun pidOf(process: Process): Int? = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(process)
    }.getOrNull()

    private fun Process.isAliveCompat(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)

    private val PUBKEY_HEX = Regex("^[0-9a-fA-F]{64}$")
    private val CLI_TUNNEL_TYPES = setOf(
        "dnstt", "dnstt_ssh", "sayedns", "sayedns_ssh", "vaydns", "vaydns_ssh",
        "ssh", "direct_ssh", "socks5", "direct_socks", "vless", "doh",
    )
}

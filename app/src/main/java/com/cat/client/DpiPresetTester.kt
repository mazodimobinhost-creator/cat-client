package com.cat.client

import com.follow.clash.core.TunInterface
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/*
 * beta247 🧪 «Which fragmenting method works on my internet?» — the per-network automatic method choice idea
 * (Shroudly-style: try each way of splitting the TLS ClientHello, keep the one that gets through), built on Cat's
 * own ByeByeDPI proxy and its 7 presets. Targets are the user's OWN servers (their TLS names), so the answer is
 * about the connections Cat actually makes. Success = a real TLS handshake whose certificate is valid for that name.
 * Runs only while the VPN is off (one ByeByeDPI instance per process). UI: MainActivityDpiTester.kt.
 */

internal data class DpiTarget(val server: String, val port: Int, val sni: String)

internal data class DpiTrial(val presetId: String, val ok: Int, val total: Int, val bestMs: Long?)

internal object DpiPresetPlan {
    private val SKIP_TYPES = setOf("hysteria", "hysteria2", "tuic", "wireguard", "ss", "ssr", "ssh", "socks5", "http", "direct", "snell", "mieru")

    private fun field(raw: String, key: String): String? =
        Regex("""(?:^|[\s{,])$key\s*:\s*['"]?([^'",}\s]+)""").find(raw)?.groupValues?.get(1)?.trim()

    private fun isIp(v: String) = v.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || v.contains(':')

    /**
     * TLS proxies (VLESS / VMess / Trojan / AnyTLS with a TLS name) → distinct (server, port, name). Loopback servers,
     * IP names and UDP-based types are skipped: fragmenting only changes TCP ClientHellos.
     */
    fun targets(proxyRawYamls: List<String>, max: Int = 4): List<DpiTarget> {
        val out = linkedMapOf<String, DpiTarget>()
        for (raw in proxyRawYamls) {
            val type = field(raw, "type")?.lowercase() ?: continue
            if (type in SKIP_TYPES) continue
            val server = field(raw, "server") ?: continue
            if (server.startsWith("127.") || server == "localhost" || server == "::1") continue
            val port = field(raw, "port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
            val tls = type == "trojan" || type == "anytls" || field(raw, "tls") == "true" || raw.contains("reality-opts")
            if (!tls) continue
            val sni = (field(raw, "servername") ?: field(raw, "sni"))?.takeIf { it.isNotBlank() && !isIp(it) }
                ?: server.takeIf { !isIp(it) } ?: continue
            out.putIfAbsent(sni.lowercase(), DpiTarget(server, port, sni))
            if (out.size >= max) break
        }
        return out.values.toList()
    }

    /** Working presets first: most targets passed, then the fastest handshake. */
    fun rank(trials: List<DpiTrial>): List<DpiTrial> =
        trials.filter { it.ok > 0 }.sortedWith(compareByDescending<DpiTrial> { it.ok }.thenBy { it.bestMs ?: Long.MAX_VALUE })
}

internal object DpiPresetTester {
    class Outcome(val direct: DpiTrial, val trials: List<DpiTrial>, val notes: List<String>)

    private val noProtect = object : TunInterface {
        override fun protect(fd: Int) = Unit // the VPN is off while testing: nothing to protect from
        override fun resolverProcess(protocol: Int, source: String, target: String, uid: Int): String = ""
    }

    /** One TLS handshake (optionally through the local fragmenting proxy); ms on success, null on failure. */
    fun handshake(target: DpiTarget, ip: InetAddress, socksPort: Int?, timeoutMs: Int = 6_000): Long? = runCatching<Long?> {
        val started = System.nanoTime()
        val raw = if (socksPort == null) Socket() else Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        raw.use { s ->
            s.soTimeout = timeoutMs
            s.connect(InetSocketAddress(ip, target.port), timeoutMs) // resolved address → the proxy gets the IP, not a name
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, target.sni, target.port, true) as SSLSocket
            ssl.use {
                it.soTimeout = timeoutMs
                // The certificate must be valid for the name: checked in the handshake (endpoint identification) and,
                // on Android, again with the platform verifier (desktop Java's default verifier always says no).
                it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                it.startHandshake()
                if (ON_ANDROID && !HttpsURLConnection.getDefaultHostnameVerifier().verify(target.sni, it.session)) return@runCatching null
            }
        }
        (System.nanoTime() - started) / 1_000_000
    }.getOrNull()

    private val ON_ANDROID = System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true)

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun trial(id: String, targets: List<Pair<DpiTarget, InetAddress>>, socksPort: Int?): DpiTrial {
        val times = targets.mapNotNull { (t, ip) -> handshake(t, ip, socksPort) }
        return DpiTrial(id, times.size, targets.size, times.minOrNull())
    }

    /** Blocking; call off the main thread with the VPN stopped. [step] gets one human line per stage. */
    fun run(targets: List<DpiTarget>, fa: Boolean, step: (String) -> Unit, cancelled: () -> Boolean): Outcome {
        val notes = mutableListOf<String>()
        val resolved = targets.mapNotNull { t ->
            runCatching { t to InetAddress.getByName(t.server) }.getOrElse {
                notes += (if (fa) "نشانی پیدا نشد: " else "Could not resolve: ") + t.server
                null
            }
        }
        step(if (fa) "۱) بدون تکه‌تکه‌کردن (مستقیم)…" else "1) Without fragmenting (direct)…")
        val direct = trial("direct", resolved, null)
        val trials = mutableListOf<DpiTrial>()
        DpiFragmentPreset.entries.forEachIndexed { i, preset ->
            if (cancelled()) return Outcome(direct, trials, notes)
            step((if (fa) "${i + 2}) روش «${preset.id}»…" else "${i + 2}) Method “${preset.id}”…"))
            val port = runCatching { freePort() }.getOrNull()
            if (port == null) {
                trials += DpiTrial(preset.id, 0, resolved.size, null)
                return@forEachIndexed
            }
            val worker = Thread { runCatching { ByeDpiProxy.start(port, noProtect, preset) } }.apply { isDaemon = true; name = "cat-dpi-test" }
            worker.start()
            val deadline = System.currentTimeMillis() + 3_000
            while (DpiBypassPort.canBind(port) && worker.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(80)
            if (DpiBypassPort.canBind(port)) {
                notes += (if (fa) "روش ${preset.id} اجرا نشد" else "Method ${preset.id} did not start")
                trials += DpiTrial(preset.id, 0, resolved.size, null)
            } else {
                trials += trial(preset.id, resolved, port)
            }
            runCatching { ByeDpiProxy.stop() }
            worker.join(3_000)
        }
        return Outcome(direct, trials, notes)
    }
}

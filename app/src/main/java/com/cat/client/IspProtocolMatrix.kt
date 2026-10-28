package com.cat.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 📤 `catfusion://` — one link / one QR code that carries a whole fused multi-tunnel
 * (up to [MAX_LINKS] configs of ANY protocol). Payload = `CATFUSION1\n` + share URIs
 * joined by `\n`, gzip-compressed, Base64-URL (no padding). The `#fragment` is the title.
 *
 * Importing it anywhere in the app (add-subscription box, QR scanner, Omni hub, clipboard)
 * rebuilds the same 3-group fused profile (load-balance · failover · url-test).
 */
object CatFusionLink {
    const val SCHEME = "catfusion://"
    const val MAX_LINKS = 12
    private const val MAGIC = "CATFUSION1"

    fun isCatFusion(text: String): Boolean = text.trim().startsWith(SCHEME, ignoreCase = true)

    fun encode(shareUris: List<String>, title: String = "CatFusion"): String? {
        val clean = shareUris.map { it.trim() }
            .filter { it.isNotEmpty() && "://" in it && !isCatFusion(it) && '\n' !in it }
            .distinct()
            .take(MAX_LINKS)
        if (clean.isEmpty()) return null
        val plain = (listOf(MAGIC) + clean).joinToString("\n").toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(plain) }
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
        val frag = URLEncoder.encode(title.ifBlank { "CatFusion" }, "UTF-8").replace("+", "%20")
        return "$SCHEME$payload#$frag"
    }

    fun decode(link: String): List<String>? {
        val trimmed = link.trim()
        if (!isCatFusion(trimmed)) return null
        val payload = trimmed.substring(SCHEME.length).substringBefore('#').substringBefore('?').trim()
        if (payload.isEmpty()) return null
        return runCatching {
            val normalized = payload.replace('+', '-').replace('/', '_').trimEnd('=')
            val compressed = Base64.getUrlDecoder().decode(normalized)
            val text = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
                .toString(Charsets.UTF_8)
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.firstOrNull() != MAGIC) return@runCatching null
            lines.drop(1).filter { "://" in it && !isCatFusion(it) }.take(MAX_LINKS)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun title(link: String): String? = link.substringAfter('#', "").takeIf { it.isNotBlank() }?.let {
        runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
    }

    /** Replaces every `catfusion://` line inside [text] with the share URIs it carries. */
    fun expandInText(text: String): String {
        if (!text.contains(SCHEME, ignoreCase = true)) return text
        return text.lines().joinToString("\n") { line ->
            val idx = line.indexOf(SCHEME, ignoreCase = true)
            if (idx < 0) {
                line
            } else {
                val token = line.substring(idx).trim().split(Regex("\\s+")).first()
                decode(token)?.joinToString("\n") ?: line
            }
        }
    }

    /** Converts a Mihomo proxy JSON (from SubConvConverter or a YAML subscription) back to a share URI. */
    fun shareUriOf(proxy: JSONObject): String? {
        listOf("whitedns-uri", "paqet-uri", "fenix-uri", "aether-uri").forEach { key ->
            proxy.optString(key).takeIf { it.isNotBlank() && "://" in it }?.let { return it }
        }
        return runCatching { MihomoShareLink.build(jsonToMap(proxy)) }.getOrNull()
    }

    internal fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        obj.keys().forEach { key -> out[key] = unwrap(obj.opt(key)) }
        return out
    }

    private fun unwrap(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonToMap(value)
        is JSONArray -> (0 until value.length()).map { unwrap(value.opt(it)) }
        else -> value
    }
}

/**
 * 📡 1-Tap ISP Protocol Matrix — answers "which protocol is open on MY line right now?"
 * with REAL network probes only (no simulated pings):
 *
 *  • Baseline: TLS to a domestic site, TLS to Cloudflare, TLS to Google, QUIC Version-Negotiation
 *    on UDP/443, DNS over UDP/53 (foreign + domestic resolver), DNS over TCP/53, DoH and IPv6.
 *  • Every one of the user's own configs is probed by its transport:
 *      TLS handshake (VLESS/Trojan/VMess/AnyTLS/HTTPS/Reality with its own SNI),
 *      TCP connect (SS/SOCKS/HTTP/Snell/Mieru/plain WS), SSH banner,
 *      QUIC Version-Negotiation (Hysteria2/TUIC/Juicity),
 *      DNS query through the tunnel's own resolver for the tunnel domain (SlipNet/DNSTT/…).
 *  • WireGuard/WARP, Paqet, Firefox VPN and Aether cannot be verified without a full
 *    handshake, so they are reported as "unknown" instead of a fake green.
 */
object IspProtocolMatrix {

    enum class Family(val fa: String, val en: String) {
        TLS("TLS روی TCP (VLESS · Trojan · VMess · AnyTLS · Reality)", "TLS over TCP (VLESS · Trojan · VMess · AnyTLS · Reality)"),
        TCP("TCP ساده (SS · SOCKS · HTTP · Snell · WS بدون TLS)", "Plain TCP (SS · SOCKS · HTTP · Snell · plain WS)"),
        SSH("SSH", "SSH"),
        QUIC("QUIC / UDP (Hysteria2 · TUIC · Juicity)", "QUIC / UDP (Hysteria2 · TUIC · Juicity)"),
        DNS_TUNNEL("تانل DNS (SlipNet · DNSTT · NoizDNS · CottenMax)", "DNS tunnel (SlipNet · DNSTT · NoizDNS · CottenMax)"),
        WIREGUARD("WireGuard / WARP", "WireGuard / WARP"),
        OTHER("سایر (Paqet · Firefox VPN · Aether)", "Other (Paqet · Firefox VPN · Aether)"),
    }

    data class BaselineRow(
        val key: String,
        val labelFa: String,
        val labelEn: String,
        val ok: Boolean,
        val latencyMs: Long?,
        val detail: String,
    )

    data class ConfigRow(
        val name: String,
        val family: Family,
        val endpoint: String,
        /** true = really reachable, false = really blocked, null = cannot be verified without connecting. */
        val ok: Boolean?,
        val latencyMs: Long?,
        val detail: String,
        val shareUri: String,
        val proxyJson: String,
    )

    data class Report(
        val baseline: List<BaselineRow>,
        val configs: List<ConfigRow>,
        val durationMs: Long,
    ) {
        private fun base(key: String) = baseline.firstOrNull { it.key == key }?.ok == true
        val domesticOk get() = base("ir-tls")
        val cloudflareOk get() = base("cf-tls")
        val internationalOk get() = base("google-tls")
        val quicOk get() = base("quic")
        val udpDnsForeignOk get() = base("dns-udp-foreign")
        val udpDnsDomesticOk get() = base("dns-udp-ir")
        val tcpDnsOk get() = base("dns-tcp")
        val dohOk get() = base("doh")
        val ipv6Ok get() = base("ipv6")
        val greenConfigs get() = configs.filter { it.ok == true }.sortedBy { it.latencyMs ?: Long.MAX_VALUE }

        fun familyStatus(): Map<Family, Triple<Int, Int, Int>> {
            val out = linkedMapOf<Family, Triple<Int, Int, Int>>()
            configs.groupBy { it.family }.forEach { (fam, rows) ->
                out[fam] = Triple(rows.count { it.ok == true }, rows.count { it.ok == false }, rows.count { it.ok == null })
            }
            return out
        }

        fun verdict(isFarsi: Boolean): List<String> {
            val lines = mutableListOf<String>()
            if (!domesticOk && !internationalOk && !cloudflareOk) {
                lines += if (isFarsi) "🔴 اینترنت قطع است یا هیچ مسیر TLS باز نیست؛ دادهٔ موبایل/وای‌فای را بررسی کن."
                else "🔴 No TLS path works at all — check mobile data / Wi‑Fi."
                return lines
            }
            if (domesticOk && !internationalOk && !cloudflareOk) {
                lines += if (isFarsi) "🟠 حالت نت ملی/وایت‌لیست: فقط سایت‌های داخلی باز است → تانل DNS یا آی‌پی/دامنهٔ سفید (اسکنر ۱۳) را امتحان کن."
                else "🟠 National-intranet / whitelist mode: only domestic sites → use DNS tunnels or whitelisted IPs (scanner 13)."
            }
            if (cloudflareOk) {
                lines += if (isFarsi) "🟢 کلادفلر روی TLS باز است → کانفیگ‌های Worker/CDN (VLESS/Trojan) شانس بالایی دارند."
                else "🟢 Cloudflare TLS is open → Worker/CDN VLESS/Trojan configs should work."
            }
            if (!quicOk) {
                lines += if (isFarsi) "🟠 UDP/QUIC بسته یا محدود است → Hysteria2، TUIC و WireGuard احتمالاً کار نمی‌کنند؛ از کانفیگ‌های TCP/TLS استفاده کن."
                else "🟠 UDP/QUIC is blocked/throttled → Hysteria2, TUIC and WireGuard will likely fail; prefer TCP/TLS configs."
            } else {
                lines += if (isFarsi) "🟢 QUIC روی UDP/443 باز است → Hysteria2/TUIC/WARP قابل استفاده‌اند."
                else "🟢 QUIC on UDP/443 is open → Hysteria2/TUIC/WARP are usable."
            }
            if (!udpDnsForeignOk && udpDnsDomesticOk) {
                lines += if (isFarsi) "🟡 DNS خارجی روی UDP بسته ولی ریزولور داخلی باز است → تانل‌های DNS را با ریزولور داخلی (مثل شکن/۱۷۸.۲۲.۱۲۲.۱۰۰) بزن."
                else "🟡 Foreign UDP DNS is blocked but domestic resolvers answer → run DNS tunnels through a domestic resolver."
            }
            if (!udpDnsForeignOk && !udpDnsDomesticOk && (tcpDnsOk || dohOk)) {
                lines += if (isFarsi) "🟡 DNS روی UDP بسته است ولی DNS روی TCP/DoH باز است → در تانل DNS حالت انتقال را DoH یا TCP بگذار."
                else "🟡 UDP DNS is blocked but TCP/DoH works → switch DNS tunnels to DoH/TCP transport."
            }
            if (ipv6Ok) {
                lines += if (isFarsi) "🟢 IPv6 کار می‌کند → روش «CDN/Worker با IPv6» (فایروال همراه اول) قابل استفاده است."
                else "🟢 IPv6 works → the “CDN/Worker with IPv6” method is usable."
            }
            val green = greenConfigs.size
            lines += if (isFarsi) "✅ $green کانفیگ از کانفیگ‌های خودت روی همین خط واقعاً باز است."
            else "✅ $green of your own configs are really reachable on this line."
            return lines
        }
    }

    const val DEFAULT_TIMEOUT_MS = 3500
    const val MAX_CONFIGS = 24

    /** Collects share URIs from raw subscription texts: share links, SlipNet, raw JSON/TOML, YAML and catfusion://. */
    fun collectProxies(rawTexts: List<String>, max: Int = MAX_CONFIGS): List<Pair<String, JSONObject>> {
        val out = mutableListOf<Pair<String, JSONObject>>()
        val seen = HashSet<String>()
        for (raw in rawTexts) {
            if (out.size >= max) break
            val proxies = proxiesFromRaw(raw)
            for (p in proxies) {
                if (out.size >= max) break
                val share = CatFusionLink.shareUriOf(p).orEmpty()
                val key = share.ifBlank { "${p.optString("type")}|${p.optString("server")}|${p.optInt("port")}|${p.optString("name")}" }
                    .substringBefore('#')
                if (seen.add(key)) out += share to p
            }
        }
        return out
    }

    /** Proxy JSONs from one raw text (share links / SlipNet / JSON / TOML / catfusion via SubConvConverter, or a Mihomo YAML). */
    fun proxiesFromRaw(raw: String): List<JSONObject> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val looksLikeMihomoYaml = text.removePrefix("\uFEFF").startsWith("proxies:") || text.contains("\nproxies:")
        if (!looksLikeMihomoYaml) {
            return runCatching { SubConvConverter.convert(text) }.getOrNull().orEmpty()
        }
        return runCatching {
            MihomoConfigParser.parseSummary(text).proxies.mapNotNull { proxy ->
                val map = runCatching { MihomoProxyYaml.parse(proxy.rawYaml) }.getOrNull()
                if (map.isNullOrEmpty()) {
                    JSONObject().put("name", proxy.name).put("type", proxy.type)
                        .put("server", proxy.server).put("port", proxy.port)
                } else {
                    val obj = JSONObject(map)
                    map.forEach { (k, v) ->
                        if (v is String) {
                            when {
                                v.equals("true", true) -> obj.put(k, true)
                                v.equals("false", true) -> obj.put(k, false)
                                k in NUMERIC_YAML_KEYS && v.toIntOrNull() != null -> obj.put(k, v.toInt())
                            }
                        }
                    }
                    obj
                }
            }
        }.getOrDefault(emptyList())
    }

    private val NUMERIC_YAML_KEYS = setOf(
        "port", "alterId", "alter-id", "mtu", "persistent-keepalive", "up", "down",
        "udp-over-tcp-version", "recv-window-conn", "recv-window", "version", "protocol-param-int",
    )

    fun familyOf(proxy: JSONObject): Family {
        if (proxy.has("whitedns-uri")) return Family.DNS_TUNNEL
        if (proxy.has("paqet-uri") || proxy.has("fenix-uri") || proxy.has("aether-uri")) return Family.OTHER
        return when (proxy.optString("type").lowercase()) {
            "vless", "vmess", "trojan", "anytls" -> if (isTls(proxy)) Family.TLS else Family.TCP
            "http" -> if (isTls(proxy)) Family.TLS else Family.TCP
            "ss", "ssr", "socks5", "socks", "snell", "mieru" -> Family.TCP
            "ssh" -> Family.SSH
            "hysteria2", "hy2", "hysteria", "tuic", "juicity" -> Family.QUIC
            "wireguard", "wg", "awg" -> Family.WIREGUARD
            else -> Family.OTHER
        }
    }

    private fun isTls(proxy: JSONObject): Boolean {
        val t = proxy.opt("tls")
        if (t is Boolean) return t
        if (t?.toString().equals("true", true)) return true
        return proxy.optString("type").lowercase() in setOf("trojan", "anytls")
    }

    private fun sniOf(proxy: JSONObject): String =
        listOf("servername", "sni", "server-name").firstNotNullOfOrNull { key ->
            proxy.optString(key).takeIf { it.isNotBlank() && it != "null" }
        } ?: proxy.optString("server")

    fun run(
        rawTexts: List<String>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        maxConfigs: Int = MAX_CONFIGS,
    ): Report {
        val started = System.currentTimeMillis()
        val proxies = collectProxies(rawTexts, maxConfigs)
        val pool = Executors.newFixedThreadPool(16)
        try {
            val baselineTasks = baselineProbes(timeoutMs)
            val configTasks = proxies.map { (share, p) -> Callable { probeConfig(share, p, timeoutMs) } }
            val baseFutures = baselineTasks.map { pool.submit(it) }
            val cfgFutures = configTasks.map { pool.submit(it) }
            val waitMs = timeoutMs.toLong() * 3 + 2000
            val baseline = baseFutures.mapIndexed { i, f ->
                runCatching { f.get(waitMs, TimeUnit.MILLISECONDS) }.getOrElse {
                    f.cancel(true)
                    BASELINE_SPECS[i].let { s -> BaselineRow(s.key, s.fa, s.en, false, null, "timeout") }
                }
            }
            val configs = cfgFutures.mapIndexed { i, f ->
                runCatching { f.get(waitMs, TimeUnit.MILLISECONDS) }.getOrElse {
                    f.cancel(true)
                    val (share, p) = proxies[i]
                    ConfigRow(p.optString("name"), familyOf(p), "${p.optString("server")}:${p.optInt("port")}",
                        false, null, "timeout", share, p.toString())
                }
            }
            return Report(baseline, configs, System.currentTimeMillis() - started)
        } finally {
            pool.shutdownNow()
        }
    }

    private data class BaselineSpec(val key: String, val fa: String, val en: String)

    private val BASELINE_SPECS = listOf(
        BaselineSpec("ir-tls", "اینترنت داخلی (TLS به aparat.com)", "Domestic internet (TLS to aparat.com)"),
        BaselineSpec("cf-tls", "کلادفلر (TLS به www.cloudflare.com)", "Cloudflare (TLS to www.cloudflare.com)"),
        BaselineSpec("google-tls", "اینترنت بین‌الملل (TLS به www.google.com)", "International (TLS to www.google.com)"),
        BaselineSpec("quic", "QUIC روی UDP/443 (1.1.1.1)", "QUIC on UDP/443 (1.1.1.1)"),
        BaselineSpec("dns-udp-foreign", "DNS روی UDP/53 خارجی (8.8.8.8)", "Foreign DNS over UDP/53 (8.8.8.8)"),
        BaselineSpec("dns-udp-ir", "DNS روی UDP/53 داخلی (178.22.122.100)", "Domestic DNS over UDP/53 (178.22.122.100)"),
        BaselineSpec("dns-tcp", "DNS روی TCP/53 (8.8.8.8)", "DNS over TCP/53 (8.8.8.8)"),
        BaselineSpec("doh", "DNS-over-HTTPS (1.1.1.1)", "DNS-over-HTTPS (1.1.1.1)"),
        BaselineSpec("ipv6", "IPv6 (TLS به 2606:4700:4700::1111)", "IPv6 (TLS to 2606:4700:4700::1111)"),
    )

    private fun baselineProbes(timeoutMs: Int): List<Callable<BaselineRow>> = BASELINE_SPECS.map { spec ->
        Callable {
            val r: ProbeResult = when (spec.key) {
                "ir-tls" -> tlsHandshake("www.aparat.com", 443, "www.aparat.com", timeoutMs)
                "cf-tls" -> tlsHandshake("www.cloudflare.com", 443, "www.cloudflare.com", timeoutMs)
                "google-tls" -> tlsHandshake("www.google.com", 443, "www.google.com", timeoutMs)
                "quic" -> quicProbe("1.1.1.1", 443, timeoutMs)
                "dns-udp-foreign" -> dnsUdpProbe("8.8.8.8", 53, "www.google.com", QTYPE_A, timeoutMs)
                "dns-udp-ir" -> dnsUdpProbe("178.22.122.100", 53, "www.aparat.com", QTYPE_A, timeoutMs)
                "dns-tcp" -> dnsTcpProbe("8.8.8.8", 53, "www.google.com", timeoutMs)
                "doh" -> dohProbe(timeoutMs)
                "ipv6" -> tlsHandshake("2606:4700:4700::1111", 443, "one.one.one.one", timeoutMs)
                else -> ProbeResult(false, null, "n/a")
            }
            BaselineRow(spec.key, spec.fa, spec.en, r.ok == true, r.latencyMs, r.detail)
        }
    }

    fun probeConfig(share: String, proxy: JSONObject, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ConfigRow {
        val family = familyOf(proxy)
        val server = proxy.optString("server")
        val port = proxy.optInt("port")
        var endpoint = "$server:$port"
        val r: ProbeResult = runCatching {
            when (family) {
                Family.TLS -> tlsHandshake(server, port, sniOf(proxy), timeoutMs)
                Family.TCP -> tcpConnect(server, port, timeoutMs)
                Family.SSH -> sshBanner(server, port, timeoutMs)
                Family.QUIC -> quicProbe(server, port, timeoutMs)
                Family.DNS_TUNNEL -> {
                    val parsed = WhiteDnsSmartScanner.parseTunnelUri(proxy.optString("whitedns-uri"))
                    if (parsed == null || parsed.resolverIp.isBlank() || parsed.tunnelDomain.isBlank()) {
                        ProbeResult(null, null, "unparsed")
                    } else {
                        endpoint = "${parsed.resolverIp}:${parsed.resolverPort} → ${parsed.tunnelDomain}"
                        val label = randomLabel()
                        dnsUdpProbe(parsed.resolverIp, parsed.resolverPort.takeIf { it in 1..65535 } ?: 53,
                            "$label.${parsed.tunnelDomain.trim('.')}", QTYPE_TXT, timeoutMs)
                    }
                }
                Family.WIREGUARD -> ProbeResult(null, null, "wireguard-needs-handshake")
                Family.OTHER -> ProbeResult(null, null, "verified-on-connect")
            }
        }.getOrElse { ProbeResult(false, null, it.javaClass.simpleName) }
        return ConfigRow(proxy.optString("name"), family, endpoint, r.ok, r.latencyMs, r.detail, share, proxy.toString())
    }

    data class ProbeResult(val ok: Boolean?, val latencyMs: Long?, val detail: String)

    // ------------------------------------------------------------------ real probes

    fun tcpConnect(host: String, port: Int, timeoutMs: Int): ProbeResult {
        if (host.isBlank() || port !in 1..65535) return ProbeResult(false, null, "bad-endpoint")
        val start = System.nanoTime()
        return runCatching {
            Socket().use { s ->
                protectOutboundSocket(s) // beta205: probe THIS network, not the running tunnel
                s.connect(InetSocketAddress(host, port), timeoutMs)
            }
            ProbeResult(true, (System.nanoTime() - start) / 1_000_000, "tcp-open")
        }.getOrElse { ProbeResult(false, null, it.javaClass.simpleName) }
    }

    private val trustAll: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), SecureRandom())
        }
    }

    /** Reachability-only TLS handshake (no payload is sent; certificate trust is not the question here). */
    fun tlsHandshake(host: String, port: Int, sni: String, timeoutMs: Int): ProbeResult {
        if (host.isBlank() || port !in 1..65535) return ProbeResult(false, null, "bad-endpoint")
        val start = System.nanoTime()
        return runCatching {
            Socket().use { raw ->
                protectOutboundSocket(raw) // beta205: probe THIS network, not the running tunnel
                raw.connect(InetSocketAddress(host, port), timeoutMs)
                raw.soTimeout = timeoutMs
                val sniHost = sni.trim().takeIf { it.isNotEmpty() && !looksLikeIp(it) } ?: host
                val ssl = trustAll.socketFactory.createSocket(raw, sniHost, port, false) as SSLSocket
                ssl.use { s ->
                    if (!looksLikeIp(sniHost)) {
                        runCatching {
                            val params = s.sslParameters
                            params.serverNames = listOf(SNIHostName(sniHost))
                            s.sslParameters = params
                        }
                    }
                    s.soTimeout = timeoutMs
                    s.startHandshake()
                    ProbeResult(true, (System.nanoTime() - start) / 1_000_000, s.session?.protocol ?: "tls")
                }
            }
        }.getOrElse { ProbeResult(false, null, it.javaClass.simpleName) }
    }

    fun sshBanner(host: String, port: Int, timeoutMs: Int): ProbeResult {
        val start = System.nanoTime()
        return runCatching {
            Socket().use { s ->
                protectOutboundSocket(s) // beta205: probe THIS network, not the running tunnel
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                val buf = ByteArray(64)
                val n = s.getInputStream().read(buf)
                val banner = if (n > 0) String(buf, 0, n, Charsets.US_ASCII) else ""
                if (banner.startsWith("SSH-")) {
                    ProbeResult(true, (System.nanoTime() - start) / 1_000_000, banner.trim().take(32))
                } else {
                    ProbeResult(false, null, "no-ssh-banner")
                }
            }
        }.getOrElse { ProbeResult(false, null, it.javaClass.simpleName) }
    }

    fun quicProbe(host: String, port: Int, timeoutMs: Int): ProbeResult {
        if (host.isBlank() || port !in 1..65535) return ProbeResult(false, null, "bad-endpoint")
        val start = System.nanoTime()
        return runCatching {
            DatagramSocket().use { sock ->
                protectOutboundDatagramSocket(sock) // beta205: probe THIS network, not the running tunnel
                sock.soTimeout = timeoutMs
                val addr = InetAddress.getByName(host)
                val packet = buildQuicVersionProbe(SecureRandom())
                sock.send(DatagramPacket(packet, packet.size, addr, port))
                val resp = ByteArray(1500)
                val dp = DatagramPacket(resp, resp.size)
                sock.receive(dp)
                val ms = (System.nanoTime() - start) / 1_000_000
                if (isQuicVersionNegotiation(resp, dp.length)) ProbeResult(true, ms, "quic-version-negotiation")
                else ProbeResult(true, ms, "udp-reply")
            }
        }.getOrElse {
            ProbeResult(false, null, if (it is java.net.SocketTimeoutException) "no-udp-reply" else it.javaClass.simpleName)
        }
    }

    fun dnsUdpProbe(resolver: String, port: Int, qname: String, qtype: Int, timeoutMs: Int): ProbeResult {
        val start = System.nanoTime()
        return runCatching {
            DatagramSocket().use { sock ->
                protectOutboundDatagramSocket(sock) // beta205: probe THIS network, not the running tunnel
                sock.soTimeout = timeoutMs
                val id = SecureRandom().nextInt(0xFFFF)
                val q = buildDnsQuery(id, qname, qtype)
                sock.send(DatagramPacket(q, q.size, InetAddress.getByName(resolver), port))
                val resp = ByteArray(1500)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val dp = DatagramPacket(resp, resp.size)
                    sock.receive(dp)
                    if (isDnsResponseFor(id, resp, dp.length)) {
                        return@use ProbeResult(true, (System.nanoTime() - start) / 1_000_000, "rcode=${resp[3].toInt() and 0x0F}")
                    }
                }
                ProbeResult(false, null, "no-dns-reply")
            }
        }.getOrElse {
            ProbeResult(false, null, if (it is java.net.SocketTimeoutException) "no-dns-reply" else it.javaClass.simpleName)
        }
    }

    fun dnsTcpProbe(resolver: String, port: Int, qname: String, timeoutMs: Int): ProbeResult {
        val start = System.nanoTime()
        return runCatching {
            Socket().use { s ->
                protectOutboundSocket(s) // beta205: probe THIS network, not the running tunnel
                s.connect(InetSocketAddress(resolver, port), timeoutMs)
                s.soTimeout = timeoutMs
                val id = SecureRandom().nextInt(0xFFFF)
                val q = buildDnsQuery(id, qname, QTYPE_A)
                val out = s.getOutputStream()
                out.write(byteArrayOf(((q.size ushr 8) and 0xFF).toByte(), (q.size and 0xFF).toByte()))
                out.write(q)
                out.flush()
                val input = DataInputStream(s.getInputStream())
                val len = input.readUnsignedShort()
                val resp = ByteArray(len.coerceAtMost(4096))
                input.readFully(resp)
                if (isDnsResponseFor(id, resp, resp.size)) ProbeResult(true, (System.nanoTime() - start) / 1_000_000, "tcp-dns")
                else ProbeResult(false, null, "bad-dns-reply")
            }
        }.getOrElse { ProbeResult(false, null, it.javaClass.simpleName) }
    }

    private fun dohProbe(timeoutMs: Int): ProbeResult {
        val start = System.nanoTime()
        return runCatching {
            val conn = java.net.URL("https://1.1.1.1/dns-query?name=www.google.com&type=A")
                .openConnection() as javax.net.ssl.HttpsURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("accept", "application/dns-json")
            try {
                val code = conn.responseCode
                if (code == 200) {
                    conn.inputStream.use { it.readBytes() }
                    ProbeResult(true, (System.nanoTime() - start) / 1_000_000, "http-200")
                } else {
                    ProbeResult(false, null, "http-$code")
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { ProbeResult(false, null, it.javaClass.simpleName) }
    }

    // ------------------------------------------------------------------ wire formats (JVM-tested)

    const val QTYPE_A = 1
    const val QTYPE_TXT = 16

    internal fun buildDnsQuery(id: Int, qname: String, qtype: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write((id ushr 8) and 0xFF); bos.write(id and 0xFF)
        bos.write(0x01); bos.write(0x00) // RD
        bos.write(0x00); bos.write(0x01) // QDCOUNT
        repeat(6) { bos.write(0x00) }
        qname.trim('.').split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII).take(63).toByteArray()
            bos.write(bytes.size)
            bos.write(bytes)
        }
        bos.write(0x00)
        bos.write((qtype ushr 8) and 0xFF); bos.write(qtype and 0xFF)
        bos.write(0x00); bos.write(0x01) // IN
        return bos.toByteArray()
    }

    internal fun isDnsResponseFor(id: Int, resp: ByteArray, len: Int): Boolean {
        if (len < 12) return false
        val rid = ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
        return rid == id && (resp[2].toInt() and 0x80) != 0
    }

    /**
     * QUIC long-header Initial-shaped packet with the reserved version `0x?a?a?a?a` (RFC 9000 §15)
     * padded to 1200 bytes; RFC-compliant servers answer with a Version Negotiation packet.
     */
    internal fun buildQuicVersionProbe(rnd: java.util.Random): ByteArray {
        val pkt = ByteArray(1200)
        rnd.nextBytes(pkt)
        pkt[0] = (0xC0 or (pkt[0].toInt() and 0x0F)).toByte()
        pkt[1] = 0x1a; pkt[2] = 0x2a; pkt[3] = 0x3a; pkt[4] = 0x4a
        pkt[5] = 8 // DCID length
        pkt[14] = 8 // SCID length (after 8-byte DCID)
        return pkt
    }

    internal fun isQuicVersionNegotiation(resp: ByteArray, len: Int): Boolean =
        len >= 7 && (resp[0].toInt() and 0x80) != 0 &&
            resp[1].toInt() == 0 && resp[2].toInt() == 0 && resp[3].toInt() == 0 && resp[4].toInt() == 0

    private fun looksLikeIp(value: String): Boolean =
        ':' in value || value.all { it.isDigit() || it == '.' }

    private fun randomLabel(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}

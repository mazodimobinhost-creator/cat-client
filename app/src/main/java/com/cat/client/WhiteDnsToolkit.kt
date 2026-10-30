package com.cat.client

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Scanner toolkit inspired by the sections of WhiteDNS IP Scanner (independent implementation,
 * no code copied): ASN → CIDR export, CIDR → IP list, open HTTP / SOCKS5 proxy scan (3 waves),
 * SNI scan, Speed & Loss rank, DNS resolver / tunnel-readiness scan and Config Maker.
 *
 * Every tool does real network work; nothing returns sample values.
 */
object WhiteDnsToolkit {

    // ---------------------------------------------------------------------------------------
    // ASN export
    // ---------------------------------------------------------------------------------------

    data class AsnPreset(val asn: Int, val titleFa: String, val titleEn: String)

    val ASN_PRESETS = listOf(
        AsnPreset(58224, "مخابرات ایران (TCI)", "Iran Telecommunication Co (TCI)"),
        AsnPreset(197207, "همراه اول (MCI)", "Mobile Communication Co (MCI)"),
        AsnPreset(44244, "ایرانسل", "Irancell"),
        AsnPreset(57218, "رایتل", "RighTel"),
        AsnPreset(12880, "زیرساخت (DCI)", "Information Technology Co (ITC/DCI)"),
        AsnPreset(16322, "پارس‌آنلاین", "Pars Online"),
        AsnPreset(31549, "شاتل", "Aria Shatel"),
        AsnPreset(42337, "رسپینا", "Respina"),
        AsnPreset(43211, "دیجی‌کالا", "Digikala"),
        AsnPreset(202319, "هزاردستان (ابرآروان)", "Hezardastan (ArvanCloud)"),
        AsnPreset(205585, "ابرآروان", "ArvanCloud"),
        AsnPreset(24631, "فناوا (پاسارگاد)", "Fanava (Pasargad)"),
        AsnPreset(49100, "پیشگامان", "Pishgaman"),
        AsnPreset(13335, "کلادفلر", "Cloudflare"),
        AsnPreset(209242, "کلادفلر (اسپکتروم)", "Cloudflare Spectrum"),
        AsnPreset(14789, "کلادفلر (CDN)", "Cloudflare CDN"),
        AsnPreset(54113, "فستلی", "Fastly"),
        AsnPreset(20940, "آکامای", "Akamai"),
    )

    /** Announced prefixes of [asn] from RIPEstat (public, keyless). */
    fun fetchAsnPrefixes(asn: Int, includeIpv6: Boolean = false, timeoutMs: Int = 15_000): List<String> {
        val url = URL("https://stat.ripe.net/data/announced-prefixes/data.json?resource=AS$asn&sourceapp=cat-client")
        val body = httpGet(url, timeoutMs) ?: throw java.io.IOException("RIPEstat unreachable")
        return parseRipePrefixes(body, includeIpv6)
    }

    internal fun parseRipePrefixes(body: String, includeIpv6: Boolean): List<String> {
        val prefixes = JSONObject(body).optJSONObject("data")?.optJSONArray("prefixes")
            ?: throw java.io.IOException("RIPEstat returned no prefixes")
        return (0 until prefixes.length())
            .mapNotNull { prefixes.optJSONObject(it)?.optString("prefix")?.trim() }
            .filter { it.isNotEmpty() && (includeIpv6 || ':' !in it) }
            .distinct()
            .sortedWith(compareBy<String>({ ':' in it }, { ipv4SortKey(it) }))
    }

    private fun ipv4SortKey(cidr: String): Long {
        val parts = cidr.substringBefore('/').split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return Long.MAX_VALUE
        return parts.fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
    }

    /** Expands IPv4 CIDRs into addresses, taking at most [perPrefix] evenly spread hosts per prefix. */
    fun expandCidrs(cidrs: List<String>, perPrefix: Int = 16, maxTotal: Int = 20_000): List<String> {
        val out = LinkedHashSet<String>()
        for (cidr in cidrs) {
            if (out.size >= maxTotal) break
            val ip = cidr.substringBefore('/').trim()
            val bits = cidr.substringAfter('/', "32").trim().toIntOrNull() ?: continue
            if (':' in ip || bits !in 8..32) continue
            val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4 || parts.any { it !in 0..255 }) continue
            val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
            val base = parts.fold(0L) { acc, p -> (acc shl 8) or p.toLong() } and mask
            val size = 1L shl (32 - bits)
            val first = if (size > 2) 1L else 0L
            val usable = if (size > 2) size - 2 else size
            val take = minOf(perPrefix.toLong(), usable).coerceAtLeast(1L)
            val step = (usable / take).coerceAtLeast(1L)
            var i = 0L
            while (i < take && out.size < maxTotal) {
                val value = base + first + i * step
                out += "${(value shr 24) and 255}.${(value shr 16) and 255}.${(value shr 8) and 255}.${value and 255}"
                i++
            }
        }
        return out.toList()
    }

    // ---------------------------------------------------------------------------------------
    // Target parsing
    // ---------------------------------------------------------------------------------------

    data class Target(val host: String, val port: Int?)

    /** Accepts `ip`, `ip:port`, `[v6]:port`, bare IPv6, CIDRs (expanded) and free text with separators. */
    fun parseTargets(text: String, perCidr: Int = 32, max: Int = 4_096): List<Target> {
        val out = LinkedHashSet<Target>()
        for (token in text.split(Regex("[\\s,;|]+")).map(String::trim).filter(String::isNotEmpty)) {
            if (out.size >= max) break
            when {
                '/' in token && ':' !in token ->
                    expandCidrs(listOf(token), perCidr).forEach { out += Target(it, null) }
                token.startsWith("[") && "]" in token -> {
                    val host = token.substringAfter('[').substringBefore(']')
                    val port = token.substringAfter("]:", "").toIntOrNull()?.takeIf { it in 1..65535 }
                    out += Target(host, port)
                }
                token.count { it == ':' } == 1 -> {
                    val port = token.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 }
                    out += Target(token.substringBefore(':'), port)
                }
                else -> out += Target(token, null)
            }
        }
        return out.take(max)
    }

    fun hostForUri(host: String): String = if (':' in host && !host.startsWith("[")) "[$host]" else host

    // ---------------------------------------------------------------------------------------
    // Open proxy scan (3 waves)
    // ---------------------------------------------------------------------------------------

    enum class ProxyKind { HTTP, SOCKS5 }

    data class ProxyHit(val host: String, val port: Int, val kind: ProxyKind, val latencyMs: Long) {
        fun link(): String = when (kind) {
            ProxyKind.HTTP -> "http://${hostForUri(host)}:$port#HTTP-$host-$port"
            ProxyKind.SOCKS5 -> "socks5://${hostForUri(host)}:$port#SOCKS5-$host-$port"
        }
    }

    val DEFAULT_HTTP_PORTS = listOf(8080, 3128, 80, 8888, 8000, 8118)
    val DEFAULT_SOCKS_PORTS = listOf(1080, 1081, 10808, 9050, 7890)

    /**
     * Wave 1: TCP connect. Wave 2: protocol handshake (HTTP `CONNECT` → 200 / SOCKS5 greeting →
     * no-auth). Wave 3: a real request through the proxy (`generate_204`), so only usable proxies
     * survive. Targets with an explicit port are scanned only on that port.
     */
    fun scanOpenProxies(
        targets: List<Target>,
        kind: ProxyKind,
        defaultPorts: List<Int>,
        parallelism: Int = 48,
        connectTimeoutMs: Int = 1_500,
        progress: (done: Int, total: Int, hits: Int) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false },
    ): List<ProxyHit> {
        val jobs = targets.flatMap { t -> (t.port?.let { listOf(it) } ?: defaultPorts).map { t.host to it } }
        val hits = ConcurrentLinkedQueue<ProxyHit>()
        runParallel(jobs, parallelism, progress = { d, total -> progress(d, total, hits.size) }, cancelled = cancelled) { (host, port) ->
            val started = System.nanoTime()
            val ok = when (kind) {
                ProxyKind.HTTP -> httpProxyWorks(host, port, connectTimeoutMs)
                ProxyKind.SOCKS5 -> socks5ProxyWorks(host, port, connectTimeoutMs)
            }
            if (ok) hits += ProxyHit(host, port, kind, (System.nanoTime() - started) / 1_000_000)
        }
        return hits.sortedBy { it.latencyMs }
    }

    private fun httpProxyWorks(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        // Waves 1 + 2: TCP connect + CONNECT handshake.
        val handshake = Socket().use { s ->
            protectOutboundSocket(s) // test from THIS network even while a tunnel is up
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 4_000
            s.getOutputStream().apply {
                write("CONNECT www.google.com:443 HTTP/1.1\r\nHost: www.google.com:443\r\n\r\n".toByteArray())
                flush()
            }
            val status = readLine(s.getInputStream())
            status.startsWith("HTTP/1.") && status.contains(" 200")
        }
        if (!handshake) return@runCatching false
        // Wave 3: real plain-HTTP request through the proxy.
        Socket().use { s ->
            protectOutboundSocket(s) // test from THIS network even while a tunnel is up
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 5_000
            s.getOutputStream().apply {
                write(
                    ("GET http://cp.cloudflare.com/generate_204 HTTP/1.1\r\nHost: cp.cloudflare.com\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(),
                )
                flush()
            }
            readLine(s.getInputStream()).contains(" 204")
        }
    }.getOrDefault(false)

    private fun socks5ProxyWorks(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { s ->
            protectOutboundSocket(s) // test from THIS network even while a tunnel is up
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 5_000
            val input = s.getInputStream()
            val output = s.getOutputStream()
            // Wave 2: greeting, no-auth.
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greet = ByteArray(2)
            if (!readFully(input, greet) || greet[0].toInt() != 0x05 || greet[1].toInt() != 0x00) return@runCatching false
            // Wave 3: CONNECT cp.cloudflare.com:80 and fetch generate_204.
            val domain = "cp.cloudflare.com".toByteArray()
            val req = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00, 0x03, domain.size.toByte()))
                write(domain)
                write(0)
                write(80)
            }.toByteArray()
            output.write(req)
            output.flush()
            val head = ByteArray(4)
            if (!readFully(input, head) || head[1].toInt() != 0x00) return@runCatching false
            val skip = when (head[3].toInt()) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> input.read().coerceAtLeast(0)
                else -> return@runCatching false
            } + 2
            if (!readFully(input, ByteArray(skip))) return@runCatching false
            output.write("GET /generate_204 HTTP/1.1\r\nHost: cp.cloudflare.com\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            readLine(input).contains(" 204")
        }
    }.getOrDefault(false)

    // ---------------------------------------------------------------------------------------
    // SNI scanner (TLS hostname / domain-fronting check)
    // ---------------------------------------------------------------------------------------

    data class SniResult(val sni: String, val ok: Boolean, val certMatches: Boolean, val latencyMs: Long, val detail: String)

    val DEFAULT_SNIS = listOf(
        "www.speedtest.net", "discord.com", "www.visa.com", "zula.ir", "cdnjs.com", "www.cloudflare.com",
        "workers.dev", "chatgpt.com", "www.digikala.com", "www.aparat.com", "web.bale.ai", "www.shaparak.ir",
    )

    /** TLS handshakes to [ip]:[port] with each SNI; reports success, latency and certificate/hostname match. */
    fun scanSni(
        ip: String,
        port: Int,
        snis: List<String>,
        timeoutMs: Int = 4_000,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): List<SniResult> {
        val results = ConcurrentLinkedQueue<SniResult>()
        runParallel(snis, 8, progress, cancelled) { sni -> results += probeSni(ip, port, sni, timeoutMs) }
        return results.sortedWith(compareBy<SniResult>({ !it.ok }, { !it.certMatches }, { it.latencyMs }))
    }

    fun probeSni(ip: String, port: Int, sni: String, timeoutMs: Int = 4_000): SniResult {
        val started = System.nanoTime()
        var rawRef: Socket? = null
        return runCatching {
            val raw = Socket()
            rawRef = raw
            protectOutboundSocket(raw) // test from THIS network even while a tunnel is up
            raw.connect(InetSocketAddress(ip, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val ssl = trustAllContext.socketFactory.createSocket(raw, sni, port, true) as SSLSocket
            ssl.use { socket ->
                socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
                socket.startHandshake()
                val matches = runCatching {
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, socket.session)
                }.getOrDefault(false)
                val ms = (System.nanoTime() - started) / 1_000_000
                SniResult(sni, true, matches, ms, socket.session.protocol ?: "TLS")
            }
        }.getOrElse { error ->
            // beta205: a failed connect/handshake left the raw socket (and its fd) open — a 12-SNI × many-IP scan
            // leaked hundreds of descriptors. Closing an already-closed socket is a no-op.
            runCatching { rawRef?.close() }
            SniResult(sni, false, false, (System.nanoTime() - started) / 1_000_000, error.javaClass.simpleName)
        }
    }

    private val trustAllContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(
                null,
                arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }),
                SecureRandom(),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Speed & Loss rank
    // ---------------------------------------------------------------------------------------

    data class RankResult(
        val host: String,
        val port: Int,
        val sent: Int,
        val received: Int,
        val medianMs: Long,
        val jitterMs: Long,
        val downloadKbps: Long?,
    ) {
        val lossPercent: Int get() = if (sent == 0) 100 else ((sent - received) * 100) / sent
        val endpoint: String get() = "${hostForUri(host)}:$port"
    }

    /**
     * For every target: [samples] TCP connects (loss % + median latency + jitter), then — for the
     * best few — a real download measurement through [IpScanner.measureDownloadSpeed].
     */
    fun rankSpeedAndLoss(
        targets: List<Target>,
        defaultPort: Int,
        samples: Int = 8,
        measureDownloadTop: Int = 5,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): List<RankResult> {
        val results = ConcurrentLinkedQueue<RankResult>()
        runParallel(targets, 24, progress, cancelled) { t ->
            val port = t.port ?: defaultPort
            val rtts = ArrayList<Long>()
            repeat(samples) {
                if (cancelled()) return@repeat
                val started = System.nanoTime()
                val ok = runCatching {
                    Socket().use { s -> protectOutboundSocket(s); s.connect(InetSocketAddress(t.host, port), 1_500) }
                    true
                }.getOrDefault(false)
                if (ok) rtts += (System.nanoTime() - started) / 1_000_000
            }
            val sorted = rtts.sorted()
            val median = if (sorted.isEmpty()) Long.MAX_VALUE else sorted[sorted.size / 2]
            val jitter = if (sorted.size < 2) 0L else sorted.zipWithNext { a, b -> b - a }.average().toLong()
            results += RankResult(t.host, port, samples, rtts.size, median, jitter, null)
        }
        val ranked = results.filter { it.received > 0 }
            .sortedWith(compareBy<RankResult>({ it.lossPercent }, { it.medianMs }, { it.jitterMs }))
            .toMutableList()
        for (i in 0 until minOf(measureDownloadTop, ranked.size)) {
            if (cancelled()) break
            val r = ranked[i]
            val bps = runCatching { IpScanner.measureDownloadSpeed(r.host, r.port, timeoutMs = 7_000, maxBytes = 4_000_000) }.getOrNull()
            ranked[i] = r.copy(downloadKbps = bps?.let { it / 1000 })
        }
        return ranked + results.filter { it.received == 0 }
    }

    // ---------------------------------------------------------------------------------------
    // DNS resolver / tunnel-readiness scan
    // ---------------------------------------------------------------------------------------

    data class ResolverResult(
        val ip: String,
        val port: Int,
        val reachable: Boolean,
        val recursive: Boolean,
        val edns0: Boolean,
        val longQnameOk: Boolean,
        val txtOk: Boolean,
        val latencyMs: Long,
    ) {
        /** 0..4 — how suitable this resolver is for a DNS tunnel (dnstt / NoizDNS / VayDNS). */
        val tunnelScore: Int
            get() = listOf(recursive, edns0, longQnameOk, txtOk).count { it }
        val endpoint: String get() = "${hostForUri(ip)}:$port"
    }

    val DEFAULT_RESOLVERS = listOf(
        "178.22.122.100", "185.51.200.2", "10.202.10.10", "10.202.10.11", "217.218.127.127", "217.218.155.155",
        "2.188.21.131", "5.202.100.100", "78.157.42.100", "78.157.42.101", "1.1.1.1", "8.8.8.8", "9.9.9.9",
    )

    fun scanResolvers(
        targets: List<Target>,
        timeoutMs: Int = 2_500,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): List<ResolverResult> {
        val results = ConcurrentLinkedQueue<ResolverResult>()
        runParallel(targets, 16, progress, cancelled) { t -> results += probeResolver(t.host, t.port ?: 53, timeoutMs) }
        return results.sortedWith(compareBy<ResolverResult>({ -it.tunnelScore }, { !it.reachable }, { it.latencyMs }))
    }

    fun probeResolver(ip: String, port: Int, timeoutMs: Int = 2_500): ResolverResult {
        val started = System.nanoTime()
        val basic = dnsExchange(ip, port, buildDnsQuery("www.google.com", 1, edns = true), timeoutMs)
        val latency = (System.nanoTime() - started) / 1_000_000
        if (basic == null) return ResolverResult(ip, port, false, false, false, false, false, latency)
        val flags = ((basic[2].toInt() and 0xFF) shl 8) or (basic[3].toInt() and 0xFF)
        val rcode = flags and 0x0F
        val ancount = ((basic[6].toInt() and 0xFF) shl 8) or (basic[7].toInt() and 0xFF)
        val arcount = ((basic[10].toInt() and 0xFF) shl 8) or (basic[11].toInt() and 0xFF)
        val recursive = (flags and 0x0080) != 0 && rcode == 0 && ancount > 0
        val edns = arcount > 0
        val random = SecureRandom()
        fun label(len: Int) = (1..len).map { "abcdefghijklmnopqrstuvwxyz234567"[random.nextInt(32)] }.joinToString("")
        val longName = "${label(60)}.${label(60)}.${label(40)}.google.com"
        val longReply = dnsExchange(ip, port, buildDnsQuery(longName, 16, edns = true), timeoutMs)
        val txtReply = dnsExchange(ip, port, buildDnsQuery("google.com", 16, edns = true), timeoutMs)
        val txtOk = txtReply != null && (txtReply[3].toInt() and 0x0F) == 0 &&
            (((txtReply[6].toInt() and 0xFF) shl 8) or (txtReply[7].toInt() and 0xFF)) > 0
        return ResolverResult(ip, port, true, recursive, edns, longReply != null, txtOk, latency)
    }

    internal fun buildDnsQuery(name: String, qtype: Int, edns: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val id = SecureRandom().nextInt(0xFFFF)
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // RD
        out.write(0); out.write(1) // QDCOUNT
        out.write(0); out.write(0) // ANCOUNT
        out.write(0); out.write(0) // NSCOUNT
        out.write(0); out.write(if (edns) 1 else 0) // ARCOUNT
        name.trimEnd('.').split('.').forEach { part ->
            val bytes = part.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size.coerceAtMost(63))
            out.write(bytes, 0, bytes.size.coerceAtMost(63))
        }
        out.write(0)
        out.write(qtype shr 8); out.write(qtype and 0xFF)
        out.write(0); out.write(1) // IN
        if (edns) {
            out.write(0) // root
            out.write(0); out.write(41) // OPT
            out.write(1232 shr 8); out.write(1232 and 0xFF) // UDP payload size
            repeat(4) { out.write(0) } // ext-rcode, version, flags
            out.write(0); out.write(0) // RDLEN
        }
        return out.toByteArray()
    }

    private fun dnsExchange(ip: String, port: Int, query: ByteArray, timeoutMs: Int): ByteArray? = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            protectOutboundDatagramSocket(socket)
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(ip), port))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            if (packet.length < 12 || buffer[0] != query[0] || buffer[1] != query[1]) null else buffer.copyOf(packet.length)
        }
    }.getOrNull()

    // ---------------------------------------------------------------------------------------
    // Config Maker
    // ---------------------------------------------------------------------------------------

    private val REWRITABLE_SCHEMES = setOf("vless", "trojan", "vmess", "ss", "hysteria2", "hy2", "tuic", "anytls")

    /**
     * Rewrites every share link in [configs] onto every clean endpoint in [endpoints]. The original
     * host is kept as SNI / Host header so TLS and CDN routing still work. An endpoint written as
     * `IP:port` keeps exactly that port; a bare IP keeps the config's own port (never a guessed one).
     */
    fun makeConfigs(configs: List<String>, endpoints: List<Target>, maxOutput: Int = 2_000): List<String> {
        val out = LinkedHashSet<String>()
        for (config in configs.map(String::trim).filter { it.contains("://") }) {
            for (endpoint in endpoints) {
                if (out.size >= maxOutput) return out.toList()
                rewriteEndpoint(config, endpoint.host, endpoint.port)?.let(out::add)
            }
        }
        return out.toList()
    }

    fun rewriteEndpoint(link: String, newHost: String, newPort: Int?): String? {
        val scheme = link.substringBefore("://").lowercase(Locale.US)
        if (scheme !in REWRITABLE_SCHEMES) return null
        if (scheme == "vmess") return rewriteVmess(link, newHost, newPort)
        val body = link.substringAfter("://")
        val fragment = body.substringAfter('#', "")
        val beforeFragment = body.substringBefore('#')
        val query = beforeFragment.substringAfter('?', "")
        val authorityAndPath = beforeFragment.substringBefore('?')
        val userInfo = authorityAndPath.substringBeforeLast('@', "")
        val hostPortPath = authorityAndPath.substringAfterLast('@')
        val hostPort = hostPortPath.substringBefore('/')
        val path = hostPortPath.substring(hostPort.length)
        val (oldHost, oldPort) = splitHostPort(hostPort) ?: return null
        val port = newPort ?: oldPort ?: return null
        val params = LinkedHashMap<String, String>()
        if (query.isNotEmpty()) {
            query.split('&').forEach { pair ->
                val k = pair.substringBefore('=')
                if (k.isNotEmpty()) params[k] = pair.substringAfter('=', "")
            }
        }
        if (!isIpLiteral(oldHost)) {
            if (params["sni"].isNullOrBlank() && params["peer"].isNullOrBlank()) params["sni"] = oldHost
            val network = params["type"]?.lowercase(Locale.US)
            if (network in setOf("ws", "httpupgrade", "xhttp", "splithttp", "h2", "http") && params["host"].isNullOrBlank()) {
                params["host"] = oldHost
            }
        }
        val label = decode(fragment).ifBlank { oldHost }
        val newLabel = URLEncoder.encode("$label | $newHost:$port", "UTF-8").replace("+", "%20")
        val newQuery = params.entries.joinToString("&") { (k, v) -> if (v.isEmpty()) k else "$k=$v" }
        val authority = (if (userInfo.isNotEmpty()) "$userInfo@" else "") + "${hostForUri(newHost)}:$port"
        return "$scheme://$authority$path${if (newQuery.isNotEmpty()) "?$newQuery" else ""}#$newLabel"
    }

    private fun rewriteVmess(link: String, newHost: String, newPort: Int?): String? = runCatching {
        val payload = link.substringAfter("://").substringBefore('#').trim()
        val json = JSONObject(String(decodeBase64Any(payload), Charsets.UTF_8))
        val oldHost = json.optString("add")
        val port = newPort ?: json.optString("port").toIntOrNull() ?: return null
        if (!isIpLiteral(oldHost) && oldHost.isNotBlank()) {
            if (json.optString("sni").isBlank() && json.optString("tls").isNotBlank()) json.put("sni", oldHost)
            if (json.optString("host").isBlank()) json.put("host", oldHost)
        }
        json.put("add", newHost)
        json.put("port", port.toString())
        json.put("ps", "${json.optString("ps").ifBlank { oldHost }} | $newHost:$port")
        "vmess://" + Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }.getOrNull()

    /** Extracts unique `host:port` endpoints from share links (Config Maker "extract" mode). */
    fun extractEndpoints(configs: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        configs.map(String::trim).filter { it.contains("://") }.forEach { link ->
            val scheme = link.substringBefore("://").lowercase(Locale.US)
            if (scheme == "vmess") {
                runCatching {
                    val json = JSONObject(String(decodeBase64Any(link.substringAfter("://").substringBefore('#')), Charsets.UTF_8))
                    val host = json.optString("add")
                    val port = json.optString("port")
                    if (host.isNotBlank() && port.isNotBlank()) out += "${hostForUri(host)}:$port"
                }
                return@forEach
            }
            val hostPort = link.substringAfter("://").substringBefore('#').substringBefore('?')
                .substringAfterLast('@').substringBefore('/')
            splitHostPort(hostPort)?.let { (h, p) -> if (p != null) out += "${hostForUri(h)}:$p" }
        }
        return out.toList()
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    internal fun splitHostPort(hostPort: String): Pair<String, Int?>? {
        if (hostPort.isBlank()) return null
        if (hostPort.startsWith("[")) {
            val host = hostPort.substringAfter('[').substringBefore(']')
            return host to hostPort.substringAfter("]:", "").toIntOrNull()
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0 || hostPort.count { it == ':' } > 1) return hostPort to null
        return hostPort.substring(0, colon) to hostPort.substring(colon + 1).toIntOrNull()
    }

    internal fun isIpLiteral(host: String): Boolean =
        host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || (':' in host && host.all { it.isLetterOrDigit() || it == ':' || it == '.' })

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun decodeBase64Any(value: String): ByteArray {
        val clean = value.trim().replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return Base64.getDecoder().decode(padded)
    }

    private fun <T> runParallel(
        items: List<T>,
        parallelism: Int,
        progress: (done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean,
        work: (T) -> Unit,
    ) {
        if (items.isEmpty()) return
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, 64))
        val done = AtomicInteger()
        try {
            val futures = items.map { item ->
                pool.submit(Callable {
                    if (!cancelled()) runCatching { work(item) }
                    progress(done.incrementAndGet(), items.size)
                })
            }
            futures.forEach { runCatching { it.get(60, TimeUnit.SECONDS) } }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun httpGet(url: URL, timeoutMs: Int): String? {
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "CatClient-Toolkit")
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readLine(input: java.io.InputStream, max: Int = 512): String {
        val sb = StringBuilder()
        while (sb.length < max) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n <= 0) return false
            offset += n
        }
        return true
    }
}

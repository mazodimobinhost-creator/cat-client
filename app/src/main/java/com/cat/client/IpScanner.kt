package com.cat.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * IpScanner — clean-IP scanner that actually finishes on Iranian networks.
 *
 * v1 only ran a TLS handshake and threw the result away on any failure, so a
 * single blocked SNI wiped out every row. v2 probes in two stages:
 *
 *  1. **TCP connect** to `ip:port` — cheap, needs no DNS and no working SNI, so
 *     every reachable candidate produces a row.
 *  2. **TLS handshake with the configured SNI** plus an HTTP `GET /cdn-cgi/trace`
 *     over that socket — this is what proves the IP can actually serve the
 *     panel domain you are fronting. The trace body also tells us the colo.
 *
 * Results carry both numbers; the UI sorts by TCP and marks TLS failures, so the
 * user always sees the fastest reachable IPs even when the SNI is unavailable.
 */
object IpScanner {

    data class ScanOptions(
        val sni: String = "skk.moe",
        val port: Int = 443,
        val concurrency: Int = 24,
        val connectTimeoutMs: Int = 1500,
        val tlsTimeoutMs: Int = 2500,
        val verifyHttp: Boolean = true,
        val customSubnets: String = "",
        val includeBuiltin: Boolean = true,
        val includeIranLibrary: Boolean = true,
        /** How many hosts to draw from every CIDR range per run. */
        val perRange: Int = DEFAULT_PER_RANGE,
        /** Draw a random host from each slice of the range so repeated scans discover new IPs. */
        val randomSample: Boolean = true,
        /** Walk IPv6 ranges too. The UI sets this from [hasIpv6Connectivity] so v6
         * candidates are only spent when the carrier actually routes v6. */
        val includeIpv6: Boolean = false,
    )

    const val DEFAULT_PER_RANGE = 24

    /** Recommended SNIs: the five curated defaults first — the suggestion chips,
     * `.first()` and the health-check retry budget keep behaving exactly as before —
     * then the Cloudflare-verified rotation pool the panel ships (v5.23.12) so the
     * app and the panel offer the same hosts. The user's own Cat Panel host is
     * always added by the UI. */
    val RECOMMENDED_SNIS: List<String> = listOf(
        "skk.moe",
        "www.speedtest.net",
        "cdnjs.cloudflare.com",
        "speed.cloudflare.com",
        "www.visa.com",
        "www.cloudflare.com",
        "time.is",
        "doi.org",
        "api.ip.sb",
        "cdn.discordapp.com",
        "gateway.discord.gg",
        "www.icook.tw",
        "nodejs.org",
        "gitlab.com",
        "about.gitlab.com",
        "openai.com",
        "chatgpt.com",
        "signal.org",
        "cdn.jsdelivr.net",
        "www.ecosia.org",
        "www.udemy.com",
        "www.okx.com",
        "www.coinbase.com",
        "kraken.com",
        "www.digitalocean.com",
        "www.w3.org",
        "www.iana.org",
        "www.rfc-editor.org",
        "www.pcmag.com",
        "a.hcaptcha.com",
        "accounts.hcaptcha.com",
        "acme-staging-v02.api.letsencrypt.org",
        "acme-v02.api.letsencrypt.org",
        "ajax.cloudflare.com",
        "alternativeto.net",
        "api.cloudflare.com",
        "api.hcaptcha.com",
        "api.openai.com",
        "assets.hcaptcha.com",
        "astro.build",
        "auth.vercel.com",
        "billing.hcaptcha.com",
        "brilliant.org",
        "bun.sh",
        "cached-queries.hcaptcha.com",
        "calendly.com",
        "cdnjs.com",
        "challenge-tasks.hcaptcha.com",
        "challenges.cloudflare.com",
        "charlie.hcaptcha.com",
        "check-host.net",
        "chunker.hcaptcha.com",
        "cloudflare-dns.com",
        "codepen.io",
        "dash.cloudflare.com",
        "dashboard.hcaptcha.com",
        "defillama.com",
        "demo.hcaptcha.com",
        "developers.cloudflare.com",
        "diagrams.net",
        "directus.io",
        "dnschecker.org",
        "email.hcaptcha.com",
        "etherscan.io",
        "exchange.hcaptcha.com",
        "exercism.org",
        "factored-cognition.hcaptcha.com",
        "fantasia-assets.hcaptcha.com",
        "fontawesome.com",
        "getbootstrap.com",
        "gitbook.com",
        "hcaptcha.com",
        "health-check.hcaptcha.com",
        "hmt-elegant-rosalind.hcaptcha.com",
        "hmt-eloquent-mclaren.hcaptcha.com",
        "hmt-lucid-neumann.hcaptcha.com",
        "hmt-pensive-torvalds.hcaptcha.com",
        "hono.dev",
        "i2.hcaptcha.com",
        "imgs.hcaptcha.com",
        "imgs2.hcaptcha.com",
        "imgs3.hcaptcha.com",
        "jobs.hcaptcha.com",
        "labeling-masters.hcaptcha.com",
        "loader.hcaptcha.com",
        "maxcdn.bootstrapcdn.com",
        "metamask.io",
        "mozilla.cloudflare-dns.com",
        "netdna.bootstrapcdn.com",
        "newassets.hcaptcha.com",
        "npmjs.com",
        "onesignal.com",
        "pages.cloudflare.com",
        "past-issuer.hcaptcha.com",
        "pat-internal.hcaptcha.com",
        "photopea.com",
        "phpbb.com",
        "postman.com",
        "pre.hcaptcha.com",
        "primary.hcaptcha.com",
        "prometheus.io",
        "proxy.hcaptcha.com",
        "pst-sample.hcaptcha.com",
        "radar.cloudflare.com",
        "registry.npmjs.org",
        "remove.bg",
        "replicate.com",
        "replit.com",
        "risk-prod-srv.hcaptcha.com",
        "security.vercel.com",
        "securitytrails.com",
        "sourceforge.net",
        "speedtest.org",
        "stackpath.bootstrapcdn.com",
        "static.cloudflareinsights.com",
        "styler.hcaptcha.com",
        "tailwindcss.com",
        "tandfonline.com",
        "temple-gates.hcaptcha.com",
        "tg.hcaptcha.com",
        "three-cust-imgs.hcaptcha.com",
        "three-cust.hcaptcha.com",
        "tp.hcaptcha.com",
        "tractionrec.hcaptcha.com",
        "u.hcaptcha.com",
        "unpkg.com",
        "uptimerobot.com",
        "whoer.net",
        "workers.cloudflare.com",
        "www-canary.hcaptcha.com",
        "www.alchemy.com",
        "www.bitwarden.com",
        "www.canva.com",
        "www.codecademy.com",
        "www.coingecko.com",
        "www.crunchbase.com",
        "www.crunchyroll.com",
        "www.discord.com",
        "www.fiverr.com",
        "www.freecodecamp.org",
        "www.garmin.com",
        "www.gitlab.com",
        "www.glassdoor.com",
        "www.greasyfork.org",
        "www.gumroad.com",
        "www.hackerone.com",
        "www.hcaptcha.com",
        "www.hubspot.com",
        "www.investing.com",
        "www.medium.com",
        "www.monday.com",
        "www.namecheap.com",
        "www.npmjs.com",
        "www.patreon.com",
        "www.perplexity.ai",
        "www.producthunt.com",
        "www.quora.com",
        "www.researchgate.net",
        "www.signal.org",
        "www.time.is",
        "www.toptal.com",
        "www.upwork.com",
        "www.vimeo.com",
        "www.ycombinator.com",
        "www.zendesk.com",
        "addtoany.com",
        "atera.com",
        "belkin.com",
        "blacktoon410.com",
        "blueapron.com",
        "braze.com",
        "buzzsprout.com",
        "coingecko.com",
        "discord.com",
        "discord.media",
        "doxygen.nl",
        "easybrain.com",
        "eatingwell.com",
        "ekantipur.com",
        "elementor.com",
        "expireddomains.com",
        "federalreserve.gov",
        "filmyzilla34.com",
        "filmyzilla36.com",
        "geediting.com",
        "gist.build",
        "gitlab.io",
        "gulfnews.com",
        "haberler.com",
        "handle.net",
        "homestead.com",
        "hostgator.com",
        "ico.org.uk",
        "ietf.org",
        "incognia.com",
        "ispconfig.org",
        "jamanetwork.com",
        "japantimes.co.jp",
        "khaleejtimes.com",
        "kit.com",
        "leetcode.com",
        "mp4moviez.date",
        "myfitnesspal.com",
        "mygaru.com",
        "name.com",
        "news24.com",
        "nextdns.io",
        "npmjs.org",
        "oaistatic.com",
        "pcmag.com",
        "pravda.com.ua",
        "preply.com",
        "producthunt.com",
        "prweb.com",
        "reverso.net",
        "rocketreach.co",
        "rome2rio.com",
        "sattamatkadpboss.co",
        "scmp.com",
        "sibforms.com",
        "snowflake.com",
        "southernliving.com",
        "takeaway.com",
        "tanium.com",
        "theiconic.com.au",
        "themeforest.net",
        "theregister.co.uk",
        "thesaurus.com",
        "transcend-cdn.com",
        "udemy.com",
        "useinsider.com",
        "verywellmind.com",
        "vidtronx.com",
        "vinted.com",
        "vivo.com.br",
        "wa.link",
        "warthunder.com",
        "winvidplay.com",
        "worldometers.info",
        "wpengine.com",
        "x.com",
        "xn--1-wxfc3gwbi.net",
        "xn--69-6tia3cb.com",
        "zedge.net",
        "zoominfo.com",
    )

    /**
     * Range-first defaults shown in the scanner field. These are the Cloudflare
     * anycast blocks that most Iranian ISPs still reach; the user edits them freely.
     */
    val DEFAULT_RANGES: List<String> = listOf(
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "162.158.0.0/15",
        "162.159.0.0/16",
        "162.159.128.0/20",
        "162.159.192.0/24",
        "188.114.96.0/20",
        "141.101.64.0/18",
        "108.162.192.0/18",
        "198.41.128.0/17",
        "103.21.244.0/22",
        "103.22.200.0/22",
        "103.31.4.0/22",
        "131.0.72.0/22",
        "173.245.48.0/20",
        "190.93.240.0/20",
        "197.234.240.0/22",
        "199.27.128.0/21",
    )

    /** Cloudflare IPv6 blocks; scanned only when the device has working v6. */
    val IPV6_RANGES: List<String> = listOf("2606:4700::/32", "2a06:98c0::/29")

    /**
     * True when the device can actually open a v6 TCP connection to Cloudflare —
     * a routable v6 address on an interface is not enough on many Iranian carriers.
     */
    fun hasIpv6Connectivity(timeoutMs: Int = 1200): Boolean =
        tcpConnect("2606:4700:4700::1111", 443, timeoutMs) != null || tcpConnect("2606:4700::6810:84e5", 443, timeoutMs) != null

    fun isIpv6(value: String): Boolean = value.contains(':')

    fun defaultRangesText(): String = DEFAULT_RANGES.joinToString(", ")

    data class ScanResult(
        val ip: String,
        val pingMs: Long,
        val sni: String,
        val tlsMs: Long? = null,
        val colo: String? = null,
        val tlsOk: Boolean = false,
        val httpStatus: Int? = null,
        /** Port that was probed; retained so a result can be audited or rebuilt. */
        val port: Int = 443,
        /** Country of the responding edge, never guessed from the anycast IP itself. */
        val countryCode: String? = null,
        val countryName: String? = null,
        val sourceRange: String? = null,
    ) {
        val flag: String get() = countryCode?.toFlagEmoji() ?: "🌐"

        /** `addr#CC` — the form Cat Panel's IP list understands (country tag per address). */
        val panelLine: String get() = if (countryCode != null) "$ip#$countryCode" else ip

        /** Colour band used by the UI: green < 300 ms, amber < 700 ms, red above. */
        val band: Int get() = when {
            pingMs < 300 -> 0
            pingMs < 700 -> 1
            else -> 2
        }
    }

    /** Clean-IP library for Iranian networks (public Cloudflare anycast edges). */
    val IRAN_LIBRARY: List<String> = listOf(
        "104.16.0.1", "104.16.132.229", "104.17.0.1", "104.17.148.22", "104.18.0.1",
        "104.19.0.1", "104.20.0.1", "104.21.0.1", "104.22.0.1", "104.24.0.1",
        "104.25.0.1", "104.26.0.1", "104.27.0.1", "104.28.0.1", "104.31.0.1",
        "172.64.0.1", "172.64.80.1", "172.65.0.1", "172.66.0.1", "172.67.0.1",
        "172.68.0.1", "172.69.0.1", "172.70.0.1", "172.71.0.1",
        "162.158.0.1", "162.158.80.1", "162.159.0.1", "162.159.128.1", "162.159.192.1",
        "141.101.64.1", "141.101.90.1", "108.162.192.1", "108.162.220.1",
        "188.114.96.1", "190.93.240.1", "197.234.240.1", "198.41.128.1",
        "103.21.244.1", "103.22.200.1", "103.31.4.1", "131.0.72.1", "173.245.48.1",
    )

    // Keep the non-UI scanner path aligned with the complete curated list too.
    private val BUILTIN_RANGES: List<String> = DEFAULT_RANGES

    /**
     * Scans every candidate concurrently and streams progress as results arrive.
     * [onProgress] is invoked with (done, total, resultOrNull) from an IO thread
     * as soon as each candidate finishes, so the UI can show a live percentage
     * and append rows while the scan is still running.
     */
    suspend fun scan(
        context: Context,
        options: ScanOptions,
        onProgress: ((Int, Int, ScanResult?) -> Unit)? = null,
    ): List<ScanResult> = coroutineScope {
        val candidates = buildCandidateList(options)
        val total = candidates.size
        if (total == 0) return@coroutineScope emptyList()
        val gate = Semaphore(options.concurrency.coerceIn(1, 64))
        var done = 0
        val results = ArrayList<ScanResult>(total)
        val jobs = ArrayList<Job>(total)
        candidates.forEach { ip ->
            jobs += launch(Dispatchers.IO) {
                gate.withPermit {
                    val result = probe(ip, options)
                    synchronized(results) {
                        if (result != null) results += result
                        done += 1
                        onProgress?.invoke(done, total, result)
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        results.sortedWith(compareBy({ it.pingMs }, { if (it.tlsOk) 0 else 1 }))
    }

    /** Two-stage probe: TCP connect, then TLS + optional HTTP trace. */
    internal fun probe(ip: String, options: ScanOptions): ScanResult? {
        val tcpMs = tcpConnect(ip, options.port, options.connectTimeoutMs) ?: return null
        val sourceRange = if (isValidIpv4(ip)) {
            (parseRangeList(options.customSubnets) + DEFAULT_RANGES).firstOrNull { cidrContains(ip, it) }
        } else if (isValidHostname(ip)) {
            "domain"
        } else {
            "ipv6"
        }
        if (!options.verifyHttp && options.sni.isBlank()) {
            return ScanResult(
                ip = ip,
                pingMs = tcpMs,
                sni = options.sni,
                port = options.port,
                countryName = "Cloudflare edge",
                sourceRange = sourceRange,
            )
        }
        val tls = tlsProbe(ip, options.port, options.sni, options.tlsTimeoutMs, options.verifyHttp)
        val edge = EdgeLocationCatalog.fromColo(tls?.colo)
        return ScanResult(
            ip = ip,
            pingMs = tls?.ms ?: tcpMs,
            sni = options.sni,
            port = options.port,
            tlsMs = tls?.ms,
            colo = tls?.colo,
            tlsOk = tls != null,
            httpStatus = tls?.status,
            countryCode = edge?.countryCode,
            countryName = edge?.label ?: "Cloudflare edge",
            sourceRange = sourceRange,
        )
    }

    /** Returns the connect time in ms, or null when the socket never opened. */
    internal fun tcpConnect(ip: String, port: Int, timeoutMs: Int): Long? {
        val started = System.nanoTime()
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            (System.nanoTime() - started) / 1_000_000
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    internal data class TlsProbe(val ms: Long, val colo: String?, val status: Int?)

    /**
     * TLS handshake against [ip] with [sni] pinned via SSLParameters (the public
     * SSLSocket API has no setSNIHostName), then an optional HTTP trace request
     * that proves the edge really answers for this SNI.
     */
    internal fun tlsProbe(
        ip: String,
        port: Int,
        sni: String,
        timeoutMs: Int,
        http: Boolean,
    ): TlsProbe? {
        val started = System.nanoTime()
        var socket: SSLSocket? = null
        return try {
            val connectedSocket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            socket = connectedSocket
            connectedSocket.apply {
                soTimeout = timeoutMs
                tcpNoDelay = true
                connect(InetSocketAddress(ip, port), timeoutMs)
                if (sni.isNotBlank()) {
                    val params = sslParameters
                    params.serverNames = listOf(SNIHostName(sni))
                    params.endpointIdentificationAlgorithm = null
                    sslParameters = params
                }
                startHandshake()
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            if (!http) return TlsProbe(ms, null, null)
            val trace = readTrace(connectedSocket, sni, timeoutMs)
            TlsProbe(ms, trace?.first, trace?.second)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** `GET /cdn-cgi/trace` over the established TLS socket → (colo, status). */
    private fun readTrace(socket: Socket, host: String, timeoutMs: Int): Pair<String, Int>? {
        return try {
            val request = buildString {
                append("GET /cdn-cgi/trace HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: CatClient\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine() ?: return null
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            val body = StringBuilder()
            var line: String?
            var inBody = false
            var guard = 0
            while (guard++ < 200) {
                line = reader.readLine() ?: break
                if (inBody) {
                    body.append(line).append('\n')
                    if (body.length > 4096) break
                } else if (line.isEmpty()) {
                    inBody = true
                }
            }
            val colo = Regex("^colo=(\\S+)", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)
            Pair(colo.orEmpty(), status ?: 0)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Expands one CIDR into up to [limitPerSubnet] candidate hosts. The block is
     * split into equal slices and one host is taken from each; with [random] the
     * host is drawn at a random offset inside its slice, so two scans of the same
     * range test different addresses (range-first walking).
     * Network (`.0`) and broadcast-looking (`.255`) hosts are skipped.
     */
    fun expandSubnet(cidr: String, limitPerSubnet: Int = 80, random: Boolean = false): List<String> {
        val trimmed = cidr.trim()
        if (!trimmed.contains("/")) {
            return if (isValidIpv4(trimmed) || isValidIpv6(trimmed) || isValidHostname(trimmed)) listOf(trimmed) else emptyList()
        }
        if (trimmed.contains(':')) return expandIpv6Subnet(trimmed, limitPerSubnet, random)
        val (ipPart, prefixPart) = trimmed.split("/", limit = 2)
        val prefix = prefixPart.toIntOrNull() ?: return emptyList()
        val ipBytes = ipPart.split(".").map { it.toIntOrNull() ?: return emptyList() }
        if (ipBytes.size != 4 || ipBytes.any { it !in 0..255 } || prefix !in 8..32) return emptyList()
        val ipInt = ipBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val hostBits = 32 - prefix
        if (hostBits == 0) return listOf(longToIp(ipInt))
        val netMask = (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
        val network = ipInt and netMask
        val blockSize = 1L shl hostBits.coerceAtMost(20)
        val want = limitPerSubnet.toLong().coerceIn(1L, (blockSize - 1).coerceAtLeast(1L))
        val slice = blockSize.toDouble() / want.toDouble()
        val out = LinkedHashSet<String>()
        for (i in 0 until want) {
            val jitter = if (random) Math.random() * slice else slice / 2.0
            var offset = (i * slice + jitter).toLong().coerceIn(1L, blockSize - 1)
            val last = offset and 0xFF
            if (last == 0L && offset + 1 <= blockSize - 1) offset += 1
            else if (last == 255L && offset - 1 >= 1) offset -= 1
            out.add(longToIp(network + offset))
        }
        return out.toList()
    }

    /**
     * IPv6 CIDR → up to [limitPerSubnet] random hosts inside the prefix. Cloudflare's
     * v6 blocks (2606:4700::/32, 2a06:98c0::/29) answer on every address, so random
     * sampling is as good as walking and finds fresh ones on each run.
     */
    internal fun expandIpv6Subnet(cidr: String, limitPerSubnet: Int, random: Boolean): List<String> {
        val (ipPart, prefixPart) = cidr.split("/", limit = 2)
        val prefix = prefixPart.toIntOrNull() ?: return emptyList()
        if (prefix !in 16..128) return emptyList()
        val base = runCatching { InetAddress.getByName(ipPart) as? Inet6Address }.getOrNull()?.address ?: return emptyList()
        if (prefix == 128) return listOf(InetAddress.getByAddress(base).hostAddress ?: return emptyList())
        val rnd = java.util.Random(if (random) System.nanoTime() else 0x6CA7L)
        val out = LinkedHashSet<String>()
        var guard = 0
        while (out.size < limitPerSubnet.coerceIn(1, 256) && guard++ < limitPerSubnet * 4) {
            val bytes = base.copyOf()
            for (bit in prefix until 128) {
                val byteIndex = bit / 8
                val mask = (0x80 ushr (bit % 8)).toByte()
                val set = rnd.nextBoolean()
                bytes[byteIndex] = if (set) (bytes[byteIndex].toInt() or mask.toInt()).toByte() else (bytes[byteIndex].toInt() and mask.toInt().inv()).toByte()
            }
            // Avoid the all-zero host part (subnet-router anycast) for short prefixes.
            if (prefix <= 64 && bytes.drop(8).all { it == 0.toByte() }) bytes[15] = 1
            InetAddress.getByAddress(bytes).hostAddress?.let { out += it }
        }
        return out.toList()
    }

    fun isValidIpv6(value: String): Boolean =
        value.contains(':') && !value.contains('/') &&
            runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)

    /** A bare host name such as `www.visa.com` (scanned by resolving it on the device). */
    fun isValidHostname(value: String): Boolean =
        value.length in 4..253 && !value.contains(':') && !value.contains('/') &&
            value.contains('.') && !value.endsWith('.') &&
            !isValidIpv4(value) &&
            value.split('.').all { label -> label.isNotEmpty() && label.length <= 63 && label.all { it.isLetterOrDigit() || it == '-' } && !label.startsWith('-') && !label.endsWith('-') } &&
            value.substringAfterLast('.').let { tld -> tld.length >= 2 && tld.all(Char::isLetter) }

    /** Splits a free-form "ip, cidr, host, 2606:4700::/32" string into the entries the scanner walks. */
    fun parseRangeList(text: String): List<String> =
        text.split(",", "\n", " ", ";", "\t")
            .map { it.trim().removePrefix("[").removeSuffix("]") }
            .filter { it.isNotEmpty() }
            .filter { part -> if (part.contains("/")) expandSubnet(part, 1).isNotEmpty() else isValidIpv4(part) || isValidIpv6(part) || isValidHostname(part) }

    internal fun buildCandidateList(options: ScanOptions): List<String> {
        val ips = linkedSetOf<String>()
        val perRange = options.perRange.coerceIn(1, 256)
        // User ranges first (range-first scanner): every CIDR yields `perRange` hosts.
        val custom = parseRangeList(options.customSubnets)
        custom.forEach { part ->
            if (part.contains("/")) ips += expandSubnet(part, perRange, options.randomSample)
            else ips += part
        }
        if (options.includeBuiltin && custom.none { it.contains("/") }) {
            BUILTIN_RANGES.forEach { cidr ->
                ips += expandSubnet(cidr, perRange, options.randomSample)
            }
        }
        if (options.includeIpv6) {
            IPV6_RANGES.forEach { cidr -> ips += expandSubnet(cidr, perRange, options.randomSample) }
        } else {
            // No usable v6 on this network → drop v6 literals/ranges the user typed too.
            ips.removeAll { isIpv6(it) }
        }
        if (options.includeIranLibrary) {
            ips += IRAN_LIBRARY
            ips += CommunityIpLibrary.ips
        }
        return ips.shuffled()
    }

    fun isValidIpv4(ip: String): Boolean =
        ip.split(".").let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }

    private fun cidrContains(ip: String, cidr: String): Boolean {
        val (baseText, prefixText) = cidr.split("/", limit = 2).let { parts ->
            if (parts.size != 2) return false
            parts[0] to parts[1]
        }
        val base = baseText.split('.').map { it.toLongOrNull() ?: return false }
        val value = ip.split('.').map { it.toLongOrNull() ?: return false }
        val prefix = prefixText.toIntOrNull() ?: return false
        if (base.size != 4 || value.size != 4 || prefix !in 0..32) return false
        val baseLong = base.fold(0L) { acc, octet -> (acc shl 8) or (octet and 0xFF) }
        val valueLong = value.fold(0L) { acc, octet -> (acc shl 8) or (octet and 0xFF) }
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (baseLong and mask) == (valueLong and mask)
    }

    private fun longToIp(v: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { "${((v shr it) and 0xFF)}" }
}

/** Executes [block] on the IO dispatcher, used by the scanner UI helper below. */
internal suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

fun String.toFlagEmoji(): String {
    if (length != 2) return "🌐"
    return uppercase().map { 0x1F1E6 + (it - 'A') }.joinToString("") { String(Character.toChars(it)) }
}

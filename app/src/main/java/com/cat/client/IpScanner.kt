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
        /** TCP samples per candidate. One sample is a coin flip on a mobile
         * network: a single lost packet reads as a dead IP. The average of
         * several is the reported ping; their spread is the jitter. */
        val samples: Int = DEFAULT_SAMPLES,
        /** Ports to try, in order. A candidate is stored on the port that
         * actually answered (`ip:port`) — never on a port we assumed. */
        val ports: List<Int> = DEFAULT_SCAN_PORTS,
    )

    const val DEFAULT_PER_RANGE = 24

    /** Ports Cloudflare answers on: the three TLS ones and the two plain ones. */
    val DEFAULT_SCAN_PORTS = listOf(443, 2053, 8443, 80, 8080)

    /** 5 samples keeps a scan of a few hundred IPs inside a couple of minutes. */
    const val DEFAULT_SAMPLES = 5

    /** Upload probe body cap: an upload test spends the user's own mobile data. */
    const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024

    /** Any Cloudflare-hosted SNI works on any edge; the official speed endpoint rides along. */
    const val SPEED_TEST_SNI = "speed.cloudflare.com"

    /** Cloudflare's plain-HTTP ports; every other port (incl. custom picks) is probed with TLS. */
    val PLAIN_HTTP_PORTS = setOf(80, 8080, 8880, 2052, 2082, 2086, 2095)

    /**
     * XIU2-style download throughput probe: TLS to the edge IP with the speed-test
     * SNI, then GET /__down and count bytes for up to [timeoutMs]. Returns bytes
     * per second, or null when the edge did not deliver.
     */
    fun measureDownloadSpeed(
        ip: String,
        port: Int,
        timeoutMs: Int = 9_000,
        maxBytes: Int = 12_000_000,
    ): Long? {
        val tls = !PLAIN_HTTP_PORTS.contains(port)
        var socket: java.net.Socket? = null
        return try {
            val connected: java.net.Socket = if (tls) {
                val ssl = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                ssl.apply {
                    soTimeout = timeoutMs
                    tcpNoDelay = true
                    connect(InetSocketAddress(ip, port), timeoutMs)
                    val params = sslParameters
                    params.serverNames = listOf(SNIHostName(SPEED_TEST_SNI))
                    params.endpointIdentificationAlgorithm = null
                    sslParameters = params
                    startHandshake()
                }
            } else {
                java.net.Socket().apply {
                    soTimeout = timeoutMs
                    tcpNoDelay = true
                    connect(InetSocketAddress(ip, port), timeoutMs)
                }
            }
            socket = connected
            val request = buildString {
                append("GET /__down?bytes=").append(maxBytes).append(" HTTP/1.1\r\n")
                append("Host: ").append(SPEED_TEST_SNI).append("\r\n")
                append("User-Agent: CatClient\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n\r\n")
            }
            connected.getOutputStream().apply { write(request.toByteArray()); flush() }
            val input = connected.getInputStream()
            // Skip response headers.
            var crlf = 0
            var headerByteCount = 0
            while (headerByteCount < 32_000) {
                val b = input.read()
                if (b == -1) return null
                headerByteCount++
                crlf = if (b == '\n'.code || b == '\r'.code) crlf + 1 else 0
                if (crlf >= 4) break
            }
            if (crlf < 4) return null
            val startedAt = System.currentTimeMillis()
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            while (total < maxBytes) {
                val n = try { input.read(buffer) } catch (e: Exception) { -1 }
                if (n <= 0) break
                total += n
                if (System.currentTimeMillis() - startedAt >= timeoutMs) break
            }
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < 300 || total < 256 * 1024) return null // too little data to trust
            total * 1000 / elapsed
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * Upload throughput probe against the same Cloudflare speed endpoint:
     * TLS to the edge IP with the speed-test SNI, then POST /__up with
     * [bytes] of body and wait for the edge to answer.
     *
     * Timing note: we stop the clock when the RESPONSE arrives, not when the
     * last byte leaves the socket. Writing to a socket only fills the kernel
     * buffer, so "time to write" measures the buffer, not the network; the
     * edge answers only after it has read the whole body, which is what makes
     * the number comparable to the download probe.
     *
     * Body size is deliberately small (1 MB by default): an upload probe spends
     * the user's own mobile data, unlike the download probe.
     */
    fun measureUploadSpeed(
        ip: String,
        port: Int,
        timeoutMs: Int = 9_000,
        bytes: Int = 1_000_000,
    ): Long? {
        val capped = bytes.coerceIn(64 * 1024, MAX_UPLOAD_BYTES)
        val tls = !PLAIN_HTTP_PORTS.contains(port)
        var socket: java.net.Socket? = null
        return try {
            val connected: java.net.Socket = if (tls) {
                val ssl = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                ssl.apply {
                    soTimeout = timeoutMs
                    tcpNoDelay = true
                    connect(InetSocketAddress(ip, port), timeoutMs)
                    val params = sslParameters
                    params.serverNames = listOf(SNIHostName(SPEED_TEST_SNI))
                    params.endpointIdentificationAlgorithm = null
                    sslParameters = params
                    startHandshake()
                }
            } else {
                java.net.Socket().apply {
                    soTimeout = timeoutMs
                    tcpNoDelay = true
                    connect(InetSocketAddress(ip, port), timeoutMs)
                }
            }
            socket = connected
            val body = ByteArray(capped)
            val head = buildString {
                append("POST /__up HTTP/1.1\r\n")
                append("Host: ").append(SPEED_TEST_SNI).append("\r\n")
                append("User-Agent: CatClient\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.ISO_8859_1)
            val out = connected.getOutputStream()
            val startedAt = System.currentTimeMillis()
            out.write(head)
            val chunk = ByteArray(64 * 1024)
            var sent = 0
            while (sent < body.size) {
                val n = minOf(chunk.size, body.size - sent)
                out.write(chunk, 0, n)
                sent += n
                if (System.currentTimeMillis() - startedAt >= timeoutMs) return null // too slow to bother
            }
            out.flush()
            // The clock stops when the edge confirms it read everything.
            val input = connected.getInputStream()
            var sawResponse = false
            while (System.currentTimeMillis() - startedAt < timeoutMs) {
                val b = try { input.read() } catch (e: Exception) { -1 }
                if (b == -1) break
                sawResponse = true
                break
            }
            if (!sawResponse) return null
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < 300) return null // too little time to trust
            body.size * 1000L / elapsed
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

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
     * The v6 /48s where Cloudflare edges really answer.
     *
     * Sampling the whole `/32` used to be the strategy and it found nothing: the
     * block holds 2^96 addresses and only a handful of /48s inside it are
     * actually announced to the edges, so a random draw lands in the dark ~every
     * time. These are the slices we know answer (the first one is the v4-mapped
     * anycast block that [deriveIpv6Candidates] builds addresses in).
     */
    val IPV6_SUBNETS: List<String> = listOf(
        "2606:4700::/48",        // v4-mapped anycast: 104.16.132.229 → 2606:4700::6810:84e5
        "2a06:98c0::/48",        // same mapping, European anycast block
        "2606:4700:4700::/48",   // 1.1.1.1 / 1.0.0.1
        "2606:4700:d0::/48",
        "2606:4700:3033::/48",
    )

    /**
     * Cloudflare's dual-stack edge mapping: an IPv4 edge address is reachable as
     * `2606:4700::<hi>:<lo>` and `2a06:98c0::<hi>:<lo>` (104.16.132.229 →
     * 2606:4700::6810:84e5, because 104.16 = 0x6810 and 132.229 = 0x84e5).
     *
     * Deriving v6 candidates from v4 addresses we already believe in is worth far
     * more than another random draw: the hit rate is near 100% on networks with
     * working v6, and it costs two extra probes per v4 address.
     */
    fun deriveIpv6Candidates(v4: String): List<String> {
        val parts = v4.trim().split(".")
        if (parts.size != 4) return emptyList()
        val octets = parts.map { it.toIntOrNull() ?: return emptyList() }
        if (octets.any { it !in 0..255 }) return emptyList()
        val hi = "%02x%02x".format(octets[0], octets[1])
        val lo = "%02x%02x".format(octets[2], octets[3])
        return listOf("2606:4700::$hi:$lo", "2a06:98c0::$hi:$lo")
    }

    /**
     * Accepts everything the manual box should tolerate and returns
     * `(host, port?)`: `1.2.3.4`, `1.2.3.4:2053`, `[2606:4700::1]:443`,
     * `2606:4700::1` and `host.example.com`.
     *
     * The UI used to strip only the square brackets, so any `ip:port` input kept
     * its colon and the TCP connect failed with a bogus host name — the manual
     * add looked broken.
     */
    fun parseManualTarget(raw: String): Pair<String, Int?> {
        val value = raw.trim()
        if (value.isEmpty()) return "" to null
        // Bracketed form first: `[2606:4700::1]:443` — the brackets are what make
        // the port unambiguous, so they must survive until the split is done.
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end > 1) {
                val host = value.substring(1, end)
                val port = value.substring(end + 1).removePrefix(":").trim().toIntOrNull()
                return host to port?.takeIf { it in 1..65535 }
            }
        }
        // A bare v6 literal has 2+ colons and can never carry a port unbracketed.
        if (value.count { it == ':' } > 1) return value.trim('[', ']') to null
        val idx = value.lastIndexOf(':')
        if (idx > 0) {
            val port = value.substring(idx + 1).trim().toIntOrNull()
            if (port != null && port in 1..65535) return value.substring(0, idx).trim('[', ']') to port
        }
        return value.trim('[', ']') to null
    }

    /**
     * True when the device can actually open a v6 TCP connection to Cloudflare —
     * a routable v6 address on an interface is not enough on many Iranian carriers.
     * Two ports per host: some carriers route v6 but filter 443, and vice versa.
     */
    fun hasIpv6Connectivity(timeoutMs: Int = 2000): Boolean {
        val hosts = listOf(
            "2606:4700:4700::1111",
            "2606:4700::6810:84e5",
            "2a06:98c0::6810:84e5",
            "2606:4700:d0::a29f:c001",
        )
        for (port in listOf(443, 80)) {
            for (host in hosts) if (tcpConnect(host, port, timeoutMs) != null) return true
        }
        return false
    }

    fun isIpv6(value: String): Boolean = value.contains(':')

    fun defaultRangesText(): String = DEFAULT_RANGES.joinToString(", ")

    private val CLOUDFLARE_V4 = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18", "108.162.192.0/18",
        "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    ).map { cidr ->
        val (ip, bits) = cidr.split('/')
        val base = ip.split('.').fold(0L) { acc, s -> (acc shl 8) or s.toLong() }
        val mask = 0xFFFFFFFFL shl (32 - bits.toInt())
        (base and mask) to mask
    }

    /** True for addresses inside Cloudflare's published IPv4/IPv6 edge ranges (a ProxyIP must NOT be one). */
    fun isCloudflareAddress(host: String): Boolean {
        val v4 = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
        if (v4.matches(host)) {
            val value = host.split('.').fold(0L) { acc, s -> (acc shl 8) or (s.toLongOrNull() ?: 0L) }
            return CLOUDFLARE_V4.any { (base, mask) -> (value and mask) == base }
        }
        val h = host.lowercase()
        return h.startsWith("2606:4700:") || h.startsWith("2803:f800:") || h.startsWith("2405:b500:") || h.startsWith("2405:8100:") || h.startsWith("2a06:98c0:") || h.startsWith("2c0f:f248:")
    }

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
        /** Download throughput measured against this edge (bytes/sec), null = not measured. */
        val speedBps: Long? = null,
        /** Upload throughput measured against this edge (bytes/sec), null = not measured. */
        val uploadBps: Long? = null,
        /** Share of the ping samples that never connected (0..100). */
        val lossPct: Float = 0f,
        /** Mean deviation between samples in ms — how steady the path is. */
        val jitterMs: Long = 0L,
        /** How many samples the three numbers above are based on. */
        val sampleCount: Int = 0,
        /** True when a TLS/trace check was actually attempted on this row. */
        val verifyTls: Boolean = false,
    ) {
        val flag: String get() = countryCode?.toFlagEmoji() ?: "🌐"

        /** 0..100 composite of latency (50%), loss (30%) and jitter (20%). A
         *  candidate whose TLS/trace check failed can never score above 40 —
         *  reachable-but-unusable must not outrank a working edge. */
        val score: Int
            get() {
                val latency = ((600.0 - pingMs.coerceAtMost(600)) / (600.0 - 50.0) * 100.0).coerceIn(0.0, 100.0)
                val steady = ((150.0 - jitterMs.coerceAtMost(150)) / (150.0 - 10.0) * 100.0).coerceIn(0.0, 100.0)
                val reliable = (100.0 - lossPct.coerceAtMost(50f) * 2f).coerceIn(0.0, 100.0)
                val raw = latency * 0.5 + reliable * 0.3 + steady * 0.2
                val capped = if (verifyTls && !tlsOk) raw.coerceAtMost(40.0) else raw
                return capped.toInt()
            }

        /** `addr:port#CC` — Cat Panel pins the address to exactly the port this row verified. */
        val panelLine: String get() {
            // Bracket IPv6 literals so the panel can distinguish the address's
            // final hextet from the verified TCP port (e.g. [2606:4700::1]:2053).
            val address = if (ip.contains(':')) "[${ip.removePrefix("[").removeSuffix("]")}]" else ip
            return if (countryCode != null) "$address:$port#$countryCode" else "$address:$port"
        }

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

    /** Jitter pass helper: one fresh probe of an already-verified IP (used by
     * the UI's second pass to estimate jitter). */
    fun reprobeOnce(
        ip: String,
        sni: String,
        port: Int,
        connectTimeoutMs: Int = 1500,
        tlsTimeoutMs: Int = 2500,
    ): ScanResult? =
        probePort(ip, port, ScanOptions(sni = sni, port = port, ports = listOf(port), includeBuiltin = false, includeIranLibrary = false, randomSample = false, connectTimeoutMs = connectTimeoutMs, tlsTimeoutMs = tlsTimeoutMs, verifyHttp = true))

    /** Result of [sampleTcp]: the average is the ping, the rest is its quality. */
    internal data class PingStats(val avgMs: Long, val lossPct: Float, val jitterMs: Long, val answered: Int)

    /** [count] TCP connects to the same endpoint. Cheap (no DNS, no SNI) and the
     *  only latency a phone can measure without root — ICMP is unavailable. */
    internal fun sampleTcp(ip: String, port: Int, count: Int, timeoutMs: Int): PingStats? {
        val n = count.coerceIn(1, 20)
        val times = ArrayList<Long>(n)
        var lost = 0
        repeat(n) {
            val ms = tcpConnect(ip, port, timeoutMs)
            if (ms == null) lost += 1 else times += ms
        }
        if (times.isEmpty()) return null
        val avg = times.average().toLong()
        val jitter = times.map { kotlin.math.abs(it - avg) }.average().toLong()
        return PingStats(avg, lost * 100f / n, jitter, times.size)
    }

    /**
     * Port sweep, then a sampled probe of the winner.
     *
     * Ports are tried SEPARATELY and the candidate is only dropped when none of
     * them answers — a single-port scan used to discard IPs that simply did not
     * speak on 443. The port that answers fastest is the one stored as `ip:port`.
     */
    internal fun probe(ip: String, options: ScanOptions): ScanResult? {
        val ports = (if (options.ports.isNotEmpty()) options.ports else listOf(options.port)).distinct()
        val answered = ArrayList<Pair<Int, Long>>()
        for (port in ports) {
            val ms = tcpConnect(ip, port, options.connectTimeoutMs) ?: continue
            answered += port to ms
        }
        if (answered.isEmpty()) return null
        val port = answered.minByOrNull { it.second }!!.first
        return probePort(ip, port, options)
    }

    /** Sampled ping + TLS/trace for ONE already-chosen port. */
    internal fun probePort(ip: String, port: Int, options: ScanOptions): ScanResult? {
        val stats = sampleTcp(ip, port, options.samples, options.connectTimeoutMs) ?: return null
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
                pingMs = stats.avgMs,
                sni = options.sni,
                port = port,
                lossPct = stats.lossPct,
                jitterMs = stats.jitterMs,
                sampleCount = stats.answered,
                countryName = "Cloudflare edge",
                sourceRange = sourceRange,
            )
        }
        val verifyTls = options.verifyHttp || options.sni.isNotBlank()
        val tls = tlsProbe(ip, port, options.sni, options.tlsTimeoutMs, options.verifyHttp)
        val edge = EdgeLocationCatalog.fromColo(tls?.colo)
        return ScanResult(
            ip = ip,
            // The trace round trip is the number a client actually feels; fall
            // back to the sampled TCP average when the handshake never finished.
            pingMs = tls?.ms ?: stats.avgMs,
            sni = options.sni,
            port = port,
            tlsMs = tls?.ms,
            colo = tls?.colo,
            tlsOk = tls != null,
            httpStatus = tls?.status,
            countryCode = edge?.countryCode,
            countryName = edge?.label ?: "Cloudflare edge",
            sourceRange = sourceRange,
            lossPct = stats.lossPct,
            jitterMs = stats.jitterMs,
            sampleCount = stats.answered,
            verifyTls = verifyTls,
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

    /** Concrete, always-probed IPv6 edges (the «repo» for v6): well-known
     * Cloudflare anycast addresses that answer TLS on every port we scan.
     * Added to the walk whenever the network really has IPv6 connectivity. */
    val V6_LIBRARY: List<String> = listOf(
        "2606:4700:4700::1111",
        "2606:4700:4700::1001",
        "2606:4700::6810:84e5",
        "2606:4700:d0::a29f:c001",
        "2606:4700:d0::a29f:c002",
        "2606:4700:d0::1",
        "2606:4700:d1::1",
        "2606:4700::6812:1a2e",
        "2606:4700::6812:3ed",
        "2606:4700:3033::6810:84e5",
        "2a06:98c0::6810:84e5",
    )

    /**
     * One row of the «sources» card: which list a scan will actually walk, and how
     * much of it.
     *
     * WHY THIS EXISTS: the owner's report on the scanner was «فک کنم منبع نداره
     * اسکن کنه» (it looks like it has nothing to scan) and «داخل اسکنر ایپی‌های v6
     * نیست». Both are answerable only from the candidate list itself — the screen
     * showed no numbers, so a walk of ~500 addresses looked identical to a walk of
     * zero, and v6 (which IS in the code) is gated behind a connectivity probe, so
     * on a network without v6 the rows simply are not there. This report turns both
     * into visible numbers, and [ScanSource.key] is what the UI toggles.
     */
    data class ScanSource(
        /** Stable id used by the UI and the prefs: `mine` · `cloudflare` · `ipv6` · `iran`. */
        val key: String,
        /** How many distinct addresses this source contributes to the walk. */
        val count: Int,
        /** Whether the scan will walk it at all (off = the owner switched it off). */
        val enabled: Boolean,
        /** True for the v6 source, whose count is 0 unless the network/forced switch allows it. */
        val ipv6: Boolean = false,
    )

    /** The keys of [ScanSource] in walk order. */
    val SCAN_SOURCE_KEYS: List<String> = listOf("mine", "cloudflare", "ipv6", "iran")

    /**
     * The candidate list, kept per source instead of flattened.
     *
     * Same order and same content as the historical [buildCandidateList] — user
     * ranges first (range-first scanner), then the built-in Cloudflare ranges (only
     * when the owner typed no CIDR of their own), then v6, then the Iran/community
     * collections. v6 is derived from the v4 pool we already believe in, then the
     * /48s that really answer, then the static library, because sampling the whole
     * /32 (2^96 addresses) found nothing.
     */
    internal fun sourceCandidates(options: ScanOptions): LinkedHashMap<String, List<String>> {
        val perRange = options.perRange.coerceIn(1, 256)
        val out = LinkedHashMap<String, List<String>>()
        val custom = parseRangeList(options.customSubnets)
        val mine = linkedSetOf<String>()
        custom.forEach { part ->
            if (part.contains("/")) mine += expandSubnet(part, perRange, options.randomSample) else mine += part
        }
        val cloudflare = linkedSetOf<String>()
        if (options.includeBuiltin && custom.none { it.contains("/") }) {
            BUILTIN_RANGES.forEach { cidr -> cloudflare += expandSubnet(cidr, perRange, options.randomSample) }
        }
        val ipv6 = linkedSetOf<String>()
        if (options.includeIpv6) {
            val v4Pool = (mine + cloudflare).filter { isValidIpv4(it) }.take(120)
            v4Pool.forEach { v4 -> ipv6 += deriveIpv6Candidates(v4) }
            IPV6_SUBNETS.forEach { cidr -> ipv6 += expandSubnet(cidr, perRange, options.randomSample) }
            ipv6 += V6_LIBRARY
        }
        val iran = linkedSetOf<String>()
        if (options.includeIranLibrary) {
            iran += IRAN_LIBRARY
            iran += CommunityIpLibrary.ips
        }
        if (!options.includeIpv6) {
            // No usable v6 on this network → drop v6 literals/ranges the user typed too.
            mine.removeAll { isIpv6(it) }
            cloudflare.removeAll { isIpv6(it) }
            ipv6.clear()
            iran.removeAll { isIpv6(it) }
        }
        out["mine"] = mine.toList()
        out["cloudflare"] = cloudflare.toList()
        out["ipv6"] = ipv6.toList()
        out["iran"] = iran.toList()
        return out
    }

    /**
     * What the next scan will walk, one row per source — the numbers the UI shows
     * before (and after) a run, so «منبع ندارد» becomes answerable on the device.
     * `enabled` is false for a source the owner switched off, and for v6 when the
     * connectivity gate (or the forced switch) keeps it out.
     */
    fun sourceReport(options: ScanOptions): List<ScanSource> {
        val byKey = sourceCandidates(options)
        val custom = parseRangeList(options.customSubnets)
        return SCAN_SOURCE_KEYS.map { key ->
            val list = byKey[key].orEmpty()
            ScanSource(
                key = key,
                count = list.size,
                enabled = when (key) {
                    "mine" -> custom.isNotEmpty()
                    "cloudflare" -> options.includeBuiltin && custom.none { it.contains("/") }
                    "ipv6" -> options.includeIpv6 && list.isNotEmpty()
                    else -> options.includeIranLibrary
                },
                ipv6 = key == "ipv6",
            )
        }
    }

    internal fun buildCandidateList(options: ScanOptions): List<String> {
        val ips = linkedSetOf<String>()
        sourceCandidates(options).values.forEach { ips += it }
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

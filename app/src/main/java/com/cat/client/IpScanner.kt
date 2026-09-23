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
    )

    const val DEFAULT_PER_RANGE = 24

    /** Small, deliberately curated SNI set. The user's own Cat Panel host is always added by the UI. */
    val RECOMMENDED_SNIS: List<String> = listOf(
        "skk.moe",
        "www.speedtest.net",
        "cdnjs.cloudflare.com",
        "speed.cloudflare.com",
        "www.visa.com",
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

    fun defaultRangesText(): String = DEFAULT_RANGES.joinToString(", ")

    data class ScanResult(
        val ip: String,
        val pingMs: Long,
        val sni: String,
        val tlsMs: Long? = null,
        val colo: String? = null,
        val tlsOk: Boolean = false,
        val httpStatus: Int? = null,
        /** Country of the responding edge, never guessed from the anycast IP itself. */
        val countryCode: String? = null,
        val countryName: String? = null,
        val sourceRange: String? = null,
    ) {
        val flag: String get() = countryCode?.toFlagEmoji() ?: "🌐"

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
        val sourceRange = (parseRangeList(options.customSubnets) + DEFAULT_RANGES)
            .firstOrNull { cidrContains(ip, it) }
        if (!options.verifyHttp && options.sni.isBlank()) {
            return ScanResult(
                ip = ip,
                pingMs = tcpMs,
                sni = options.sni,
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
            socket = (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply {
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
            val trace = readTrace(socket, sni, timeoutMs)
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
            return if (isValidIpv4(trimmed)) listOf(trimmed) else emptyList()
        }
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

    /** Splits a free-form "ip, cidr, cidr" string into the entries the scanner walks. */
    fun parseRangeList(text: String): List<String> =
        text.split(",", "\n", " ", ";", "\t")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { part -> if (part.contains("/")) expandSubnet(part, 1).isNotEmpty() else isValidIpv4(part) }

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
        if (options.includeIranLibrary) {
            ips += IRAN_LIBRARY
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

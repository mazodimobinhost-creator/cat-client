package com.cat.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import javax.net.ssl.SNIHostname
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * IpScanner — extended clean-IP scanner with:
 *   - Built-in public Cloudflare / Gcore / Fastly IP ranges (Iran-working CDNs).
 *   - SNI selection per-candidate.
 *   - "Fronting / spoof" mode: connect to IP but SNI points to another hostname.
 *   - User-supplied custom subnet list (CIDR or comma-separated IPs).
 *   - Ping / real delay measurement via TLS handshake.
 *
 * Returns candidates tagged with country name & flag (when IP geolocates).
 */
object IpScanner {

    data class ScanOptions(
        val sni: String = "skk.moe",
        val port: Int = 443,
        val concurrency: Int = 32,
        val pingTimeoutMs: Int = 1500,
        val customSubnets: String = "",
        val includeBuiltin: Boolean = true,
    )

    data class ScanResult(
        val ip: String,
        val pingMs: Long,
        val sni: String,
        val countryCode: String?,
        val countryName: String?,
    ) {
        val flag: String get() = countryCode?.toFlagEmoji() ?: "🌐"
    }

    // Built-in IP ranges known to work from Iran (Cloudflare CDN nodes).
    private val BUILTIN_RANGES: List<String> = listOf(
        // Cloudflare anycast IPv4
        "104.16.0.0/12",   // 104.16-31.*
        "172.64.0.0/13",   // 172.64-71.*
        "131.0.72.0/22",
        "162.159.0.0/16",
        "198.41.128.0/17",
        // Gcore / common CDN fronts
        "92.223.0.0/16",
        "89.187.163.0/24",
    )

    suspend fun scan(context: Context, options: ScanOptions): List<ScanResult> =
        coroutineScope {
            val candidates = buildCandidateList(options)
            val results = withContext(Dispatchers.IO) {
                candidates.map { ip ->
                    async {
                        val ms = tlsPing(ip, options.port, options.sni, options.pingTimeoutMs)
                        if (ms != null) ScanResult(
                            ip = ip,
                            pingMs = ms,
                            sni = options.sni,
                            countryCode = null, // GeoIP is resolved offline lazily
                            countryName = null,
                        ) else null
                    }
                }.awaitAll().filterNotNull()
            }
            results.sortedBy { it.pingMs }
        }

    fun expandSubnet(cidr: String, limitPerSubnet: Int = 80): List<String> {
        val trimmed = cidr.trim()
        if (!trimmed.contains("/")) {
            return if (isValidIpv4(trimmed)) listOf(trimmed) else emptyList()
        }
        val (ipPart, prefixPart) = trimmed.split("/", limit = 2)
        val prefix = prefixPart.toIntOrNull() ?: return emptyList()
        val ipBytes = ipPart.split(".").map { it.toIntOrNull() ?: return emptyList() }
        if (ipBytes.size != 4 || prefix !in 0..32) return emptyList()
        val ipInt = ipBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val hostBits = 32 - prefix
        val total = if (hostBits == 0) 1 else (1L shl hostBits.coerceAtMost(14)).coerceAtMost(limitPerSubnet.toLong())
        val netMask = if (prefix == 0) 0L else (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
        val network = ipInt and netMask
        val out = mutableListOf<String>()
        // Sample evenly if subnet is larger than limit
        val step = (1L shl hostBits.coerceAtMost(20)) / total.coerceAtLeast(1)
        for (i in 0 until total) {
            val addr = network + (i * step.coerceAtLeast(1))
            out.add(longToIp(addr))
        }
        return out
    }

    private fun buildCandidateList(options: ScanOptions): List<String> {
        val ips = linkedSetOf<String>()
        if (options.includeBuiltin) {
            BUILTIN_RANGES.forEach { cidr ->
                ips += expandSubnet(cidr)
            }
        }
        // Custom subnets or single IPs
        options.customSubnets
            .split(",", "\n", " ", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { part ->
                if (part.contains("/")) ips += expandSubnet(part)
                else if (isValidIpv4(part)) ips += part
            }
        return ips.toList()
    }

    private fun tlsPing(ip: String, port: Int, sni: String, timeoutMs: Int): Long? {
        val start = System.nanoTime()
        return runCatching {
            // SSLSocketFactory.createSocket(Socket, host, port, autoClose) is protected,
            // so create the TLS socket directly against ip:port and pin the SNI via
            // SSLParameters.serverNames (TLS Server Name Indication extension).
            val ssl = (SSLSocketFactory.getDefault().createSocket(ip, port) as SSLSocket).apply {
                soTimeout = timeoutMs
                // Android's public SSLSocket API has no setHostname/setSNIHostname,
                // so pin the SNI via SSLParameters (same pattern as OkHttp; works
                // on all API levels we support, minSdk 26 >= 24).
                if (sni.isNotBlank()) {
                    val params = getSSLParameters()
                    params.serverNames = listOf(SNIHostname(sni, true))
                    setSSLParameters(params)
                }
                startHandshake()
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            ssl.close()
            ms
        }.getOrNull()
    }

    private fun isValidIpv4(ip: String): Boolean =
        ip.split(".").let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }

    private fun longToIp(v: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { "${((v shr it) and 0xFF)}" }
}

fun String.toFlagEmoji(): String {
    if (length != 2) return "🌐"
    return uppercase().map { 0x1F1E6 + (it - 'A') }.joinToString("") { String(Character.toChars(it)) }
}

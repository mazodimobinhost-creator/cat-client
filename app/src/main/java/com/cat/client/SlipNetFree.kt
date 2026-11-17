package com.cat.client

import android.content.Context
import kotlin.math.abs

/**
 * Free Cat DNS (dnstt) for Net Melli. Scan resolvers, also try Cloudflare
 * 1.1.1.1 / DoH as the recursive resolver (not a Worker dnstt server). Keep only tunnels
 * that actually carry a request. Not another app.
 *
 * Anti-sanction DNS (Shecan / Electro / 403 …) answers A for ordinary sites but will not
 * recursively carry dnstt TXT — those IPs are weak carriers and are tried last.
 */
internal object SlipNetFreePlanner {
    data class PublicTunnel(val domain: String, val pubkey: String, val tag: String)

    val PUBLIC_TUNNELS: List<PublicTunnel> = listOf(
        PublicTunnel("t.ruhnama.net", "241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f", "ruhnama"),
        PublicTunnel("t2.bypasscensorship.org", "a2fb71077eeaa54a02cda7a90be306af5d299ab21822a8b277d4eacbc9168631", "bypass"),
        PublicTunnel("t.arkadag.org", "c596c458fc3453dc40903ab235f5854a2609831075640c4c5584f76de05b8271", "arkadag"),
        PublicTunnel("t.cryptomeanscryptography.club", "172aa6b63080a9e4e87dc1bca4eb5906b9dde41f77096e43a806e77e6e069c27", "crypto"),
        PublicTunnel("t.rinsed-tinsel.site", "1606b003c808f0ef2310529c11ea0c546784a95a4a5b9b9464cd87c40da5ff1c", "tinsel"),
        PublicTunnel("rent.snapp.autos", "a5da2ba2da39ab7dd9dca5f639b4bf02acdf8f8da2ab2d4a373dbf5ff3e0563a", "snapp"),
        PublicTunnel("tunnel.dnstt.xyz", "023678bec148aaaae5e03d450a1d05917129b632ca24ce1bbbd1b42bf377c36e", "xyz"),
    )

    /** Answers A for shops/games; does not ferry dnstt TXT. */
    private val WEAK_DNSTT_CARRIERS = setOf(
        "178.22.122.100", "185.51.200.2",
        "78.157.42.100", "78.157.42.101",
        "10.202.10.10", "10.202.10.11", "10.202.10.202", "10.202.10.102",
        "185.55.226.26", "185.55.225.25",
        "87.107.110.109", "87.107.110.110",
        "172.29.0.100", "172.29.2.100",
        "193.24.103.1", "10.70.95.150",
    )

    private val PUBLIC_RECURSIVE = setOf(
        "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "149.112.112.112", "94.140.14.14",
    )

    @Volatile
    var lastNotes: List<String> = emptyList()
        private set

    fun isWeakDnsttCarrier(ip: String): Boolean {
        val t = ip.trim()
        if (t in WEAK_DNSTT_CARRIERS) return true
        if (t.startsWith("10.") || t.startsWith("172.29.") || t.startsWith("192.168.") || t.startsWith("127.")) return true
        return false
    }

    /** Higher = better dnstt carrier. 0 = unusable (RFC1918). */
    fun carrierRank(ip: String): Int {
        val t = ip.trim()
        if (t.startsWith("10.") || t.startsWith("127.") || t.startsWith("172.29.") || t.startsWith("192.168.")) return 0
        if (t in WEAK_DNSTT_CARRIERS) return 1
        if (t in PUBLIC_RECURSIVE) return 4
        return 3
    }

    fun dnsttUri(resolverIp: String, tunnel: PublicTunnel, doh: String = ""): String {
        val ip = resolverIp.trim().substringBefore('%')
        val dohQ = if (doh.startsWith("https://")) "&doh=${java.net.URLEncoder.encode(doh, "UTF-8")}" else ""
        val tag = if (dohQ.isNotBlank()) "cf-${tunnel.tag}" else tunnel.tag
        return "dnstt://$ip:53/${tunnel.domain}?pubkey=${tunnel.pubkey}$dohQ#Cat-DNS-$tag-$ip"
    }

    fun cloudflareLinks(): List<String> {
        val tunnels = PUBLIC_TUNNELS.take(4)
        return tunnels.flatMap { t ->
            listOf(
                dnsttUri("1.1.1.1", t),
                dnsttUri("1.1.1.1", t, doh = "https://1.1.1.1/dns-query"),
                dnsttUri("8.8.8.8", t),
            )
        }.distinct()
    }

    fun linksForResolvers(resolverIps: List<String>): List<String> {
        val ips = resolverIps.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            .sortedByDescending { carrierRank(it) }
            .filter { carrierRank(it) > 0 }
            .take(4)
        val iran = ips.flatMap { ip -> PUBLIC_TUNNELS.map { dnsttUri(ip, it) } }
        return (iran + cloudflareLinks()).distinct()
    }

    fun runnableUris(uris: List<String>): List<String> = uris.filter { raw ->
        val parsed = WhiteDnsSmartScanner.parseTunnelUri(raw) ?: return@filter false
        parsed.scheme in setOf("dnstt", "dnst", "noizdns", "vaydns") &&
            parsed.publicKeyHex.length >= 32 &&
            '.' in parsed.tunnelDomain &&
            parsed.resolverIp.isNotBlank()
    }

    /**
     * Starts [SlipNetCliEngine] and keeps URIs whose SOCKS port actually carries TCP.
     * Does not save 127.0.0.1 rows that the connection tester would mark failed.
     */
    fun probeWorking(
        context: Context,
        uris: List<String>,
        maxKeep: Int = 3,
        timeoutMs: Int = 15_000,
        onStep: (String) -> Unit = {},
    ): List<String> {
        SlipNetCliEngine.attach(context)
        val notes = ArrayList<String>()
        if (!SlipNetCliEngine.isInstalled(context)) {
            notes += "libslipnet.so missing"
            lastNotes = notes
            return emptyList()
        }
        val out = ArrayList<String>()
        val ordered = runnableUris(uris).sortedByDescending { raw ->
            val parsed = WhiteDnsSmartScanner.parseTunnelUri(raw)
            carrierRank(parsed?.resolverIp.orEmpty())
        }
        for (uri in ordered) {
            if (out.size >= maxKeep) break
            val parsed = WhiteDnsSmartScanner.parseTunnelUri(uri) ?: continue
            onStep("${parsed.tunnelDomain} @ ${parsed.resolverIp}")
            val preferred = 10853 + (abs(uri.hashCode()) % 45)
            val result = SlipNetCliEngine.start(context, parsed, preferred, readyTimeoutMs = 12_000L)
            if (result !is SlipNetCliEngine.StartResult.Ready) {
                val why = when (result) {
                    is SlipNetCliEngine.StartResult.Failed -> result.reasonFa
                    is SlipNetCliEngine.StartResult.Unsupported -> result.reasonFa
                    else -> "not ready"
                }
                notes += "${parsed.tunnelDomain} @ ${parsed.resolverIp}: $why"
                continue
            }
            val delay = SlipNetCliEngine.socksConnectDelay(result.port, timeoutMs)
                ?: SlipNetCliEngine.httpDelayThroughSocks(result.port, timeoutMs)
            if (delay != null) {
                out += uri
            } else {
                notes += "${parsed.tunnelDomain} @ ${parsed.resolverIp}: no TCP through tunnel"
                SlipNetCliEngine.stop(SlipNetCliEngine.keyOf(parsed))
            }
        }
        lastNotes = notes
        return out
    }
}

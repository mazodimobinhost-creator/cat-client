package com.cat.client

import android.content.Context

/**
 * beta293/294 — free Cat DNS (dnstt) for Net Melli. Scan resolvers, also try Cloudflare
 * 1.1.1.1 / DoH as the recursive resolver (not a Worker dnstt server). Keep only tunnels
 * that actually carry a request. Not another app.
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

    fun dnsttUri(resolverIp: String, tunnel: PublicTunnel, doh: String = ""): String {
        val ip = resolverIp.trim().substringBefore('%')
        val dohQ = if (doh.startsWith("https://")) "&doh=${java.net.URLEncoder.encode(doh, "UTF-8")}" else ""
        val tag = if (dohQ.isNotBlank()) "cf-${tunnel.tag}" else tunnel.tag
        return "dnstt://$ip:53/${tunnel.domain}?pubkey=${tunnel.pubkey}$dohQ#Cat-DNS-$tag-$ip"
    }

    fun cloudflareLinks(): List<String> {
        val tunnels = PUBLIC_TUNNELS.take(3)
        return tunnels.flatMap { t ->
            listOf(
                dnsttUri("1.1.1.1", t),
                dnsttUri("1.1.1.1", t, doh = "https://1.1.1.1/dns-query"),
            )
        }
    }

    fun linksForResolvers(resolverIps: List<String>): List<String> {
        val ips = resolverIps.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(2)
        val tunnels = PUBLIC_TUNNELS.take(3)
        val iran = ips.flatMap { ip -> tunnels.map { dnsttUri(ip, it) } }
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
     * Starts [SlipNetCliEngine] and keeps URIs whose SOCKS port actually carries HTTPS.
     * Does not save 127.0.0.1 rows that the connection tester would mark failed.
     */
    fun probeWorking(
        context: Context,
        uris: List<String>,
        maxKeep: Int = 3,
        timeoutMs: Int = 8_000,
        onStep: (String) -> Unit = {},
    ): List<String> {
        SlipNetCliEngine.attach(context)
        val out = ArrayList<String>()
        for (uri in runnableUris(uris)) {
            if (out.size >= maxKeep) break
            val parsed = WhiteDnsSmartScanner.parseTunnelUri(uri) ?: continue
            onStep("${parsed.tunnelDomain} @ ${parsed.resolverIp}")
            val port = WhiteDnsSmartScanner.ensureLocalTunnelBridgePort(parsed, startDaemon = true)
            val ready = WhiteDnsSmartScanner.lastEngineResult(parsed) is SlipNetCliEngine.StartResult.Ready
            if (!ready) continue
            val delay = SlipNetCliEngine.httpDelayThroughSocks(port, timeoutMs)
            if (delay != null) out += uri
        }
        return out
    }
}

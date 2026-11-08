package com.cat.client

/**
 * beta291 — free Cat DNS (dnstt) for Net Melli: scan resolvers on this line, attach public
 * tunnel domains, save as a normal subscription. The real engine is [SlipNetCliEngine]
 * (packaged libslipnet.so). Not another app.
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

    fun dnsttUri(resolverIp: String, tunnel: PublicTunnel): String {
        val ip = resolverIp.trim().substringBefore('%')
        return "dnstt://$ip:53/${tunnel.domain}?pubkey=${tunnel.pubkey}#Cat-DNS-${tunnel.tag}-$ip"
    }

    fun linksForResolvers(resolverIps: List<String>): List<String> {
        val ips = resolverIps.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(4)
        if (ips.isEmpty()) return emptyList()
        return ips.flatMap { ip -> PUBLIC_TUNNELS.map { dnsttUri(ip, it) } }
    }

    fun runnableUris(uris: List<String>): List<String> = uris.filter { raw ->
        val parsed = WhiteDnsSmartScanner.parseTunnelUri(raw) ?: return@filter false
        parsed.scheme in setOf("dnstt", "dnst", "noizdns", "vaydns") &&
            parsed.publicKeyHex.length >= 32 &&
            '.' in parsed.tunnelDomain &&
            parsed.resolverIp.isNotBlank()
    }
}

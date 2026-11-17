package com.cat.client

/**
 * beta265 — 🚀 Cat Warp in the free-networks hub, Fox-style: pick a Cloudflare transport,
 * time the listed `IP:port` on THIS network (never rewritten), one tap connects.
 * Independent of other WARP apps; Cat branding only. Exit is a Cloudflare IP (not a
 * country-change VPN). SSTP stays the separate VPN Gate item.
 *
 * beta299 — the same picker can walk Cloudflare WARP endpoints on this line (VPN off)
 * and keep the exact `IP:port` that answered, then build AmneziaWG (`awg://auto@…`)
 * plus a Cat Warp MASQUE/WireGuard config Cat already connects.
 */
internal object CatWarpFreePlanner {
    data class Proto(
        val id: String,
        val mode: AetherMode,
        val ipScan: String,
        val host: String,
        val port: Int,
        val labelFa: String,
        val labelEn: String,
        val hintFa: String,
        val hintEn: String,
    ) {
        val endpoint: String
            get() = if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

        fun label(fa: Boolean): String = if (fa) labelFa else labelEn
        fun hint(fa: Boolean): String = if (fa) hintFa else hintEn
    }

    /** Cloudflare WARP account public key (anycast). Same as the built-in AmneziaWG rows. */
    const val WARP_PUB = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    fun splitPeer(peer: String): Pair<String, Int>? {
        val s = peer.trim()
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            val host = s.substring(1, end)
            val port = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
            if (LiveNetPing.isLoopback(host) || port !in 1..65535) return null
            return host to port
        }
        val c = s.lastIndexOf(':')
        if (c <= 0) return null
        val host = s.substring(0, c)
        val port = s.substring(c + 1).toIntOrNull() ?: return null
        if (host.isBlank() || LiveNetPing.isLoopback(host) || port !in 1..65535) return null
        return host to port
    }

    fun formatHost(host: String): String {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        return if (':' in h) "[$h]" else h
    }

    fun formatPeer(host: String, port: Int): String = "${formatHost(host)}:$port"

    /** Same host and the same port that answered — never 443→80, never 80/1080. */
    fun keepExactPeer(peer: String): String? {
        val hp = splitPeer(peer) ?: return null
        if (hp.second == 80 || hp.second == 1080) return null
        return formatPeer(hp.first, hp.second)
    }

    fun ipScanForPeer(peer: String): String {
        val hp = splitPeer(peer) ?: return "v4"
        return if (':' in hp.first) "v6" else "v4"
    }

    /**
     * AmneziaWG share Cat connects (`awg://auto@…` — WARP key at connect).
     * UDP WARP ports only; 443 stays MASQUE; 80/1080 never used.
     */
    fun awgUri(peer: String, name: String): String? {
        val hp = splitPeer(peer) ?: return null
        if (hp.second == 80 || hp.second == 1080 || hp.second == 443) return null
        val host = formatHost(hp.first)
        val pub = java.net.URLEncoder.encode(WARP_PUB, "UTF-8")
        val label = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "awg://auto@$host:${hp.second}?address=172.16.0.2/32&publickey=$pub&mtu=1280&keepalive=25&jc=5&jmin=50&jmax=1000&wnoisecount=5&wpayloadsize=40-120#$label"
    }

    fun wireguardUri(peer: String, name: String): String? {
        val hp = splitPeer(peer) ?: return null
        if (hp.second == 80 || hp.second == 1080 || hp.second == 443) return null
        val host = formatHost(hp.first)
        val pub = java.net.URLEncoder.encode(WARP_PUB, "UTF-8")
        val label = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "wireguard://auto@$host:${hp.second}?address=172.16.0.2/32&publickey=$pub&mtu=1280&keepalive=25#$label"
    }

    fun awgLinksFromPeers(peers: List<String>): List<String> =
        peers.mapNotNull { keepExactPeer(it)?.let { p -> awgUri(p, "Cat Warp $p") } }.distinct()

    val PROTOS = listOf(
        Proto(
            id = "wg4",
            mode = AetherMode.WireGuard,
            ipScan = "v4",
            host = "162.159.192.1",
            port = 2408,
            labelFa = "WireGuard IPv4",
            labelEn = "WireGuard IPv4",
            hintFa = "ایرانسل",
            hintEn = "Irancell",
        ),
        Proto(
            id = "wg6",
            mode = AetherMode.WireGuard,
            ipScan = "v6",
            host = "2606:4700:d0::a29f:c001",
            port = 2408,
            labelFa = "WireGuard IPv6",
            labelEn = "WireGuard IPv6",
            hintFa = "همراه اول",
            hintEn = "Hamrah-e Aval",
        ),
        Proto(
            id = "h2",
            mode = AetherMode.MasqueHttp2,
            ipScan = "v4",
            host = "162.159.198.1",
            port = 443,
            labelFa = "MASQUE HTTP/2",
            labelEn = "MASQUE HTTP/2",
            hintFa = "همراه اول · داخل HTTPS",
            hintEn = "Hamrah-e Aval · inside HTTPS",
        ),
        Proto(
            id = "mim",
            mode = AetherMode.MasqueInMasque,
            ipScan = "v4",
            host = "162.159.198.1",
            port = 443,
            labelFa = "MASQUE in MASQUE",
            labelEn = "MASQUE in MASQUE",
            hintFa = "دو لایه",
            hintEn = "two-hop",
        ),
    )

    /**
     * Extra MASQUE HTTP/2 edges (Hamrah default is H2 on 162.159.198.1:443).
     * Same port that answered — 443 only; never 80/1080.
     */
    val EXTRA_H2_PEERS = listOf(
        "162.159.198.1:443",
        "162.159.192.1:443",
        "162.159.193.1:443",
        "188.114.96.1:443",
        "188.114.97.1:443",
        "188.114.98.1:443",
    )

    /** HTTP/3 (QUIC) — often closed on Hamrah; try when H2 is empty. */
    val EXTRA_H3_PEERS = listOf(
        "162.159.198.1:443",
        "162.159.192.1:443",
        "188.114.96.1:443",
    )
}

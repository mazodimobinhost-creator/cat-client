package com.cat.client

/**
 * beta265 — 🚀 Cat Warp in the free-networks hub, Fox-style: pick a Cloudflare transport,
 * time the listed `IP:port` on THIS network (never rewritten), one tap connects.
 * Independent of other WARP apps; Cat branding only. Exit is a Cloudflare IP (not a
 * country-change VPN). SSTP stays the separate VPN Gate item.
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
}

package com.cat.client

import java.util.Locale

/**
 * UI-only labels for connection rows. Mihomo still uses [ConnectionProfile.tag]
 * internally; changing a label must never break a selector or cached profile.
 */
object ConnectionLabelPolicy {
    private val addressPattern = Regex(
        "(?<![A-Za-z0-9])(?:\\[?[0-9a-fA-F]*:[0-9a-fA-F:]+\\]?|(?:\\d{1,3}\\.){3}\\d{1,3})(?::\\d+)?(?![A-Za-z0-9])",
    )
    private val numberedPrefix = Regex("^\\s*(?:🐱\\s*)?\\d+[.)]\\s*")

    fun displayName(profile: ConnectionProfile): String =
        displayName(profile.tag, profile.type, profile.server, profile.port)

    fun displayName(tag: String, type: String, server: String, port: Int): String {
        val raw = tag.trim()
        // New Cat Panel labels are already location-first and should stay stable.
        if (raw.startsWith("🐱 Cat ·") && !addressPattern.containsMatchIn(raw)) return raw
        if (raw.startsWith("Cat ·") && !addressPattern.containsMatchIn(raw)) return "🐱 $raw"

        // beta206: in-app engines appear to mihomo as a loopback SOCKS entry. They are shown in Cat's own format
        // («🐱 Cat · Germany · Warp MIM-H2 · 🇩🇪»): no «Socks5 · 1819», and no third-party app or remark names.
        val host = server.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        val localType = type.lowercase(Locale.US) in setOf("socks5", "socks", "http")
        if (localType && (host == "127.0.0.1" || host == "localhost" || host == "::1")) {
            val engine = when (port) {
                1819 -> "Warp"
                10829 -> "Fox VPN"
                10809 -> "KCP"
                else -> "Local"
            }
            val variant = if (port == 1819) {
                val tokens = raw.split(Regex("[^A-Za-z0-9]+")).map { it.uppercase(Locale.US) }.toSet()
                listOf("MIM", "WIW", "WG", "GOOL", "H2", "H3", "V6", "PSIPHON", "TOR").filter { it in tokens }
                    .joinToString("-") { if (it == "PSIPHON") "Psiphon" else if (it == "TOR") "Tor" else if (it == "GOOL") "Gool" else it }
            } else ""
            val country = ConnectionLocationPolicy.countryFromText(Locale.ENGLISH, raw, "")
            val place = country?.country ?: "Auto"
            val kind = if (variant.isBlank()) engine else "$engine $variant"
            return "🐱 Cat · $place · $kind · ${country?.flag ?: "🌐"}"
        }

        val country = ConnectionLocationPolicy.countryFromText(Locale.ENGLISH, raw, server)
        val protocol = when (type.lowercase(Locale.US)) {
            "mihomo-group" -> "Proxy"
            "wireguard" -> "WireGuard"
            "vless" -> "VLESS"
            "vmess" -> "VMess"
            "trojan" -> "Trojan"
            "ss", "shadowsocks" -> "Shadowsocks"
            "hysteria2", "hy2" -> "Hysteria2"
            "tuic" -> "TUIC"
            else -> type.trim().ifBlank { "Proxy" }.replaceFirstChar { it.uppercase() }
        }
        val flag = country?.flag ?: "🌐"
        val countryName = country?.country ?: "Cloudflare edge"
        return "🐱 Cat · $countryName · $protocol · $port · $flag"
    }
}

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

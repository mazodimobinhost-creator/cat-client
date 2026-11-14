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

    fun displayName(profile: ConnectionProfile): String {
        val fallback = displayName(profile.tag, profile.type, profile.server, profile.port)
        if (" · DNS · " in fallback || " · Spoof · " in fallback) return fallback
        val pub = ConnectionPublicEndpoint.of(profile)
        if (pub.isLoopback) return fallback
        val loc = ConnectionLocationPolicy.countryFromText(Locale.ENGLISH, pub.host, pub.extra, profile.tag)
        val place = loc?.country ?: pub.host
        val flag = loc?.flag ?: "🌐"
        return "🐱 Cat · $place · ${pub.kind} · ${pub.authority()} · $flag"
    }

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
            val dnsMark = Regex("Cat-DNS-([A-Za-z0-9]+)-([0-9.]+)").find(raw)
            if (dnsMark != null || port in 10_853..10_899) {
                val tunnel = dnsMark?.groupValues?.getOrNull(1) ?: "DNS"
                val resolver = dnsMark?.groupValues?.getOrNull(2).orEmpty()
                val place = if (resolver.isNotBlank()) "$tunnel · $resolver" else tunnel
                return "🐱 Cat · DNS · $place · 🧵"
            }
            if (port in 19_000..19_899 || raw.contains("Spoof", ignoreCase = true) || raw.contains("sni_fwd", ignoreCase = true)) {
                val sni = Regex("sni=([^&]+)").find(raw)?.groupValues?.getOrNull(1)?.take(24)
                val place = sni?.ifBlank { null } ?: "whitelist SNI"
                return "🐱 Cat · Spoof · $place · 🪄"
            }
            val engine = when (port) {
                1819 -> "Warp"
                10829 -> "Fox VPN"
                10809 -> "KCP"
                CatGpEngine.PORT -> "Cat GP"
                SstpLink.PLACEHOLDER_PORT -> "SSTP"
                else -> "Local"
            }
            val variant = if (port == 1819) {
                val tokens = raw.split(Regex("[^A-Za-z0-9]+")).map { it.uppercase(Locale.US) }.toSet()
                listOf("MIM", "WIW", "WG", "GOOL", "H2", "H3", "V6", "PSIPHON", "TOR").filter { it in tokens }
                    .joinToString("-") { if (it == "PSIPHON") "Psiphon" else if (it == "TOR") "Tor" else if (it == "GOOL") "Gool" else it }
            } else if (port == SstpLink.PLACEHOLDER_PORT) {
                // beta212: many SSTP servers share a country; the measured speed from the list tells them apart.
                Regex("""(\d{1,5})(?:\.\d+)?\s*Mbps""", RegexOption.IGNORE_CASE).find(raw)?.let { "${it.groupValues[1]} Mbps" }.orEmpty()
            } else if (port == 10829 && "🔒" in raw) {
                "🔒" // beta250: a Fox country locked for free accounts (Mozilla answers 403)
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

package com.cat.client

/**
 * Home connection path: phone → how you connected → server → exit.
 * Built from the live profile and the measured IPs (never loopback).
 */
internal data class ConnectionPathHop(
    val title: String,
    val detail: String,
)

internal data class ConnectionPathSnapshot(
    val hops: List<ConnectionPathHop>,
    val caption: String,
)

internal object ConnectionPath {

    fun snapshot(
        fa: Boolean,
        profile: ConnectionProfile?,
        geo: IpGeolocation.Info?,
        chainHops: Int,
        tag: String,
    ): ConnectionPathSnapshot {
        val proto = protocolLabel(profile?.type.orEmpty(), tag, fa)
        val fromPlace = geo?.realCountryName?.takeIf { it.isNotBlank() }
            ?: if (fa) "این خط" else "this line"
        val toPlace = geo?.countryName?.takeIf { it.isNotBlank() && it != geo.ip }
            ?: if (fa) "خروجی" else "the exit"
        val fromIp = publicIp(geo?.realIp)
        val exitIp = publicIp(geo?.ip)
        val hops = ArrayList<ConnectionPathHop>(4)
        hops += ConnectionPathHop(
            title = if (fa) "گوشی" else "Phone",
            detail = fromIp ?: fromPlace,
        )
        hops += ConnectionPathHop(title = proto, detail = "")
        peerLabel(profile)?.let { peer ->
            hops += ConnectionPathHop(
                title = if (fa) "سرور" else "Server",
                detail = peer,
            )
        }
        if (chainHops > 1) {
            hops += ConnectionPathHop(
                title = if (fa) "رله" else "Relay",
                detail = if (fa) "$chainHops گام" else "$chainHops hops",
            )
        }
        hops += ConnectionPathHop(
            title = if (fa) "خروجی" else "Exit",
            detail = exitIp ?: toPlace,
        )
        val caption = if (fa) {
            "از $fromPlace با $proto می‌رود به $toPlace" + (exitIp?.let { " · $it" } ?: "")
        } else {
            "From $fromPlace with $proto to $toPlace" + (exitIp?.let { " · $it" } ?: "")
        }
        return ConnectionPathSnapshot(hops, caption)
    }

    fun protocolLabel(type: String, tag: String, fa: Boolean): String {
        val t = type.trim().lowercase()
        val g = tag.lowercase()
        return when {
            "cat gp" in g -> "Cat GP"
            "masque" in t || "masque" in g || "aether" in t || "aether" in g -> "Cat Warp"
            "warp" in g -> "Cat Warp"
            t in setOf("awg", "amnezia", "amneziawg") || "amnezia" in g -> "Amnezia"
            t in setOf("wireguard", "wg") -> if (fa) "وایرگارد" else "WireGuard"
            t in setOf("hysteria", "hysteria2", "hy2") -> "Hysteria2"
            t == "tuic" -> "TUIC"
            t == "trojan" -> "Trojan"
            t == "vless" -> "VLESS"
            t == "vmess" -> "VMess"
            t in setOf("ss", "shadowsocks") -> "Shadowsocks"
            t == "sstp" -> "SSTP"
            t in setOf("socks", "socks5", "http", "mixed") -> if (fa) "پروکسی" else "Proxy"
            t.isNotBlank() -> type.trim()
            tag.isNotBlank() -> tag.take(18)
            else -> if (fa) "تونل" else "Tunnel"
        }
    }

    /** Exact `host:port` from the connected profile. Loopback and 80/1080-as-placeholder are dropped. */
    fun peerLabel(profile: ConnectionProfile?): String? {
        val host = profile?.server?.trim()?.removePrefix("[")?.removeSuffix("]")?.trim().orEmpty()
        if (host.isBlank() || LiveNetPing.isLoopback(host)) return null
        val port = profile?.port ?: return host
        if (port !in 1..65535) return host
        return if (':' in host) "[$host]:$port" else "$host:$port"
    }

    fun publicIp(raw: String?): String? {
        val ip = raw?.trim().orEmpty()
        if (ip.isBlank() || LiveNetPing.isLoopback(ip)) return null
        if (ip.startsWith("127.") || ip == "::1") return null
        return ip
    }
}

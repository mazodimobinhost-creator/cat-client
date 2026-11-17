package com.cat.client

import org.json.JSONObject

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

    private val PATH_KEYS = setOf(
        "name", "protocol", "transport", "security", "host", "ip", "port",
        "sni", "path", "cdn", "cdnIp", "localIp", "dns", "fail",
    )

    fun snapshot(
        fa: Boolean,
        profile: ConnectionProfile?,
        geo: IpGeolocation.Info?,
        chainHops: Int,
        tag: String,
        peerOverride: String? = null,
        peerTitle: String? = null,
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
        val peer = peerOverride?.trim()?.takeIf { it.isNotBlank() && "127.0.0.1" !in it && it != "::1" }
            ?: peerLabel(profile)
        val peerHost = peer?.substringBeforeLast(':')?.removePrefix("[")?.removeSuffix("]")
        if (peer != null && peerHost != exitIp) {
            hops += ConnectionPathHop(
                title = peerTitle ?: if (fa) "سرور" else "Server",
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

    fun looksLikeIp(raw: String): Boolean {
        val s = raw.trim().removePrefix("[").removeSuffix("]")
        if (s.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return true
        if (':' in s && s.any { it.isDigit() } && s.all { it.isDigit() || it in "abcdefABCDEF:" }) return true
        return false
    }

    /**
     * JSON for `CatPath.load` in the home WebView. Only fields from the connected
     * profile / live geo — never UUID, password, or keys.
     */
    fun toPathJson(
        profile: ConnectionProfile?,
        geo: IpGeolocation.Info?,
        tag: String,
        failAt: String? = null,
        failMsg: String? = null,
    ): JSONObject {
        val q = query(profile?.shareLink.orEmpty())
        val ob = runCatching { JSONObject(profile?.outboundJson.orEmpty()) }.getOrNull()
        val type = profile?.type.orEmpty()
        val host = profile?.server?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        val port = profile?.port?.takeIf { it in 1..65535 }
        val wsOpts = ob?.optJSONObject("ws-opts")
        val httpUpgrade = wsOpts?.optBoolean("v2ray-http-upgrade") == true ||
            q["type"].equals("httpupgrade", ignoreCase = true)
        val transport = (
            q["type"] ?: q["network"]
                ?: ob?.optString("network")?.takeIf { it.isNotBlank() }
                ?: profile?.transport?.takeIf { it.isNotBlank() }
                ?: if (httpUpgrade) "httpupgrade" else "tcp"
            ).lowercase().let { t ->
                when {
                    httpUpgrade && t == "ws" -> "httpupgrade"
                    t == "websocket" -> "ws"
                    t == "httpupgrade" -> "httpupgrade"
                    t == "grpc" -> "grpc"
                    t == "h2" || t == "http2" -> "h2"
                    t == "quic" -> "quic"
                    t == "ws" -> "ws"
                    else -> t
                }
            }
        val reality = q["security"].equals("reality", ignoreCase = true) ||
            ob?.has("reality-opts") == true ||
            ob?.optString("reality-enabled").equals("true", ignoreCase = true)
        val tlsFlag = jsonFlag(ob, "tls") ||
            q["security"].equals("tls", ignoreCase = true) ||
            type.equals("trojan", ignoreCase = true)
        val security = when {
            reality -> "reality"
            tlsFlag -> "tls"
            else -> "none"
        }
        val sni = listOf(
            q["sni"],
            q["servername"],
            ob?.optString("servername")?.takeIf { it.isNotBlank() },
            ob?.optString("sni")?.takeIf { it.isNotBlank() },
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val path = listOf(
            q["path"],
            wsOpts?.optString("path")?.takeIf { it.isNotBlank() },
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val protocol = when {
            type.contains("hysteria", ignoreCase = true) -> "hysteria"
            type.equals("shadowsocks", ignoreCase = true) || type.equals("ss", ignoreCase = true) -> "ss"
            type.isNotBlank() -> type.lowercase()
            else -> "tunnel"
        }
        val publicHost = host.takeIf { it.isNotBlank() && !LiveNetPing.isLoopback(it) }
        val cdn = looksCdn(host, sni, transport, security)
        val o = JSONObject()
        o.put("name", tag.take(40).ifBlank { profile?.displayTag.orEmpty().take(40) })
        o.put("protocol", protocol)
        o.put("transport", transport)
        o.put("security", security)
        if (!publicHost.isNullOrBlank()) o.put("host", publicHost)
        if (!publicHost.isNullOrBlank() && looksLikeIp(publicHost)) o.put("ip", publicHost)
        if (port != null) o.put("port", port)
        if (sni.isNotBlank()) o.put("sni", sni)
        if (path.isNotBlank() && transport in setOf("ws", "httpupgrade")) o.put("path", path)
        o.put("cdn", cdn)
        if (cdn && !publicHost.isNullOrBlank() && looksLikeIp(publicHost)) o.put("cdnIp", publicHost)
        publicIp(geo?.realIp)?.takeIf { looksLikeIp(it) }?.let { o.put("localIp", it) }
        val at = failAt?.lowercase()?.takeIf { it in setOf("dns", "tcp", "tls", "ws") }
        if (at != null) {
            val fail = JSONObject().put("at", at)
            failMsg?.trim()?.takeIf { it.isNotBlank() }?.let { fail.put("msg", it.take(180)) }
            o.put("fail", fail)
        }
        return stripSecrets(o)
    }

    fun looksCdn(host: String, sni: String, transport: String = "", security: String = ""): Boolean {
        val h = host.lowercase()
        val s = sni.lowercase()
        if (h.endsWith(".workers.dev") || h.endsWith(".pages.dev")) return true
        if ("cloudflare" in h || "cloudflare" in s) return true
        val edgeTransport = transport.lowercase() in setOf("ws", "httpupgrade", "grpc", "h2")
        return edgeTransport && security == "tls" && looksLikeIp(host) && sni.contains('.')
    }

    fun failStep(raw: String?): String? {
        val m = raw?.lowercase().orEmpty()
        if (m.isBlank()) return null
        return when {
            "dns" in m -> "dns"
            "tls" in m || "ssl" in m || "certificate" in m || "handshake" in m -> "tls"
            "websocket" in m || "switching protocols" in m -> "ws"
            "tcp" in m || "econnrefused" in m || "connection refused" in m -> "tcp"
            else -> null
        }
    }

    fun allowedKeys(): Set<String> = PATH_KEYS

    /**
     * Overlay a real hop (Cat GP Google/CF front, etc.) so Home never shows loopback
     * as the path. Only values the caller already has — never UUID/password.
     */
    fun applyEdge(
        o: JSONObject,
        name: String? = null,
        host: String? = null,
        ip: String? = null,
        sni: String? = null,
        port: Int? = null,
        cdn: Boolean? = null,
        protocol: String? = null,
        transport: String? = null,
        security: String? = null,
    ): JSONObject {
        name?.trim()?.takeIf { it.isNotBlank() }?.let { o.put("name", it.take(40)) }
        protocol?.trim()?.takeIf { it.isNotBlank() }?.let { o.put("protocol", it.lowercase()) }
        transport?.trim()?.takeIf { it.isNotBlank() }?.let { o.put("transport", it.lowercase()) }
        security?.trim()?.takeIf { it.isNotBlank() }?.let { o.put("security", it.lowercase()) }
        val publicHost = host?.trim()?.removePrefix("[")?.removeSuffix("]")
            ?.takeIf { it.isNotBlank() && !LiveNetPing.isLoopback(it) }
        val publicIp = ip?.trim()?.removePrefix("[")?.removeSuffix("]")
            ?.takeIf { it.isNotBlank() && !LiveNetPing.isLoopback(it) && looksLikeIp(it) }
        if (!publicHost.isNullOrBlank()) o.put("host", publicHost)
        if (!publicIp.isNullOrBlank()) o.put("ip", publicIp)
        sni?.trim()?.takeIf { it.isNotBlank() }?.let { o.put("sni", it) }
        port?.takeIf { it in 1..65535 }?.let { o.put("port", it) }
        if (cdn != null) o.put("cdn", cdn)
        if (cdn == true && !publicIp.isNullOrBlank()) o.put("cdnIp", publicIp)
        return stripSecrets(o)
    }

    private fun stripSecrets(o: JSONObject): JSONObject {
        val keys = o.keys().asSequence().toList()
        for (k in keys) {
            if (k !in PATH_KEYS) o.remove(k)
        }
        return o
    }

    private fun jsonFlag(ob: JSONObject?, key: String): Boolean {
        if (ob == null || !ob.has(key)) return false
        return when (val v = ob.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> v.toString().equals("true", ignoreCase = true)
        }
    }

    private fun query(link: String): Map<String, String> {
        val q = link.substringAfter('?', "").substringBefore('#')
        if (q.isBlank()) return emptyMap()
        return q.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null
            else runCatching {
                java.net.URLDecoder.decode(part.substring(0, i), "UTF-8").lowercase() to
                    java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }.getOrNull()
        }.toMap()
    }
}

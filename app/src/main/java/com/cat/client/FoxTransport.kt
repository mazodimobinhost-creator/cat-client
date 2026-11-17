package com.cat.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Cat Fox VPN tunnel protocol (independent of other clients).
 *
 * Mozilla's Fastly nodes speak HTTP CONNECT on TCP 2499:
 *  - HTTP/2 (`h2`) is the default
 *  - HTTP/1.1 is a real choice for lines that stall long HTTP/2 connections
 *  - HTTP/3 is QUIC; this build has no QUIC stack, so `h3` uses HTTP/2 and says so
 *
 * [nodesFromRecord] reads every city/server/protocol host from Mozilla's
 * `vpn-serverlist` JSON (not only the first server).
 */
internal object FoxTransport {
    const val AUTO = "auto"
    const val H1 = "h1"
    const val H2 = "h2"
    const val H3 = "h3"

    val MODES: List<String> = listOf(AUTO, H2, H1, H3)

    data class Node(val host: String, val port: Int, val masqueTemplate: String = "") {
        val endpoint: String get() = "$host:$port"
    }

    fun normalize(raw: String?): String = when (raw.orEmpty().trim().lowercase(Locale.US)) {
        H1, "http1", "http/1.1", "http1.1", "tls" -> H1
        H3, "http3", "http/3", "quic" -> H3
        H2, "http2", "http/2" -> H2
        else -> AUTO
    }

    fun labelFa(mode: String): String = when (normalize(mode)) {
        H1 -> "HTTP/1.1 (هر سایت یک اتصال TLS — برای خط‌هایی که HTTP/2 را قطع می‌کنند)"
        H2 -> "HTTP/2 (پیشنهادی — چند سایت روی یک اتصال)"
        H3 -> "HTTP/3 (QUIC) — این ساخت هستهٔ QUIC ندارد؛ همان HTTP/2 استفاده می‌شود"
        else -> "خودکار (اول HTTP/2، اگر نود نداد HTTP/1.1)"
    }

    fun labelEn(mode: String): String = when (normalize(mode)) {
        H1 -> "HTTP/1.1 (one TLS connection per site — for lines that stall HTTP/2)"
        H2 -> "HTTP/2 (recommended — many sites on one connection)"
        H3 -> "HTTP/3 (QUIC) — this build has no QUIC stack; HTTP/2 is used"
        else -> "Automatic (HTTP/2 first, HTTP/1.1 if the node does not offer it)"
    }

    fun shortFa(mode: String): String = when (normalize(mode)) {
        H1 -> "HTTP/1.1"
        H2 -> "HTTP/2"
        H3 -> "HTTP/3 → HTTP/2"
        else -> "خودکار"
    }

    fun shortEn(mode: String): String = when (normalize(mode)) {
        H1 -> "HTTP/1.1"
        H2 -> "HTTP/2"
        H3 -> "HTTP/3 → HTTP/2"
        else -> "Auto"
    }

    fun forceH1(mode: String): Boolean = normalize(mode) == H1

    fun alpn(mode: String): Array<String> = when (normalize(mode)) {
        H1 -> arrayOf("http/1.1")
        H2, H3 -> arrayOf("h2")
        else -> arrayOf("h2", "http/1.1")
    }

    fun wireName(mode: String): String = when (normalize(mode)) {
        H1 -> "HTTP/1.1"
        H2 -> "HTTP/2"
        H3 -> "HTTP/2"
        else -> "HTTP/2"
    }

    /**
     * Every usable Fastly node on a Mozilla country record: each city's servers,
     * plus `protocols` named `connect` / `masque`. First item is the one to dial first.
     */
    fun nodesFromRecord(rec: JSONObject, defaultPort: Int = 2499): List<Node> {
        val out = ArrayList<Node>()
        val seen = HashSet<String>()
        fun add(host: String, port: Int, template: String = "") {
            val h = host.trim().lowercase(Locale.US)
            if (h.isEmpty() || h.endsWith(".fpn.mozilla.net")) return
            val p = if (port in 1..65535) port else defaultPort
            val key = "$h:$p"
            if (!seen.add(key)) return
            out += Node(h, p, template.trim())
        }
        val cities = rec.optJSONArray("cities") ?: JSONArray()
        for (i in 0 until cities.length()) {
            val city = cities.optJSONObject(i) ?: continue
            val servers = city.optJSONArray("servers") ?: JSONArray()
            for (s in 0 until servers.length()) {
                val srv = servers.optJSONObject(s) ?: continue
                if (srv.optBoolean("quarantined", false)) continue
                val host = srv.optString("hostname").ifBlank { srv.optString("host") }
                val port = srv.optInt("port", defaultPort)
                add(host, port)
                val protocols = srv.optJSONArray("protocols") ?: JSONArray()
                for (p in 0 until protocols.length()) {
                    val proto = protocols.optJSONObject(p) ?: continue
                    val name = proto.optString("name").trim().lowercase(Locale.US)
                    if (name.isNotEmpty() && name != "connect" && name != "masque") continue
                    add(
                        proto.optString("host").ifBlank { host },
                        proto.optInt("port", port),
                        proto.optString("templateString"),
                    )
                }
            }
        }
        return out
    }

    fun extraEndpointsJson(nodes: List<Node>): JSONArray {
        val arr = JSONArray()
        nodes.drop(1).forEach { arr.put(it.endpoint) }
        return arr
    }

    fun parseExtraEndpoints(raw: Any?): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        when (raw) {
            is JSONArray -> for (i in 0 until raw.length()) {
                val e = raw.optString(i).trim()
                if (e.isNotEmpty() && seen.add(e.lowercase(Locale.US))) out += e
            }
            is String -> raw.split(',', ' ').map { it.trim() }.filter { it.contains(':') }.forEach {
                if (seen.add(it.lowercase(Locale.US))) out += it
            }
        }
        return out
    }

    fun parseEndpoint(raw: String): Pair<String, Int>? {
        val t = raw.trim()
        val host: String
        val portText: String
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end < 0) return null
            host = t.substring(1, end)
            portText = t.substring(end + 1).removePrefix(":")
        } else {
            val c = t.lastIndexOf(':')
            if (c <= 0) return null
            host = t.substring(0, c)
            portText = t.substring(c + 1)
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank()) return null
        return host to port
    }
}

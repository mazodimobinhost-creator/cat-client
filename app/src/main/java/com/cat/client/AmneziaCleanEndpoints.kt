package com.cat.client

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Clean Cloudflare WARP `IP:port` rows for AmneziaWG. Exact port kept (never 80/1080/443).
 * Amnezia 1.5 init packets (`i1`–`i5`, `itime`) are extra UDP before the handshake —
 * vanilla WARP ignores them; `s1`/`h1` are not added (those must match the server).
 */
internal object AmneziaCleanEndpoints {
    val FEEDS = listOf(
        "https://cdn.jsdelivr.net/gh/DarknessShade/Amnezia-VPN-Config@main/IP.txt",
        "https://fastly.jsdelivr.net/gh/DarknessShade/Amnezia-VPN-Config@main/IP.txt",
        "https://raw.githubusercontent.com/DarknessShade/Amnezia-VPN-Config/main/IP.txt",
    )

    /** Real rows from the public list (UDP WARP ports). Used when the list cannot be fetched. */
    val FALLBACK = listOf(
        "8.6.112.208:2408",
        "8.6.112.10:1701",
        "8.6.112.121:500",
        "8.6.112.237:3138",
        "8.6.112.224:5279",
        "8.6.112.132:8854",
    )

    private val LINE = Regex("""^(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})$""")

    fun parse(text: String, maxEntries: Int = 80): List<String> {
        if (text.isEmpty() || text.length > 512 * 1024) return emptyList()
        val out = LinkedHashSet<String>()
        text.lineSequence().forEach { raw ->
            if (out.size >= maxEntries) return@forEach
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach
            val m = LINE.matchEntire(line) ?: return@forEach
            val host = m.groupValues[1]
            val port = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (port !in 1..65535 || port == 80 || port == 1080 || port == 443) return@forEach
            if (LiveNetPing.isLoopback(host)) return@forEach
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return@forEach
            CatWarpFreePlanner.keepExactPeer("$host:$port")?.let { out += it }
        }
        return out.toList()
    }

    fun initPacketQuery(): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        return "&i1=${enc("<r 8+8>")}&i2=${enc("<r 4+4>")}&i3=${enc("<r 4+4>")}&i4=${enc("<r 2+2>")}&i5=${enc("<r 2+2>")}&itime=120"
    }

    fun awgUri(peer: String, withInitPackets: Boolean = true): String? {
        val base = CatWarpFreePlanner.awgUri(peer, "Cat Warp $peer") ?: return null
        if (!withInitPackets) return base
        val extra = initPacketQuery()
        val hash = base.indexOf('#')
        return if (hash < 0) base + extra else base.substring(0, hash) + extra + base.substring(hash)
    }

    fun urisFromPeers(peers: Collection<String>, withInitPackets: Boolean = true): List<String> =
        peers.mapNotNull { keep -> CatWarpFreePlanner.keepExactPeer(keep)?.let { awgUri(it, withInitPackets) } }.distinct()

    fun fetch(timeoutMs: Int = 3500): List<String> {
        for (url in FEEDS) {
            val body = runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "CatClient")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            val parsed = body?.let { parse(it) }.orEmpty()
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    fun liveOrFallback(): List<String> {
        val live = runCatching { fetch() }.getOrDefault(emptyList())
        if (live.isNotEmpty()) return live
        return parse(FALLBACK.joinToString("\n"))
    }
}

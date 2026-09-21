package com.cat.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * FreeConfigs — fetches publicly available, tested configs from multiple
 * community-maintained sources. Sources are refreshed daily; each config is
 * returned with a tag that includes source + country hint (if parsable).
 *
 * All sources are HTTPS and serve plain or base64 lists of standard share-links
 * (vless://, vmess://, trojan://, ss://, hy2://, tuic://, wg://).
 */
object FreeConfigs {

    data class FreeSource(
        val id: String,
        val name: String,
        val url: String,
        val nameFa: String,
    )

    val SOURCES = listOf(
        FreeSource(
            id = "v2fly",
            name = "V2Fly Community",
            url = "https://raw.githubusercontent.com/v2fly/config/master/config.txt",
            nameFa = "جامعه V2Fly",
        ),
        FreeSource(
            id = "ircf",
            name = "IRCf Space",
            url = "https://raw.githubusercontent.com/ircfspace/cfworker-vless/main/dist/worker.txt",
            nameFa = "IRCf Space",
        ),
        FreeSource(
            id = "freefq",
            name = "FreeFQ",
            url = "https://raw.githubusercontent.com/freefq/free/master/v2",
            nameFa = "FreeFQ",
        ),
        FreeSource(
            id = "ermilite",
            name = "ErmiLite Auto",
            url = "https://raw.githubusercontent.com/ErmiLite/AutoConfig/main/normal",
            nameFa = "ErmiLite Auto",
        ),
    )

    data class FreeEntry(
        val link: String,
        val tag: String,
        val source: String,
    )

    suspend fun fetchAll(context: Context): List<FreeEntry> = withContext(Dispatchers.IO) {
        val all = mutableListOf<FreeEntry>()
        for (s in SOURCES) {
            runCatching {
                val text = fetchUrl(s.url)
                val decoded = decodeIfBase64(text)
                val links = decoded.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .filter { it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("trojan://") || it.startsWith("ss://") || it.startsWith("hy2://") || it.startsWith("hysteria2://") || it.startsWith("tuic://") || it.startsWith("wg://") }
                links.forEachIndexed { i, link ->
                    all.add(FreeEntry(link = link, tag = "Free · ${s.name} #${i + 1}", source = s.id))
                }
            }
        }
        // Deduplicate
        all.distinctBy { it.link }
    }

    private fun fetchUrl(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "CatClient/1.0")
        conn.useCaches = false
        return try {
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeIfBase64(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        // If it's already share-links, return as-is
        if (trimmed.lines().any { it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("trojan://") || it.startsWith("ss://") }) {
            return trimmed
        }
        return runCatching {
            val decoded = String(Base64.getDecoder().decode(trimmed.filter { !it.isWhitespace() }))
            if (decoded.contains("://")) decoded else trimmed
        }.getOrDefault(trimmed)
    }
}

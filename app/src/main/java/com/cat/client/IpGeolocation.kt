package com.cat.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * IpGeolocation — what's-my-ip and country detection used on the main dashboard.
 * The app calls this after connect/disconnect and whenever the active tunnel
 * interface changes so it can show flag + country + IP on the orb / globe.
 */
object IpGeolocation {

    data class Info(
        val ip: String,
        val countryCode: String,
        val countryName: String,
        val city: String?,
        val isp: String?,
        val colo: String? = null,
    ) {
        val flag: String get() = countryCode.toFlagEmoji()
    }

    /**
     * Live exit detection for the dashboard map: reads Cloudflare's own
     * /cdn-cgi/trace through the active tunnel first — it reports the real
     * edge colo (e.g. FRA) and exit IP — then falls back to geoip APIs.
     */
    suspend fun locate(proxy: java.net.Proxy? = null): Info? = withContext(Dispatchers.IO) {
        trace(proxy) ?: detect(proxy)
    }

    private suspend fun trace(proxy: java.net.Proxy?): Info? {
        val body = runCatching { fetchRaw("https://www.cloudflare.com/cdn-cgi/trace", proxy) }.getOrNull()
            ?: return null
        val fields = body.lineSequence()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        val cc = fields["loc"]?.trim()?.uppercase().orEmpty()
        val ip = fields["ip"]?.trim().orEmpty()
        if (cc.length != 2 || ip.isBlank()) return null
        val colo = fields["colo"]?.trim()?.takeIf { it.isNotBlank() }
        val countryName = runCatching { java.util.Locale("", cc).displayCountry }.getOrNull().orEmpty()
        return Info(
            ip = ip,
            countryCode = cc,
            countryName = countryName.ifBlank { cc },
            city = colo,
            isp = "Cloudflare",
            colo = colo,
        )
    }

    private fun fetchRaw(url: String, proxy: java.net.Proxy?): String? {
        val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection())
            as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 6_000
        conn.setRequestProperty("User-Agent", "CatClient/1.0")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            return null
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return body
    }

    suspend fun detect(proxy: java.net.Proxy? = null): Info? = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://ipwho.is/",           // free, no key, returns country_code/country/city/isp
            "https://ipapi.co/json/",      // fallback
            "https://api.ip.sb/geoip",     // second fallback
        )
        for (url in endpoints) {
            val v = runCatching { fetch(url, proxy) }.getOrNull()
            if (v != null) return@withContext v
        }
        null
    }

    private fun fetch(url: String, proxy: java.net.Proxy?): Info? {
        val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection())
            as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "CatClient/1.0")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            return null
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val o = JSONObject(body)
        // ipwho.is format
        val ip = o.optString("ip").ifBlank { o.optString("query") }
        val cc = o.optString("country_code").ifBlank { o.optString("country") }
        val cn = o.optString("country_name").ifBlank { o.optString("country_name") }.ifBlank { cc }
        if (ip.isBlank() || cc.isBlank()) return null
        return Info(
            ip = ip,
            countryCode = cc.uppercase(),
            countryName = cn,
            city = o.optString("city").ifBlank { null },
            isp = o.optString("org").ifBlank { o.optString("isp") }.ifBlank { null },
        )
    }
}

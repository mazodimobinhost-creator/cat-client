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
    ) {
        val flag: String get() = countryCode.toFlagEmoji()
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

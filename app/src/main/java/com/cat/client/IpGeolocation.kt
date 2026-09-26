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
 *
 * Two different IPs matter and they are NOT the same:
 *  - [Info.ip]      = the tunnel exit IP (what websites actually see).
 *  - [Info.realIp]  = the phone's own ISP IP (the tunnel entry).
 * Sites compare against the exit; "what is my ip" pages show both, which is
 * exactly why the dashboard now labels them separately.
 */
object IpGeolocation {

    data class Info(
        val ip: String,
        val countryCode: String,
        val countryName: String,
        val city: String?,
        val isp: String?,
        val colo: String? = null,
        val realIp: String? = null,
        val realCountryCode: String? = null,
        val realCountryName: String? = null,
    ) {
        val flag: String get() = countryCode.toFlagEmoji()
        val realFlag: String? get() = realCountryCode?.toFlagEmoji()
    }

    /**
     * Live exit detection for the dashboard map. Order:
     *  1. the panel worker's /api/geo (when [workerHost] is known) — one call
     *     returns the exit IP with a real geo-DB lookup AND the phone's real IP;
     *  2. Cloudflare's /cdn-cgi/trace through the active tunnel (real edge colo
     *     + exit IP), then refined with a geo-DB lookup so the country shown
     *     matches what geo sites display for that IP (trace `loc` alone can
     *     disagree with them);
     *  3. plain geoip APIs (direct when the tunnel is down).
     */
    suspend fun locate(proxy: java.net.Proxy? = null, workerHost: String? = null): Info? =
        withContext(Dispatchers.IO) {
            if (!workerHost.isNullOrBlank()) {
                runCatching { panelGeo(workerHost) }.getOrNull()?.let { return@withContext it }
            }
            trace(proxy, refine = true) ?: detect(proxy)
        }

    /** One-shot dashboard payload from the user's own deployed worker. */
    private fun panelGeo(workerHost: String): Info? {
        val host = workerHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (host.isEmpty() || host.contains(' ') || host.contains('/')) return null
        val body = runCatching {
            fetchRaw("https://" + host + "/api/geo", null, timeoutMs = 9_000)
        }.getOrNull() ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!o.optBoolean("ok", false)) return null
        val exit = o.optJSONObject("exit")
        val real = o.optJSONObject("real")
        val main = exit ?: real ?: return null
        val ip = main.optString("ip").orEmpty()
        val cc = main.optString("cc").orEmpty().uppercase()
        if (ip.isBlank() || cc.length != 2) return null
        val countryName = main.optString("country").orEmpty().ifBlank {
            runCatching { java.util.Locale("", cc).displayCountry }.getOrNull().orEmpty().ifBlank { cc }
        }
        val realIp = real?.optString("ip")?.takeIf { it.isNotBlank() && it != ip }
        val realCc = real?.optString("cc")?.orEmpty()?.uppercase()?.takeIf { it.length == 2 }
        return Info(
            ip = ip,
            countryCode = cc,
            countryName = countryName,
            city = main.optString("city").orEmpty().takeIf { it.isNotBlank() },
            isp = main.optString("isp").orEmpty().takeIf { it.isNotBlank() },
            colo = o.optString("entryColo").orEmpty().takeIf { it.isNotBlank() },
            realIp = realIp,
            realCountryCode = realCc,
            realCountryName = realCc?.let { code ->
                real?.optString("country")?.orEmpty()?.takeIf { it.isNotBlank() }
                    ?: runCatching { java.util.Locale("", code).displayCountry }.getOrNull().orEmpty().ifBlank { code }
            },
        )
    }

    private suspend fun trace(proxy: java.net.Proxy?, refine: Boolean = false): Info? {
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
        val base = Info(
            ip = ip,
            countryCode = cc,
            countryName = countryName.ifBlank { cc },
            city = colo,
            isp = "Cloudflare",
            colo = colo,
        )
        // Trace `loc` is Cloudflare's own view of the exit IP and can disagree
        // with the geo databases that "what is my ip" style sites use — refine
        // it so the dashboard never contradicts the browser.
        if (!refine) return base
        return runCatching { refineWithGeoDb(base, proxy) }.getOrDefault(base)
    }

    /** Overrides country/city/isp of [info] with a geo-DB answer for its IP. */
    private suspend fun refineWithGeoDb(info: Info, proxy: java.net.Proxy?): Info {
        val raw = runCatching {
            fetchRaw("https://ipwho.is/" + java.net.URLEncoder.encode(info.ip, "UTF-8"), proxy, timeoutMs = 6_000)
        }.getOrNull() ?: return info
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return info
        if (o.optBoolean("success", true) == false) return info
        val cc = o.optString("country_code").orEmpty().uppercase()
        if (cc.length != 2) return info
        val countryName = o.optString("country").orEmpty().ifBlank {
            runCatching { java.util.Locale("", cc).displayCountry }.getOrNull().orEmpty().ifBlank { cc }
        }
        return info.copy(
            countryCode = cc,
            countryName = countryName,
            city = o.optString("city").orEmpty().takeIf { it.isNotBlank() } ?: info.city,
            isp = (o.optJSONObject("connection")?.optString("isp")).orEmpty()
                .ifBlank { o.optString("isp").orEmpty() }
                .ifBlank { info.isp ?: "" }
                .takeIf { it.isNotBlank() },
        )
    }

    private fun fetchRaw(url: String, proxy: java.net.Proxy?, timeoutMs: Int = 6_000): String? {
        val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection())
            as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
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

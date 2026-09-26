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
 *  - [Info.ip]     = the tunnel EXIT IP (what websites actually see). Measured
 *    with a /cdn-cgi/trace sent over DEFAULT routing — the TUN captures it
 *    while connected, so it always matches the browser.
 *  - [Info.realIp] = the phone's own ISP IP (the tunnel ENTRY). Measured with
 *    the same trace BOUND to the physical network, which bypasses the TUN.
 *
 * Country names come from a geo-DB (the same source class "what is my ip"
 * sites use), not from Cloudflare's trace `loc`, so the dashboard never
 * disagrees with the browser. The worker is used ONLY as a geo-DB proxy
 * (/api/geo?ip=) — it must never decide the exit itself: its own egress is a
 * datacenter IP, not the user's.
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

    /** Geo-DB answer for one address. */
    data class Geo(val cc: String, val country: String, val city: String?, val isp: String?)

    /**
     * Live exit detection for the dashboard map.
     * @param workerHost the deployed panel worker, used as a cached geo-DB proxy.
     * @param physicalNetwork the network BEHIND the VPN (null = skip real-IP step).
     * @param includeReal fetch the real ISP IP (only meaningful while connected).
     */
    suspend fun locate(
        proxy: java.net.Proxy? = null,
        workerHost: String? = null,
        physicalNetwork: android.net.Network? = null,
        includeReal: Boolean = false,
    ): Info? = withContext(Dispatchers.IO) {
        // 1. EXIT — default routing: while connected the TUN captures this.
        val exit = runCatching { traceRaw(null, proxy) }.getOrNull()
            ?: return@withContext detect(proxy)
        val exitGeo = runCatching { geoLookup(exit.ip, workerHost, proxy) }.getOrNull()
        // 2. REAL — bound to the physical network, outside the tunnel.
        var realIp: String? = null
        var realCc: String? = null
        var realCountry: String? = null
        if (includeReal && physicalNetwork != null) {
            val real = runCatching { traceRaw(physicalNetwork, null) }.getOrNull()
            if (real != null && real.ip.isNotBlank() && real.ip != exit.ip) {
                val g = runCatching { geoLookup(real.ip, workerHost, proxy) }.getOrNull()
                realIp = real.ip
                realCc = (g?.cc ?: real.countryCode).takeIf { it.length == 2 }
                realCountry = g?.country?.takeIf { it.isNotBlank() } ?: real.countryName
            }
        }
        val cc = (exitGeo?.cc ?: exit.countryCode).uppercase()
        Info(
            ip = exit.ip,
            countryCode = cc.ifBlank { exit.countryCode },
            countryName = exitGeo?.country?.takeIf { it.isNotBlank() }
                ?: exit.countryName,
            city = exitGeo?.city ?: exit.city,
            isp = exitGeo?.isp ?: exit.isp,
            colo = exit.colo,
            realIp = realIp,
            realCountryCode = realCc,
            realCountryName = realCountry,
        )
    }

    /** /cdn-cgi/trace parsed into an Info. [network] binds the socket to a
     *  specific network (physical = bypasses the VPN). */
    private fun traceRaw(network: android.net.Network?, proxy: java.net.Proxy?): Info? {
        val body = fetchRaw("https://www.cloudflare.com/cdn-cgi/trace", proxy, network = network) ?: return null
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

    /** Geo-DB lookup for one IP: the panel worker first (cached, reachable
     *  through the tunnel), then ipwho.is directly. */
    private suspend fun geoLookup(ip: String, workerHost: String?, proxy: java.net.Proxy?): Geo? {
        if (!workerHost.isNullOrBlank()) {
            runCatching { workerGeo(workerHost, ip) }.getOrNull()?.let { return it }
        }
        return runCatching { directGeo(ip, proxy) }.getOrNull()
    }

    private fun workerGeo(workerHost: String, ip: String): Geo? {
        val host = workerHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (host.isEmpty() || host.contains(' ') || host.contains('/')) return null
        val body = fetchRaw("https://" + host + "/api/geo?ip=" + java.net.URLEncoder.encode(ip, "UTF-8"), timeoutMs = 9_000) ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!o.optBoolean("ok", false)) return null
        val g = o.optJSONObject("geo")?.optJSONObject(ip) ?: return null
        val cc = g.optString("cc").orEmpty().uppercase()
        if (cc.length != 2) return null
        return Geo(
            cc = cc,
            country = g.optString("country").orEmpty(),
            city = g.optString("city").orEmpty().takeIf { it.isNotBlank() },
            isp = g.optString("isp").orEmpty().takeIf { it.isNotBlank() },
        )
    }

    private suspend fun directGeo(ip: String, proxy: java.net.Proxy?): Geo? = withContext(Dispatchers.IO) {
        val raw = fetchRaw("https://ipwho.is/" + java.net.URLEncoder.encode(ip, "UTF-8"), proxy, timeoutMs = 6_000) ?: return@withContext null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
        if (o.optBoolean("success", true) == false) return@withContext null
        val cc = o.optString("country_code").orEmpty().uppercase()
        if (cc.length != 2) return@withContext null
        Geo(
            cc = cc,
            country = o.optString("country").orEmpty(),
            city = o.optString("city").orEmpty().takeIf { it.isNotBlank() },
            isp = (o.optJSONObject("connection")?.optString("isp")).orEmpty()
                .ifBlank { o.optString("isp").orEmpty() }
                .takeIf { it.isNotBlank() },
        )
    }

    private fun fetchRaw(
        url: String,
        proxy: java.net.Proxy? = null,
        timeoutMs: Int = 6_000,
        network: android.net.Network? = null,
    ): String? {
        val conn = (when {
            network != null -> network.openConnection(URL(url))
            proxy != null -> URL(url).openConnection(proxy)
            else -> URL(url).openConnection()
        }) as HttpURLConnection
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

    /** Direct geoip APIs — used when the tunnel is down (shows the real IP). */
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

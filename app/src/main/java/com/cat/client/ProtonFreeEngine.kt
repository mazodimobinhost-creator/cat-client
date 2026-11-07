package com.cat.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * beta254 — 🟢 Cat Proton: the service's own **free** plan (unlimited data, a handful of countries),
 * used the same way Cat Fox uses a free Firefox account: the user creates / signs in on the official
 * page (human check stays theirs). After the session cookie appears, Cat calls Proton's account API
 * and adds every WireGuard row the account is allowed (Tier 0 for free; paid countries only if they
 * already paid). Plus is not faked.
 *
 * Protocol from Proton's published API shape (`LogicalServers`, `Tier`, `X25519`) and the official
 * account API (`/api/vpn`, `/api/vpn/logicals`, `/api/vpn/v1/certificate`); independent Kotlin.
 */
internal object ProtonFreePlanner {
    const val SIGNUP = "https://account.proton.me/vpn/signup"
    const val LOGIN = "https://account.proton.me/vpn/login"
    const val DOWNLOADS = "https://account.protonvpn.com/downloads"
    const val APP_VERSION = "web-vpn-settings@5.0.2.0"

    private val API_ROOTS = listOf(
        "https://account.protonvpn.com/api",
        "https://account.proton.me/api",
    )

    data class Country(val code: String, val flag: String, val nameFa: String, val nameEn: String) {
        fun label(fa: Boolean) = flag + " " + (if (fa) nameFa else nameEn)
    }

    /**
     * Countries Proton's free plan has publicly offered (PCMag / Proton, 2025–2026). The live
     * `logicals` list (when the signed-in page gives it) is the authority; this is the picker
     * before the first fetch.
     */
    val FREE_COUNTRIES = listOf(
        Country("NL", "🇳🇱", "هلند", "Netherlands"),
        Country("JP", "🇯🇵", "ژاپن", "Japan"),
        Country("US", "🇺🇸", "آمریکا", "United States"),
        Country("CA", "🇨🇦", "کانادا", "Canada"),
        Country("RO", "🇷🇴", "رومانی", "Romania"),
        Country("PL", "🇵🇱", "لهستان", "Poland"),
        Country("NO", "🇳🇴", "نروژ", "Norway"),
        Country("SG", "🇸🇬", "سنگاپور", "Singapore"),
    )

    fun flagOf(code: String): String = LanternPlanner.flagOf(code)

    data class Server(
        val name: String,
        val country: String,
        val city: String,
        val tier: Int,
        val entryIp: String,
        val x25519: String,
        val load: Int,
    ) {
        val free: Boolean get() = tier == 0
    }

    data class Session(val uid: String, val cookieHeader: String)

    fun interface Http {
        fun call(method: String, url: String, headers: Map<String, String>, body: String?): String
    }

    /** `AUTH-<uid>=…` in the WebView cookie jar means the human check is done. */
    fun sessionFromCookies(vararg cookies: String?): Session? {
        val joined = cookies.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.joinToString("; ")
        if (joined.isBlank()) return null
        val auth = Regex("""(?:^|;\s*)AUTH-([^=\s]+)=([^;]+)""").find(joined) ?: return null
        val uid = auth.groupValues[1].trim()
        val token = auth.groupValues[2].trim()
        if (uid.isEmpty() || token.isEmpty()) return null
        return Session(uid, joined)
    }

    fun looksSignedIn(cookies: String?, url: String = ""): Boolean {
        if (sessionFromCookies(cookies) != null) return true
        val u = url.lowercase(Locale.US)
        if ("login" in u || "signup" in u || "pricing" in u) return false
        return false
    }

    /** `GET /vpn/logicals` (or `/api/vpn/logicals`) body → online servers with an X25519 key. */
    fun servers(json: String): List<Server> = runCatching {
        val root = JSONObject(json)
        val arr = root.optJSONArray("LogicalServers") ?: JSONArray()
        val out = ArrayList<Server>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("Status", 1) != 1) continue
            val country = o.optString("ExitCountry").ifBlank { o.optString("EntryCountry") }.trim().uppercase(Locale.US)
            if (country.length != 2) continue
            val name = o.optString("Name").ifBlank { country }
            val city = o.optString("City")
            val tier = o.optInt("Tier", 2)
            val load = o.optInt("Load", 0)
            val nodes = o.optJSONArray("Servers") ?: JSONArray()
            for (j in 0 until nodes.length()) {
                val n = nodes.optJSONObject(j) ?: continue
                if (n.optInt("Status", 1) != 1) continue
                val ip = n.optString("EntryIP").ifBlank { n.optString("ExitIP") }.trim()
                val key = n.optString("X25519").ifBlank { n.optString("X25519PublicKey") }.trim()
                if (ip.isBlank() || key.isBlank()) continue
                out += Server(name, country, city, tier, ip, key, load)
                break // one node per logical is enough for a row
            }
        }
        out
    }.getOrDefault(emptyList())

    fun freeServers(all: List<Server>): List<Server> = all.filter { it.free }

    fun countriesOf(servers: List<Server>): List<String> =
        servers.map { it.country }.filter { it.length == 2 }.distinct()

    /** Lowest-load servers the account may use (free = tier 0 only). Cap keeps the list usable. */
    fun pickServers(all: List<Server>, maxTier: Int, want: Set<String> = emptySet()): List<Server> {
        val entitled = all.filter { it.tier <= maxTier }
            .filter { want.isEmpty() || it.country in want }
        val out = ArrayList<Server>()
        for ((_, group) in entitled.groupBy { it.country }) {
            out += group.sortedBy { it.load }.take(3)
        }
        return out.take(40)
    }

    /** Country code from a Proton `.conf` (filename, `# NL-FREE#1`, `# Netherlands`, …). */
    fun countryOfConf(text: String, filename: String = ""): String {
        val hay = (filename + "\n" + text)
        Regex("""(?i)\b([A-Z]{2})-FREE(?:#\d+)?\b""").find(hay)?.groupValues?.get(1)?.uppercase(Locale.US)?.let { return it }
        Regex("""(?i)\b([A-Z]{2})#\d+\b""").find(hay)?.groupValues?.get(1)?.uppercase(Locale.US)?.let { return it }
        val upper = hay.uppercase(Locale.US)
        FREE_COUNTRIES.firstOrNull { c ->
            Regex("""\b${c.code}\b""").containsMatchIn(upper) ||
                upper.contains(c.nameEn.uppercase(Locale.US))
        }?.let { return it.code }
        return ""
    }

    fun looksLikeConf(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return "[interface]" in lower && "[peer]" in lower && "privatekey" in lower && "publickey" in lower && "endpoint" in lower
    }

    /** Fragment shown on each `wireguard://` row (the URI itself is built with [WireGuardConfText]). */
    fun rowLabel(text: String, filename: String = "", fa: Boolean = true): String {
        val code = countryOfConf(text, filename)
        val country = FREE_COUNTRIES.firstOrNull { it.code == code }
        return when {
            country != null -> country.flag + " " + (if (fa) country.nameFa else country.nameEn)
            code.length == 2 -> flagOf(code) + " " + code
            else -> filename.ifBlank { "Proton" }
        }
    }

    fun subscriptionText(links: List<String>): String =
        "# 🟢 Cat Proton\n" + links.distinct().joinToString("\n")

    fun maxTier(vpnInfoJson: String): Int? = runCatching {
        val o = JSONObject(vpnInfoJson)
        val vpn = o.optJSONObject("VPN") ?: o
        vpn.optInt("MaxTier", -1).takeIf { it >= 0 }
    }.getOrNull()

    fun pemBody(pem: String): String =
        pem.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("-----") }.joinToString("")

    /**
     * Proton's certificate key is Ed25519 in PKCS#8. WireGuard wants the clamped X25519 private
     * key (SHA-512 of the 32-byte seed, RFC 8032 / RFC 7748). Last 32 bytes of the DER are the seed.
     */
    fun x25519PrivateFromPkcs8Body(bodyB64: String): String {
        val der = Base64.getDecoder().decode(bodyB64.trim())
        require(der.size >= 32) { "Proton private key is too short" }
        val seed = der.copyOfRange(der.size - 32, der.size)
        val hash = MessageDigest.getInstance("SHA-512").digest(seed).copyOf(32)
        hash[0] = (hash[0].toInt() and 0xf8).toByte()
        hash[31] = ((hash[31].toInt() and 0x7f) or 0x40).toByte()
        return Base64.getEncoder().encodeToString(hash)
    }

    fun confFor(privateX25519: String, server: Server): String = buildString {
        append("# ").append(server.name).append('\n')
        append("[Interface]\n")
        append("PrivateKey = ").append(privateX25519).append('\n')
        append("Address = 10.2.0.2/32\n")
        append("DNS = 10.2.0.1\n\n")
        append("[Peer]\n")
        append("PublicKey = ").append(server.x25519).append('\n')
        append("AllowedIPs = 0.0.0.0/0\n")
        append("Endpoint = ").append(server.entryIp).append(":51820\n")
    }

    /**
     * After login: VPN info → logicals the account may use → one Ed25519 key → register it →
     * one `.conf` per picked server. Never bumps MaxTier.
     */
    fun pullWireGuardFiles(session: Session, want: Set<String> = emptySet(), http: Http = DefaultHttp): List<Pair<String, String>> {
        val vpnJson = firstOk(session, http, "/vpn")
        val tier = maxTier(vpnJson) ?: 0
        val logicals = firstOk(session, http, "/vpn/logicals")
        val picked = pickServers(servers(logicals), tier, want)
        if (picked.isEmpty()) throw IOException("this Proton account has no allowed WireGuard servers")
        val keyJson = JSONObject(firstOk(session, http, "/vpn/v1/certificate/key/EC"))
        if (keyJson.optInt("Code", 1000) !in setOf(1000, 0) && keyJson.optString("PrivateKey").isBlank()) {
            throw IOException(keyJson.optString("Error").ifBlank { "Proton key request failed" })
        }
        val privatePem = keyJson.optString("PrivateKey")
        val publicPem = keyJson.optString("PublicKey")
        if (privatePem.isBlank() || publicPem.isBlank()) throw IOException("Proton did not return a WireGuard key")
        val x25519 = x25519PrivateFromPkcs8Body(pemBody(privatePem))
        val register = JSONObject()
            .put("ClientPublicKey", pemBody(publicPem))
            .put("Mode", "persistent")
            .put("DeviceName", "Cat")
            .put(
                "Features",
                JSONObject()
                    .put("platform", "Android")
                    .put("SafeMode", false)
                    .put("SplitTCP", true)
                    .put("PortForwarding", false)
                    .put("RandomNAT", false)
                    .put("NetShieldLevel", 0),
            )
        runCatching { firstOk(session, http, "/vpn/v1/certificate", "POST", register.toString()) }
        return picked.map { server ->
            val file = server.name.replace(Regex("[^A-Za-z0-9._-]"), "-") + ".conf"
            file to confFor(x25519, server)
        }
    }

    private fun firstOk(session: Session, http: Http, path: String, method: String = "GET", body: String? = null): String {
        var last: IOException? = null
        for (root in API_ROOTS) {
            try {
                return http.call(method, root + path, apiHeaders(session, json = body != null), body)
            } catch (error: IOException) {
                last = error
            }
        }
        throw last ?: IOException("Proton API unreachable")
    }

    private fun apiHeaders(session: Session, json: Boolean): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/vnd.protonmail.v1+json"
        headers["x-pm-appversion"] = APP_VERSION
        headers["x-pm-uid"] = session.uid
        headers["Cookie"] = session.cookieHeader
        headers["User-Agent"] = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
        if (json) headers["Content-Type"] = "application/json"
        return headers
    }

    object DefaultHttp : Http {
        override fun call(method: String, url: String, headers: Map<String, String>, body: String?): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
                    .orEmpty()
                if (status !in 200..299) throw IOException("Proton API $status ${text.take(180)}")
                return text
            } finally {
                connection.disconnect()
            }
        }
    }

    /** JS run inside the official Proton page: never fills forms, never solves the human check. */
    const val SCAN_JS =
        "(function(){try{var t=[document.body?document.body.innerText:'',document.title||''];" +
            "var as=document.querySelectorAll('a[href]');for(var i=0;i<as.length;i++)t.push(as[i].href||'');" +
            "return t.join('\\n').slice(0,20000);}catch(e){return '';}})()"
}

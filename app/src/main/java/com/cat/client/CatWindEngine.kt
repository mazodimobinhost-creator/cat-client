package com.cat.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

/**
 * beta263 — 🌬️ Cat Wind: the service's own **free** plan (10 GB/month, a handful of cities),
 * used the same way Cat Fox uses a free Firefox account: the user creates / signs in with their
 * own account (Cat never fills the human check), Cat takes the public city list, times each host
 * on THIS network, then builds ordinary `https://user:pass@host:443` rows (TCP/TLS CONNECT — the
 * same shape as Cat Free Proxy) so the existing core can connect. WireGuard UDP is not faked.
 *
 * Protocol from the public HTTP API shape (`Session`, `ServerCredentials`,
 * `assets…/serverlist/mob-v2`) documented by independent clients; independent Kotlin, no copy of
 * that service's app (GPL). Premium cities are not unlocked. Ports are never rewritten.
 */
internal object CatWindPlanner {
    const val SIGNUP = "https://windscribe.com/signup"
    const val LOGIN_PAGE = "https://windscribe.com/login"
    const val SESSION = "https://api.windscribe.com/Session"
    const val CREDENTIALS = "https://api.windscribe.com/ServerCredentials"
    const val LIST_FREE = "https://assets.windscribe.com/serverlist/mob-v2/0/"
    const val LIST_ALL = "https://assets.windscribe.com/serverlist/mob-v2/1/"
    const val UA = "CatClient"
    const val PORT = 443
    const val NAME = "🌬️ Cat Wind"

    data class Location(
        val country: String,
        val region: String,
        val city: String,
        val hostname: String,
        val ip: String,
        val premiumOnly: Boolean,
    ) {
        val pingHost: String get() = ip.ifBlank { hostname }
        fun names() = FoxCountryNames.of(country, region, city)
        fun label(fa: Boolean): String {
            val n = names()
            val where = city.ifBlank { if (fa) n.fa else n.en }
            return "${n.flag} $where"
        }
    }

    data class Session(
        val auth: String,
        val username: String,
        val premium: Boolean,
        val proxyUser: String,
        val proxyPass: String,
        val trafficUsed: Long,
        val trafficMax: Long,
        val status: Int,
    ) {
        val usable: Boolean get() = auth.isNotBlank() && status == 1
    }

    data class Ping(val location: Location, val rttMs: Long?) {
        val reachable: Boolean get() = rttMs != null
    }

    /**
     * Cities the free plan has publicly offered (the live `mob-v2/0` list is the authority).
     * Used only when that list is blocked from this network.
     */
    val FALLBACK = listOf(
        Location("NL", "Netherlands", "Amsterdam", "nl.windscribe.com", "", false),
        Location("DE", "Germany", "Frankfurt", "de.windscribe.com", "", false),
        Location("FR", "France", "Paris", "fr.windscribe.com", "", false),
        Location("GB", "United Kingdom", "London", "uk.windscribe.com", "", false),
        Location("US", "US Central", "Dallas", "us-central.windscribe.com", "", false),
        Location("US", "US East", "New Jersey", "us-east.windscribe.com", "", false),
        Location("US", "US West", "Los Angeles", "us-west.windscribe.com", "", false),
        Location("CA", "Canada", "Toronto", "ca.windscribe.com", "", false),
        Location("CH", "Switzerland", "Zurich", "ch.windscribe.com", "", false),
        Location("NO", "Norway", "Oslo", "no.windscribe.com", "", false),
        Location("RO", "Romania", "Bucharest", "ro.windscribe.com", "", false),
        Location("HK", "Hong Kong", "Hong Kong", "hk.windscribe.com", "", false),
    )

    fun form(fields: Map<String, String>): String = fields.entries.joinToString("&") { (k, v) ->
        URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
    }

    fun headers(auth: String? = null): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("User-Agent", UA)
        if (!auth.isNullOrBlank()) put("Authorization", auth)
    }

    fun sessionError(json: String): String {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return json.take(180)
        return root.optString("errorMessage").ifBlank { root.optString("message") }.ifBlank {
            val data = root.optJSONObject("data")
            data?.optString("errorMessage").orEmpty()
        }.ifBlank { json.take(180) }
    }

    fun session(json: String): Session? = runCatching {
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: return@runCatching null
        val auth = data.optString("session_auth_hash").ifBlank { data.optString("auth_hash") }.trim()
        if (auth.isBlank()) return@runCatching null
        Session(
            auth = auth,
            username = data.optString("username"),
            premium = data.optInt("is_premium", 0) == 1 || data.optBoolean("is_premium"),
            proxyUser = data.optString("proxy_username"),
            proxyPass = data.optString("proxy_password"),
            trafficUsed = data.optLong("traffic_used", 0L),
            trafficMax = data.optLong("traffic_max", 0L),
            status = data.optInt("status", 1),
        )
    }.getOrNull()

    /** OpenVPN / proxy credentials: the API wraps username+password in standard Base64. */
    fun serverCredentials(json: String): Pair<String, String>? = runCatching {
        val data = JSONObject(json).optJSONObject("data") ?: JSONObject(json)
        val user = b64plain(data.optString("username"))
        val pass = b64plain(data.optString("password"))
        if (user.isBlank() || pass.isBlank()) null else user to pass
    }.getOrNull()

    fun b64plain(raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return ""
        val decoded = runCatching { String(Base64.getDecoder().decode(s), Charsets.UTF_8) }.getOrNull() ?: return s
        return if (decoded.isNotBlank() && decoded.none { it.code < 32 }) decoded else s
    }

    fun countryOf(code: String, region: String): String {
        val c = code.trim().uppercase(Locale.US)
        if (c.length == 2 && c.all { it in 'A'..'Z' }) return c
        val n = region.lowercase(Locale.US)
        return when {
            "nether" in n || n == "nl" -> "NL"
            "german" in n || n == "de" -> "DE"
            "france" in n || n == "fr" -> "FR"
            "united kingdom" in n || n == "uk" || n == "gb" || "britain" in n -> "GB"
            "canada" in n || n == "ca" -> "CA"
            "switzerland" in n || n == "ch" -> "CH"
            "norway" in n || n == "no" -> "NO"
            "romania" in n || n == "ro" -> "RO"
            "hong kong" in n || n == "hk" -> "HK"
            "japan" in n || n == "jp" -> "JP"
            "australia" in n || n == "au" -> "AU"
            "sweden" in n || n == "se" -> "SE"
            "spain" in n || n == "es" -> "ES"
            "italy" in n || n == "it" -> "IT"
            "poland" in n || n == "pl" -> "PL"
            "turkey" in n || "türk" in n || n == "tr" -> "TR"
            "india" in n || n == "in" -> "IN"
            "singapore" in n || n == "sg" -> "SG"
            "us east" in n || "us west" in n || "us central" in n || "united states" in n || n.startsWith("us ") -> "US"
            n == "us" -> "US"
            else -> ""
        }
    }

    fun premiumOnly(o: JSONObject): Boolean =
        o.optInt("premium_only", 0) == 1 || o.optBoolean("premium_only") || o.optInt("pro", 0) == 1

    /** `GET …/serverlist/mob-v2/{0|1}/{rev}` body → one city per group (first node). */
    fun locations(json: String): List<Location> = runCatching {
        val root = JSONObject(json)
        val arr = root.optJSONArray("data")
            ?: root.optJSONObject("data")?.optJSONArray("locations")
            ?: JSONArray()
        val out = ArrayList<Location>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val region = arr.optJSONObject(i) ?: continue
            val regionName = region.optString("name").ifBlank { region.optString("country") }
            val cc = countryOf(region.optString("country_code").ifBlank { region.optString("country") }, regionName)
            val pro = premiumOnly(region)
            val groups = region.optJSONArray("groups") ?: JSONArray()
            for (g in 0 until groups.length()) {
                val group = groups.optJSONObject(g) ?: continue
                val city = group.optString("city").ifBlank { regionName }
                val nodes = group.optJSONArray("nodes") ?: JSONArray()
                val node = nodes.optJSONObject(0) ?: continue
                val host = node.optString("hostname").trim().lowercase(Locale.US)
                if (host.isBlank() || '.' !in host) continue
                val ip = node.optString("ip").ifBlank { group.optString("ping_ip") }.trim()
                val key = cc + "\u0000" + city.lowercase(Locale.US)
                if (!seen.add(key)) continue
                out += Location(cc.ifBlank { "XX" }, regionName, city, host, ip, pro || premiumOnly(group))
            }
        }
        out
    }.getOrDefault(emptyList())

    fun freeLocations(all: List<Location>, premium: Boolean): List<Location> =
        if (premium) all else all.filter { !it.premiumOnly }

    fun pick(all: List<Location>, codes: Set<String>, premium: Boolean): List<Location> {
        val pool = freeLocations(all, premium)
        if (codes.isEmpty()) return pool
        val want = codes.map { it.uppercase(Locale.US) }.toSet()
        if ("FASTEST" in want) return pool
        return pool.filter { it.country in want }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * One `https://` CONNECT-over-TLS row per city. Port is always the listed 443 — never rewritten.
     * TLS is verified against the hostname (`sni=host`); a bare IP would get no SNI.
     */
    fun httpsLink(user: String, pass: String, loc: Location, fa: Boolean): String {
        val n = loc.names()
        val title = "$NAME · ${n.flag} " + (if (fa) n.fa else n.en)
        return "https://${enc(user)}:${enc(pass)}@${loc.hostname}:$PORT?sni=${enc(loc.hostname)}#${enc(title)}"
    }

    fun subscriptionText(links: List<String>): String =
        "# $NAME\n" + links.distinct().joinToString("\n")

    fun listUrls(premium: Boolean): List<String> {
        val t = (System.currentTimeMillis() / 1000L).toString()
        val bases = if (premium) listOf(LIST_ALL, LIST_FREE) else listOf(LIST_FREE, LIST_ALL)
        return bases.flatMap { listOf(it + "0", it + t) }
    }
}

/** Blocking HTTP; call off the main thread. Uses [FreeNetHttp] (physical → direct → tunnel). */
internal class CatWindClient(private val store: FreeNetworkStore) {
    val log = mutableListOf<String>()

    fun cachedSession(): CatWindPlanner.Session? {
        val auth = store.windAuth
        if (auth.isBlank()) return null
        return CatWindPlanner.Session(
            auth = auth,
            username = store.windUser,
            premium = store.windPremium,
            proxyUser = store.windProxyUser,
            proxyPass = store.windProxyPass,
            trafficUsed = 0L,
            trafficMax = 0L,
            status = 1,
        )
    }

    fun remember(s: CatWindPlanner.Session) {
        store.windAuth = s.auth
        store.windUser = s.username
        store.windPremium = s.premium
        store.windProxyUser = s.proxyUser
        store.windProxyPass = s.proxyPass
    }

    fun forget() {
        store.windAuth = ""
        store.windProxyUser = ""
        store.windProxyPass = ""
    }

    fun login(username: String, password: String, otp: String = ""): CatWindPlanner.Session {
        val fields = linkedMapOf(
            "username" to username.trim(),
            "password" to password,
            "session_type_id" to "2",
            "platform" to "android",
        )
        if (otp.isNotBlank()) fields["2fa_code"] = otp.trim()
        val a = FreeNetHttp.request(
            CatWindPlanner.SESSION,
            "POST",
            CatWindPlanner.headers() + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            CatWindPlanner.form(fields),
            timeoutMs = 15_000,
        )
        val session = CatWindPlanner.session(a.body)
            ?: throw IOException(CatWindPlanner.sessionError(a.body).ifBlank { "HTTP ${a.code}" })
        if (!session.usable) throw IOException(
            CatWindPlanner.sessionError(a.body).ifBlank { "account status ${session.status}" },
        )
        remember(session)
        if (store.windUser.isBlank()) store.windUser = username.trim()
        log += "session ✓ ${session.username.ifBlank { username.trim() }}" + a.routeLabel()
        return session
    }

    fun locations(premium: Boolean): List<CatWindPlanner.Location> {
        var last = "no list"
        for (url in CatWindPlanner.listUrls(premium)) {
            val a = runCatching { FreeNetHttp.request(url, timeoutMs = 12_000) }.getOrElse {
                last = it.javaClass.simpleName; continue
            }
            if (a.code !in 200..299) { last = "HTTP ${a.code}"; continue }
            val parsed = CatWindPlanner.locations(a.body)
            if (parsed.isNotEmpty()) {
                log += "list ✓ ${parsed.size}" + a.routeLabel()
                return CatWindPlanner.freeLocations(parsed, premium)
            }
            last = "empty list"
        }
        log += "list fallback ($last)"
        return CatWindPlanner.FALLBACK
    }

    fun credentials(session: CatWindPlanner.Session): Pair<String, String> {
        if (session.proxyUser.isNotBlank() && session.proxyPass.isNotBlank()) {
            return session.proxyUser to session.proxyPass
        }
        val url = CatWindPlanner.CREDENTIALS +
            "?session_auth_hash=" + URLEncoder.encode(session.auth, "UTF-8") +
            "&session_type_id=2"
        val a = FreeNetHttp.request(url, headers = CatWindPlanner.headers(session.auth), timeoutMs = 12_000)
        val pair = CatWindPlanner.serverCredentials(a.body)
            ?: throw IOException(CatWindPlanner.sessionError(a.body).ifBlank { "HTTP ${a.code}" })
        store.windProxyUser = pair.first
        store.windProxyPass = pair.second
        log += "creds ✓" + a.routeLabel()
        return pair
    }

    fun ping(loc: CatWindPlanner.Location, timeoutMs: Int = 850): CatWindPlanner.Ping =
        CatWindPlanner.Ping(loc, LiveNetPing.tcpMs(loc.pingHost, CatWindPlanner.PORT, timeoutMs))
}

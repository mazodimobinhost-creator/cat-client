package com.cat.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

/**
 * beta263/265 — 🌬️ Cat Wind: the service's own **free** plan (10 GB/month, a handful of cities),
 * used the same way Cat Fox uses a free Firefox account: the user creates / signs in with their
 * own account (Cat never fills the human check), Cat takes the public city list, times each host
 * on THIS network, then the user picks a protocol:
 *  • Stealth = ordinary `https://user:pass@host:443` CONNECT/TLS (TCP).
 *  • WireGuard = `wireguard://` on the listed UDP **:443** (never rewritten) via public
 *    `WgConfigs/init` + `WgConfigs/connect`. Private key stays on device.
 * Custom SNI (e.g. python.org) is **not** emitted: that needs skip-cert-verify, which Cat never
 * sets. Registry wipe / extra-account farming is not implemented.
 *
 * Protocol from the public HTTP API shape (`Session`, `ServerCredentials`, `WgConfigs`,
 * `assets…/serverlist/mob-v2`) documented by independent clients; independent Kotlin, no copy of
 * that service's app (GPL). Premium cities are not unlocked. Ports are never rewritten.
 */
internal object CatWindPlanner {
    const val SIGNUP = "https://windscribe.com/signup"
    const val LOGIN_PAGE = "https://windscribe.com/login"
    const val SESSION = "https://api.windscribe.com/Session"
    const val CREDENTIALS = "https://api.windscribe.com/ServerCredentials"
    const val WG_INIT = "https://api.windscribe.com/WgConfigs/init"
    const val WG_CONNECT = "https://api.windscribe.com/WgConfigs/connect"
    const val LIST_FREE = "https://assets.windscribe.com/serverlist/mob-v2/0/"
    const val LIST_ALL = "https://assets.windscribe.com/serverlist/mob-v2/1/"
    const val UA = "CatClient"
    const val PORT = 443
    const val WG_PORT = 443
    const val NAME = "🌬️ Cat Wind"
    const val WG_MAX = 4

    enum class Proto { Stealth, WireGuard }

    data class Location(
        val country: String,
        val region: String,
        val city: String,
        val hostname: String,
        val ip: String,
        val premiumOnly: Boolean,
        val ip3: String = "",
        val wgPubKey: String = "",
    ) {
        val pingHost: String get() = ip.ifBlank { hostname }
        fun pingHostFor(proto: Proto): String =
            if (proto == Proto.WireGuard) ip3.ifBlank { pingHost } else pingHost
        fun names() = FoxCountryNames.of(country, region, city)
        fun label(fa: Boolean): String {
            val n = names()
            val where = city.ifBlank { if (fa) n.fa else n.en }
            return "${n.flag} $where"
        }
        fun canWireGuard(): Boolean = wgPubKey.isNotBlank() && WireGuardConfText.isKey32(wgPubKey)
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
        // "text" is technically valid Base64; only unwrap when the payload is printable ASCII.
        if (s.length % 4 != 0 || !s.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))) return s
        val decoded = runCatching { String(Base64.getDecoder().decode(s), Charsets.UTF_8) }.getOrNull() ?: return s
        return if (decoded.isNotBlank() && decoded.all { it.code in 32..126 }) decoded else s
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
                val ip3 = node.optString("ip3").ifBlank { group.optString("ip3") }.trim()
                val wg = node.optString("wg_pubkey").ifBlank { group.optString("wg_pubkey") }.trim()
                val key = cc + "\u0000" + city.lowercase(Locale.US)
                if (!seen.add(key)) continue
                out += Location(
                    cc.ifBlank { "XX" }, regionName, city, host, ip,
                    pro || premiumOnly(group), ip3, wg,
                )
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
     * Custom SNI is not used (would need skip-cert-verify).
     */
    fun httpsLink(user: String, pass: String, loc: Location, fa: Boolean): String {
        val n = loc.names()
        val title = "$NAME · ${n.flag} " + (if (fa) n.fa else n.en)
        return "https://${enc(user)}:${enc(pass)}@${loc.hostname}:$PORT?sni=${enc(loc.hostname)}#${enc(title)}"
    }

    fun keyPair(privStored: String, pubStored: String): Pair<String, String> {
        if (WireGuardConfText.isKey32(privStored) && WireGuardConfText.isKey32(pubStored)) {
            return privStored.trim() to pubStored.trim()
        }
        val priv = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        WarpRegistration.clamp(priv)
        val pub = WarpRegistration.publicKeyOf(priv)
        val b64 = Base64.getEncoder()
        return b64.encodeToString(priv) to b64.encodeToString(pub)
    }

    /** `data.config.PresharedKey` + `AllowedIPs` from `POST /WgConfigs/init`. */
    fun wgInit(json: String): Pair<String, String>? = runCatching {
        val root = JSONObject(json)
        if (root.has("errorCode") && root.optInt("errorCode") != 0) return@runCatching null
        val data = root.optJSONObject("data") ?: return@runCatching null
        val cfg = data.optJSONObject("config") ?: data
        val psk = cfg.optString("PresharedKey").ifBlank { cfg.optString("presharedKey") }
        val allowed = cfg.optString("AllowedIPs").ifBlank { "0.0.0.0/0, ::/0" }
        if (psk.isBlank() && !cfg.has("PresharedKey") && !cfg.has("AllowedIPs")) return@runCatching null
        psk to allowed
    }.getOrNull()

    /** Assigned tunnel address + DNS from `POST /WgConfigs/connect`. */
    fun wgAddress(json: String): Pair<String, String>? = runCatching {
        val root = JSONObject(json)
        if (root.has("errorCode") && root.optInt("errorCode") != 0) return@runCatching null
        val data = root.optJSONObject("data") ?: return@runCatching null
        val obj = data.optJSONObject("config")
        val raw = if (obj == null) data.optString("config") else ""
        fun line(key: String): String = raw.lineSequence()
            .firstOrNull { it.trim().startsWith(key, ignoreCase = true) && '=' in it }
            ?.substringAfter('=')?.trim().orEmpty()
        val address = obj?.optString("Address").orEmpty()
            .ifBlank { obj?.optString("address").orEmpty() }
            .ifBlank { data.optString("Address") }
            .ifBlank { data.optString("address") }
            .ifBlank { line("Address") }
            .ifBlank { if ('/' in raw && '[' !in raw) raw.trim() else "" }
        val dns = obj?.optString("DNS").orEmpty()
            .ifBlank { obj?.optString("dns").orEmpty() }
            .ifBlank { data.optString("DNS") }
            .ifBlank { data.optString("dns") }
            .ifBlank { line("DNS") }
            .ifBlank { "10.255.255.3" }
        if (address.isBlank()) null else address to dns
    }.getOrNull()

    fun endpointLiteral(host: String): String =
        if (':' in host && !host.startsWith("[")) "[$host]" else host

    fun wireguardConf(
        privateKey: String,
        loc: Location,
        address: String,
        dns: String,
        psk: String,
        allowedIps: String,
        fa: Boolean,
        peerPub: String = loc.wgPubKey,
    ): String {
        val host = loc.ip3.ifBlank { loc.ip }.ifBlank { loc.hostname }
        val n = loc.names()
        val title = "$NAME · WG · ${n.flag} " + (if (fa) n.fa else n.en)
        val allowed = allowedIps.ifBlank { "0.0.0.0/0, ::/0" }
        return buildString {
            append("# Name = $title\n")
            append("[Interface]\n")
            append("PrivateKey = ${privateKey.trim()}\n")
            append("Address = ${address.trim()}\n")
            if (dns.isNotBlank()) append("DNS = ${dns.trim()}\n")
            append("\n[Peer]\n")
            append("PublicKey = ${peerPub.trim()}\n")
            if (psk.isNotBlank()) append("PresharedKey = ${psk.trim()}\n")
            append("AllowedIPs = $allowed\n")
            append("Endpoint = ${endpointLiteral(host)}:$WG_PORT\n")
            append("PersistentKeepalive = 25\n")
        }
    }

    fun wireguardLink(
        privateKey: String,
        loc: Location,
        address: String,
        dns: String,
        psk: String,
        allowedIps: String,
        fa: Boolean,
    ): String? {
        if (!loc.canWireGuard() || address.isBlank()) return null
        return WireGuardConfText.toUris(wireguardConf(privateKey, loc, address, dns, psk, allowedIps, fa)).firstOrNull()
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
        store.windWgPriv = ""
        store.windWgPub = ""
        store.windWgPsk = ""
        store.windWgAllowed = ""
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

    fun ping(
        loc: CatWindPlanner.Location,
        timeoutMs: Int = 850,
        proto: CatWindPlanner.Proto = CatWindPlanner.Proto.Stealth,
    ): CatWindPlanner.Ping =
        CatWindPlanner.Ping(loc, LiveNetPing.tcpMs(loc.pingHostFor(proto), CatWindPlanner.PORT, timeoutMs))

    fun wgKeys(): Pair<String, String> {
        val pair = CatWindPlanner.keyPair(store.windWgPriv, store.windWgPub)
        if (store.windWgPriv != pair.first) {
            store.windWgPriv = pair.first
            store.windWgPub = pair.second
            store.windWgPsk = ""
            store.windWgAllowed = ""
        }
        return pair
    }

    fun wgInit(session: CatWindPlanner.Session): Pair<String, String> {
        if (store.windWgPsk.isNotBlank()) return store.windWgPsk to store.windWgAllowed
        val pub = wgKeys().second
        val headers = CatWindPlanner.headers(session.auth) + mapOf("Content-Type" to "application/x-www-form-urlencoded")
        fun post(force: Int): Pair<String, String>? {
            val a = FreeNetHttp.request(
                CatWindPlanner.WG_INIT,
                "POST",
                headers,
                CatWindPlanner.form(mapOf("wg_pubkey" to pub, "force_init" to force.toString())),
                timeoutMs = 15_000,
            )
            return CatWindPlanner.wgInit(a.body).also { parsed ->
                if (parsed == null) log += "wg init HTTP ${a.code}: ${CatWindPlanner.sessionError(a.body).take(80)}"
            }
        }
        val first = post(0) ?: post(1) ?: throw IOException("WireGuard init failed")
        store.windWgPsk = first.first
        store.windWgAllowed = first.second
        log += "wg init ✓"
        return first
    }

    fun wgLink(session: CatWindPlanner.Session, loc: CatWindPlanner.Location, fa: Boolean): String {
        if (!loc.canWireGuard()) throw IOException("no WireGuard key for ${loc.hostname}")
        val (priv, pub) = wgKeys()
        val (psk, allowed) = wgInit(session)
        val a = FreeNetHttp.request(
            CatWindPlanner.WG_CONNECT,
            "POST",
            CatWindPlanner.headers(session.auth) + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            CatWindPlanner.form(mapOf("wg_pubkey" to pub, "hostname" to loc.hostname, "wg_ttl" to "0")),
            timeoutMs = 15_000,
        )
        val assigned = CatWindPlanner.wgAddress(a.body)
            ?: throw IOException(CatWindPlanner.sessionError(a.body).ifBlank { "HTTP ${a.code}" })
        val link = CatWindPlanner.wireguardLink(priv, loc, assigned.first, assigned.second, psk, allowed, fa)
            ?: throw IOException("WireGuard row was not a valid conf")
        log += "wg ✓ ${loc.hostname}:${CatWindPlanner.WG_PORT}"
        return link
    }
}

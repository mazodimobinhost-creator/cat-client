package com.cat.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * beta247 — three free networks the user asked for, all ending as ordinary Cat configs (no new core):
 *
 *  • 🆓 Cat Free Proxy — the free network of a browser-extension VPN (7 locations, no account). Its servers are
 *    HTTPS (CONNECT-over-TLS) proxies with basic auth, i.e. mihomo's `http` type with `tls: true` — so the app only
 *    talks to the extension API (launch → token → server list) and writes `https://user:pass@host:443?sni=host` links.
 *    Protocol learned from the MIT-licensed VPeeN project (independent Kotlin code). TCP + DNS only (no UDP).
 *  • 🏮 Cat Lantern (experimental) — Lantern's own config service (anonymous user-create → config-new), asking only for
 *    standard protocols; the sing-box outbounds it returns are translated to share links. Anything Cat's core cannot
 *    run (Lantern-only transports) is counted and reported, never faked.
 *  • 🎫 50 GB free account — the site's human check is solved by the user in an in-app page; Cat only picks up the
 *    subscription link the page shows / the “open in app” link it builds ([FreeAccountLinks]).
 *
 * Network: every call is tried directly first, then through Cat's running core (local mixed port), because these API
 * hosts are often filtered in Iran but reachable once any config is connected.
 */
internal object FreeNetHttp {
    class Answer(val code: Int, val body: String, val viaTunnel: Boolean)

    fun tunnelPortOpen(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", MihomoRuntimeDefaults.MIXED_PORT), 300) }
        true
    }.getOrDefault(false)

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 12_000,
        allowTunnel: Boolean = true,
    ): Answer {
        var last: IOException? = null
        val routes = if (allowTunnel && tunnelPortOpen()) listOf(false, true) else listOf(false)
        for (viaTunnel in routes) {
            try {
                return once(url, method, headers, body, timeoutMs, viaTunnel)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("no route")
    }

    private fun once(url: String, method: String, headers: Map<String, String>, body: String?, timeoutMs: Int, viaTunnel: Boolean): Answer {
        val u = URL(url)
        val conn = (if (viaTunnel) u.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MihomoRuntimeDefaults.MIXED_PORT))) else u.openConnection()) as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs + 8_000
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                if (!headers.keys.any { it.equals("Content-Type", true) }) conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Answer(code, text, viaTunnel)
        } finally {
            conn.disconnect()
        }
    }
}

// ───────────────────────────── 🆓 Cat Free Proxy ─────────────────────────────

internal data class FreeProxyLocation(val region: String, val flag: String, val nameFa: String, val nameEn: String)

internal data class FreeProxyCred(val region: String, val host: String, val username: String, val password: String)

internal data class FreeProxyServer(val host: String, val port: Int, val username: String, val password: String)

internal object FreeProxyPlanner {
    /** The 7 free locations (region ids the API expects). */
    val LOCATIONS = listOf(
        FreeProxyLocation("nl", "🇳🇱", "هلند", "Netherlands"),
        FreeProxyLocation("fr-prs", "🇫🇷", "فرانسه", "France"),
        FreeProxyLocation("gb-lnd", "🇬🇧", "انگلیس", "United Kingdom"),
        FreeProxyLocation("us-va", "🇺🇸", "آمریکا (ویرجینیا)", "USA (Virginia)"),
        FreeProxyLocation("us-or", "🇺🇸", "آمریکا (اورگان)", "USA (Oregon)"),
        FreeProxyLocation("sg", "🇸🇬", "سنگاپور", "Singapore"),
        FreeProxyLocation("ru-spb", "🇷🇺", "روسیه", "Russia"),
    )

    const val APP_VERSION = "5.0.2"
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    val DEFAULT_DOMAINS = listOf(
        "https://antpeak.com", "https://hibchr.com", "https://hisball.com", "https://bitphox.com",
        "https://freloop.com", "https://tronlit.com", "https://tronyza.com",
    )
    val RESERVE_BUCKETS = listOf("https://s3-oregon-1.s3-us-west-2.amazonaws.com/api.json", "https://proigor.com/payload.json")
    private val DOMAIN_RE = Regex("^https://([A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$")

    fun validDomain(url: String) = DOMAIN_RE.matches(url.trim().trimEnd('/'))

    /** Reserve-domain bucket JSON → extra API domains (strictly https + hostname; the token is sent to them). */
    fun reserveDomains(json: String): List<String> = runCatching {
        val o = JSONObject(json)
        val out = mutableListOf<String>()
        o.optJSONObject("domains")?.optJSONArray("free")?.let { a -> for (i in 0 until a.length()) out += a.optString(i) }
        when (val extra = o.opt("free")) {
            is String -> out += extra
            is JSONArray -> for (i in 0 until extra.length()) out += extra.optString(i)
        }
        out.map { it.trim().trimEnd('/') }.filter(::validDomain).distinct().take(10)
    }.getOrDefault(emptyList())

    fun launchBody(udid: String, timeZone: String): String = JSONObject()
        .put("udid", udid).put("appVersion", APP_VERSION).put("platform", "chrome")
        .put("platformVersion", USER_AGENT).put("timeZone", timeZone).put("deviceName", "Chrome 130").toString()

    fun serverListBody(region: String): String = JSONObject().put("protocol", "https").put("region", region).put("type", 0).toString()

    /** `[{addresses:[…], port, username, password}]` → one server per address (each its own TLS name). */
    fun servers(json: String): List<FreeProxyServer> = runCatching {
        val arr = JSONArray(json)
        val out = mutableListOf<FreeProxyServer>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val port = s.optInt("port", 0)
            val user = s.optString("username")
            val pass = s.optString("password")
            val addrs = s.optJSONArray("addresses") ?: continue
            if (port !in 1..65535) continue
            for (j in 0 until addrs.length()) {
                val host = addrs.optString(j).trim()
                if (host.isNotBlank() && !host.contains('/') && !host.contains(' ')) out += FreeProxyServer(host, port, user, pass)
            }
        }
        out.distinctBy { it.host + ":" + it.port }
    }.getOrDefault(emptyList())

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    /**
     * Inline subscription text. The leading comment line matters: a subscription whose text STARTS with https:// is
     * treated as a remote URL to download (UserSubscriptionSourceKind), so the proxy lines must not come first.
     */
    fun subscriptionText(links: List<String>): String = "# 🆓 Cat Free Proxy\n" + links.joinToString("\n")

    /** One credential line: region, host, user, pass (tab separated). */
    fun credLines(region: String, servers: List<FreeProxyServer>): List<String> =
        servers.map { listOf(region, it.host, it.username, it.password).joinToString("\t") }

    fun parseCreds(text: String): List<FreeProxyCred> = text.lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size == 4 && f[2].isNotBlank()) FreeProxyCred(f[0], f[1], f[2], f[3]) else null
    }

    /** True when any credential written in the subscription is present in this runtime YAML. */
    fun mentions(yaml: String, written: List<FreeProxyCred>): Boolean = written.any { c ->
        Regex("""\busername\s*:\s*['"]?""" + Regex.escape(c.username) + """['"]?\s*(?:[,}\r\n]|$)""").containsMatchIn(yaml)
    }

    /**
     * Swaps each written credential for a fresh one: same host first, then same region, then any. Only the
     * `username:` / `password:` values change. Returns the YAML unchanged when [fresh] is empty.
     */
    fun swapAll(yaml: String, written: List<FreeProxyCred>, fresh: List<FreeProxyCred>): String {
        if (fresh.isEmpty()) return yaml
        var out = yaml
        val done = mutableSetOf<String>()
        for (w in written) {
            if (!done.add(w.username + "\u0000" + w.password)) continue
            val f = fresh.firstOrNull { it.host == w.host } ?: fresh.firstOrNull { it.region == w.region } ?: fresh.first()
            out = swapCredential(out, "username", w.username, f.username)
            out = swapCredential(out, "password", w.password, f.password)
        }
        return out
    }

    /** Replaces ONLY the value of `key: old` (block or flow YAML, quoted or not) — never other text. */
    fun swapCredential(yaml: String, key: String, old: String, new: String): String {
        if (old.isBlank() || old == new) return yaml
        val re = Regex("""(\b$key\s*:\s*['"]?)""" + Regex.escape(old) + """(?=['"]?\s*(?:[,}\r\n]|$))""")
        return re.replace(yaml) { m -> m.groupValues[1] + new } // the lambda form inserts text literally
    }

    private fun isIp(host: String) = host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || host.contains(':')

    /**
     * One `https://` link per server. TLS is verified against the server's own name (`sni=host`); a bare IP gets no
     * SNI and the core verifies the IP certificate as-is (never skip-cert-verify).
     */
    fun links(location: FreeProxyLocation, servers: List<FreeProxyServer>, fa: Boolean, max: Int = 4): List<String> =
        servers.take(max).mapIndexed { i, s ->
            val name = "🆓 Cat Free Proxy · ${location.flag} " + (if (fa) location.nameFa else location.nameEn) + " #${i + 1}"
            val host = if (s.host.contains(':') && !s.host.startsWith("[")) "[${s.host}]" else s.host
            val sni = if (isIp(s.host)) "" else "?sni=" + enc(s.host)
            "https://${enc(s.username)}:${enc(s.password)}@$host:${s.port}$sni#${enc(name)}"
        }
}

/** Blocking client for the free extension network; call off the main thread. */
internal class FreeProxyApi(private val store: FreeNetworkStore) {
    private var domains: List<String>? = null
    val log = mutableListOf<String>()

    private fun allDomains(): List<String> {
        domains?.let { return it }
        val extra = FreeProxyPlanner.RESERVE_BUCKETS.flatMap { bucket ->
            runCatching { FreeNetHttp.request(bucket, timeoutMs = 8_000).takeIf { it.code == 200 }?.body }.getOrNull()
                ?.let(FreeProxyPlanner::reserveDomains).orEmpty()
        }
        val all = (FreeProxyPlanner.DEFAULT_DOMAINS + extra).distinct()
        domains = all
        return all
    }

    private fun headers(token: String?) = buildMap {
        put("Accept", "application/json")
        put("User-Agent", FreeProxyPlanner.USER_AGENT)
        if (token != null) put("Authorization", "Bearer $token")
    }

    private fun token(force: Boolean): String {
        val cached = store.freeProxyToken()
        if (!force && cached != null) return cached
        val body = FreeProxyPlanner.launchBody(store.freeProxyUdid(), TimeZone.getDefault().id)
        var lastError = "no domain answered"
        for (d in allDomains()) {
            val a = runCatching { FreeNetHttp.request("$d/v3/launch/", "POST", headers(null), body) }.getOrElse { lastError = "$d: ${it.javaClass.simpleName}"; null } ?: continue
            if (a.code == 200) {
                val access = runCatching { JSONObject(a.body).optString("access") }.getOrNull().orEmpty()
                if (access.isNotBlank()) {
                    store.saveFreeProxyToken(access)
                    log += "token ✓ ${d.removePrefix("https://")}" + if (a.viaTunnel) " (tunnel)" else ""
                    return access
                }
            }
            lastError = "$d: HTTP ${a.code}"
        }
        throw IOException(lastError)
    }

    /** Fresh servers for [region]; a rejected token is renewed once. */
    fun servers(region: String): List<FreeProxyServer> {
        var force = false
        var lastError = "no domain answered"
        repeat(2) {
            val tok = token(force)
            for (d in allDomains()) {
                val a = runCatching { FreeNetHttp.request("$d/v3/server/list/", "POST", headers(tok), FreeProxyPlanner.serverListBody(region)) }
                    .getOrElse { lastError = "$d: ${it.javaClass.simpleName}"; null } ?: continue
                if (a.code == 401 || a.code == 403) { force = true; lastError = "token rejected"; return@repeat }
                if (a.code == 200) {
                    val list = FreeProxyPlanner.servers(a.body)
                    if (list.isNotEmpty()) {
                        log += "$region: ${list.size} server(s)"
                        return list
                    }
                    lastError = "$region: empty list"
                    break
                }
                lastError = "$d: HTTP ${a.code}"
            }
            if (!force) throw IOException(lastError)
        }
        throw IOException(lastError)
    }
}

// ───────────────────────────── 🏮 Cat Lantern (experimental) ─────────────────────────────

internal object LanternPlanner {
    const val USER_CREATE = "https://api.getiantem.org/user-create"
    const val CONFIG_NEW = "https://df.iantem.io/api/v1/config-new"

    /**
     * beta248: Google's edge answers Iranian clients on df.iantem.io with a 403 HTML page, while the same origin
     * behind Cloudflare (df.getiantem.org) gets through — the official client maps df.iantem.io → df.getiantem.org
     * for exactly this reason. Cloudflare-fronted host first, the original as a fallback.
     */
    val CONFIG_URLS = listOf("https://df.getiantem.org/api/v1/config-new", CONFIG_NEW)

    /** True for a CDN/WAF block page (HTML instead of the service's own answer) → try the next host. */
    fun isEdgeBlock(code: Int, body: String): Boolean =
        code !in 200..299 && code != 304 && code != 204 && body.trimStart().let { it.startsWith("<") || it.isEmpty() }
    const val VERSION = "10.0.5"
    val PROTOCOLS = listOf("shadowsocks", "vless", "trojan", "hysteria2", "vmess", "tuic")

    fun headers(deviceId: String, userId: String, token: String): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "application/json")
        put("X-Lantern-App-Version", VERSION)
        put("X-Lantern-Version", VERSION)
        put("X-Lantern-Platform", "android")
        put("X-Lantern-App", "lantern")
        put("X-Lantern-Device-Id", deviceId)
        put("X-Lantern-Time-Zone", TimeZone.getDefault().id)
        put("X-Lantern-Rand", "x".repeat((deviceId.hashCode() and 0x7f) + 8))
        if (userId.isNotBlank()) put("X-Lantern-User-Id", userId)
        if (token.isNotBlank()) put("X-Lantern-Pro-Token", token)
    }

    /** user-create answer → (userId, token) or null. */
    fun user(json: String): Pair<String, String>? = runCatching {
        val o = JSONObject(json)
        val id = o.optLong("userId", 0L).takeIf { it != 0L }?.toString() ?: o.optString("userId")
        val tok = o.optString("token")
        if (id.isNullOrBlank() || id == "0") null else id to tok
    }.getOrNull()

    /** One location the service offers (`servers[]` / `outbound_locations{}` of the config answer). */
    data class Location(val country: String, val city: String, val code: String) {
        val flag: String get() = flagOf(code)
        fun label(): String = listOf(flag, country.ifBlank { code }, city).filter(String::isNotBlank).joinToString(" ").trim()
        fun toJson(): JSONObject = JSONObject().put("country", country).put("city", city).put("country_code", code)
    }

    fun flagOf(code: String): String {
        val cc = code.trim().uppercase(Locale.US)
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return ""
        return String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
    }

    private fun location(o: JSONObject?): Location? {
        if (o == null) return null
        val code = o.optString("country_code").trim().uppercase(Locale.US)
        val country = o.optString("country").trim()
        if (code.isBlank() && country.isBlank()) return null
        return Location(country, o.optString("city").trim(), code)
    }

    /** `preferred_location` = an entry copied from the service's own `servers` list (how the official client asks). */
    fun configBody(deviceId: String, userId: String, token: String, preferred: Location?): String = JSONObject()
        .put("device_id", deviceId).put("singbox_version", "v1.12.0").put("platform", "android").put("app_name", "lantern")
        .put("user_id", userId).put("pro_token", token).put("locale", "fa-IR").put("version", VERSION).put("backend", "sing-box")
        .put("protocols", JSONArray(PROTOCOLS)).apply {
            if (preferred != null) put("preferred_location", preferred.toJson())
        }.toString()

    data class Translation(
        val links: List<String>,
        val byType: Map<String, Int>,
        val skipped: Map<String, Int>,
        /** Location of each produced link, same order as [links] (null = the service did not say). */
        val linkLocations: List<Location?> = emptyList(),
        /** Every location the service offers (its `servers` list). */
        val offered: List<Location> = emptyList(),
        /** The country the service sees this phone in. */
        val clientCountry: String = "",
    ) {
        /** «🇩🇪 Germany ×2 · 🇺🇸 United States ×1» */
        fun countrySummary(unknown: String): String = linkLocations.groupingBy { it?.let { l -> l.flag + " " + l.country.ifBlank { l.code } }?.trim() ?: unknown }
            .eachCount().entries.joinToString(" · ") { "${it.key} ×${it.value}" }
    }

    /**
     * Keeps earlier countries: links of the countries that just came are replaced, links of other countries stay
     * (so asking for 🇩🇪 after 🇺🇸 gives both). Unknown-location answers replace everything. Max [cap] links.
     * Items are (link, country code or "").
     */
    fun merge(old: List<Pair<String, String>>, fresh: List<Pair<String, String>>, cap: Int = 40): List<Pair<String, String>> {
        if (fresh.isEmpty()) return old
        if (fresh.all { it.second.isBlank() }) return fresh.take(cap)
        val freshCodes = fresh.map { it.second }.toSet()
        val kept = old.filter { it.second.isNotBlank() && it.second !in freshCodes && fresh.none { f -> f.first == it.first } }
        return (fresh + kept).take(cap)
    }

    /** Did the service honour the requested country? (null = nothing was requested.) */
    fun honoured(preferred: Location?, got: List<Location?>): Boolean? {
        if (preferred == null) return null
        return got.any { it != null && it.code.equals(preferred.code, ignoreCase = true) }
    }

    /** config-new answer → share links for every outbound Cat's core can run; the rest is counted. */
    fun translate(configJson: String): Translation {
        val o = runCatching { JSONObject(configJson) }.getOrNull() ?: return Translation(emptyList(), emptyMap(), emptyMap())
        val outbounds = o.optJSONObject("options")?.optJSONArray("outbounds") ?: o.optJSONArray("outbounds") ?: JSONArray()
        val links = mutableListOf<String>()
        val locs = mutableListOf<Location?>()
        val byType = linkedMapOf<String, Int>()
        val skipped = linkedMapOf<String, Int>()
        val where = o.optJSONObject("outbound_locations")
        val offeredArr = o.optJSONArray("servers") ?: JSONArray()
        val offered = (0 until offeredArr.length()).mapNotNull { location(offeredArr.optJSONObject(it)) }
            .distinctBy { (it.code + "|" + it.city).lowercase(Locale.US) }
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            val type = ob.optString("type").lowercase(Locale.US)
            if (type in setOf("direct", "block", "dns", "selector", "urltest", "mutableurltest", "mutableselector")) continue
            val loc = location(where?.optJSONObject(ob.optString("tag")))
            val name = "🏮 Cat Fanoos " + (loc?.label()?.plus(" ") ?: "") + "#${links.size + 1}"
            val link = runCatching { SingBoxOutboundLinks.link(ob, name) }.getOrNull()
            if (link != null && link !in links) {
                links += link
                locs += loc
                byType[type] = (byType[type] ?: 0) + 1
            } else if (link == null) {
                skipped[type.ifBlank { "?" }] = (skipped[type.ifBlank { "?" }] ?: 0) + 1
            }
        }
        return Translation(links, byType, skipped, locs, offered, o.optString("country").trim().uppercase(Locale.US))
    }
}

/** sing-box outbound JSON → standard share link (null when Cat's core cannot run it as-is). Pure. */
internal object SingBoxOutboundLinks {
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private fun hostPort(ob: JSONObject): String? {
        val server = ob.optString("server").trim()
        val port = ob.optInt("server_port", 0)
        if (server.isBlank() || port !in 1..65535) return null
        return (if (server.contains(':') && !server.startsWith("[")) "[$server]" else server) + ":" + port
    }

    private fun tlsParams(ob: JSONObject, out: MutableList<Pair<String, String>>): Boolean {
        val tls = ob.optJSONObject("tls") ?: return false
        if (!tls.optBoolean("enabled", false)) return false
        // A config that only works with certificate checks off is not used (Cat never skips verification).
        if (tls.optBoolean("insecure", false)) throw IllegalArgumentException("insecure tls")
        val reality = tls.optJSONObject("reality")
        if (reality != null && reality.optBoolean("enabled", false)) {
            out += "security" to "reality"
            out += "pbk" to reality.optString("public_key")
            reality.optString("short_id").takeIf { it.isNotBlank() }?.let { out += "sid" to it }
        } else {
            out += "security" to "tls"
        }
        tls.optString("server_name").takeIf { it.isNotBlank() }?.let { out += "sni" to it }
        tls.optJSONObject("utls")?.optString("fingerprint")?.takeIf { it.isNotBlank() }?.let { out += "fp" to it }
        tls.optJSONArray("alpn")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let { out += "alpn" to it.joinToString(",") } }
        return true
    }

    private fun transportParams(ob: JSONObject, out: MutableList<Pair<String, String>>) {
        val tr = ob.optJSONObject("transport") ?: run { out += "type" to "tcp"; return }
        when (val t = tr.optString("type").lowercase(Locale.US)) {
            "ws" -> {
                out += "type" to "ws"
                tr.optString("path").takeIf { it.isNotBlank() }?.let { out += "path" to it }
                tr.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotBlank() }?.let { out += "host" to it }
            }
            "grpc" -> { out += "type" to "grpc"; tr.optString("service_name").takeIf { it.isNotBlank() }?.let { out += "serviceName" to it } }
            "http" -> {
                out += "type" to "http"
                tr.optString("path").takeIf { it.isNotBlank() }?.let { out += "path" to it }
                tr.optJSONArray("host")?.optString(0)?.takeIf { it.isNotBlank() }?.let { out += "host" to it }
            }
            "httpupgrade" -> {
                out += "type" to "httpupgrade"
                tr.optString("path").takeIf { it.isNotBlank() }?.let { out += "path" to it }
                tr.optString("host").takeIf { it.isNotBlank() }?.let { out += "host" to it }
            }
            else -> throw IllegalArgumentException("transport $t")
        }
    }

    private fun query(params: List<Pair<String, String>>) = params.filter { it.second.isNotBlank() }.joinToString("&") { it.first + "=" + enc(it.second) }

    fun link(ob: JSONObject, name: String): String? {
        val hp = hostPort(ob) ?: return null
        val tag = "#" + enc(name)
        return when (ob.optString("type").lowercase(Locale.US)) {
            "shadowsocks" -> {
                val method = ob.optString("method")
                val password = ob.optString("password")
                if (method.isBlank() || password.isBlank() || ob.has("plugin") && ob.optString("plugin").isNotBlank()) return null
                "ss://" + Base64.getUrlEncoder().withoutPadding().encodeToString("$method:$password".toByteArray()) + "@" + hp + tag
            }
            "vless" -> {
                val uuid = ob.optString("uuid").ifBlank { return null }
                val p = mutableListOf("encryption" to "none")
                ob.optString("flow").takeIf { it.isNotBlank() }?.let { p += "flow" to it }
                tlsParams(ob, p)
                transportParams(ob, p)
                "vless://$uuid@$hp?" + query(p) + tag
            }
            "trojan" -> {
                val pass = ob.optString("password").ifBlank { return null }
                val p = mutableListOf<Pair<String, String>>()
                if (!tlsParams(ob, p)) return null // trojan without TLS is not runnable — counted as skipped
                transportParams(ob, p)
                "trojan://" + enc(pass) + "@$hp?" + query(p) + tag
            }
            "hysteria2" -> {
                val pass = ob.optString("password").ifBlank { return null }
                val p = mutableListOf<Pair<String, String>>()
                val tls = ob.optJSONObject("tls")
                if (tls?.optBoolean("insecure", false) == true) return null
                tls?.optString("server_name")?.takeIf { it.isNotBlank() }?.let { p += "sni" to it }
                ob.optJSONObject("obfs")?.let { o -> if (o.optString("type").isNotBlank()) { p += "obfs" to o.optString("type"); p += "obfs-password" to o.optString("password") } }
                "hysteria2://" + enc(pass) + "@$hp" + (if (p.isEmpty()) "" else "?" + query(p)) + tag
            }
            "tuic" -> {
                val uuid = ob.optString("uuid").ifBlank { return null }
                val p = mutableListOf<Pair<String, String>>()
                val tls = ob.optJSONObject("tls")
                if (tls?.optBoolean("insecure", false) == true) return null
                tls?.optString("server_name")?.takeIf { it.isNotBlank() }?.let { p += "sni" to it }
                ob.optString("congestion_control").takeIf { it.isNotBlank() }?.let { p += "congestion_control" to it }
                "tuic://$uuid:" + enc(ob.optString("password")) + "@$hp" + (if (p.isEmpty()) "" else "?" + query(p)) + tag
            }
            "vmess" -> {
                val uuid = ob.optString("uuid").ifBlank { return null }
                val p = mutableListOf<Pair<String, String>>()
                val tls = tlsParams(ob, p)
                transportParams(ob, p)
                val m = p.toMap()
                val host = hp.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
                val j = JSONObject().put("v", "2").put("ps", name).put("add", host).put("port", hp.substringAfterLast(':'))
                    .put("id", uuid).put("aid", ob.optInt("alter_id", 0).toString()).put("scy", ob.optString("security").ifBlank { "auto" })
                    .put("net", m["type"] ?: "tcp").put("type", "none").put("host", m["host"] ?: "").put("path", m["path"] ?: m["serviceName"] ?: "")
                    .put("tls", if (tls) "tls" else "").put("sni", m["sni"] ?: "")
                "vmess://" + Base64.getEncoder().encodeToString(j.toString().toByteArray())
            }
            else -> null
        }
    }
}

// ───────────────────────────── 🎫 50 GB free account ─────────────────────────────

/** Picks the subscription link / share links / account number out of what the account page shows. Pure. */
internal object FreeAccountLinks {
    const val SITE = "https://freesocks.org"
    private val SUB_RE = Regex("https://[A-Za-z0-9.-]+(?::\\d{1,5})?/api/v1/sub/[A-Za-z0-9_-]{8,}")
    private val SHARE_RE = Regex("(?i)\\b(?:vless|vmess|trojan|ss|hysteria2|hy2|tuic)://[^\\s\"'<>]+")
    private val ACCOUNT_RE = Regex("(?<!\\d)(\\d{4}[ -]?){7}\\d{4}(?!\\d)")

    /** «Open in app» links the page builds (v2rayng / hiddify / clash / karing / sing-box / sub:// / anywhere). */
    fun fromDeepLink(raw: String): String? {
        val link = raw.trim()
        val scheme = link.substringBefore("://", "").lowercase(Locale.US)
        val candidate = when (scheme) {
            "hiddify" -> link.substringAfter("://").removePrefix("import/").substringBefore('#')
            "sub" -> runCatching { String(Base64.getDecoder().decode(link.substringAfter("://").substringBefore('#').trim())).substringBefore('#') }.getOrNull()
            "v2rayng", "clash", "karing", "sing-box", "clashmeta", "stash" -> param(link, "url")
            "anywhere" -> param(link, "link")
            else -> null
        } ?: return null
        val decoded = if (candidate.startsWith("http", true)) candidate else runCatching { URLDecoder.decode(candidate, "UTF-8") }.getOrDefault(candidate)
        return decoded.takeIf { it.startsWith("https://", true) || SHARE_RE.matches(it) }
    }

    private fun param(link: String, key: String): String? =
        link.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.substringBefore('=').equals(key, true) }
            ?.substringAfter('=')?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    fun subscriptionUrls(text: String): List<String> = SUB_RE.findAll(text).map { it.value }.distinct().toList()

    fun shareLinks(text: String): List<String> = SHARE_RE.findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }.distinct().toList()

    /** The 32-digit account number (grouped or not), digits only. */
    fun accountNumber(text: String): String? = ACCOUNT_RE.find(text)?.value?.filter(Char::isDigit)?.takeIf { it.length == 32 }

    fun groupAccount(digits: String): String = digits.chunked(4).joinToString(" ")

    /** Script injected into the page: reports text, inputs and links (no clicks, no form filling). */
    const val SCAN_JS = "(function(){try{var t=[document.body?document.body.innerText:''];" +
        "document.querySelectorAll('input,textarea').forEach(function(e){t.push(e.value||'')});" +
        "document.querySelectorAll('a[href]').forEach(function(a){t.push(a.href)});" +
        "return t.join('\\n').slice(0,200000);}catch(e){return ''}})()"
}

/** Ids + small caches for the three free networks (kept out of MainActivity* area files). */
internal class FreeNetworkStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cat_free_networks", Context.MODE_PRIVATE)

    fun freeProxyUdid(): String = prefs.getString("fp_udid", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("fp_udid", it).apply() }
    fun freeProxyToken(): String? {
        val at = prefs.getLong("fp_token_at", 0L)
        if (System.currentTimeMillis() - at > 6L * 24 * 3600 * 1000) return null
        return prefs.getString("fp_token", null)?.takeIf { it.isNotBlank() }
    }
    fun saveFreeProxyToken(token: String) = prefs.edit().putString("fp_token", token).putLong("fp_token_at", System.currentTimeMillis()).apply()

    var freeProxySubId: String
        get() = prefs.getString("fp_sub", "").orEmpty()
        set(v) { prefs.edit().putString("fp_sub", v).apply() }
    var freeProxyRegions: String
        get() = prefs.getString("fp_regions", "nl").orEmpty()
        set(v) { prefs.edit().putString("fp_regions", v).apply() }
    var freeProxyAt: Long
        get() = prefs.getLong("fp_at", 0L)
        set(v) { prefs.edit().putLong("fp_at", v).apply() }
    /** Credentials AS WRITTEN in the subscription (FreeProxyCred lines); never overwritten by the connect hook. */
    var freeProxyCreds: String
        get() = prefs.getString("fp_creds", "").orEmpty()
        set(v) { prefs.edit().putString("fp_creds", v).apply() }
    /** Latest fetched credentials (same format) + when; reused for 6 h so a connect does not fetch every time. */
    var freeProxyFresh: String
        get() = prefs.getString("fp_fresh", "").orEmpty()
        set(v) { prefs.edit().putString("fp_fresh", v).apply() }
    var freeProxyFreshAt: Long
        get() = prefs.getLong("fp_fresh_at", 0L)
        set(v) { prefs.edit().putLong("fp_fresh_at", v).apply() }

    fun lanternDeviceId(): String = prefs.getString("ln_dev", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("ln_dev", it).apply() }
    var lanternUser: String
        get() = prefs.getString("ln_user", "").orEmpty()
        set(v) { prefs.edit().putString("ln_user", v).apply() }
    var lanternToken: String
        get() = prefs.getString("ln_token", "").orEmpty()
        set(v) { prefs.edit().putString("ln_token", v).apply() }
    /** The locations the service offered last time (JSON array of {country, city, country_code}) — the country picker. */
    var lanternOffered: String
        get() = prefs.getString("ln_offered", "").orEmpty()
        set(v) { prefs.edit().putString("ln_offered", v).apply() }
    fun lanternOfferedList(): List<LanternPlanner.Location> = runCatching {
        val a = JSONArray(lanternOffered)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            LanternPlanner.Location(o.optString("country"), o.optString("city"), o.optString("country_code"))
        }
    }.getOrDefault(emptyList())
    /** Saved Fanoos links with their country code: JSON [{l, c}] — used to merge countries across fetches. */
    var lanternLinks: List<Pair<String, String>>
        get() = runCatching {
            val a = JSONArray(prefs.getString("ln_links", null) ?: "[]")
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("l") to it.optString("c") } }.filter { it.first.isNotBlank() }
        }.getOrDefault(emptyList())
        set(v) { prefs.edit().putString("ln_links", JSONArray(v.map { JSONObject().put("l", it.first).put("c", it.second) }).toString()).apply() }
    var lanternSubId: String
        get() = prefs.getString("ln_sub", "").orEmpty()
        set(v) { prefs.edit().putString("ln_sub", v).apply() }

    var freeAccountSubId: String
        get() = prefs.getString("fa_sub", "").orEmpty()
        set(v) { prefs.edit().putString("fa_sub", v).apply() }
    /** The 32-digit account number is the ONLY way back into that account: kept here and shown to the user. */
    var freeAccountNumber: String
        get() = prefs.getString("fa_acct", "").orEmpty()
        set(v) { prefs.edit().putString("fa_acct", v).apply() }
    var freeAccountSite: String
        get() = prefs.getString("fa_site", FreeAccountLinks.SITE).orEmpty().ifBlank { FreeAccountLinks.SITE }
        set(v) { prefs.edit().putString("fa_site", v).apply() }
}

/** Fetch + build for the free proxy network and Lantern; blocking, off the main thread. */
internal object FreeNetworkBuilder {
    class Result(val links: List<String>, val report: List<String>)

    fun freeProxy(context: Context, regions: List<String>, fa: Boolean): Result {
        val store = FreeNetworkStore(context)
        val api = FreeProxyApi(store)
        val links = mutableListOf<String>()
        val report = mutableListOf<String>()
        val creds = mutableListOf<String>()
        val perRegion = if (regions.size <= 1) 4 else 2
        for (region in regions) {
            val loc = FreeProxyPlanner.LOCATIONS.firstOrNull { it.region == region } ?: continue
            try {
                val servers = api.servers(region)
                links += FreeProxyPlanner.links(loc, servers, fa, perRegion)
                creds += FreeProxyPlanner.credLines(region, servers.take(perRegion))
                report += "✓ ${loc.flag} " + (if (fa) loc.nameFa else loc.nameEn) + ": " + minOf(servers.size, perRegion)
            } catch (e: Exception) {
                report += "✗ ${loc.flag} " + (if (fa) loc.nameFa else loc.nameEn) + ": " + (e.message ?: e.javaClass.simpleName).take(80)
            }
        }
        if (links.isNotEmpty()) {
            store.freeProxyRegions = regions.joinToString(",")
            store.freeProxyAt = System.currentTimeMillis()
            store.freeProxyCreds = creds.joinToString("\n")
            store.freeProxyFresh = ""
            store.freeProxyFreshAt = 0L
        }
        return Result(links, api.log + report)
    }

    fun lantern(context: Context, preferred: LanternPlanner.Location?): Pair<Result, LanternPlanner.Translation> {
        val store = FreeNetworkStore(context)
        val dev = store.lanternDeviceId()
        val report = mutableListOf<String>()
        if (store.lanternUser.isBlank()) {
            val a = FreeNetHttp.request(LanternPlanner.USER_CREATE, "POST", LanternPlanner.headers(dev, "", ""), "{}", timeoutMs = 15_000)
            val user = (if (a.code in 200..299) LanternPlanner.user(a.body) else null)
                ?: throw IOException("user-create HTTP ${a.code} " + a.body.take(80))
            store.lanternUser = user.first
            store.lanternToken = user.second
            report += "user ✓" + if (a.viaTunnel) " (tunnel)" else ""
        }
        var c: FreeNetHttp.Answer? = null
        val tries = mutableListOf<String>()
        for (url in LanternPlanner.CONFIG_URLS) {
            val host = url.substringAfter("://").substringBefore('/')
            val answer = try {
                FreeNetHttp.request(
                    url, "POST",
                    LanternPlanner.headers(dev, store.lanternUser, store.lanternToken),
                    LanternPlanner.configBody(dev, store.lanternUser, store.lanternToken, preferred), timeoutMs = 20_000,
                )
            } catch (e: IOException) {
                tries += "$host → " + (e.message ?: e.javaClass.simpleName).take(60)
                continue
            }
            c = answer
            if (answer.code in 200..299) {
                report += "config ✓ $host" + if (answer.viaTunnel) " (tunnel)" else ""
                break
            }
            tries += "$host → HTTP ${answer.code}" + if (LanternPlanner.isEdgeBlock(answer.code, answer.body)) " (CDN block page)" else " " + answer.body.take(60)
            if (!LanternPlanner.isEdgeBlock(answer.code, answer.body)) break
        }
        if (c == null || c.code !in 200..299) throw IOException("config: " + tries.joinToString(" · "))
        val tr = LanternPlanner.translate(c.body)
        if (tr.offered.isNotEmpty()) store.lanternOffered = JSONArray(tr.offered.map { it.toJson() }).toString()
        report += "outbounds: " + tr.byType.entries.joinToString(" ") { "${it.key}×${it.value}" }
        if (tr.skipped.isNotEmpty()) report += "skipped: " + tr.skipped.entries.joinToString(" ") { "${it.key}×${it.value}" }
        return Result(tr.links, report) to tr
    }

    /**
     * Connect-time hook (VPN service, IO thread, before the core starts). When the subscription's free-proxy
     * credentials are older than 6 h, the runtime YAML gets fresh ones (cached 6 h). The fetch has a hard 8 s budget
     * so a blocked API never stalls connecting; on any failure the written credentials stay (they often still work).
     */
    fun refreshFreeProxyCreds(context: Context, yaml: String, budgetMs: Long = 8_000L): String {
        val store = FreeNetworkStore(context)
        val written = FreeProxyPlanner.parseCreds(store.freeProxyCreds)
        if (written.isEmpty() || !FreeProxyPlanner.mentions(yaml, written)) return yaml
        val now = System.currentTimeMillis()
        val sixHours = 6L * 3600 * 1000
        if (now - store.freeProxyAt < sixHours) return yaml
        val cached = FreeProxyPlanner.parseCreds(store.freeProxyFresh)
        if (cached.isNotEmpty() && now - store.freeProxyFreshAt < sixHours) return FreeProxyPlanner.swapAll(yaml, written, cached)
        val regions = written.map { it.region }.filter(String::isNotBlank).distinct().take(3)
        val box = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
        val worker = Thread {
            val api = FreeProxyApi(store)
            box.set(regions.flatMap { r -> runCatching { FreeProxyPlanner.credLines(r, api.servers(r)) }.getOrDefault(emptyList()) })
        }.apply { isDaemon = true; name = "cat-free-proxy-refresh" }
        worker.start()
        worker.join(budgetMs)
        val lines = box.get()
        if (lines.isEmpty()) return yaml // too slow or failed: keep the written credentials
        store.freeProxyFresh = lines.joinToString("\n")
        store.freeProxyFreshAt = System.currentTimeMillis()
        return FreeProxyPlanner.swapAll(yaml, written, FreeProxyPlanner.parseCreds(store.freeProxyFresh))
    }
}

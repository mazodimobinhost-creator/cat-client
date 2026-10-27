package com.cat.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * Vercel XHTTP relay ("config on Vercel").
 *
 * Vercel cannot run a VLESS server by itself (no raw TCP, every request may land on another
 * instance), so — like every working Vercel method on GitHub/YouTube (XHTTPRelayECO,
 * vercel-xhttp-relay, SNI-XHTTP) — the app deploys a tiny relay that forwards XHTTP requests to an
 * XHTTP server the user already has (own VPS or a config whose server still works but whose
 * address is filtered). The client then talks TLS to vercel.com / *.vercel.app / Vercel anycast IPs.
 *
 * Everything in [VercelRelayPlanner] is pure Kotlin so it is unit-tested; [VercelApi] does the
 * REST calls with the user's own token.
 */
internal data class VercelSourceLink(
    val scheme: String,
    val credential: String,
    val address: String,
    val port: Int,
    val params: LinkedHashMap<String, String>,
    val remark: String,
)

internal data class VercelRelayBackend(
    val proto: String,
    val connect: String,
    val port: Int,
    val servername: String,
    val host: String,
    val insecure: Boolean,
    val path: String,
)

internal enum class VercelRuntime(val wire: String) { Node("node"), Edge("edge") }

internal sealed class VercelSourceCheck {
    data class Ok(val source: VercelSourceLink, val backend: VercelRelayBackend, val edgeAllowed: Boolean) : VercelSourceCheck()
    data class Rejected(val fa: String, val en: String) : VercelSourceCheck()
}

internal data class VercelRelayLink(val labelFa: String, val labelEn: String, val link: String)

internal object VercelRelayPlanner {
    /** Vercel anycast addresses (vercel.com KB + community reports of the pools that answer on 443). */
    val VERCEL_IPS = listOf("76.76.21.21", "64.29.17.65", "216.198.79.65")

    /** Hobby allows one function region; Frankfurt is closest to Iran with good peering. */
    val REGIONS = listOf(
        "fra1" to ("فرانکفورت (پیشنهادی)" to "Frankfurt (recommended)"),
        "cdg1" to ("پاریس" to "Paris"),
        "lhr1" to ("لندن" to "London"),
        "arn1" to ("استکهلم" to "Stockholm"),
        "iad1" to ("واشنگتن" to "Washington"),
    )

    private val NAME_WORDS = listOf("notes", "static", "docs", "gallery", "recipes", "journal", "landing", "portfolio", "garden", "studio")

    fun parse(raw: String): VercelSourceLink? {
        val text = raw.trim()
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme != "vless" && scheme != "trojan") return null
        val uri = runCatching { URI(text.replace(" ", "%20")) }.getOrNull() ?: return null
        val credential = decode(uri.rawUserInfo ?: return null)
        var host = uri.host ?: return null
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        val port = if (uri.port > 0) uri.port else 443
        val params = LinkedHashMap<String, String>()
        uri.rawQuery?.split('&')?.forEach { pair ->
            if (pair.isBlank()) return@forEach
            val key = decode(pair.substringBefore('=')).lowercase()
            val value = decode(pair.substringAfter('=', ""))
            if (key.isNotBlank()) params[key] = value
        }
        val remark = uri.rawFragment?.let(::decode).orEmpty()
        if (credential.isBlank() || host.isBlank()) return null
        return VercelSourceLink(scheme, credential, host, port, params, remark)
    }

    fun check(raw: String): VercelSourceCheck {
        val source = parse(raw) ?: return VercelSourceCheck.Rejected(
            "لینک باید vless:// یا trojan:// باشد.",
            "The link must be vless:// or trojan://.",
        )
        val type = source.params["type"].orEmpty().lowercase()
        if (type != "xhttp" && type != "splithttp") {
            return VercelSourceCheck.Rejected(
                "این کانفیگ از نوع «${type.ifBlank { "tcp" }}» است. Vercel فقط XHTTP را رد می‌کند (WS، gRPC، TCP و Reality روی Vercel کار نمی‌کنند).",
                "This config uses '${type.ifBlank { "tcp" }}'. Vercel can only relay XHTTP (WS, gRPC, TCP and Reality do not work on Vercel).",
            )
        }
        val security = source.params["security"].orEmpty().lowercase()
        if (security == "reality") {
            return VercelSourceCheck.Rejected(
                "کانفیگ‌های Reality از پشت Vercel کار نمی‌کنند؛ سرور باید XHTTP با TLS معمولی یا بدون TLS باشد.",
                "Reality configs cannot work behind Vercel; the server must be XHTTP with normal TLS or without TLS.",
            )
        }
        val tls = security == "tls"
        if (!tls && security.isNotBlank() && security != "none") {
            return VercelSourceCheck.Rejected("نوع امنیت «$security» پشتیبانی نمی‌شود.", "Security '$security' is not supported.")
        }
        val hostParam = source.params["host"].orEmpty().trim()
        val sni = source.params["sni"].orEmpty().trim()
        val hostHeader = hostParam.ifBlank { sni }.ifBlank { source.address }
        val servername = sni.ifBlank { hostParam }.ifBlank { source.address }
        val insecure = source.params["allowinsecure"] == "1" || source.params["allowinsecure"] == "true" || source.params["insecure"] == "1"
        val path = normalizePath(source.params["path"].orEmpty())
        val backend = VercelRelayBackend(
            proto = if (tls) "https" else "http",
            connect = source.address,
            port = source.port,
            servername = servername,
            host = hostHeader,
            insecure = tls && insecure,
            path = path,
        )
        // Edge can only fetch a URL: the host must be a real domain and the certificate must be valid.
        val edgeAllowed = !isIpLiteral(hostHeader) && !(tls && insecure) &&
            (hostHeader.equals(source.address, true) || hostHeader.equals(servername, true))
        return VercelSourceCheck.Ok(source, backend, edgeAllowed)
    }

    fun normalizePath(raw: String): String {
        var path = raw.trim().substringBefore('?')
        if (path.isBlank()) path = "/"
        if (!path.startsWith('/')) path = "/$path"
        return path
    }

    fun isIpLiteral(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.contains(':')) return true
        val parts = h.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
    }

    fun upstreamJson(backend: VercelRelayBackend, runtime: VercelRuntime): String {
        // Edge connects by URL, so "connect" must equal the host name there.
        val connect = if (runtime == VercelRuntime.Edge) backend.host else backend.connect
        return buildString {
            append('{')
            append("\"proto\":").append(jsonString(backend.proto)).append(',')
            append("\"connect\":").append(jsonString(connect)).append(',')
            append("\"port\":").append(backend.port).append(',')
            append("\"servername\":").append(jsonString(backend.servername)).append(',')
            append("\"host\":").append(jsonString(backend.host)).append(',')
            append("\"insecure\":").append(backend.insecure).append(',')
            append("\"path\":").append(jsonString(backend.path))
            append('}')
        }
    }

    fun fillTemplate(template: String, upstreamJson: String, region: String): String {
        val upstream = Regex("/\\*CAT_UPSTREAM\\*/[\\s\\S]*?/\\*END\\*/")
        val regions = Regex("/\\*CAT_REGIONS\\*/[\\s\\S]*?/\\*END\\*/")
        require(upstream.containsMatchIn(template)) { "relay template has no CAT_UPSTREAM marker" }
        return template
            .replace(upstream) { upstreamJson }
            .replace(regions) { "[" + jsonString(region) + "]" }
    }

    fun vercelJson(runtime: VercelRuntime, region: String, maxDuration: Int): String =
        if (runtime == VercelRuntime.Node) {
            "{\"version\":2,\"regions\":[${jsonString(region)}]," +
                "\"functions\":{\"api/index.js\":{\"maxDuration\":$maxDuration}}," +
                "\"rewrites\":[{\"source\":\"/(.*)\",\"destination\":\"/api/index\"}]}"
        } else {
            "{\"version\":2,\"rewrites\":[{\"source\":\"/(.*)\",\"destination\":\"/api/index\"}]}"
        }

    fun packageJson(projectName: String): String =
        "{\"name\":${jsonString(projectName)},\"version\":\"1.0.0\",\"private\":true,\"type\":\"module\"," +
            "\"engines\":{\"node\":\">=18\"}}"

    /** Files of the deployment, path → UTF-8 content. */
    fun files(template: String, backend: VercelRelayBackend, runtime: VercelRuntime, region: String, projectName: String, maxDuration: Int): LinkedHashMap<String, String> =
        linkedMapOf(
            "package.json" to packageJson(projectName),
            "vercel.json" to vercelJson(runtime, region, maxDuration),
            "api/index.js" to fillTemplate(template, upstreamJson(backend, runtime), region),
        )

    fun randomProjectName(random: java.util.Random = SecureRandom()): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val word = NAME_WORDS[random.nextInt(NAME_WORDS.size)]
        val tail = (1..5).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        return "$word-$tail"
    }

    /** Vercel project names: lowercase, ≤100 chars, letters/digits/'.', '_', '-', no '---'. */
    fun isValidProjectName(name: String): Boolean =
        name.length in 1..100 && name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' } &&
            !name.contains("---") && name.first().isLetterOrDigit()

    /**
     * Client links for the relay. All use TLS 443 to Vercel; the UUID/password and the XHTTP
     * path/extra stay as on the original config, so the user's server does not change.
     */
    fun buildLinks(source: VercelSourceLink, projectHost: String, baseName: String): List<VercelRelayLink> {
        val keep = LinkedHashMap<String, String>()
        source.params["encryption"]?.let { keep["encryption"] = it }
        if (source.scheme == "vless" && keep["encryption"].isNullOrBlank()) keep["encryption"] = "none"
        source.params["extra"]?.takeIf { it.isNotBlank() }?.let { keep["extra"] = it }
        val path = normalizePath(source.params["path"].orEmpty())
        val variants = mutableListOf<Triple<String, String, String>>() // address, sni, label
        variants += Triple("vercel.com", "vercel.com", "front")
        variants += Triple(projectHost, projectHost, "direct")
        VERCEL_IPS.forEachIndexed { index, ip -> variants += Triple(ip, projectHost, "ip${index + 1}") }
        return variants.map { (address, sni, label) ->
            val params = LinkedHashMap<String, String>()
            params["encryption"] = keep["encryption"] ?: ""
            params["security"] = "tls"
            params["sni"] = sni
            params["alpn"] = "h2,http/1.1"
            params["fp"] = "chrome"
            params["type"] = "xhttp"
            params["host"] = projectHost
            params["path"] = path
            params["mode"] = "packet-up"
            keep["extra"]?.let { params["extra"] = it }
            val query = params.filterValues { it.isNotEmpty() }.entries.joinToString("&") { (k, v) -> k + "=" + encode(v) }
            val name = "🔺Vercel-$label · $baseName"
            val link = "${source.scheme}://${encode(source.credential)}@$address:443?$query#${encode(name)}"
            val (fa, en) = when (label) {
                "front" -> "از طریق vercel.com (اول این را امتحان کنید)" to "Via vercel.com (try this first)"
                "direct" -> "مستقیم با دامنه پروژه" to "Direct to the project domain"
                else -> "با IP ورسل $address" to "Through Vercel IP $address"
            }
            VercelRelayLink(fa, en, link)
        }
    }

    /** Optional helper for VPS owners: a ready Xray inbound (VLESS + XHTTP, no TLS) behind the relay. */
    fun serverInboundJson(uuid: String, port: Int, path: String): String = """
{
  "inbounds": [
    {
      "tag": "vercel-xhttp",
      "listen": "0.0.0.0",
      "port": $port,
      "protocol": "vless",
      "settings": { "clients": [ { "id": ${jsonString(uuid)} } ], "decryption": "none" },
      "streamSettings": {
        "network": "xhttp",
        "security": "none",
        "xhttpSettings": { "path": ${jsonString(normalizePath(path))}, "mode": "auto" }
      }
    }
  ],
  "outbounds": [ { "protocol": "freedom", "tag": "direct" } ]
}
""".trim()

    fun serverLink(uuid: String, serverAddress: String, port: Int, path: String): String {
        val host = if (serverAddress.contains(':') && !serverAddress.startsWith("[")) "[$serverAddress]" else serverAddress
        return "vless://${encode(uuid)}@$host:$port?encryption=none&security=none&type=xhttp&path=${encode(normalizePath(path))}#${encode("my-xhttp-server")}"
    }

    fun randomUuid(random: java.util.Random = SecureRandom()): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        bytes[6] = (bytes[6].toInt() and 0x0f or 0x40).toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    fun randomPath(random: java.util.Random = SecureRandom()): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "/" + (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Picks the shortest *.vercel.app alias (the production one), else null. */
    fun pickProductionHost(aliases: List<String>, projectName: String): String? {
        val clean = aliases.map { it.trim().removePrefix("https://").removeSuffix("/").lowercase() }.filter { it.endsWith(".vercel.app") }
        clean.firstOrNull { it == "$projectName.vercel.app" }?.let { return it }
        return clean.minByOrNull { it.length }
    }

    fun extractLinks(text: String): List<String> =
        Regex("(?i)(vless|trojan)://[^\\s\"'<>]+").findAll(text).map { it.value.trimEnd(',', ';') }.distinct().toList()

    fun relayableLinks(text: String): List<String> = extractLinks(text).filter { check(it) is VercelSourceCheck.Ok }

    fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

internal class VercelApiException(message: String, val status: Int = 0) : IOException(message)

internal data class VercelDeployResult(
    val projectId: String,
    val projectName: String,
    val deploymentId: String,
    val host: String,
)

/** Minimal Vercel REST client (user's own token). Must be called off the main thread. */
internal class VercelApi(private val token: String) {
    private var teamId: String? = null
    private var teamChecked = false

    fun user(): JSONObject = request("GET", "/v2/user", null, scoped = false).optJSONObject("user") ?: JSONObject()

    private fun ensureScope() {
        if (teamChecked) return
        teamChecked = true
        val id = runCatching { user().optString("defaultTeamId") }.getOrNull().orEmpty()
        if (id.isBlank()) return
        teamId = id
        // A token created for the personal scope may not see the team: fall back silently.
        val ok = runCatching { request("GET", "/v9/projects?limit=1", null) }.isSuccess
        if (!ok) teamId = null
    }

    fun createProject(name: String): JSONObject {
        ensureScope()
        val body = JSONObject().put("name", name).put("framework", JSONObject.NULL)
        return request("POST", "/v11/projects", body)
    }

    fun disableProtection(projectId: String) {
        runCatching {
            request("PATCH", "/v9/projects/${enc(projectId)}", JSONObject().put("ssoProtection", JSONObject.NULL))
        }
    }

    fun deploy(projectId: String, projectName: String, files: Map<String, String>, region: String?): JSONObject {
        ensureScope()
        val list = JSONArray()
        files.forEach { (path, content) ->
            list.put(
                JSONObject()
                    .put("file", path)
                    .put("data", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
                    .put("encoding", "base64"),
            )
        }
        val body = JSONObject()
            .put("name", projectName)
            .put("project", projectId)
            .put("target", "production")
            .put("files", list)
            .put("projectSettings", JSONObject().put("framework", JSONObject.NULL))
        if (region != null) body.put("regions", JSONArray().put(region))
        return request("POST", "/v13/deployments?skipAutoDetectionConfirmation=1&forceNew=1", body)
    }

    fun deployment(id: String): JSONObject = request("GET", "/v13/deployments/${enc(id)}", null)

    fun buildErrors(id: String): String = runCatching {
        val text = requestRaw("GET", "/v3/deployments/${enc(id)}/events?builds=1&limit=60", null)
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return@runCatching ""
        (0 until arr.length()).mapNotNull { i ->
            val event = arr.optJSONObject(i) ?: return@mapNotNull null
            val payload = event.optJSONObject("payload")
            val line = payload?.optString("text").orEmpty().ifBlank { event.optString("text") }
            line.takeIf { it.contains("error", true) || it.contains("fail", true) }
        }.takeLast(4).joinToString("\n")
    }.getOrDefault("")

    fun projectDomains(projectId: String): List<String> = runCatching {
        val arr = request("GET", "/v9/projects/${enc(projectId)}/domains", null).optJSONArray("domains") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    fun deleteProject(projectIdOrName: String) {
        ensureScope()
        request("DELETE", "/v9/projects/${enc(projectIdOrName)}", null)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun request(method: String, path: String, body: JSONObject?, scoped: Boolean = true): JSONObject {
        val text = requestRaw(method, path, body, scoped)
        return if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject() }
    }

    private fun requestRaw(method: String, path: String, body: JSONObject?, scoped: Boolean = true): String {
        val team = teamId
        val full = if (scoped && !team.isNullOrBlank()) {
            path + (if (path.contains('?')) "&" else "?") + "teamId=" + enc(team)
        } else path
        val connection = (URL("https://api.vercel.com$full").openConnection() as HttpURLConnection).apply {
            val direct = runCatching { requestMethod = method }.isSuccess
            if (!direct) {
                // Some HttpURLConnection builds reject PATCH: set the protected field directly.
                requestMethod = "POST"
                runCatching {
                    val field = HttpURLConnection::class.java.getDeclaredField("method")
                    field.isAccessible = true
                    field.set(this, method)
                }
            }
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CatClient-VercelRelay")
        }
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
                val message = error?.optString("message").orEmpty().ifBlank { text.take(200) }.ifBlank { "HTTP $code" }
                val errCode = error?.optString("code").orEmpty()
                throw VercelApiException(if (errCode.isNotBlank()) "$message ($errCode)" else message, code)
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}

internal data class SavedVercelRelay(
    val projectId: String,
    val projectName: String,
    val host: String,
    val runtime: String,
    val createdAt: Long,
    val sourceName: String,
    val links: List<String>,
)

/** Token + the projects this app created (so the user can see/delete them later). */
internal class VercelRelayStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cat_vercel_relay", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString("token", "").orEmpty()
        set(value) { prefs.edit().putString("token", value.trim()).apply() }

    fun list(): List<SavedVercelRelay> = runCatching {
        val arr = JSONArray(prefs.getString("projects", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val links = o.optJSONArray("links") ?: JSONArray()
            SavedVercelRelay(
                projectId = o.optString("id"),
                projectName = o.optString("name"),
                host = o.optString("host"),
                runtime = o.optString("runtime"),
                createdAt = o.optLong("at"),
                sourceName = o.optString("source"),
                links = (0 until links.length()).map { links.optString(it) }.filter(String::isNotBlank),
            )
        }
    }.getOrDefault(emptyList())

    fun save(item: SavedVercelRelay) = write(list().filterNot { it.projectId == item.projectId } + item)

    fun remove(projectId: String) = write(list().filterNot { it.projectId == projectId })

    private fun write(items: List<SavedVercelRelay>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.projectId)
                    .put("name", item.projectName)
                    .put("host", item.host)
                    .put("runtime", item.runtime)
                    .put("at", item.createdAt)
                    .put("source", item.sourceName)
                    .put("links", JSONArray(item.links)),
            )
        }
        prefs.edit().putString("projects", arr.toString()).apply()
    }
}

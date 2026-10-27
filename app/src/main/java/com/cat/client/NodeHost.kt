package com.cat.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 6.69.45 Cat panel on other Node.js hosts — KataBump (free), Lucity, Shiprr.
 *
 * The host only needs the 2-file launcher (assets/node-panel: index.js + package.json, + README) and an optional
 * cat-config.json with the main UUID / Trojan password this app generates — so the app knows the subscription
 * address without the user copying anything. The launcher fetches the panel from this repo's release and checks
 * it against cat-node-panel.sha256 before running it.
 *
 * Pure helpers only in this object (no Android, no org.json) — they are unit tested on the JVM.
 */
object NodeHostPanel {
    const val REPO = "mazodimobinhost-creator/cat-client"
    const val ZIP_ASSET = "cat-node-panel.zip"

    const val KATABUMP = "katabump"
    const val LUCITY = "lucity"
    const val SHIPRR = "shiprr"

    const val KATABUMP_SITE = "https://katabump.com"
    const val KATABUMP_CONTROL = "https://control.katabump.com"
    const val KATABUMP_API_KEYS = "https://control.katabump.com/account/api"
    const val KATABUMP_DASHBOARD = "https://dashboard.katabump.com"
    const val KATABUMP_RULES = "https://legal.katabump.com/acceptable-use/"
    const val LUCITY_LOGIN = "https://lucity.cloud/app/login"
    const val SHIPRR_SIGNUP = "https://shiprr.app/registration"
    const val CLOUDFLARE_DASH = "https://dash.cloudflare.com"
    const val GITHUB_SIGNUP = "https://github.com/signup"
    const val GITHUB_TOKEN_URL = "https://github.com/settings/tokens/new?scopes=repo&description=Cat%20Client%20panel"

    /** KataBump deletes a free server that is not renewed within 4 days; remind a day early. */
    const val RENEW_EVERY_MS = 4L * 24 * 60 * 60 * 1000
    const val REMIND_AFTER_MS = 3L * 24 * 60 * 60 * 1000

    val LAUNCHER_FILES = listOf("index.js", "package.json", "README.md")
    const val CONFIG_FILE = "cat-config.json"

    data class Host(val id: String, val name: String, val url: String, val free: Boolean)

    val HOSTS: List<Host> = listOf(
        Host(KATABUMP, "KataBump", KATABUMP_SITE, free = true),
        Host(LUCITY, "Lucity", "https://lucity.cloud", free = false),
        Host(SHIPRR, "Shiprr", "https://shiprr.app", free = false),
    )

    fun hostName(kind: String): String = HOSTS.firstOrNull { it.id == kind }?.name ?: kind

    /** The release tag CI makes for an app version: «v» + versionName (e.g. v1.10.0-beta190). */
    fun releaseTag(versionName: String): String = "v" + versionName.trim().removePrefix("v")

    fun zipUrl(versionName: String): String =
        "https://github.com/$REPO/releases/download/${releaseTag(versionName)}/$ZIP_ASSET"

    fun releasePage(versionName: String): String =
        "https://github.com/$REPO/releases/tag/${releaseTag(versionName)}"

    private const val ALNUM = "abcdefghijkmnopqrstuvwxyz23456789"

    fun randomToken(length: Int, random: SecureRandom = SecureRandom()): String =
        (1..length).map { ALNUM[random.nextInt(ALNUM.length)] }.joinToString("")

    fun newUuid(): String = UUID.randomUUID().toString()

    fun newTrojanPass(random: SecureRandom = SecureRandom()): String = "cat-" + randomToken(16, random)

    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /**
     * cat-config.json for the launcher. noDisk = the host has no permanent disk (Lucity / Shiprr): the main
     * subscription still works forever (fixed UUID), only users created inside the panel would not survive.
     */
    fun configJson(uuid: String, trojanPass: String, noDisk: Boolean): String {
        val parts = mutableListOf("\"UUID\": " + jsonString(uuid), "\"TROJAN_PASS\": " + jsonString(trojanPass))
        if (noDisk) parts += "\"CAT_NO_DISK\": \"1\""
        return "{\n  " + parts.joinToString(",\n  ") + "\n}\n"
    }

    /** A zip with fixed timestamps (same input → same bytes). */
    fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in files) {
                val entry = ZipEntry(name)
                entry.time = 1_767_225_600_000L // 2026-01-01
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\\.[A-Za-z0-9-]{1,63})*$")

    /**
     * What the user typed → the panel's base URL. «1.2.3.4:25565» / «x.kdns.fr:25565» → http (a host port has
     * no TLS); «x.lucity.app» / «x.shiprr.dev» / «x.kdns.fr» (on Cloudflare) → https. «/panel…» is dropped.
     */
    fun normalizeAddress(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        var scheme = ""
        val m = Regex("^(https?)://", RegexOption.IGNORE_CASE).find(s)
        if (m != null) {
            scheme = m.groupValues[1].lowercase()
            s = s.substring(m.range.last + 1)
        }
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (s.isEmpty() || s.contains('@')) return null
        val hostPart: String
        var port: Int? = null
        val colon = s.lastIndexOf(':')
        if (colon > 0 && !s.startsWith("[")) {
            hostPart = s.substring(0, colon)
            port = s.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
        } else {
            hostPart = s
        }
        val host = hostPart.lowercase()
        if (!IPV4.matches(host) && !HOSTNAME.matches(host)) return null
        if (IPV4.matches(host) && host.split('.').any { (it.toIntOrNull() ?: 999) > 255 }) return null
        if (scheme.isEmpty()) scheme = if (IPV4.matches(host) || (port != null && port != 443)) "http" else "https"
        val defaultPort = if (scheme == "https") 443 else 80
        return scheme + "://" + host + (if (port != null && port != defaultPort) ":$port" else "")
    }

    fun panelUrl(base: String): String = base.trimEnd('/') + "/panel"

    fun subUrl(base: String, uuid: String): String = base.trimEnd('/') + "/sub/" + uuid

    /** Host and port of a base URL from [normalizeAddress]. */
    fun hostPort(base: String): Pair<String, Int> {
        val https = base.startsWith("https://")
        val rest = base.substringAfter("://").substringBefore('/')
        val colon = rest.lastIndexOf(':')
        val fallback = if (https) 443 else 80
        if (colon <= 0) return Pair(rest, fallback)
        return Pair(rest.substring(0, colon), rest.substring(colon + 1).toIntOrNull() ?: fallback)
    }

    /** «Nodejs 22», «Node.js 22», «ghcr.io/…:nodejs_20», «node:18» → 22 / 22 / 20 / 18. */
    fun nodeVersionOf(text: String): Int? =
        Regex("(?i)node(?:[\\s._-]*js)?[^0-9]{0,3}(\\d{2})").find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The Docker image to switch to when the server runs Node < 20 (the panel needs fetch/WebSocket/web crypto
     * of Node 20+). null = keep the current one. [images] = the egg's «label → image» list.
     */
    fun pickNodeImage(images: Map<String, String>, current: String): String? {
        val now = nodeVersionOf(current) ?: images.entries.firstOrNull { it.value == current }?.let { nodeVersionOf(it.key) }
        if (now != null && now >= 20) return null
        val best = images.entries
            .mapNotNull { e -> (nodeVersionOf(e.key) ?: nodeVersionOf(e.value))?.let { v -> v to e.value } }
            .filter { it.first >= 20 }
            .maxByOrNull { it.first }
            ?: return null
        return if (best.second == current) null else best.second
    }

    /** The egg variable that names the file Node starts (JS_FILE / MAIN_FILE / BOT_JS_FILE …). */
    fun isMainFileVar(envName: String, serverValue: String, defaultValue: String): Boolean {
        val n = envName.uppercase()
        if (n.contains("PACKAGE") || n.contains("ARGS") || n.contains("UPDATE") || n.contains("GIT")) return false
        val named = n.contains("FILE") || n == "MAIN" || n.contains("ENTRY") || n.contains("STARTUP_SCRIPT")
        val looksJs = listOf(serverValue, defaultValue).any { it.trim().lowercase().let { v -> v.endsWith(".js") || v.endsWith(".mjs") || v.endsWith(".cjs") } }
        return named && (looksJs || (serverValue.isBlank() && defaultValue.isBlank()))
    }

    /** The address phones use for a Pterodactyl allocation: the alias when set, never 0.0.0.0 / a private IP. */
    fun publicHost(ip: String, alias: String?): String? {
        val a = alias?.trim().orEmpty()
        if (a.isNotEmpty()) return a
        val i = ip.trim()
        if (i.isEmpty() || i == "0.0.0.0" || i.startsWith("127.") || i.startsWith("10.") || i.startsWith("192.168.")) return null
        if (Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(i)) return null
        return i
    }

    /** Days left before KataBump deletes the server; null when the last renewal is unknown. */
    fun daysLeft(renewedAt: Long, now: Long): Double? =
        if (renewedAt <= 0L) null else (renewedAt + RENEW_EVERY_MS - now) / (24.0 * 60 * 60 * 1000)

    /** A subscription body (plain lines or base64) → its vless:// / trojan:// lines. */
    fun decodeSub(body: String): List<String> {
        val text = if (body.contains("://")) body else runCatching {
            String(Base64.getMimeDecoder().decode(body.trim()), Charsets.UTF_8)
        }.getOrDefault("")
        return text.lines().map { it.trim() }.filter { it.startsWith("vless://") || it.startsWith("trojan://") }
    }

    /** A query parameter of a share link (URL-decoded). */
    fun linkParam(link: String, key: String): String? {
        val q = link.substringBefore('#').substringAfter('?', "")
        if (q.isEmpty()) return null
        for (part in q.split('&')) {
            val k = part.substringBefore('=')
            if (k == key) return runCatching { java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull()
        }
        return null
    }

    /** «HTTP/1.x 200 …\r\n…\r\n\r\nbody» → (200, body). Plain http only (HTTP/1.0 request → no chunking). */
    fun parseHttpResponse(raw: ByteArray): Pair<Int, String> {
        val text = String(raw, Charsets.UTF_8)
        val status = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val split = text.indexOf("\r\n\r\n")
        return status to (if (split >= 0) text.substring(split + 4) else "")
    }

    fun repoName(random: SecureRandom = SecureRandom()): String = "cat-node-panel-" + randomToken(5, random)

    /** Body of GitHub «create or update file contents». */
    fun githubContentBody(message: String, bytes: ByteArray, sha: String = ""): String =
        "{\"message\":" + jsonString(message) + ",\"content\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"" +
            (if (sha.isNotBlank()) ",\"sha\":" + jsonString(sha) else "") + "}"
}

internal data class SavedNodeHost(
    val id: String,
    val kind: String,
    val name: String,
    val base: String,
    val uuid: String,
    val serverId: String,
    val createdAt: Long,
    val renewedAt: Long,
    val trojanPass: String = "",
)

/** Keys (on this phone only, like the Vercel token) + the panels this app set up on other hosts. */
internal class NodeHostStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cat_node_host", Context.MODE_PRIVATE)

    var pteroKey: String
        get() = prefs.getString("ptero_key", "").orEmpty()
        set(value) { prefs.edit().putString("ptero_key", value.trim()).apply() }

    var githubToken: String
        get() = prefs.getString("github_token", "").orEmpty()
        set(value) { prefs.edit().putString("github_token", value.trim()).apply() }

    /** The identity written into the last zip / repo for a host kind, so «register» knows the UUID. */
    fun pending(kind: String): Pair<String, String>? {
        val u = prefs.getString("pending_uuid_$kind", "").orEmpty()
        val p = prefs.getString("pending_pass_$kind", "").orEmpty()
        return if (u.isNotBlank() && p.isNotBlank()) u to p else null
    }

    fun setPending(kind: String, uuid: String, pass: String) {
        prefs.edit().putString("pending_uuid_$kind", uuid).putString("pending_pass_$kind", pass).apply()
    }

    fun lastRepo(kind: String): String = prefs.getString("last_repo_$kind", "").orEmpty()

    fun setLastRepo(kind: String, fullName: String) {
        prefs.edit().putString("last_repo_$kind", fullName).apply()
    }

    fun list(): List<SavedNodeHost> = runCatching {
        val arr = JSONArray(prefs.getString("hosts", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            SavedNodeHost(
                id = o.optString("id"),
                kind = o.optString("kind"),
                name = o.optString("name"),
                base = o.optString("base"),
                uuid = o.optString("uuid"),
                serverId = o.optString("server"),
                createdAt = o.optLong("at"),
                renewedAt = o.optLong("renewed"),
                trojanPass = o.optString("pass"),
            )
        }.filter { it.id.isNotBlank() && it.base.isNotBlank() }
    }.getOrDefault(emptyList())

    fun save(item: SavedNodeHost) = write(list().filterNot { it.id == item.id } + item)

    fun remove(id: String) = write(list().filterNot { it.id == id })

    fun markRenewed(id: String, at: Long) {
        val item = list().firstOrNull { it.id == id } ?: return
        save(item.copy(renewedAt = at))
    }

    private fun write(items: List<SavedNodeHost>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("kind", item.kind)
                    .put("name", item.name)
                    .put("base", item.base)
                    .put("uuid", item.uuid)
                    .put("server", item.serverId)
                    .put("at", item.createdAt)
                    .put("renewed", item.renewedAt)
                    .put("pass", item.trojanPass),
            )
        }
        prefs.edit().putString("hosts", arr.toString()).apply()
    }
}

internal class NodeHostApiException(message: String, val code: Int) : Exception(message)

/** Blocking network calls — always run on Dispatchers.IO. */
internal object NodeHostApi {
    data class PteroServer(val identifier: String, val name: String, val host: String?, val port: Int?, val node: String)
    data class PteroVariable(val env: String, val serverValue: String, val defaultValue: String, val editable: Boolean)

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
        contentType: String = "application/json",
        timeoutMs: Int = 20_000,
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "Cat Client/${BuildConfig.VERSION_NAME}")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching {
                    val j = JSONObject(text)
                    j.optJSONArray("errors")?.optJSONObject(0)?.let { it.optString("detail").ifBlank { it.optString("code") } }
                        ?: j.optString("message")
                }.getOrNull().orEmpty().ifBlank { text.take(200) }.ifBlank { "HTTP $code" }
                throw NodeHostApiException(msg, code)
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    // ---------------- KataBump (Pterodactyl client API) ----------------

    private fun ptero(key: String) = mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")

    private fun pteroUrl(path: String) = NodeHostPanel.KATABUMP_CONTROL + "/api/client" + path

    fun pteroServers(key: String): List<PteroServer> {
        val j = JSONObject(request(pteroUrl("?per_page=50"), "GET", ptero(key)))
        val data = j.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { i ->
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: return@mapNotNull null
            val allocs = a.optJSONObject("relationships")?.optJSONObject("allocations")?.optJSONArray("data") ?: JSONArray()
            var host: String? = null
            var port: Int? = null
            for (k in 0 until allocs.length()) {
                val al = allocs.optJSONObject(k)?.optJSONObject("attributes") ?: continue
                val alias = al.optString("ip_alias").takeIf { s -> s.isNotBlank() && s != "null" }
                val h = NodeHostPanel.publicHost(al.optString("ip"), alias)
                if (h != null && (host == null || al.optBoolean("is_default"))) {
                    host = h
                    port = al.optInt("port")
                }
            }
            PteroServer(a.optString("identifier"), a.optString("name"), host, port, a.optString("node"))
        }.filter { it.identifier.isNotBlank() }
    }

    fun pteroStartup(key: String, id: String): Pair<List<PteroVariable>, Map<String, String>> {
        val j = JSONObject(request(pteroUrl("/servers/$id/startup"), "GET", ptero(key)))
        val data = j.optJSONArray("data") ?: JSONArray()
        val vars = (0 until data.length()).mapNotNull { i ->
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: return@mapNotNull null
            PteroVariable(a.optString("env_variable"), a.optString("server_value"), a.optString("default_value"), a.optBoolean("is_editable"))
        }
        val images = mutableMapOf<String, String>()
        j.optJSONObject("meta")?.optJSONObject("docker_images")?.let { o ->
            val keys = o.keys()
            while (keys.hasNext()) {
                val label = keys.next()
                val image = o.optString(label)
                if (image.isNotBlank()) images[label] = image
            }
        }
        return vars to images
    }

    fun pteroImage(key: String, id: String): String =
        JSONObject(request(pteroUrl("/servers/$id"), "GET", ptero(key))).optJSONObject("attributes")?.optString("docker_image").orEmpty()

    fun pteroSetVariable(key: String, id: String, name: String, value: String) {
        val body = JSONObject().put("key", name).put("value", value).toString().toByteArray()
        request(pteroUrl("/servers/$id/startup/variable"), "PUT", ptero(key), body)
    }

    fun pteroSetImage(key: String, id: String, image: String) {
        val body = JSONObject().put("docker_image", image).toString().toByteArray()
        request(pteroUrl("/servers/$id/settings/docker-image"), "PUT", ptero(key), body)
    }

    fun pteroWrite(key: String, id: String, path: String, bytes: ByteArray) {
        val q = URLEncoder.encode(path, "UTF-8")
        request(pteroUrl("/servers/$id/files/write?file=$q"), "POST", ptero(key), bytes, "text/plain", 60_000)
    }

    fun pteroPower(key: String, id: String, signal: String) {
        val body = JSONObject().put("signal", signal).toString().toByteArray()
        request(pteroUrl("/servers/$id/power"), "POST", ptero(key), body)
    }

    fun pteroState(key: String, id: String): String =
        JSONObject(request(pteroUrl("/servers/$id/resources"), "GET", ptero(key))).optJSONObject("attributes")?.optString("current_state").orEmpty()

    // ---------------- GitHub ----------------

    private fun gh(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
    )

    fun githubLogin(token: String): String =
        JSONObject(request("https://api.github.com/user", "GET", gh(token))).optString("login")

    /** Creates a PRIVATE empty repository; returns its full name «owner/name». */
    fun githubCreateRepo(token: String, name: String): String {
        val body = JSONObject()
            .put("name", name)
            .put("private", true)
            .put("description", "Cat Panel launcher (Cat Client) — keep this repository private")
            .put("auto_init", false)
            .toString().toByteArray()
        return JSONObject(request("https://api.github.com/user/repos", "POST", gh(token), body)).optString("full_name")
    }

    /** Creates or replaces a file (an existing file needs its blob sha, or GitHub answers 422). */
    fun githubPut(token: String, fullName: String, path: String, bytes: ByteArray) {
        val sha = githubSha(token, fullName, path)
        val body = NodeHostPanel.githubContentBody("Cat panel: $path", bytes, sha.orEmpty()).toByteArray()
        request("https://api.github.com/repos/$fullName/contents/$path", "PUT", gh(token), body)
    }

    /** Blob sha of a file in the repo; null when it does not exist yet. */
    fun githubSha(token: String, fullName: String, path: String): String? = try {
        JSONObject(request("https://api.github.com/repos/$fullName/contents/$path", "GET", gh(token))).optString("sha").ifBlank { null }
    } catch (e: NodeHostApiException) {
        if (e.code == 404) null else throw e
    }

    // ---------------- the panel itself ----------------

    /**
     * GET on the user's own panel. https → the normal client; http (a KataBump IP:port) → a tiny HTTP/1.0
     * request on a socket, because this app's network policy forbids clear-text in HttpURLConnection.
     */
    fun panelGet(base: String, path: String, timeoutMs: Int = 12_000): Pair<Int, String> {
        if (base.startsWith("https://")) {
            return try {
                200 to request(base.trimEnd('/') + path, "GET", mapOf("Accept" to "*/*"), timeoutMs = timeoutMs)
            } catch (e: NodeHostApiException) {
                e.code to ""
            }
        }
        val (host, port) = NodeHostPanel.hostPort(base)
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val hostHeader = if (port == 80) host else "$host:$port"
            val req = "GET $path HTTP/1.0\r\nHost: $hostHeader\r\nUser-Agent: Cat Client\r\nAccept: */*\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            val input = socket.getInputStream()
            while (out.size() < 512 * 1024) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            NodeHostPanel.parseHttpResponse(out.toByteArray())
        }
    }

    /**
     * A real WebSocket handshake on the tunnel path of a config (what v2rayNG/Xray do first). 101 = the host
     * passes WebSocket through to the panel; anything else = configs from this host cannot connect.
     */
    fun websocketStatus(base: String, wsPath: String, hostHeader: String, timeoutMs: Int = 12_000): Int {
        val (host, port) = NodeHostPanel.hostPort(base)
        val tls = base.startsWith("https://")
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = timeoutMs
        val socket: Socket = if (tls) {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
            runCatching {
                val params = ssl.sslParameters
                params.serverNames = listOf<javax.net.ssl.SNIServerName>(SNIHostName(host))
                ssl.sslParameters = params
            }
            ssl.startHandshake()
            ssl
        } else raw
        return socket.use { s ->
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            val path = if (wsPath.startsWith("/")) wsPath else "/$wsPath"
            val req = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nUser-Agent: Cat Client\r\n\r\n"
            s.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
            s.getOutputStream().flush()
            val head = StringBuilder()
            val input = s.getInputStream()
            while (head.length < 4096) {
                val c = input.read()
                if (c < 0) break
                head.append(c.toChar())
                if (head.endsWith("\r\n")) break
            }
            Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }
}

/** «Renew your KataBump server» — a notification 3 days after the last renewal (KataBump deletes after 4). */
object NodeHostRenewReminder {
    private const val CHANNEL = "node_host_renew"

    fun schedule(context: Context, hostId: String, name: String, delayMs: Long) {
        val data = Data.Builder().putString("id", hostId).putString("name", name).build()
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setInitialDelay(delayMs.coerceAtLeast(60_000L), TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("node-renew-$hostId", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, hostId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork("node-renew-$hostId")
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val name = inputData.getString("name").orEmpty()
            val id = inputData.getString("id").orEmpty()
            notifyRenew(applicationContext, id, name)
            return Result.success()
        }
    }

    private fun notifyRenew(context: Context, id: String, name: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.node_renew_channel), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(NodeHostPanel.KATABUMP_DASHBOARD)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.node_renew_text, name)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud_tab)
            .setContentTitle(context.getString(R.string.node_renew_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(7300 + (id.hashCode() and 0xff), notification) }
    }
}

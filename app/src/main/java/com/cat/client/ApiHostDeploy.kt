package com.cat.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 🤖 Bot-Hosting.net and 🚂 Railway — the Cat Node panel brought up FROM THE APP with the user's own API key.
 *
 * Bot-Hosting.net (new panel, Oct 2026) has a public REST API (`https://bot-hosting.net/api/v1`, Bearer `bhk_…`):
 * create a Node.js deployment → write the launcher files → set the start command → activate the free
 * `*.apps.bot-hosting.cloud` HTTPS subdomain → start. Its free plan (256 MB, renew every 4 days) does not forbid
 * proxies in its acceptable-use list (§5.1), so it is the recommended free target.
 *
 * Railway has a public GraphQL API (`https://backboard.railway.com/graphql/v2`, Bearer account token): project →
 * service from the official `node:22-alpine` image → variables (UUID / TROJAN_PASS / PORT) → start command that
 * downloads the launcher of THIS release → `*.up.railway.app` domain → deploy. Railway's Acceptable Use Policy
 * names «operating proxies or anonymization services» as abuse, so the app shows that clause and asks for an
 * explicit confirmation first (same rule as KataBump).
 *
 * Pure helpers only in [ApiHostPlan] (no Android, no org.json) — they are unit tested on the JVM.
 */
object ApiHostPlan {
    const val BOTHOSTING_API = "https://bot-hosting.net/api/v1"
    const val BOTHOSTING_SITE = "https://bot-hosting.net"
    const val BOTHOSTING_LOGIN = "https://bot-hosting.net/login"
    const val BOTHOSTING_KEYS = "https://bot-hosting.net/a/developer"
    const val BOTHOSTING_DASHBOARD = "https://bot-hosting.net/a"
    const val BOTHOSTING_RULES = "https://bot-hosting.net/legal"

    const val RAILWAY_API = "https://backboard.railway.com/graphql/v2"
    const val RAILWAY_SITE = "https://railway.com"
    const val RAILWAY_LOGIN = "https://railway.com/login"
    const val RAILWAY_TOKENS = "https://railway.com/account/tokens"
    const val RAILWAY_DASHBOARD = "https://railway.com/dashboard"
    const val RAILWAY_RULES = "https://railway.com/legal/acceptable-use"
    const val RAILWAY_IMAGE = "node:22-alpine"
    const val RAILWAY_PORT = 8080

    const val DEPLOY_NAME = "cat-panel"

    /** Scopes the Bot-Hosting key needs — nothing that can spend credits or delete. */
    val BOTHOSTING_SCOPES = listOf("deployments:read", "deployments:write", "deployments:power", "files:write", "env:write")

    fun isBotHostingKey(key: String): Boolean = key.startsWith("bhk_") && key.length >= 20 && key.none { it.isWhitespace() }

    /** Railway account / workspace tokens are UUID-like; a project token would only reach one project. */
    fun looksLikeRailwayToken(token: String): Boolean = token.length >= 30 && token.none { it.isWhitespace() }

    /** Bot-Hosting runs this at every boot from /home/container: install `ws`, then the launcher. */
    const val BOTHOSTING_START = "npm install --omit=dev --no-audit --no-fund && node index.js"

    fun rawLauncherUrl(tag: String, file: String): String =
        "https://raw.githubusercontent.com/" + NodeHostPanel.REPO + "/" + tag + "/node-panel/" + file

    /**
     * Railway start command for the plain Node image: fetch the launcher of the SAME release the app is on
     * (it then pulls the panel itself and checks its SHA-256), install `ws`, run. busybox wget in Alpine does HTTPS.
     */
    fun railwayStartCommand(tag: String): String =
        "sh -c 'mkdir -p /srv/cat && cd /srv/cat && wget -qO index.js " + rawLauncherUrl(tag, "index.js") +
            " && wget -qO package.json " + rawLauncherUrl(tag, "package.json") +
            " && npm install --omit=dev --no-audit --no-fund && exec node index.js'"

    fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun jsonObject(map: Map<String, String>): String =
        map.entries.joinToString(",", "{", "}") { jsonString(it.key) + ":" + jsonString(it.value) }

    /** One GraphQL request body. [variablesJson] is already JSON. */
    fun gqlBody(query: String, variablesJson: String = "{}"): String =
        "{\"query\":" + jsonString(query) + ",\"variables\":" + variablesJson + "}"

    /** «x.up.railway.app» / «https://x.apps.bot-hosting.cloud/» → «https://x…» without a trailing slash. */
    fun httpsBase(domainOrUrl: String): String? {
        val raw = domainOrUrl.trim().removeSuffix("/")
        if (raw.isBlank()) return null
        val host = raw.removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (host.isBlank() || host.contains(' ') || !host.contains('.')) return null
        return "https://$host"
    }

    /** Bot-Hosting gives «my-bot.apps» as the subdomain id; the public name ends with .bot-hosting.cloud. */
    fun botHostingUrl(subdomain: String, url: String): String? =
        httpsBase(url.takeIf { it.startsWith("http") && !it.contains("/files/") }.orEmpty())
            ?: subdomain.trim().takeIf { it.isNotBlank() }?.let { s ->
                httpsBase(if (s.endsWith(".bot-hosting.cloud")) s else "$s.bot-hosting.cloud")
            }

    /** Saved-host id for Railway: three ids in one field so «update» can redeploy the same service. */
    fun railwayRef(projectId: String, serviceId: String, environmentId: String): String =
        "$projectId|$serviceId|$environmentId"

    fun parseRailwayRef(ref: String): Triple<String, String, String>? {
        val p = ref.split('|')
        return if (p.size == 3 && p.all { it.isNotBlank() }) Triple(p[0], p[1], p[2]) else null
    }

    /** Plain-language reason for the most common API failures (null = show the raw message). */
    fun explainBotHosting(code: Int, message: String): String? = when {
        code == 401 -> "key"
        code == 403 && message.contains("scope", true) -> "scope"
        code == 402 || message.contains("slot", true) || message.contains("quota", true) || message.contains("pool", true) -> "slot"
        code == 429 -> "rate"
        else -> null
    }

    fun explainRailway(message: String): String? = when {
        message.contains("Not Authorized", true) || message.contains("unauthorized", true) || message.contains("invalid token", true) -> "token"
        message.contains("trial", true) || message.contains("plan", true) || message.contains("limit", true) || message.contains("verify", true) -> "plan"
        message.contains("workspace", true) || message.contains("team", true) -> "workspace"
        else -> null
    }
}

internal class ApiHostException(message: String, val code: Int) : Exception(message)

/** Network half (org.json + HttpURLConnection). Every call runs on a background dispatcher. */
internal object ApiHostClient {
    data class BhDeployment(
        val id: String,
        val name: String,
        val state: String,
        val runtime: String,
        val url: String?,
        val nodeHost: String?,
        val port: Int?,
    )

    private fun request(url: String, method: String, headers: Map<String, String>, body: String? = null, timeoutMs: Int = 30_000): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "Cat Client/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return Pair(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    // ---------------- Bot-Hosting.net REST ----------------

    private fun bh(key: String, method: String, path: String, body: String? = null, timeoutMs: Int = 30_000): JSONObject {
        val (code, text) = request(ApiHostPlan.BOTHOSTING_API + path, method, mapOf("Authorization" to "Bearer $key"), body, timeoutMs)
        if (code !in 200..299) {
            val msg = runCatching {
                val j = JSONObject(text)
                j.optString("error").ifBlank { j.optString("message") }.ifBlank { j.optJSONObject("error")?.optString("message").orEmpty() }
            }.getOrNull().orEmpty().ifBlank { text.take(200) }.ifBlank { "HTTP $code" }
            throw ApiHostException(msg, code)
        }
        return runCatching { JSONObject(text.ifBlank { "{}" }) }.getOrElse { JSONObject() }
    }

    private fun bhDeployment(j: JSONObject): BhDeployment {
        val domains = j.optJSONObject("domains")
        val node = j.optJSONObject("node")
        val port = j.optInt("port", 0).takeIf { it in 1..65535 }
        return BhDeployment(
            id = j.optString("id"),
            name = j.optString("name"),
            state = j.optString("state"),
            runtime = j.optString("runtime"),
            url = domains?.let { ApiHostPlan.botHostingUrl(it.optString("subdomain"), it.optString("url")) },
            nodeHost = node?.optString("fqdn")?.takeIf { it.isNotBlank() },
            port = port,
        )
    }

    fun bhList(key: String): List<BhDeployment> {
        val j = bh(key, "GET", "/deployments")
        val arr = j.optJSONArray("deployments") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { bhDeployment(it) } }.filter { it.id.isNotBlank() }
    }

    fun bhGet(key: String, id: String): BhDeployment = bhDeployment(bh(key, "GET", "/deployments/$id"))

    fun bhCreate(key: String, name: String): BhDeployment {
        val body = "{\"name\":" + ApiHostPlan.jsonString(name) + ",\"source\":\"blank\",\"runtime\":\"nodejs\",\"runtimeVersion\":\"22\"}"
        val j = bh(key, "POST", "/deployments", body, 60_000)
        return bhDeployment(j.optJSONObject("deployment") ?: j)
    }

    fun bhWriteMany(key: String, id: String, files: Map<String, String>) {
        val arr = files.entries.joinToString(",", "[", "]") {
            "{\"path\":" + ApiHostPlan.jsonString("/" + it.key) + ",\"content\":" + ApiHostPlan.jsonString(it.value) + ",\"mode\":\"overwrite\"}"
        }
        val j = bh(key, "POST", "/deployments/$id/files/writeMany", "{\"files\":$arr}", 60_000)
        val failed = j.optJSONArray("failed")
        if (failed != null && failed.length() > 0) {
            val f = failed.optJSONObject(0)
            throw ApiHostException((f?.optString("path") ?: "") + ": " + (f?.optString("error") ?: "write failed"), 500)
        }
    }

    fun bhStartup(key: String, id: String, startCommand: String) {
        bh(key, "PATCH", "/deployments/$id/startup", "{\"runtime\":\"nodejs\",\"runtimeVersion\":\"22\",\"entryFile\":\"index.js\",\"startCommand\":" + ApiHostPlan.jsonString(startCommand) + "}")
    }

    fun bhDomains(key: String, id: String): String? {
        val j = bh(key, "POST", "/deployments/$id/domains")
        return ApiHostPlan.botHostingUrl(j.optString("subdomain"), j.optString("url"))
    }

    /** start / restart waits server-side; returns (state, last log lines). */
    fun bhPower(key: String, id: String, action: String, waitSeconds: Int = 30): Pair<String, List<String>> {
        val j = bh(key, "POST", "/deployments/$id/power", "{\"action\":" + ApiHostPlan.jsonString(action) + ",\"waitSeconds\":$waitSeconds}", (waitSeconds + 40) * 1000)
        val logs = j.optJSONArray("logs") ?: JSONArray()
        return Pair(j.optString("state"), (0 until logs.length()).map { logs.optString(it) }.takeLast(8))
    }

    fun bhLogs(key: String, id: String, waitSeconds: Int): Pair<String, List<String>> {
        val j = bh(key, "GET", "/deployments/$id/logs?size=40&waitSeconds=$waitSeconds", null, (waitSeconds + 40) * 1000)
        val lines = j.optJSONArray("lines") ?: JSONArray()
        return Pair(j.optString("state"), (0 until lines.length()).map { lines.optString(it) })
    }

    // ---------------- Railway GraphQL ----------------

    private fun rw(token: String, query: String, variablesJson: String = "{}"): JSONObject {
        val (code, text) = request(ApiHostPlan.RAILWAY_API, "POST", mapOf("Authorization" to "Bearer $token"), ApiHostPlan.gqlBody(query, variablesJson), 45_000)
        val j = runCatching { JSONObject(text) }.getOrNull()
        val err = j?.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty()
        if (code !in 200..299 || err.isNotBlank() || j == null) {
            throw ApiHostException(err.ifBlank { text.take(200) }.ifBlank { "HTTP $code" }, code)
        }
        return j.optJSONObject("data") ?: JSONObject()
    }

    fun rwWhoAmI(token: String): String {
        val me = rw(token, "query { me { name email } }").optJSONObject("me")
        return me?.optString("email")?.ifBlank { me.optString("name") }.orEmpty()
    }

    private fun rwWorkspaceId(token: String): String? = runCatching {
        val ws = rw(token, "query { me { workspaces { id name } } }").optJSONObject("me")?.optJSONArray("workspaces")
        ws?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** projectCreate; accounts that must name a workspace get their first workspace. Returns (projectId, environmentId). */
    fun rwCreateProject(token: String, name: String): Pair<String, String> {
        val q = "mutation projectCreate(\$input: ProjectCreateInput!) { projectCreate(input: \$input) { id environments { edges { node { id name } } } } }"
        val first = runCatching { rw(token, q, "{\"input\":{\"name\":" + ApiHostPlan.jsonString(name) + "}}") }
        val data = first.getOrElse { e ->
            val ws = if (ApiHostPlan.explainRailway(e.message.orEmpty()) == "workspace") rwWorkspaceId(token) else null
            if (ws == null) throw e
            rw(token, q, "{\"input\":{\"name\":" + ApiHostPlan.jsonString(name) + ",\"workspaceId\":" + ApiHostPlan.jsonString(ws) + "}}")
        }
        val p = data.optJSONObject("projectCreate") ?: throw ApiHostException("projectCreate: empty answer", 500)
        val projectId = p.optString("id")
        var envId = firstEnvironment(p)
        if (envId.isBlank()) envId = rwEnvironment(token, projectId)
        if (projectId.isBlank() || envId.isBlank()) throw ApiHostException("projectCreate: no project/environment id", 500)
        return Pair(projectId, envId)
    }

    private fun firstEnvironment(project: JSONObject): String {
        val edges = project.optJSONObject("environments")?.optJSONArray("edges") ?: return ""
        var any = ""
        for (i in 0 until edges.length()) {
            val n = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            if (any.isBlank()) any = n.optString("id")
            if (n.optString("name").equals("production", true)) return n.optString("id")
        }
        return any
    }

    fun rwEnvironment(token: String, projectId: String): String {
        val q = "query project(\$id: String!) { project(id: \$id) { environments { edges { node { id name } } } } }"
        val p = rw(token, q, "{\"id\":" + ApiHostPlan.jsonString(projectId) + "}").optJSONObject("project") ?: return ""
        return firstEnvironment(p)
    }

    fun rwCreateService(token: String, projectId: String, name: String, image: String): String {
        val q = "mutation serviceCreate(\$input: ServiceCreateInput!) { serviceCreate(input: \$input) { id name } }"
        val v = "{\"input\":{\"projectId\":" + ApiHostPlan.jsonString(projectId) + ",\"name\":" + ApiHostPlan.jsonString(name) +
            ",\"source\":{\"image\":" + ApiHostPlan.jsonString(image) + "}}}"
        return rw(token, q, v).optJSONObject("serviceCreate")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: throw ApiHostException("serviceCreate: no id", 500)
    }

    fun rwUpsertVariables(token: String, projectId: String, environmentId: String, serviceId: String, vars: Map<String, String>) {
        val q = "mutation variableCollectionUpsert(\$input: VariableCollectionUpsertInput!) { variableCollectionUpsert(input: \$input) }"
        val v = "{\"input\":{\"projectId\":" + ApiHostPlan.jsonString(projectId) + ",\"environmentId\":" + ApiHostPlan.jsonString(environmentId) +
            ",\"serviceId\":" + ApiHostPlan.jsonString(serviceId) + ",\"variables\":" + ApiHostPlan.jsonObject(vars) + "}}"
        rw(token, q, v)
    }

    fun rwSetStartCommand(token: String, serviceId: String, environmentId: String, startCommand: String) {
        val q = "mutation serviceInstanceUpdate(\$serviceId: String!, \$environmentId: String!, \$input: ServiceInstanceUpdateInput!) { serviceInstanceUpdate(serviceId: \$serviceId, environmentId: \$environmentId, input: \$input) }"
        val v = "{\"serviceId\":" + ApiHostPlan.jsonString(serviceId) + ",\"environmentId\":" + ApiHostPlan.jsonString(environmentId) +
            ",\"input\":{\"startCommand\":" + ApiHostPlan.jsonString(startCommand) + ",\"restartPolicyType\":\"ON_FAILURE\",\"restartPolicyMaxRetries\":5}}"
        rw(token, q, v)
    }

    fun rwCreateDomain(token: String, serviceId: String, environmentId: String, port: Int): String {
        val q = "mutation serviceDomainCreate(\$input: ServiceDomainCreateInput!) { serviceDomainCreate(input: \$input) { id domain } }"
        val v = "{\"input\":{\"serviceId\":" + ApiHostPlan.jsonString(serviceId) + ",\"environmentId\":" + ApiHostPlan.jsonString(environmentId) + ",\"targetPort\":$port}}"
        return rw(token, q, v).optJSONObject("serviceDomainCreate")?.optString("domain")?.takeIf { it.isNotBlank() }
            ?: throw ApiHostException("serviceDomainCreate: no domain", 500)
    }

    fun rwDeploy(token: String, serviceId: String, environmentId: String) {
        val v = "{\"serviceId\":" + ApiHostPlan.jsonString(serviceId) + ",\"environmentId\":" + ApiHostPlan.jsonString(environmentId) + "}"
        val first = runCatching {
            rw(token, "mutation d(\$serviceId: String!, \$environmentId: String!) { serviceInstanceDeployV2(serviceId: \$serviceId, environmentId: \$environmentId) }", v)
        }
        if (first.isFailure) {
            rw(token, "mutation r(\$serviceId: String!, \$environmentId: String!) { serviceInstanceRedeploy(serviceId: \$serviceId, environmentId: \$environmentId) }", v)
        }
    }

    /** Latest deployment status of the service (SUCCESS / BUILDING / DEPLOYING / FAILED / CRASHED …), "" if unknown. */
    fun rwStatus(token: String, serviceId: String, environmentId: String): String = runCatching {
        val q = "query s(\$serviceId: String!, \$environmentId: String!) { serviceInstance(serviceId: \$serviceId, environmentId: \$environmentId) { latestDeployment { id status } } }"
        val v = "{\"serviceId\":" + ApiHostPlan.jsonString(serviceId) + ",\"environmentId\":" + ApiHostPlan.jsonString(environmentId) + "}"
        rw(token, q, v).optJSONObject("serviceInstance")?.optJSONObject("latestDeployment")?.optString("status").orEmpty()
    }.getOrDefault("")
}

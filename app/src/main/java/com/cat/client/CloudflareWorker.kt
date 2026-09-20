package com.cat.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * CloudflareWorker — minimal Cloudflare API client that lets the user:
 *  1. Paste a Cloudflare API token (scoped to Workers + Account Settings Write).
 *  2. Auto-creates a Worker script that acts as a BPB/Zeus-style proxy panel.
 *  3. Returns the subscription URL the user can feed back into Cat Client.
 *
 * This uses the public Cloudflare REST API directly (no wrappers needed).
 */
object CloudflareWorker {

    data class CfTokenPermissions(
        val valid: Boolean,
        val accountId: String?,
        val accountEmail: String?,
        val missingScopes: List<String>,
    )

    data class DeploymentResult(
        val workerName: String,
        val subdomain: String,
        val subscriptionUrl: String,
        val workerUrl: String,
    )

    private const val MINIMUM_SCOPES = listOf(
        "com.cloudflare.api.account:read",
        "com.cloudflare.api.account.workers_scripts:edit",
        "com.cloudflare.api.account.workers_subdomain:read",
    )

    /**
     * Verify a Cloudflare API token and resolve the target account id.
     * The token must be created via https://dash.cloudflare.com/profile/api-tokens
     * with "Edit Cloudflare Workers" template (or custom with Workers Scripts Edit + Account Read).
     */
    suspend fun verifyToken(token: String): CfTokenPermissions = withContext(Dispatchers.IO) {
        val tokenDetails = cfGet(token, "https://api.cloudflare.com/client/v4/user/tokens/verify")
        val tokenOk = tokenDetails.optBoolean("success", false)
        if (!tokenOk) {
            return@withContext CfTokenPermissions(false, null, null, MINIMUM_SCOPES)
        }
        // Find accounts
        val accountsJson = cfGet(token, "https://api.cloudflare.com/client/v4/accounts?per_page=1")
        val accounts = accountsJson.optJSONArray("result") ?: JSONArray()
        if (accounts.length() == 0) {
            return@withContext CfTokenPermissions(false, null, null, listOf("account_access"))
        }
        val first = accounts.getJSONObject(0)
        val accountId = first.getString("id")
        val accountName = first.optString("name", "")
        CfTokenPermissions(true, accountId, accountName, emptyList())
    }

    /**
     * Deploy a pre-compiled worker script (a trimmed-down BPB-style panel)
     * under the chosen worker name. The script serves both the panel UI and
     * the subscription endpoint used by Cat Client.
     */
    suspend fun deployPanel(token: String, accountId: String, workerName: String): DeploymentResult =
        withContext(Dispatchers.IO) {
            // Resolve account workers subdomain
            val subdomainJson = cfGet(
                token,
                "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain"
            )
            val subdomain = subdomainJson.optJSONObject("result")?.optString("subdomain").orEmpty()
                .ifEmpty { "catclient-${accountId.take(8)}" }

            val workerScript = WORKER_SCRIPT
            val uploadUrl =
                "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$workerName"
            val putResult = cfUploadWorker(token, uploadUrl, workerScript)
            if (!putResult.optBoolean("success", false)) {
                val errors = putResult.optJSONArray("errors")?.toString() ?: "unknown"
                throw RuntimeException("Worker upload failed: $errors")
            }

            val workerUrl = "https://$workerName.$subdomain.workers.dev"
            DeploymentResult(
                workerName = workerName,
                subdomain = subdomain,
                workerUrl = workerUrl,
                subscriptionUrl = "$workerUrl/sub",
            )
        }

    private fun cfGet(token: String, url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        return conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun cfUploadWorker(token: String, url: String, script: String): JSONObject {
        val boundary = "----catclient${System.currentTimeMillis()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        OutputStreamWriter(conn.outputStream).use { w ->
            w.write("--$boundary\r\n")
            w.write("Content-Disposition: form-data; name=\"metadata\"\r\n")
            w.write("Content-Type: application/json\r\n\r\n")
            w.write("{\"main_module\":\"worker.js\",\"type\":\"esm\"}\r\n")
            w.write("--$boundary\r\n")
            w.write("Content-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"\r\n")
            w.write("Content-Type: application/javascript+module\r\n\r\n")
            w.write(script)
            w.write("\r\n--$boundary--\r\n")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        return runCatching { JSONObject(body) }.getOrDefault(JSONObject().put("success", code in 200..299))
    }

    /**
     * Minimal Cloudflare Worker — VLESS config generator + subscription endpoint.
     * This is a much smaller, stripped-down implementation inspired by BPB/Zeus.
     */
    private val WORKER_SCRIPT = """
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const host = request.headers.get('Host') || '';
    if (url.pathname === '/sub') return subResponse(host);
    if (url.pathname === '/') return panelHtml(host);
    return new Response('Cat Client Worker', { status: 200 });
  }
};
function subResponse(host) {
  const uuid = crypto.randomUUID();
  const conf = `vless://${uuid}@${host}:443?encryption=none&security=tls&sni=${host}&type=ws&path=%2F%3Fed%3D2048&host=${host}#Cat-Client-${host}`;
  return new Response(conf + '\n', {
    headers: { 'content-type': 'text/plain; charset=utf-8', 'access-control-allow-origin': '*' }
  });
}
function panelHtml(host) {
  return new Response(`<!doctype html><html><head><meta charset="utf-8"><title>Cat Client Panel</title>
<style>body{font-family:system-ui;background:#000;color:#fff;padding:24px}
h1{color:#a855f7}code{background:#111;padding:4px 8px;border-radius:6px;display:block;word-break:break-all;margin:8px 0}
button{background:#7c3aed;border:0;color:#fff;padding:10px 18px;border-radius:8px;cursor:pointer}
</style></head><body>
<h1>🐱 Cat Client · Worker Panel</h1>
<p>Subscription link for this worker:</p>
<code>https://${host}/sub</code>
<button onclick="navigator.clipboard.writeText('https://${host}/sub')">Copy</button>
<p style="margin-top:24px;color:#999">Add this link inside Cat Client → Subscriptions → + Add.</p>
</body></html>`, { headers: { 'content-type': 'text/html; charset=utf-8' } });
}
""".trimIndent()
}

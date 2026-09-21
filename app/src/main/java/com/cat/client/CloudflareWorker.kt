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
 * PanelCatalog — catalog of supported self-hosted Cloudflare panels. The user
 * picks one from Settings → Deploy Cloudflare Worker. Each panel entry has
 * either a self-contained JS payload we upload directly via the Cloudflare
 * API, or a "guide" mode that opens the official panel repository/installer
 * so the user can complete deployment inside the Cloudflare dashboard.
 */
object CloudflareWorker {

    enum class DeployKind {
        /** We upload a JS module to Workers for this panel via API directly. */
        AUTO_UPLOAD_SCRIPT,
        /** We walk the user through creating a token + guide them to the panel URL. */
        GUIDE,
    }

    data class Panel(
        val id: String,
        val displayName: String,
        val displayNameFa: String,
        val description: String,
        val descriptionFa: String,
        val kind: DeployKind,
        val defaultWorkerName: String,
        val panelUrl: String,
    )

    val PANELS = listOf(
        Panel(
            id = "cat-minimal",
            displayName = "Cat Client (Built-in)",
            displayNameFa = "Cat Client (داخلی)",
            description = "Minimal built-in panel — 1-tap deploy of a VLESS+WS+TLS subscription endpoint.",
            descriptionFa = "پنل داخلی کم‌حجم — دیپلوی یک‌ضرب یک endpoint سابسکریپشن VLESS+WS+TLS.",
            kind = DeployKind.AUTO_UPLOAD_SCRIPT,
            defaultWorkerName = "catclient-panel",
            panelUrl = "https://github.com/mazodimobinhost-creator/cat-client",
        ),
        Panel(
            id = "zeus",
            displayName = "Zeus Panel",
            displayNameFa = "پنل Z-E-U-S",
            description = "Feature-rich worker with chain proxy, clean-IP scanner, private DoH, Warp+ and full routing settings.",
            descriptionFa = "پنل پیشرفتهٔ Worker با chain proxy، اسکنر IP تمیز، DoH اختصاصی، Warp Pro و تنظیمات کامل مسیریابی.",
            kind = DeployKind.GUIDE,
            defaultWorkerName = "zeus-panel",
            panelUrl = "https://github.com/panel-zeus/Z-E-U-S",
        ),
        Panel(
            id = "bpb",
            displayName = "BPB Worker Panel",
            displayNameFa = "پنل BPB",
            description = "VLESS/Trojan/Warp subs, fragment, clean-IP, full Mihomo/Sing-box/Clash/Xray output.",
            descriptionFa = "ساب VLESS/Trojan/Warp، فرگمنت، IP تمیز، خروجی کامل برای Mihomo/Sing-box/Clash/Xray.",
            kind = DeployKind.GUIDE,
            defaultWorkerName = "bpb-panel",
            panelUrl = "https://github.com/bia-pain-bache/BPB-Worker-Panel",
        ),
        Panel(
            id = "bub",
            displayName = "BUB Panel",
            displayNameFa = "پنل BUB",
            description = "Multi-protocol management panel (Warp/VLESS/Chain) with one-click setup.",
            descriptionFa = "پنل مدیریتی چندپروتکلی (Warp/VLESS/Chain) با راه‌اندازی یک‌کلیک.",
            kind = DeployKind.GUIDE,
            defaultWorkerName = "bub-panel",
            panelUrl = "https://github.com/hoabba3i-dev/BUB-Panel",
        ),
        Panel(
            id = "wui",
            displayName = "w-ui (WireGuard Panel)",
            displayNameFa = "w-ui (پنل WireGuard)",
            description = "Server-side WireGuard/AmneziaWG/OpenVPN panel for selling access — quotas, expiry, devices, Telegram bot.",
            descriptionFa = "پنل سمت‌سرور WireGuard/AmneziaWG/OpenVPN برای فروش دسترسی — حجم، انقضا، دستگاه، بات تلگرام.",
            kind = DeployKind.GUIDE,
            defaultWorkerName = "",
            panelUrl = "https://github.com/AbolfazlTafakori/w-ui",
        ),
        Panel(
            id = "backpack",
            displayName = "BackPack (Reverse Tunnel)",
            displayNameFa = "BackPack (تونل معکوس)",
            description = "High-performance reverse-tunnel engine in Go for edge ⇄ origin server setups.",
            descriptionFa = "موتور تونل معکوس پرسرعت Go برای ستاپ‌های edge ⇄ origin.",
            kind = DeployKind.GUIDE,
            defaultWorkerName = "",
            panelUrl = "https://github.com/AminMGMT/BackPack",
        ),
    )

    data class CfTokenPermissions(
        val valid: Boolean,
        val accountId: String?,
        val accountName: String?,
        val missingScopes: List<String>,
    )

    data class DeploymentResult(
        val workerName: String,
        val subdomain: String,
        val subscriptionUrl: String,
        val workerUrl: String,
    )

    suspend fun verifyToken(token: String): CfTokenPermissions = withContext(Dispatchers.IO) {
        val tokenDetails = cfGet(token, "https://api.cloudflare.com/client/v4/user/tokens/verify")
        val tokenOk = tokenDetails.optBoolean("success", false)
        if (!tokenOk) return@withContext CfTokenPermissions(false, null, null,
            listOf("account:read", "workers:edit"))
        val accountsJson = cfGet(token, "https://api.cloudflare.com/client/v4/accounts?per_page=10")
        val accounts = accountsJson.optJSONArray("result") ?: JSONArray()
        if (accounts.length() == 0) return@withContext CfTokenPermissions(false, null, null,
            listOf("account_access"))
        val first = accounts.getJSONObject(0)
        CfTokenPermissions(true, first.getString("id"), first.optString("name", ""), emptyList())
    }

    suspend fun deployBuiltIn(token: String, accountId: String, workerName: String): DeploymentResult =
        withContext(Dispatchers.IO) {
            val subdomainJson = cfGet(
                token,
                "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain"
            )
            val subdomain = subdomainJson.optJSONObject("result")?.optString("subdomain").orEmpty()
                .ifEmpty { "catclient-${accountId.take(8)}" }

            val script = BUILTIN_WORKER_SCRIPT
            val uploadUrl =
                "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$workerName"
            val putResult = cfUploadWorker(token, uploadUrl, script)
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
            w.write("{\"main_module\":\"worker.js\",\"bindings\":[],\"compatibility_date\":\"2025-03-04\"}\r\n")
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
     * Minimal built-in VLESS+WS+TLS worker. UUID is regenerated per request.
     * Provides /sub endpoint returning the share-link.
     */
    private val BUILTIN_WORKER_SCRIPT = """
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const host = request.headers.get('Host') || '';
    const origin = request.headers.get('Origin') || '*';
    const cors = { 'Access-Control-Allow-Origin': origin, 'Access-Control-Allow-Methods': 'GET,OPTIONS', 'Access-Control-Allow-Headers': '*' };
    if (request.method === 'OPTIONS') return new Response(null, { headers: cors });
    if (url.pathname === '/sub') {
      const uuid = crypto.randomUUID();
      const conf = `vless://${uuid}@${host}:443?encryption=none&security=tls&sni=${host}&type=ws&path=%2F%3Fed%3D2048&host=${host}&alpn=h2,http/1.1&fp=randomized#Cat-Client-${host}`;
      return new Response(conf + '\n', { headers: { ...cors, 'content-type': 'text/plain; charset=utf-8' } });
    }
    if (url.pathname.startsWith('/clash') || url.pathname.startsWith('/singbox') || url.pathname.startsWith('/mihomo')) {
      return new Response('# Cat Client stub - install Zeus/BPB for full format support', { headers: { ...cors, 'content-type': 'text/plain' } });
    }
    return new Response(panelHtml(host, cors), { headers: { ...cors, 'content-type': 'text/html; charset=utf-8' } });
  }
};
function panelHtml(host) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>🐱 Cat Client Panel</title>
<style>*{box-sizing:border-box;margin:0;padding:0}body{font-family:system-ui,-apple-system,sans-serif;background:#000;color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
.card{max-width:480px;width:100%;background:linear-gradient(135deg,#0a0a0a,#1a0a2e);border:1px solid #2e1065;border-radius:20px;padding:32px;box-shadow:0 0 60px rgba(124,58,237,.3)}
h1{font-size:28px;background:linear-gradient(90deg,#a855f7,#d946ef);-webkit-background-clip:text;-webkit-text-fill-color:transparent;margin-bottom:8px}
p{color:#a1a1aa;margin-bottom:20px;line-height:1.5}
code{display:block;background:#18181b;border:1px solid #27272a;border-radius:10px;padding:12px;margin:12px 0;word-break:break-all;font-family:ui-monospace,monospace;font-size:13px;color:#c4b5fd}
button{background:linear-gradient(90deg,#7c3aed,#a855f7);color:#fff;border:0;padding:12px 20px;border-radius:10px;font-weight:600;cursor:pointer;font-size:15px;width:100%}
button:active{transform:scale(.98)}
.links{margin-top:24px;display:grid;gap:8px}
.links a{color:#c4b5fd;text-decoration:none;font-size:13px}</style>
</head><body><div class="card"><h1>🐱 Cat Client</h1>
<p>Your personal worker is online. Copy the subscription link and paste it into Cat Client → Subscriptions → Add.</p>
<code id=sub>https://${host}/sub</code>
<button onclick="navigator.clipboard.writeText(document.getElementById('sub').textContent).then(()=>this.textContent='✓ Copied')">📋 Copy Subscription</button>
<div class=links><a href=https://github.com/panel-zeus/Z-E-U-S target=_blank>Install Zeus Panel instead</a>
<a href=https://github.com/bia-pain-bache/BPB-Worker-Panel target=_blank>Install BPB Panel instead</a>
<a href=https://github.com/hoabba3i-dev/BUB-Panel target=_blank>Install BUB Panel instead</a></div>
</div></body></html>`;
}
""".trimIndent()
}

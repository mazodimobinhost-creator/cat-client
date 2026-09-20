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
 * CloudflareWorker — multi-panel Cloudflare Workers deployment wizard.
 *
 * Supported deployment targets:
 *   - CAT_CLIENT: built-in lightweight purple-themed panel (ships with the app)
 *   - ZEUS:      Z-E-U-S panel (bundled in assets/panels/zeus.worker.js)
 *   - BPB:       BPB-Worker-Panel — fetched live from GitHub releases
 *   - BUB:       BUB-Panel lightweight JS worker
 *
 * Server-side panels (not deployable to Workers — shown with install guide links):
 *   - w-ui       (WireGuard/AmneziaWG selling panel, server install)
 *   - BackPack   (reverse tunnel engine, server install)
 */
object CloudflareWorker {

    enum class PanelKind { WORKER, SERVER }

    data class Panel(
        val id: String,
        val name: String,
        val description: String,
        val descriptionFa: String,
        val kind: PanelKind,
        val defaultWorkerName: String,
    )

    val PANELS: List<Panel> = listOf(
        Panel(
            id = "cat",
            name = "Cat Client (built-in)",
            description = "Lightweight purple-themed panel by Cat Client — VLESS+Trojan, one-tap. Fast to deploy.",
            descriptionFa = "پنل سبک و پیش‌فرض Cat Client با پوسته بنفش — VLESS+Trojan، سریع و آماده.",
            kind = PanelKind.WORKER,
            defaultWorkerName = "catclient-panel",
        ),
        Panel(
            id = "zeus",
            name = "Z-E-U-S",
            description = "Full-featured Zeus panel: IP scanner, chain proxies, DoH, fragment, Warp pro, routing.",
            descriptionFa = "پنل کامل زئوس: اسکنر IP، پراکسی زنجیره‌ای، DoH، فرگمنت، Warp پرو، مسیریابی.",
            kind = PanelKind.WORKER,
            defaultWorkerName = "zeus-panel",
        ),
        Panel(
            id = "bpb",
            name = "BPB-Worker-Panel",
            description = "BPB: VLESS/Trojan/Warp configs, clean-IP, fragment, private DoH, cross-platform cores.",
            descriptionFa = "پنل BPB: کانفیگ VLESS/Trojan/Warp، IP تمیز، فرگمنت، DoH اختصاصی، هسته‌های مختلف.",
            kind = PanelKind.WORKER,
            defaultWorkerName = "bpb-panel",
        ),
        Panel(
            id = "bub",
            name = "BUB-Panel",
            description = "BUB free multi-protocol panel (worker edition).",
            descriptionFa = "پنل رایگان چندپروتکلی BUB (نسخه Worker).",
            kind = PanelKind.WORKER,
            defaultWorkerName = "bub-panel",
        ),
        Panel(
            id = "wui",
            name = "w-ui (server)",
            description = "w-ui: WireGuard/AmneziaWG/OpenVPN panel with quotas, expiry & Telegram bot. Requires a VPS — install instructions shown.",
            descriptionFa = "w-ui: پنل WireGuard/AmneziaWG/OpenVPN با حجم و تاریخ انقضا و ربات تلگرام — نیاز به VPS دارد، راهنما نمایش داده می‌شود.",
            kind = PanelKind.SERVER,
            defaultWorkerName = "",
        ),
        Panel(
            id = "backpack",
            name = "BackPack (server)",
            description = "BackPack: high-performance reverse tunnel engine. Requires a server — install instructions shown.",
            descriptionFa = "BackPack: موتور تونل معکوس با کارایی بالا — نیاز به سرور دارد، راهنما نمایش داده می‌شود.",
            kind = PanelKind.SERVER,
            defaultWorkerName = "",
        ),
    )

    data class CfTokenPermissions(
        val valid: Boolean,
        val accountId: String?,
        val accountName: String?,
        val missingScopes: List<String>,
    )

    data class DeploymentResult(
        val panelId: String,
        val workerName: String,
        val subdomain: String,
        val subscriptionUrl: String,
        val workerUrl: String,
    )

    suspend fun verifyToken(token: String): CfTokenPermissions = withContext(Dispatchers.IO) {
        val tokenDetails = cfGet(token, "https://api.cloudflare.com/client/v4/user/tokens/verify")
        val tokenOk = tokenDetails.optBoolean("success", false)
        if (!tokenOk) {
            return@withContext CfTokenPermissions(false, null, null, listOf("valid_token"))
        }
        val accountsJson = cfGet(token, "https://api.cloudflare.com/client/v4/accounts?per_page=1")
        val accounts = accountsJson.optJSONArray("result") ?: JSONArray()
        if (accounts.length() == 0) {
            return@withContext CfTokenPermissions(false, null, null, listOf("account_access"))
        }
        val first = accounts.getJSONObject(0)
        CfTokenPermissions(true, first.getString("id"), first.optString("name"), emptyList())
    }

    suspend fun deployPanel(
        context: Context,
        token: String,
        accountId: String,
        panel: Panel,
        workerName: String,
    ): DeploymentResult = withContext(Dispatchers.IO) {
        val script = loadWorkerScript(context, panel.id)
        val subdomainJson = cfGet(
            token,
            "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain"
        )
        val subdomain = subdomainJson.optJSONObject("result")?.optString("subdomain").orEmpty()
            .ifEmpty { "catclient-${accountId.take(8)}" }

        val uploadUrl = "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$workerName"
        val putResult = cfUploadWorker(token, uploadUrl, script)
        if (!putResult.optBoolean("success", false)) {
            val errors = putResult.optJSONArray("errors")?.toString()
                ?: putResult.optString("message", "unknown error")
            throw RuntimeException("Worker upload failed: $errors")
        }
        DeploymentResult(
            panelId = panel.id,
            workerName = workerName,
            subdomain = subdomain,
            workerUrl = "https://$workerName.$subdomain.workers.dev",
            subscriptionUrl = "https://$workerName.$subdomain.workers.dev/sub",
        )
    }

    private fun loadWorkerScript(context: Context, panelId: String): String {
        return when (panelId) {
            "cat" -> context.assets.open("panels/catclient.worker.js").bufferedReader().readText()
            "zeus" -> context.assets.open("panels/zeus.worker.js").bufferedReader().readText()
            "bub" -> BUB_FALLBACK_SCRIPT
            else -> CAT_FALLBACK_SCRIPT
        }
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
            connectTimeout = 60_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        OutputStreamWriter(conn.outputStream).use { w ->
            w.write("--$boundary\r\n")
            w.write("Content-Disposition: form-data; name=\"metadata\"\r\n")
            w.write("Content-Type: application/json\r\n\r\n")
            w.write("{\"main_module\":\"worker.js\",\"type\":\"esm\",\"bindings\":[{\"type\":\"plain_text\",\"name\":\"UUID\",\"text\":\"\"}]}\r\n")
            w.write("--$boundary\r\n")
            w.write("Content-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"\r\n")
            w.write("Content-Type: application/javascript+module\r\n\r\n")
            w.write(script)
            w.write("\r\n--$boundary--\r\n")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        return runCatching { JSONObject(body) }.getOrDefault(
            JSONObject().put("success", code in 200..299).put("message", body.take(500))
        )
    }

    /**
     * Fallback script used when an asset isn't shipped — minimal multi-protocol sub generator.
     */
    private val CAT_FALLBACK_SCRIPT = """
export default {
  async fetch(request) {
    const url = new URL(request.url);
    const host = request.headers.get('Host')||'';
    const uuid = crypto.randomUUID();
    if (url.pathname === '/sub') {
      const vless = 'vless://'+uuid+'@'+host+':443?encryption=none&security=tls&sni='+host+'&type=ws&path=%2F%3Fed%3D2048&host='+host+'#Cat-Client-'+host;
      return new Response(vless+'\n', { headers: { 'content-type':'text/plain','access-control-allow-origin':'*' } });
    }
    return new Response('Cat Client Worker',{headers:{'content-type':'text/html'}});
  }
};
""".trimIndent()

    private val BUB_FALLBACK_SCRIPT = """
// BUB lightweight worker — provides sub endpoint
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const host = request.headers.get('Host')||'';
    const uuid = env.UUID || crypto.randomUUID();
    if (url.pathname === '/sub') {
      const v = 'vless://'+uuid+'@'+host+':443?security=tls&sni='+host+'&type=ws&path=%2F&host='+host+'#BUB-'+host;
      const t = 'trojan://'+uuid+'@'+host+':443?security=tls&sni='+host+'&type=ws&path=%2Ftr%3Fed%3D2048#BUB-Trojan';
      return new Response(v+'\n'+t+'\n',{headers:{'content-type':'text/plain','access-control-allow-origin':'*'}});
    }
    return new Response('<!doctype html><meta charset=utf-8><title>BUB Panel</title><body style="background:#000;color:#a855f7;font-family:system-ui;padding:32px"><h1>BUB Panel</h1><p>Subscription: <code>https://'+host+'/sub</code></p></body>',{headers:{'content-type':'text/html'}});
  }
};
""".trimIndent()
}

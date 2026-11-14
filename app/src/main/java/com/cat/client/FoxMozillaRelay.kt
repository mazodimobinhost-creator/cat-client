package com.cat.client

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale

/**
 * Cat Fox control-plane relay: when Mozilla sign-in / Guardian cannot be reached,
 * those HTTPS calls (never the VPN traffic) go through the user's own Worker.
 * Optional. Any HTTPS host with `?secret=` — not only workers.dev.
 */
internal object FoxMozillaRelay {
    const val PREFS_KEY = "mozilla_relay_url"
    const val HEALTH_RELAY_NAME = "cat-fox"

    private val ALLOWED_SUFFIXES = listOf("mozilla.org", "mozilla.com", "firefox.com")

    data class Probe(val ok: Boolean, val detail: String)

    fun allowedHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase(Locale.US).orEmpty()
        if (h.isEmpty() || '.' !in h) return false
        return ALLOWED_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    fun newSecret(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return buildString(24) { repeat(24) { append(chars[rnd.nextInt(chars.length)]) } }
    }

    fun normalize(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.length > 500) return null
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        if (!u.scheme.equals("https", ignoreCase = true)) return null
        if (!u.userInfo.isNullOrEmpty()) return null
        val host = u.host?.trim()?.lowercase(Locale.US) ?: return null
        if (host.isEmpty() || host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")) {
            return null
        }
        if (u.port != -1 && u.port != 443) return null
        val path = (u.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/")
        if (path.contains("..") || path.contains('\\')) return null
        val secret = queryMap(u.rawQuery)["secret"]?.trim().orEmpty()
        if (secret.isEmpty() || secret.length > 200 || secret.any { it.isWhitespace() || it == '"' || it == '\'' }) {
            return null
        }
        val kept = queryMap(u.rawQuery).filterKeys { it != "url" && it != "health" }
        val query = kept.entries.joinToString("&") { "${it.key}=${it.value}" }
        return "https://$host$path?$query"
    }

    fun wrap(targetUrl: String, relayUrl: String?): String? {
        val relay = normalize(relayUrl) ?: return null
        val target = runCatching { URI(targetUrl.trim()) }.getOrNull() ?: return null
        if (!target.scheme.equals("https", ignoreCase = true)) return null
        if (!target.userInfo.isNullOrEmpty()) return null
        if (target.port != -1 && target.port != 443) return null
        val host = target.host?.lowercase(Locale.US) ?: return null
        if (!allowedHost(host)) return null
        val relayHost = runCatching { URI(relay).host?.lowercase(Locale.US) }.getOrNull() ?: return null
        if (host == relayHost) return null
        val encoded = URLEncoder.encode(target.toString(), "UTF-8")
        val sep = if (relay.contains('?')) '&' else '?'
        return "$relay${sep}url=$encoded"
    }

    fun healthUrl(relayUrl: String?): String? {
        val relay = normalize(relayUrl) ?: return null
        val sep = if (relay.contains('?')) '&' else '?'
        return "$relay${sep}health=1"
    }

    fun parseHealth(status: Int, body: String): Probe {
        if (status != 200) return Probe(false, "HTTP $status")
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.length > 8_000) return Probe(false, "empty")
        val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return Probe(false, "not json")
        if (!obj.optBoolean("ready", false)) return Probe(false, "not ready")
        return Probe(true, "ready")
    }

    fun workerScript(secret: String): String {
        val s = secret.trim()
        require(s.matches(Regex("[a-zA-Z0-9]{8,64}"))) { "secret" }
        return WORKER_JS.replace("const SECRET = \"\";", "const SECRET = \"$s\";")
    }

    fun redact(url: String): String {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return "(invalid)"
        val host = u.host ?: return "(invalid)"
        val path = u.rawPath.orEmpty().ifEmpty { "/" }
        return "https://$host$path?secret=…"
    }

    private fun queryMap(raw: String?): Map<String, String> {
        val q = raw.orEmpty()
        if (q.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        q.split('&').forEach { part ->
            if (part.isEmpty()) return@forEach
            val eq = part.indexOf('=')
            val k = if (eq < 0) part else part.substring(0, eq)
            val v = if (eq < 0) "" else part.substring(eq + 1)
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    /**
     * User deploys this as a Cloudflare Worker. Control-plane only (Mozilla/Firefox hosts).
     * Requires SECRET. Health: GET ?secret=&health=1 → {"relay":"cat-fox","version":1,"ready":true}
     */
    const val WORKER_JS = """
// Cat Fox Mozilla relay — control-plane only. VPN traffic does not go through this Worker.
const SECRET = "";
const ALLOWED = ["mozilla.org", "mozilla.com", "firefox.com"];
const DROP = ["host", "connection", "content-length", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "forwarded", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "x-real-ip", "true-client-ip"];

function fail(status, why) {
  return new Response(why, { status, headers: { "X-Relay-Error": why, "Content-Type": "text/plain" } });
}

function health() {
  return new Response(JSON.stringify({ relay: "cat-fox", version: 1, ready: true }), {
    status: 200,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

export default {
  async fetch(request, env = {}) {
    const here = new URL(request.url);
    const probe = here.searchParams.get("health") === "1";
    const browser = !here.searchParams.has("url") && !probe;
    const secret = env.SECRET || SECRET;
    if (!secret) return fail(503, "no secret is set in the relay");
    if (here.searchParams.get("secret") !== secret) return fail(403, "wrong secret");
    if (probe) {
      if (request.method !== "GET") return fail(405, "health requires GET");
      return health();
    }
    if (browser) return new Response("Cat Fox relay ready", { status: 200, headers: { "Content-Type": "text/plain; charset=utf-8" } });
    let target;
    try { target = new URL(here.searchParams.get("url") || ""); } catch { return fail(400, "url must be an https URL"); }
    if (target.protocol !== "https:") return fail(400, "url must be an https URL");
    if (target.username || target.password || target.hash || (target.port && target.port !== "443")) {
      return fail(400, "target must not contain credentials, a fragment or a non-HTTPS port");
    }
    const host = target.hostname.toLowerCase();
    if (!ALLOWED.some((domain) => host === domain || host.endsWith("." + domain))) {
      return fail(403, "host not allowed");
    }
    const headers = new Headers();
    for (const [name, value] of request.headers) {
      const lower = name.toLowerCase();
      if (DROP.includes(lower) || lower.startsWith("cf-")) continue;
      headers.set(name, value);
    }
    const hasBody = request.method !== "GET" && request.method !== "HEAD";
    const body = hasBody ? await request.arrayBuffer() : undefined;
    try {
      const upstream = await fetch(target, { method: request.method, headers, body, redirect: "manual" });
      const responseHeaders = new Headers(upstream.headers);
      responseHeaders.set("X-Cat-Fox-Relay", "1");
      return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: responseHeaders });
    } catch {
      return fail(502, "upstream request failed");
    }
  },
};
"""
}

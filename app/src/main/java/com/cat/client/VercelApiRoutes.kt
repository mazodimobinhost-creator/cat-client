package com.cat.client

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * beta247 — how the app reaches api.vercel.com for the token build of a Cat panel on Vercel.
 *
 * On many Iranian lines api.vercel.com is filtered or refuses Iranian IPs, so a single direct request
 * («اصلا نمیشه با توکن بیارم بالا») fails before anything is created. The client now tries, in order:
 *  1. Direct (when Cat is connected this already goes through the tunnel);
 *  2. Through Cat's running core (local mixed port) — covers engines that keep the app itself outside the tunnel;
 *  3. Through the owner's own Cloudflare Cat panel (panel 6.82.0+ `/vrelay/<UUID>/…`: owner-only, fixed host
 *     api.vercel.com, the token passes through and is never stored).
 * The first route that answers sticks for the rest of the build. Pure (no Android) so it is unit-tested.
 */
internal enum class VercelRouteKind { Direct, Tunnel, Panel }

internal data class VercelRoute(
    val kind: VercelRouteKind,
    val base: String,
    val proxyPort: Int = 0,
    val panelHost: String = "",
) {
    fun label(fa: Boolean): String = when (kind) {
        VercelRouteKind.Direct -> if (fa) "مستقیم" else "direct"
        VercelRouteKind.Tunnel -> if (fa) "از داخل اتصال Cat" else "through Cat's connection"
        VercelRouteKind.Panel -> if (fa) "از پنل Cloudflare شما ($panelHost)" else "through your Cloudflare panel ($panelHost)"
    }
}

/** Every route failed: what each one answered, in order, for a clear message the user can copy. */
internal class VercelRouteException(val attempts: List<Pair<VercelRoute, String>>) :
    IOException("api.vercel.com unreachable: " + attempts.joinToString(" | ") { it.first.kind.name + ": " + it.second })

/** The answer came from something in the way (a filter page, a panel without the relay), not from Vercel. */
internal class VercelForeignAnswerException(message: String) : IOException(message)

internal object VercelRoutePlanner {
    const val API_BASE = "https://api.vercel.com"
    const val RELAY_MARK = "x-cat-vrelay"
    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val HOST_RE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    fun direct() = VercelRoute(VercelRouteKind.Direct, API_BASE)

    /** A pasted token often carries «Bearer », quotes, spaces or a line break: keep only the token itself. */
    fun cleanToken(raw: String): String {
        var t = raw.trim().trim('"', '\'', '`', '“', '”').trim()
        if (t.startsWith("Bearer ", ignoreCase = true)) t = t.substring(7)
        return t.filterNot { it.isWhitespace() || it == '\u200c' || it == '\u200f' || it == '\u200e' }
    }

    /** `https://name.sub.workers.dev[/panel]` + the panel's master UUID → its relay base, or null when unusable. */
    fun panelRoute(workerUrl: String, uuid: String): VercelRoute? {
        val url = workerUrl.trim()
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val host = url.substring(8).substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        val id = uuid.trim().lowercase()
        if (!HOST_RE.matches(host) || !UUID_RE.matches(id)) return null
        return VercelRoute(VercelRouteKind.Panel, "https://$host/vrelay/$id", panelHost = host)
    }

    private val SUB_LINK_RE = Regex("https://([A-Za-z0-9.-]+)(?:/[^\\s/?#]+)*/sub/([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})")
    private val VLESS_RE = Regex("vless://([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})@[^\\s?#]+\\?([^\\s#]+)")

    /**
     * beta248: Cloudflare panels the app did NOT build (pasted into the dashboard by hand) are still known from the
     * phone's saved subscriptions: `https://host/…/sub/<uuid>` links, and `vless://<uuid>@…?host=x.workers.dev` configs.
     * The panel's relay accepts its master UUID or an enabled user's UUID (6.82.1+). Sub links come first.
     */
    fun panelsFromSubscriptions(inputs: List<String>): List<Pair<String, String>> {
        val subs = LinkedHashMap<String, String>()
        val vless = LinkedHashMap<String, String>()
        for (text in inputs) {
            SUB_LINK_RE.findAll(text).forEach { m ->
                val host = m.groupValues[1].lowercase()
                if (HOST_RE.matches(host)) subs.putIfAbsent(host, m.groupValues[2].lowercase())
            }
            VLESS_RE.findAll(text).forEach { m ->
                val params = m.groupValues[2].split('&').associate {
                    it.substringBefore('=').lowercase() to runCatching { java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                }
                val host = (params["host"].orEmpty().ifBlank { params["sni"].orEmpty() }).lowercase().substringBefore(':')
                if (host.endsWith(".workers.dev") && HOST_RE.matches(host)) vless.putIfAbsent(host, m.groupValues[1].lowercase())
            }
        }
        vless.forEach { (h, id) -> subs.putIfAbsent(h, id) }
        return subs.map { (host, id) -> "https://$host" to id }
    }

    fun routes(tunnelPort: Int?, panels: List<Pair<String, String>>, maxPanels: Int = 3): List<VercelRoute> {
        val out = mutableListOf(direct())
        if (tunnelPort != null && tunnelPort in 1..65535) out += VercelRoute(VercelRouteKind.Tunnel, API_BASE, proxyPort = tunnelPort)
        panels.mapNotNull { (url, uuid) -> panelRoute(url, uuid) }
            .distinctBy { it.panelHost }
            // *.vercel.app is the name that is filtered most; a workers.dev / own-domain panel goes first.
            .sortedBy { if (it.panelHost.endsWith(".vercel.app")) 1 else 0 }
            .take(maxPanels)
            .forEach { out += it }
        return out
    }

    /**
     * True when an HTTP answer did not come from Vercel: every real api.vercel.com answer (errors included) is JSON,
     * and every relay answer carries [RELAY_MARK]. A panel older than 6.82.0 answers /vrelay with its own 404 page.
     */
    fun isForeignAnswer(kind: VercelRouteKind, code: Int, relayMark: String?, contentType: String?, body: String): Boolean {
        if (kind == VercelRouteKind.Panel && relayMark != "1") return true
        if (kind == VercelRouteKind.Panel && code == 502 && !body.trimStart().startsWith("{")) return true
        if (code in 200..299) return false
        if (code == 451) return true
        val json = body.trimStart().startsWith("{") || body.trimStart().startsWith("[") || contentType?.contains("json", true) == true
        return !json
    }

    /**
     * Network-level failures move on to the next route. A POST whose request may already have reached Vercel
     * (read timeout after writing) is NOT replayed elsewhere — that could create a second project.
     */
    fun failover(error: Throwable, method: String): Boolean {
        if (error is VercelApiException) return false
        if (error is VercelForeignAnswerException) return true
        if (error is SocketTimeoutException && !method.equals("GET", true) &&
            error.message.orEmpty().contains("connect", true).not()
        ) return false
        return error is IOException
    }

    /**
     * beta248: the «no route» report says WHICH route was missing or why it failed, in plain words, so the next step
     * is obvious (connect first / update that exact panel / add the panel's subscription link).
     */
    fun routeReport(attempts: List<Pair<VercelRoute, String>>, fa: Boolean): String {
        val lines = mutableListOf<String>()
        attempts.forEach { (route, why) ->
            val old = route.kind == VercelRouteKind.Panel && why.startsWith("VercelForeignAnswerException")
            lines += "• " + route.label(fa) + " → " + why + if (old) {
                if (fa) "\n   ↳ این پنل هنوز رلهٔ Vercel ندارد (نسخهٔ قدیمی): آن را به ۶.۸۲.۱+ به‌روز کنید (داخل پنل: «بررسی بروزرسانی» ← دانلود worker.js ← جایگزینی در Cloudflare؛ یا اگر با برنامه ساخته شده، از خود برنامه)." else "\n   ↳ This panel has no Vercel relay yet (old version): update it to 6.82.1+ (in the panel: «check for updates» → download worker.js → replace it in Cloudflare; or from the app if the app built it)."
            } else ""
        }
        if (attempts.none { it.first.kind == VercelRouteKind.Tunnel }) {
            lines += if (fa) "• تونل Cat → امتحان نشد: برنامه وصل نبود (این راه فقط وقتی برنامه به یک کانفیگ وصل است وجود دارد)." else "• Cat tunnel → not tried: the app was not connected (this route exists only while connected)."
        }
        if (attempts.none { it.first.kind == VercelRouteKind.Panel }) {
            lines += if (fa) "• پنل Cloudflare → هیچ پنلی پیدا نشد: نه پنلی که برنامه ساخته، نه لینک ساب https://…/sub/… در اشتراک‌ها." else "• Cloudflare panel → none found: no panel built by the app and no https://…/sub/… link among the subscriptions."
        }
        return lines.joinToString("\n")
    }

    /** Plain words for one failed attempt (shown in the copyable report). */
    fun describe(error: Throwable): String {
        val name = error.javaClass.simpleName
        val msg = error.message.orEmpty().take(140)
        return if (msg.isBlank()) name else "$name: $msg"
    }
}

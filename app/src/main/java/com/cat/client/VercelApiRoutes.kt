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

    /** Plain words for one failed attempt (shown in the copyable report). */
    fun describe(error: Throwable): String {
        val name = error.javaClass.simpleName
        val msg = error.message.orEmpty().take(140)
        return if (msg.isBlank()) name else "$name: $msg"
    }
}

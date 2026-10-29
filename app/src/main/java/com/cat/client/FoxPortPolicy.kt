// beta224: Cat Fox VPN — which destination ports Mozilla's proxy carries, and whether a CONNECT error is the destination's or the node's.
package com.cat.client

import java.util.Locale

/**
 * Mozilla's proxy (the Fastly MASQUE nodes behind Cat Fox VPN) only opens TCP to the web ports 80, 443, 8080 and
 * 8443; a CONNECT to any other port answers 502 (documented by an independent open-source client of the same
 * service — ideas only, no code taken). The phone cannot reach mail, DoT 853, push 5228, XMPP 5222 … through it.
 *
 * Before beta224 the bridge asked Mozilla anyway, and every 502 for a dead website was blamed on the node: the next
 * node was dialled, the remembered good node was forgotten and all following connections paid for it. Now:
 *  - a non-web port is refused locally at once (apps that have a 443 fallback switch to it immediately instead of
 *    waiting on Mozilla), except `github.com:22`, which goes to GitHub's SSH server on 443;
 *  - 400/502/504 for one destination is the destination's problem and stays with it; only when several different
 *    destinations fail that way within half a minute is the node suspected and the next one tried.
 *
 * Pure Kotlin (no Android) — unit tested on the JVM.
 */
internal object FoxPortPolicy {
    val WEB_PORTS: Set<Int> = setOf(80, 443, 8080, 8443)
    const val WEB_PORTS_TEXT = "80, 443, 8080, 8443"

    sealed class Route {
        data class Tunnel(val host: String, val port: Int) : Route()
        data class Refuse(val reason: String) : Route()
    }

    fun route(host: String, port: Int): Route {
        val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (port == 22 && h == "github.com") return Route.Tunnel("ssh.github.com", 443)
        if (port in WEB_PORTS) return Route.Tunnel(host, port)
        return Route.Refuse("Cat Fox VPN (Mozilla) only carries web ports $WEB_PORTS_TEXT — $host:$port is not carried")
    }

    /** 502 / 504: the destination did not answer; 400: the authority was not accepted. Not the node's fault. */
    fun isTargetStatus(status: Int): Boolean = status == 400 || status == 502 || status == 504

    /** Different destinations that failed with a target status lately; enough of them make the node suspect. */
    class TargetFailures(private val windowMs: Long = 30_000L, private val distinct: Int = 3) {
        private val seen = LinkedHashMap<String, Long>()

        @Synchronized
        fun success() = seen.clear()

        /** Records one failure; `true` = so many different destinations failed lately that the node is suspect. */
        @Synchronized
        fun failed(authority: String, now: Long): Boolean {
            val it = seen.entries.iterator()
            while (it.hasNext()) if (now - it.next().value > windowMs) it.remove()
            seen[authority] = now
            return seen.size >= distinct
        }
    }
}

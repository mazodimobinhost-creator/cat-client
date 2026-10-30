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

/**
 * beta237: what the Fox bridge does with an HTTP-proxy request line. `CONNECT host:port` opens a tunnel; an
 * absolute-form request (`GET http://host[:port]/path`) is sent on to the host (port 80 unless given) in origin form
 * (`GET /path`), without the hop-by-hop proxy headers. Pure Kotlin — unit tested on the JVM.
 */
internal object FoxHttpProxyRequest {
    data class Plan(val host: String, val port: Int, val path: String, val isConnect: Boolean)

    fun parse(method: String, target: String): Plan? {
        val connect = method.equals("CONNECT", ignoreCase = true)
        val t = target.trim()
        if (t.isEmpty()) return null
        val secure = t.startsWith("https://", ignoreCase = true)
        val rest = when {
            t.startsWith("http://", ignoreCase = true) -> t.substring(7)
            secure -> t.substring(8)
            else -> t
        }
        val authority = rest.substringBefore('/').substringBefore('?')
        if (authority.isEmpty()) return null
        val path = if (connect) "" else rest.substring(authority.length).ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" }
        val defaultPort = if (connect || secure) 443 else 80
        val (host, port) = splitHostPort(authority, defaultPort) ?: return null
        return Plan(host, port, path, connect)
    }

    private fun splitHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            val host = authority.substring(1, end)
            val port = authority.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return host to port
        }
        val colons = authority.count { it == ':' }
        if (colons == 1) {
            val host = authority.substringBefore(':')
            val port = authority.substringAfter(':').toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) return null
            return host to port
        }
        return authority to defaultPort // a bare IPv6 address without brackets, or a host without a port
    }

    private val HOP_BY_HOP = setOf("proxy-connection", "proxy-authorization", "proxy-authenticate", "keep-alive")

    fun originRequest(method: String, path: String, version: String, headerLines: List<String>): ByteArray {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(' ').append(version.ifBlank { "HTTP/1.1" }).append("\r\n")
        headerLines.forEach { line ->
            val name = line.substringBefore(':').trim().lowercase(Locale.US)
            if (name !in HOP_BY_HOP) sb.append(line).append("\r\n")
        }
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}

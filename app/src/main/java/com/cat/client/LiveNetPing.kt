package com.cat.client

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TCP connect time on THIS network (protected socket, so a running VPN does not fake the RTT).
 * Used when the user is about to add a country/server — the number next to the name is that ping.
 * Ports are never rewritten: the listed `host:port` is the one that is timed.
 */
internal data class SharePing(val link: String, val host: String?, val port: Int, val rttMs: Long?)

internal object LiveNetPing {
    fun tcpMs(host: String, port: Int, timeoutMs: Int = 1_200): Long? {
        if (host.isBlank() || port !in 1..65535 || isLoopback(host)) return null
        return runCatching {
            val t0 = System.nanoTime()
            Socket().use { sock ->
                protectOutboundSocket(sock)
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(host, port), timeoutMs.coerceAtLeast(200))
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    /** `scheme://user:pass@host:port/...` → host and the listed port (never rewritten). No port → null. */
    fun hostPortFromShare(link: String): Pair<String, Int>? {
        val trimmed = link.trim().substringBefore('#').substringAfter("://", missingDelimiterValue = "")
        if (trimmed.isEmpty()) return null
        val authority = trimmed.substringAfter('@', trimmed).substringBefore('/').substringBefore('?')
        val host: String
        val portText: String
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            host = authority.substring(1, end)
            portText = authority.substring(end + 1).removePrefix(":")
        } else {
            val c = authority.lastIndexOf(':')
            if (c <= 0) return null
            host = authority.substring(0, c)
            portText = authority.substring(c + 1)
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank()) return null
        return host to port
    }

    fun isLoopback(host: String): Boolean {
        val h = host.trim().lowercase()
        return h == "127.0.0.1" || h == "::1" || h == "localhost" || h.endsWith(".localhost")
    }

    /**
     * Times every share link on this network, then reachable-first by RTT.
     * Closed / unparsable rows stay, with `rttMs = null` — never a made-up delay.
     */
    fun rankShares(links: List<String>, timeoutMs: Int = 1_200, parallelism: Int = 6): List<SharePing> {
        if (links.isEmpty()) return emptyList()
        val wait = timeoutMs.toLong().coerceAtLeast(200L) + 1_800L
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, 12))
        return try {
            val futs = links.map { link ->
                pool.submit<SharePing> {
                    val hp = hostPortFromShare(link)
                    val ms = hp?.let { tcpMs(it.first, it.second, timeoutMs) }
                    SharePing(link, hp?.first, hp?.second ?: 0, ms)
                }
            }
            val hits = futs.mapIndexed { i, fut ->
                runCatching { fut.get(wait, TimeUnit.MILLISECONDS) }.getOrElse {
                    val hp = hostPortFromShare(links[i])
                    SharePing(links[i], hp?.first, hp?.second ?: 0, null)
                }
            }
            hits.sortedWith(compareBy({ it.rttMs == null }, { it.rttMs ?: Long.MAX_VALUE }))
        } finally {
            pool.shutdownNow()
        }
    }

    fun labelFa(ms: Long?): String = if (ms != null) "🟢 ${ms} ms روی خط شما" else "⚪ روی این خط بسته"
    fun labelEn(ms: Long?): String = if (ms != null) "🟢 ${ms} ms on your line" else "⚪ closed on this line"
    fun waitingFa(): String = "⏳ پینگ…"
    fun waitingEn(): String = "⏳ ping…"
}

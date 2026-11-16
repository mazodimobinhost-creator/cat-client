package com.cat.client

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connect time on THIS network (protected socket, so a running VPN does not fake the RTT).
 * Used when the user is about to add a country/server — the number next to the name is that ping.
 * Ports are never rewritten: the listed `host:port` is the one that is timed.
 *
 * TCP shares (vmess/vless/https/…) use a TCP connect.
 * UDP shares (awg / WireGuard / Hysteria / TUIC) cannot be timed with TCP — those ports
 * stay closed on TCP and the list used to say «closed» for every Amnezia row. They get a
 * real UDP wait-for-reply, then ICMP to the host if the UDP port stays silent.
 */
internal data class SharePing(val link: String, val host: String?, val port: Int, val rttMs: Long?)

internal object LiveNetPing {
    private val UDP_SCHEMES = setOf(
        "awg", "amneziawg", "wireguard", "hysteria", "hysteria2", "hy2",
        "tuic", "juicity", "wireguard-amnezia",
    )
    private val IPV4 = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
    private val PING_TIME = Regex("""time[=<]\s*([0-9.]+)""", RegexOption.IGNORE_CASE)

    fun isUdpShare(link: String): Boolean {
        val scheme = link.trim().substringBefore("://").lowercase()
        return scheme in UDP_SCHEMES
    }

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

    /**
     * UDP ping that only counts when something answers (never the time of a send).
     * WireGuard/Amnezia get a handshake-initiation-sized datagram (type 1); any reply is RTT.
     */
    fun udpReplyMs(host: String, port: Int, timeoutMs: Int = 1_200): Long? {
        if (host.isBlank() || port !in 1..65535 || isLoopback(host)) return null
        return runCatching {
            val t0 = System.nanoTime()
            DatagramSocket().use { sock ->
                protectOutboundDatagramSocket(sock)
                sock.soTimeout = timeoutMs.coerceAtLeast(200)
                val addr = InetAddress.getByName(host)
                val payload = ByteArray(148).also { buf ->
                    SecureRandom().nextBytes(buf)
                    buf[0] = 1 // WireGuard handshake initiation
                }
                sock.send(DatagramPacket(payload, payload.size, addr, port))
                val buf = ByteArray(1500)
                sock.receive(DatagramPacket(buf, buf.size))
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    /** IPv4 ICMP echo via the system ping. Null when the binary is missing or the host does not answer. */
    fun icmpMs(host: String, timeoutMs: Int = 1_200): Long? {
        if (!IPV4.matches(host) || isLoopback(host)) return null
        return runCatching {
            val t0 = System.nanoTime()
            val waitSec = ((timeoutMs.coerceAtLeast(200) + 999) / 1000).coerceIn(1, 3).toString()
            val pb = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", waitSec, host)
                .redirectErrorStream(true)
                .start()
            val finished = pb.waitFor(timeoutMs.toLong() + 400L, TimeUnit.MILLISECONDS)
            if (!finished) {
                pb.destroyForcibly()
                return null
            }
            val out = pb.inputStream.bufferedReader().readText()
            if (pb.exitValue() != 0) return null
            val parsed = PING_TIME.find(out)?.groupValues?.get(1)?.toDouble()?.toLong()
            parsed?.coerceAtLeast(1L) ?: ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    fun pingMs(host: String, port: Int, udp: Boolean, timeoutMs: Int = 1_200): Long? {
        if (udp) return udpReplyMs(host, port, timeoutMs) ?: icmpMs(host, timeoutMs)
        return tcpMs(host, port, timeoutMs)
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
     * [onEach] is called from the worker as each row finishes (tried, total).
     */
    fun rankShares(
        links: List<String>,
        timeoutMs: Int = 1_200,
        parallelism: Int = 6,
        onEach: ((SharePing, Int, Int) -> Unit)? = null,
    ): List<SharePing> {
        if (links.isEmpty()) return emptyList()
        val wait = timeoutMs.toLong().coerceAtLeast(200L) + 1_800L
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, 12))
        val done = AtomicInteger(0)
        return try {
            val futs = links.map { link ->
                pool.submit<SharePing> {
                    val hp = hostPortFromShare(link)
                    val ms = hp?.let { pingMs(it.first, it.second, isUdpShare(link), timeoutMs) }
                    val hit = SharePing(link, hp?.first, hp?.second ?: 0, ms)
                    onEach?.invoke(hit, done.incrementAndGet(), links.size)
                    hit
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

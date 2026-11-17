package com.cat.client

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Which country a proxy actually exits from — MEASURED through the proxy itself.
 *
 * WHY THIS EXISTS (beta100): every database resolver (ipwho.is / api.country.is /
 * ipapi.co / the panel worker's `/api/geo`) is reached from the PHONE's own network.
 * On the filtered networks this app is used from, that whole class of host answers
 * nothing, so a different endpoint order cannot help — the previous fix changed
 * exactly that and the chips still said «🌐 نامشخص». A *verified* proxy, however, is
 * by definition a working way OUT: opening a TLS connection to `www.cloudflare.com`
 * THROUGH it and reading `/cdn-cgi/trace` returns `loc=<ISO-3166>`, the country of
 * the network the proxy exits from — from Cloudflare's own edge, no geo API involved.
 *
 * The same trace call is the app's proven path already (the IP scanner reads `colo=`
 * from it), so this trusts nothing new. Hosts that answer nothing stay absent from
 * the result and the caller keeps them on «نامشخص», never guessed.
 */
object ProxyEgressCountry {
    private const val TRACE_HOST = "www.cloudflare.com"
    private const val TRACE_PORT = 443
    private const val PARALLELISM = 6
    private const val TOTAL_BUDGET_MS = 25_000L
    private const val MAX_BODY = 8_192

    /** One verified proxy. [proto] is `ProxyScannerPage.Proto.label`. */
    data class Endpoint(val host: String, val port: Int, val proto: String)

    /** `host -> ISO-3166 alpha-2`, only for the endpoints that answered. */
    fun resolve(endpoints: List<Endpoint>, timeoutMs: Int = 6_000): Map<String, String> {
        val pending = endpoints
            .filter { it.host.isNotBlank() && it.port in 1..65535 }
            .distinctBy { it.host to it.port }
        if (pending.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        val started = System.currentTimeMillis()
        val pool = runCatching { Executors.newFixedThreadPool(PARALLELISM) }.getOrNull() ?: return emptyMap()
        try {
            val futures = pending.map { endpoint ->
                pool.submit {
                    if (System.currentTimeMillis() - started > TOTAL_BUDGET_MS) return@submit
                    val cc = runCatching { traceThrough(endpoint, timeoutMs) }.getOrNull() ?: return@submit
                    synchronized(out) { out[endpoint.host] = cc }
                }
            }
            futures.forEach { runCatching { it.get(TOTAL_BUDGET_MS + 2_000L, TimeUnit.MILLISECONDS) } }
        } finally {
            runCatching { pool.shutdownNow() }
        }
        return out
    }

    /** Tunnel → TLS → `GET /cdn-cgi/trace` → `loc=`. Null on any failure. */
    private fun traceThrough(endpoint: Endpoint, timeoutMs: Int): String? {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            socket.soTimeout = timeoutMs
            val opened = when (endpoint.proto.uppercase(Locale.ROOT)) {
                "SOCKS4" -> socks4Connect(socket)
                "HTTP", "HTTPS" -> httpConnect(socket)
                else -> socks5Connect(socket)
            }
            if (!opened) return null
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, TRACE_HOST, TRACE_PORT, true) as SSLSocket
            try {
                tls.soTimeout = timeoutMs
                runCatching {
                    val params = tls.sslParameters
                    params.serverNames = listOf(SNIHostName(TRACE_HOST))
                    tls.sslParameters = params
                }
                tls.startHandshake()
                val request = "GET /cdn-cgi/trace HTTP/1.1\r\n" +
                    "Host: $TRACE_HOST\r\n" +
                    "User-Agent: CatClient\r\n" +
                    "Accept: text/plain\r\n" +
                    "Connection: close\r\n\r\n"
                tls.outputStream.let { out -> out.write(request.toByteArray()); out.flush() }
                return countryFromTrace(readBody(tls.inputStream))
            } finally {
                runCatching { tls.close() }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    /** SOCKS5 (no auth) → CONNECT `www.cloudflare.com:443` by domain name. */
    private fun socks5Connect(socket: Socket): Boolean {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        val greeting = readFully(input, 2) ?: return false
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) return false
        val hostBytes = TRACE_HOST.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(7 + hostBytes.size)
        request[0] = 0x05; request[1] = 0x01; request[2] = 0x00; request[3] = 0x03
        request[4] = hostBytes.size.toByte()
        hostBytes.copyInto(request, 5)
        request[5 + hostBytes.size] = ((TRACE_PORT shr 8) and 0xFF).toByte()
        request[6 + hostBytes.size] = (TRACE_PORT and 0xFF).toByte()
        out.write(request); out.flush()
        val head = readFully(input, 4) ?: return false
        if (head[1] != 0x00.toByte()) return false
        val skip = when (head[3].toInt() and 0xFF) {
            0x01 -> 4
            0x03 -> (readFully(input, 1)?.get(0)?.toInt()?.and(0xFF)) ?: return false
            0x04 -> 16
            else -> return false
        }
        return readFully(input, skip + 2) != null
    }

    /** SOCKS4a → CONNECT `www.cloudflare.com:443` (domain name form). */
    private fun socks4Connect(socket: Socket): Boolean {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        val hostBytes = TRACE_HOST.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(9 + hostBytes.size)
        request[0] = 0x04; request[1] = 0x01
        request[2] = ((TRACE_PORT shr 8) and 0xFF).toByte()
        request[3] = (TRACE_PORT and 0xFF).toByte()
        // 0.0.0.1 is the SOCKS4a marker: the real target follows as a name.
        request[4] = 0; request[5] = 0; request[6] = 0; request[7] = 1
        hostBytes.copyInto(request, 8)
        request[8 + hostBytes.size] = 0
        out.write(request); out.flush()
        val reply = readFully(input, 8) ?: return false
        return reply[1] == 0x5A.toByte()
    }

    /** HTTP proxy → `CONNECT www.cloudflare.com:443`. */
    private fun httpConnect(socket: Socket): Boolean {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(
            ("CONNECT $TRACE_HOST:$TRACE_PORT HTTP/1.1\r\n" +
                "Host: $TRACE_HOST:$TRACE_PORT\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "User-Agent: CatClient\r\n\r\n").toByteArray(),
        )
        out.flush()
        val head = StringBuilder()
        while (head.length < 512) {
            val ch = input.read()
            if (ch < 0) break
            head.append(ch.toChar())
            if (head.endsWith("\r\n\r\n")) break
        }
        return Regex("^HTTP/1\\.[01] 200").containsMatchIn(head.toString())
    }

    /**
     * Reads until `loc=` shows up, the peer closes, or the buffer fills. A proxy that
     * keeps the socket open after the body must not cost a full timeout per host.
     */
    private fun readBody(stream: InputStream): String {
        val buf = ByteArray(MAX_BODY)
        var total = 0
        while (total < buf.size) {
            val n = runCatching { stream.read(buf, total, buf.size - total) }.getOrDefault(-1)
            if (n <= 0) break
            total += n
            val from = maxOf(0, total - 128)
            if (String(buf, from, total - from, Charsets.ISO_8859_1).contains("loc=")) break
        }
        return String(buf, 0, total, Charsets.ISO_8859_1)
    }

    private fun countryFromTrace(body: String): String? {
        val loc = body.lineSequence()
            .firstOrNull { it.startsWith("loc=") }
            ?.removePrefix("loc=")
            ?.trim()
            .orEmpty()
        val cc = loc.uppercase(Locale.ROOT).take(2)
        return cc.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
    }

    private fun readFully(stream: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = runCatching { stream.read(buf, read, count - read) }.getOrDefault(-1)
            if (n <= 0) return null
            read += n
        }
        return buf
    }
}

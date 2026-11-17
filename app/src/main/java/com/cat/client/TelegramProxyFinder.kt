package com.cat.client

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * beta239 — Telegram proxy (MTProto / MTProxy) finder.
 *
 * Pulls a public, frequently re-verified MTProxy list (MIT-licensed data set, published as plain
 * `tg://proxy?…` lines; fetched through jsDelivr first because raw GitHub is often filtered) or takes the
 * user's own pasted `tg://proxy` / `t.me/proxy` links, then tests every proxy FROM THIS LINE:
 *
 *  - FakeTLS secrets (`ee…`, the kind that survives DPI best): a real FakeTLS handshake. The ClientHello's
 *    random field is HMAC-SHA256(secret, hello-with-zero-random) XOR the Unix time, and only a genuine proxy
 *    holding the same secret can answer with a ServerHello whose random is
 *    HMAC-SHA256(secret, client-random ‖ response-with-zero-random). A match proves the proxy is alive AND that
 *    this network's DPI let the disguised TLS through → [Verdict.VERIFIED].
 *  - `dd…` / classic secrets have no verifiable greeting, so only a TCP open is measured → [Verdict.TCP_ONLY].
 *
 * Nothing is invented: a proxy is only listed as working when that test passed on the user's network.
 */
internal object TelegramProxyFinder {
    enum class Kind { FAKE_TLS, SECURE, CLASSIC }
    enum class Verdict { VERIFIED, TCP_ONLY, FAILED }

    data class Proxy(val server: String, val port: Int, val secret: String) {
        val kind: Kind get() = kindOf(secret)
        val key: String get() = "${server.lowercase()}:$port:${secret.lowercase()}"
    }

    data class Result(val proxy: Proxy, val verdict: Verdict, val latencyMs: Long, val detail: String)

    /** Lists inside the public data set, best-for-Iran first (FakeTLS). */
    val LIST_PATHS = listOf("faketls/tg.txt", "fast/tg.txt", "secure/tg.txt")
    val MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/0xRadikal/Free-Telegram-Proxy@main/",
        "https://fastly.jsdelivr.net/gh/0xRadikal/Free-Telegram-Proxy@main/",
        "https://raw.githubusercontent.com/0xRadikal/Free-Telegram-Proxy/main/",
    )
    const val MAX_PROXIES = 300
    private const val DIGEST_POS = 11
    private const val HELLO_SIZE = 517

    private val linkRegex = Regex("""(?i)(?:tg://proxy|https?://(?:www\.)?(?:t\.me|telegram\.me)/proxy)\?[^\s"'<>]+""")

    /* ------------------------------------------------------------------ parsing (pure) */

    fun parseLinks(text: String): List<Proxy> {
        val seen = LinkedHashMap<String, Proxy>()
        linkRegex.findAll(text).forEach { m ->
            val query = m.value.substringAfter('?').replace("&amp;", "&")
            val params = query.split('&').mapNotNull { part ->
                val k = part.substringBefore('=', "").lowercase()
                if (k.isEmpty()) null else k to runCatching { URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrDefault("")
            }.toMap()
            val server = params["server"]?.trim()?.trim('[', ']').orEmpty()
            val port = params["port"]?.trim()?.toIntOrNull()
            val secret = params["secret"]?.trim().orEmpty()
            if (server.isEmpty() || port == null || port !in 1..65535 || secret.isEmpty()) return@forEach
            if (server.any { it.isWhitespace() || it == '/' || it == '@' }) return@forEach
            if (secretBytes(secret) == null) return@forEach
            val p = Proxy(server, port, secret)
            seen.putIfAbsent(p.key, p)
        }
        return seen.values.toList()
    }

    /** Secret as bytes: hex (classic form) or base64/base64url (newer share links). */
    fun secretBytes(secret: String): ByteArray? {
        val s = secret.trim()
        if (s.length >= 32 && s.length % 2 == 0 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
        val b64 = runCatching { Base64.getUrlDecoder().decode(s.replace('+', '-').replace('/', '_').trimEnd('=')) }.getOrNull()
        return b64?.takeIf { it.size >= 16 }
    }

    fun kindOf(secret: String): Kind {
        val b = secretBytes(secret) ?: return Kind.CLASSIC
        return when {
            b.size > 17 && (b[0].toInt() and 0xff) == 0xee -> Kind.FAKE_TLS
            b.size == 17 && (b[0].toInt() and 0xff) == 0xdd -> Kind.SECURE
            else -> Kind.CLASSIC
        }
    }

    /** FakeTLS secret → 16-byte key + the TLS domain (SNI) it impersonates. */
    fun fakeTlsParts(secret: String): Pair<ByteArray, String>? {
        val b = secretBytes(secret) ?: return null
        if (b.size <= 17 || (b[0].toInt() and 0xff) != 0xee) return null
        val domain = String(b, 17, b.size - 17, Charsets.US_ASCII)
        if (domain.isEmpty() || domain.any { it.code !in 0x21..0x7e }) return null
        return b.copyOfRange(1, 17) to domain
    }

    fun tgLink(p: Proxy): String = "tg://proxy?server=${enc(p.server)}&port=${p.port}&secret=${enc(p.secret)}"
    fun shareLink(p: Proxy): String = "https://t.me/proxy?server=${enc(p.server)}&port=${p.port}&secret=${enc(p.secret)}"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /* ------------------------------------------------------------------ FakeTLS handshake (pure) */

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /**
     * Builds a 517-byte TLS 1.3-style ClientHello for [domain] and stamps the FakeTLS digest into its random.
     * Returns the full record and the 32-byte random that was sent (the server's HMAC is keyed on it).
     */
    fun buildClientHello(key: ByteArray, domain: String, nowSeconds: Long, rnd: java.util.Random = SecureRandom()): Pair<ByteArray, ByteArray> {
        fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        val ext = ByteArrayOutputStream()
        fun extension(type: Int, body: ByteArray) { ext.write(u16(type)); ext.write(u16(body.size)); ext.write(body) }
        val host = domain.toByteArray(Charsets.US_ASCII)
        extension(0x0000, u16(host.size + 3) + byteArrayOf(0) + u16(host.size) + host) // server_name
        extension(0x0017, ByteArray(0)) // extended_master_secret
        extension(0xff01, byteArrayOf(0)) // renegotiation_info
        extension(0x000a, u16(6) + u16(0x001d) + u16(0x0017) + u16(0x0018)) // supported_groups
        extension(0x000b, byteArrayOf(1, 0)) // ec_point_formats
        extension(0x0023, ByteArray(0)) // session_ticket
        val alpn = byteArrayOf(2) + "h2".toByteArray() + byteArrayOf(8) + "http/1.1".toByteArray()
        extension(0x0010, u16(alpn.size) + alpn)
        extension(0x0005, byteArrayOf(1, 0, 0, 0, 0)) // status_request
        val sigs = intArrayOf(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601)
        extension(0x000d, u16(sigs.size * 2) + sigs.fold(ByteArray(0)) { a, s -> a + u16(s) })
        extension(0x0033, u16(36) + u16(0x001d) + u16(32) + bytes(32)) // key_share (x25519)
        extension(0x002d, byteArrayOf(1, 1)) // psk_key_exchange_modes
        extension(0x002b, byteArrayOf(4, 0x03, 0x04, 0x03, 0x03)) // supported_versions
        val suites = intArrayOf(0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8, 0xc013, 0xc014, 0x009c, 0x009d, 0x002f, 0x0035)
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0x03, 0x03))
        body.write(ByteArray(32)) // random, stamped below
        body.write(32); body.write(bytes(32)) // session id
        body.write(u16(suites.size * 2)); suites.forEach { body.write(u16(it)) }
        body.write(byteArrayOf(1, 0)) // compression: null
        // padding (0x0015) so the whole record is HELLO_SIZE bytes, like real clients
        val fixed = 5 + 4 + body.size() + 2 + ext.size() + 4
        val pad = (HELLO_SIZE - fixed).coerceAtLeast(0)
        extension(0x0015, ByteArray(pad))
        val extBytes = ext.toByteArray()
        body.write(u16(extBytes.size)); body.write(extBytes)
        val hs = body.toByteArray()
        val handshake = byteArrayOf(0x01, (hs.size ushr 16).toByte(), (hs.size ushr 8).toByte(), hs.size.toByte()) + hs
        val record = byteArrayOf(0x16, 0x03, 0x01) + u16(handshake.size) + handshake
        val digest = hmac(key, record)
        val t = nowSeconds.toInt()
        for (i in 0 until 4) digest[28 + i] = (digest[28 + i].toInt() xor (t ushr (8 * i))).toByte()
        System.arraycopy(digest, 0, record, DIGEST_POS, 32)
        return record to digest
    }

    /** True when [response] (ServerHello + CCS + first app-data record) carries the HMAC only the real proxy can make. */
    fun verifyServerHello(key: ByteArray, clientRandom: ByteArray, response: ByteArray): Boolean {
        if (response.size < DIGEST_POS + 32 || response[0] != 0x16.toByte()) return false
        val got = response.copyOfRange(DIGEST_POS, DIGEST_POS + 32)
        val zeroed = response.copyOf().also { java.util.Arrays.fill(it, DIGEST_POS, DIGEST_POS + 32, 0) }
        return java.security.MessageDigest.isEqual(got, hmac(key, clientRandom, zeroed))
    }

    /* ------------------------------------------------------------------ network */

    private fun readRecord(input: DataInputStream, out: ByteArrayOutputStream, expectType: Int): Boolean {
        val header = ByteArray(5)
        input.readFully(header)
        if ((header[0].toInt() and 0xff) != expectType) return false
        val len = ((header[3].toInt() and 0xff) shl 8) or (header[4].toInt() and 0xff)
        if (len <= 0 || len > 18_432) return false
        val payload = ByteArray(len)
        input.readFully(payload)
        out.write(header); out.write(payload)
        return true
    }

    /** Tests one proxy from this network. [protect] keeps the socket outside the VPN tunnel. */
    fun probe(p: Proxy, timeoutMs: Int, protect: (Socket) -> Unit): Result {
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        return try {
            Socket().use { s ->
                protect(s)
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(p.server, p.port), timeoutMs)
                val connectMs = ms()
                val parts = fakeTlsParts(p.secret)
                    ?: return Result(p, Verdict.TCP_ONLY, connectMs, "TCP")
                val (key, domain) = parts
                val (hello, clientRandom) = buildClientHello(key, domain, System.currentTimeMillis() / 1000)
                s.getOutputStream().apply { write(hello); flush() }
                val input = DataInputStream(s.getInputStream())
                val response = ByteArrayOutputStream()
                val ok = readRecord(input, response, 0x16) && readRecord(input, response, 0x14) &&
                    readRecord(input, response, 0x17) && verifyServerHello(key, clientRandom, response.toByteArray())
                if (ok) Result(p, Verdict.VERIFIED, ms(), "FakeTLS $domain")
                else Result(p, Verdict.FAILED, ms(), "handshake rejected")
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result(p, Verdict.FAILED, ms(), "timeout")
        } catch (e: java.io.EOFException) {
            Result(p, Verdict.FAILED, ms(), "closed by network")
        } catch (e: Exception) {
            Result(p, Verdict.FAILED, ms(), e.javaClass.simpleName)
        }
    }

    fun sortResults(results: List<Result>): List<Result> = results.sortedWith(
        compareBy<Result> { it.verdict.ordinal }.thenBy { it.latencyMs },
    )

    fun scan(
        proxies: List<Proxy>,
        timeoutMs: Int = 4_000,
        parallel: Int = 24,
        protect: (Socket) -> Unit,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): List<Result> {
        val list = proxies.take(MAX_PROXIES)
        if (list.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(parallel.coerceIn(1, 48))
        val out = ConcurrentLinkedQueue<Result>()
        val done = AtomicInteger()
        try {
            list.forEach { p ->
                pool.execute {
                    if (!cancelled()) out += probe(p, timeoutMs, protect)
                    progress(done.incrementAndGet(), list.size)
                }
            }
            pool.shutdown()
            while (!pool.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                if (cancelled()) { pool.shutdownNow(); break }
            }
        } finally {
            pool.shutdownNow()
        }
        return sortResults(out.toList())
    }

    /** Downloads the public lists (mirror by mirror). Returns the merged proxies and the mirror that answered. */
    fun fetchPublicList(timeoutMs: Int = 10_000): Pair<List<Proxy>, String> {
        for (mirror in MIRRORS) {
            val merged = LinkedHashMap<String, Proxy>()
            LIST_PATHS.forEach { path ->
                httpGet(mirror + path, timeoutMs)?.let { text -> parseLinks(text).forEach { merged.putIfAbsent(it.key, it) } }
            }
            if (merged.isNotEmpty()) return merged.values.take(MAX_PROXIES) to java.net.URI(mirror).host
        }
        return emptyList<Proxy>() to ""
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        val c = java.net.URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "CatClient")
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            c.disconnect()
        }
    }
}

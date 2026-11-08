package com.cat.client

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager

/*
 * 🌐 Cat GP — a personal Google Apps Script relay (Cat's own implementation, pure JVM so it is unit-tested).
 *
 *   browser ─▶ Cat VPN core ─▶ CatGpProxy (127.0.0.1) ─▶ TLS to a Google IP, SNI www.google.com,
 *   Host: script.google.com, POST /macros/s/<id>/exec ─▶ the user's Code.gs (UrlFetchApp) ─▶ the site
 *
 * The censor only sees TLS to Google. The wire format ({k,u,m,h,b,ct,r} → {s,h,b} | {e}) is the one used by the
 * MIT-licensed MasterHttpRelayVPN / ArasTey relay engines so scripts are interchangeable; no code was copied and
 * nothing from the proprietary panel/ directory of that project is used.
 *
 * Honest limits (shown in the UI): HTTPS is opened locally with Cat's own CA (MITM), so it only works in apps that
 * trust user-installed CAs (Chrome and other browsers); apps with their own trust store (Telegram, Instagram…)
 * refuse it. A normal Google account allows ~20 000 relayed requests per day.
 */

/** Minimal JSON (the relay answer is the only thing parsed): objects → Map, arrays → List, numbers → Double. */
internal object CatGpJson {
    fun quote(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' || c == '\u2028' || c == '\u2029' -> b.append(String.format(Locale.US, "\\u%04x", c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw IOException("JSON: trailing data at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            if (i >= s.length) throw IOException("JSON: unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw IOException("JSON: bad literal at $i")
            i += w.length
            return v
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDoubleOrNull() ?: throw IOException("JSON: bad number at $st")
        }
        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw IOException("JSON: open string")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                if (s[i++] != ':') throw IOException("JSON: ':' expected at ${i - 1}")
                ws(); m[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> throw IOException("JSON: ',' or '}' expected at ${i - 1}") }
            }
        }
        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                ws(); l += value(); ws()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> throw IOException("JSON: ',' or ']' expected at ${i - 1}") }
            }
        }
    }
}

/** Relay wire format + HTTP/1.1 framing helpers. */
internal object CatGpCodec {
    /** Hop-by-hop / proxy headers never sent to the script (UrlFetchApp sets Host and length itself). */
    val DROP_REQUEST = setOf(
        "host", "connection", "content-length", "proxy-connection", "proxy-authorization", "keep-alive",
        "transfer-encoding", "te", "trailer", "upgrade", "accept-encoding",
    )
    /** Response headers not passed back: the body arrives decoded and is re-framed with our own length. */
    val DROP_RESPONSE = setOf("transfer-encoding", "content-encoding", "content-length", "connection", "keep-alive", "alt-svc")

    class Reply(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray)

    /** UrlFetchApp knows get/post/put/delete/patch only: HEAD is fetched as GET and its body dropped locally. */
    fun relayMethod(method: String): String = when (method.uppercase(Locale.US)) { "HEAD" -> "GET"; else -> method.uppercase(Locale.US) }

    fun payload(key: String, url: String, method: String, headers: List<Pair<String, String>>, body: ByteArray?): String {
        val sb = StringBuilder("{")
        sb.append("\"k\":").append(CatGpJson.quote(key))
        sb.append(",\"u\":").append(CatGpJson.quote(url))
        sb.append(",\"m\":").append(CatGpJson.quote(relayMethod(method)))
        val kept = LinkedHashMap<String, String>()
        var contentType: String? = null
        for ((k, v) in headers) {
            val lk = k.lowercase(Locale.US)
            if (lk in DROP_REQUEST || lk.startsWith("proxy-")) continue
            if (lk == "content-type") contentType = v
            kept[k] = kept[k]?.let { "$it, $v" } ?: v
        }
        sb.append(",\"h\":{").append(kept.entries.joinToString(",") { CatGpJson.quote(it.key) + ":" + CatGpJson.quote(it.value) }).append('}')
        if (body != null && body.isNotEmpty()) {
            sb.append(",\"b\":").append(CatGpJson.quote(Base64.getEncoder().encodeToString(body)))
            if (contentType != null) sb.append(",\"ct\":").append(CatGpJson.quote(contentType))
        }
        sb.append(",\"r\":false}")
        return sb.toString()
    }

    /** Script answer → [Reply]; `{e:…}` and non-JSON pages (login / "not found") become a clear IOException. */
    fun parseReply(text: String): Reply {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) {
            val hint = when {
                trimmed.contains("accounts.google.com", true) || trimmed.contains("ServiceLogin", true) ->
                    "the script asks for a Google login — deploy it with “Who has access: Anyone”"
                trimmed.contains("Script function not found", true) -> "the script has no doPost — paste Cat's Code.gs again"
                else -> "not a relay answer: " + trimmed.take(80).replace(Regex("\\s+"), " ")
            }
            throw IOException(hint)
        }
        @Suppress("UNCHECKED_CAST")
        val m = CatGpJson.parse(trimmed) as? Map<String, Any?> ?: throw IOException("relay answer is not an object")
        (m["e"] as? String)?.let { throw IOException("script: " + it.take(160)) }
        val status = (m["s"] as? Double)?.toInt() ?: throw IOException("relay answer has no status")
        val headers = ArrayList<Pair<String, String>>()
        (m["h"] as? Map<*, *>)?.forEach { (k, v) ->
            val name = k?.toString() ?: return@forEach
            val values = when (v) {
                is List<*> -> v.mapNotNull { it?.toString() }
                null -> emptyList()
                else -> listOf(v.toString())
            }
            // Set-Cookie may come joined with newlines; every cookie needs its own header line.
            for (one in values) for (part in one.split('\n')) if (part.isNotBlank()) headers += name to part.trim()
        }
        val body = (m["b"] as? String)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() ?: Base64.getMimeDecoder().decode(it) } ?: ByteArray(0)
        return Reply(status, headers, body)
    }

    const val MAX_GZIP_LAYERS = 8
    const val MAX_DECODED_BYTES = 8 * 1024 * 1024

    fun looksGzip(data: ByteArray): Boolean = data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()

    /** Peel gzip (and stacked gzip) up to [MAX_GZIP_LAYERS]; refuse a bomb. */
    fun gunzip(data: ByteArray, encoding: String? = null): ByteArray {
        var out = data
        var layers = 0
        val named = encoding?.split(',')?.count { it.trim().equals("gzip", ignoreCase = true) } ?: 0
        if (named > MAX_GZIP_LAYERS) throw IOException("too many gzip layers")
        while (layers < MAX_GZIP_LAYERS && looksGzip(out)) {
            out = GZIPInputStream(out.inputStream()).use { ins ->
                val buf = ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = ins.read(tmp)
                    if (n < 0) break
                    total += n
                    if (total > MAX_DECODED_BYTES) throw IOException("decompressed body larger than cap")
                    buf.write(tmp, 0, n)
                }
                buf.toByteArray()
            }
            layers++
        }
        if (looksGzip(out)) throw IOException("too many gzip layers")
        return out
    }

    private val REASONS = mapOf(
        200 to "OK", 201 to "Created", 204 to "No Content", 206 to "Partial Content", 301 to "Moved Permanently", 302 to "Found",
        303 to "See Other", 304 to "Not Modified", 307 to "Temporary Redirect", 308 to "Permanent Redirect", 400 to "Bad Request",
        401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 429 to "Too Many Requests",
        500 to "Internal Server Error", 501 to "Not Implemented", 502 to "Bad Gateway", 503 to "Service Unavailable", 504 to "Gateway Timeout",
    )

    /** [Reply] → raw HTTP/1.1 response for the browser (HEAD: headers with the real length, no body). */
    fun toHttp(reply: Reply, head: Boolean, keepAlive: Boolean = true): ByteArray {
        val noBody = head || reply.status == 204 || reply.status == 304 || reply.status in 100..199
        val sb = StringBuilder("HTTP/1.1 ${reply.status} ${REASONS[reply.status] ?: "Status"}\r\n")
        for ((k, v) in reply.headers) {
            if (k.lowercase(Locale.US) in DROP_RESPONSE) continue
            if (k.any { it == '\r' || it == '\n' } || v.any { it == '\r' || it == '\n' }) continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        if (reply.status != 204 && reply.status != 304) sb.append("Content-Length: ").append(reply.body.size).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        val headBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        return if (noBody) headBytes else headBytes + reply.body
    }

    fun errorHttp(status: Int, message: String): ByteArray {
        val body = ("<!doctype html><meta charset=utf-8><title>Cat GP</title><body style=\"font:16px sans-serif;padding:24px\">" +
            "<h3>🌐 Cat GP</h3><p>" + message.replace("&", "&amp;").replace("<", "&lt;") + "</p>").toByteArray()
        return toHttp(Reply(status, listOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store"), body), head = false, keepAlive = false)
    }

    // ── HTTP/1.1 framing (shared by the proxy side and the Google side) ──

    class Head(val firstLine: String, val headers: List<Pair<String, String>>) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    /** Reads one header block byte by byte (never over-reads, so the raw socket can be handed to TLS after CONNECT). */
    fun readHead(input: InputStream, max: Int = 64 * 1024): Head? {
        val buf = ByteArrayOutputStream(512)
        var prev1 = -1
        var prev2 = -1
        var prev3 = -1
        while (true) {
            val b = input.read()
            if (b < 0) { if (buf.size() == 0) return null; throw EOFException("connection closed inside headers") }
            if (buf.size() == 0 && (b == '\r'.code || b == '\n'.code)) continue // stray CRLF between requests
            buf.write(b)
            if (buf.size() > max) throw IOException("headers too large")
            val crlfcrlf = prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code
            val lflf = prev1 == '\n'.code && b == '\n'.code
            if (crlfcrlf || lflf) break
            prev3 = prev2; prev2 = prev1; prev1 = b
        }
        val lines = String(buf.toByteArray(), Charsets.ISO_8859_1).split("\n").map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }
        return Head(lines[0], headers)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw EOFException("body cut after $off of $n bytes")
            off += r
        }
        return out
    }

    private fun readLine(input: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw EOFException("chunk header cut")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return String(b.toByteArray(), Charsets.ISO_8859_1)
    }

    /** Body by Content-Length or chunked; [untilClose] reads to EOF (responses without length). */
    fun readBody(input: InputStream, head: Head, untilClose: Boolean, max: Int = 64 * 1024 * 1024): ByteArray {
        if (head.header("transfer-encoding")?.contains("chunked", ignoreCase = true) == true) {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk size")
                if (size == 0) {
                    while (readLine(input).isNotEmpty()) { /* trailers */ }
                    break
                }
                if (out.size() + size > max) throw IOException("body too large")
                out.write(readExactly(input, size))
                readLine(input)
            }
            return out.toByteArray()
        }
        head.header("content-length")?.trim()?.toLongOrNull()?.let { len ->
            if (len > max) throw IOException("body too large")
            return readExactly(input, len.toInt())
        }
        if (!untilClose) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val tmp = ByteArray(16 * 1024)
        while (true) {
            val r = input.read(tmp)
            if (r < 0) break
            out.write(tmp, 0, r)
            if (out.size() > max) throw IOException("body too large")
        }
        return out.toByteArray()
    }
}

/** Hand-written DER / X.509 (Android has no public certificate builder): enough for a CA and per-host leaves. */
internal object CatGpDer {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val lenBytes = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
            else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
        }
        return byteArrayOf(tag.toByte()) + lenBytes + content
    }
    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0))
    fun octets(v: ByteArray) = tlv(0x04, v)
    fun bits(v: ByteArray, unused: Int = 0) = tlv(0x03, byteArrayOf(unused.toByte()) + v)
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun explicit(n: Int, content: ByteArray) = tlv(0xA0 + n, content)
    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (a in arcs.drop(2)) {
            val stack = ArrayList<Int>()
            var v = a
            stack += (v and 0x7F).toInt()
            v = v shr 7
            while (v > 0) { stack += ((v and 0x7F) or 0x80).toInt(); v = v shr 7 }
            for (b in stack.reversed()) out.write(b)
        }
        return tlv(0x06, out.toByteArray())
    }
    fun time(d: Date): ByteArray {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = d }
        val utc = cal.get(java.util.Calendar.YEAR) < 2050
        val f = SimpleDateFormat(if (utc) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(if (utc) 0x17 else 0x18, f.format(d).toByteArray(Charsets.US_ASCII))
    }
    fun name(cn: String) = seq(
        set(seq(oid("2.5.4.10"), utf8("Cat Client"))),
        set(seq(oid("2.5.4.3"), utf8(cn))),
    )
    fun ext(oid: String, critical: Boolean, value: ByteArray) =
        if (critical) seq(oid(oid), bool(true), octets(value)) else seq(oid(oid), octets(value))

    private const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"

    /** Key id = SHA-1 of the subjectPublicKey BIT STRING contents (RFC 5280 §4.2.1.2 method 1, close enough). */
    fun keyId(spki: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(spki)

    fun certificate(
        subjectCn: String, issuerCn: String, subjectKey: java.security.PublicKey, signer: PrivateKey,
        notBefore: Date, notAfter: Date, isCa: Boolean, sanHost: String?, issuerSpki: ByteArray?,
    ): X509Certificate {
        val serial = BigInteger(1, ByteArray(16).also { SecureRandom().nextBytes(it) }).setBit(120).clearBit(127)
        val alg = seq(oid(ECDSA_SHA256))
        val exts = ArrayList<ByteArray>()
        if (isCa) {
            exts += ext("2.5.29.19", true, seq(bool(true)))
            exts += ext("2.5.29.15", true, bits(byteArrayOf(0x06), unused = 1)) // keyCertSign | cRLSign
        } else {
            exts += ext("2.5.29.19", true, seq())
            exts += ext("2.5.29.15", true, bits(byteArrayOf(0x80.toByte()), unused = 7)) // digitalSignature
            exts += ext("2.5.29.37", false, seq(oid("1.3.6.1.5.5.7.3.1"))) // serverAuth
            if (sanHost != null) {
                val ip = ipLiteral(sanHost)
                exts += ext("2.5.29.17", false, seq(if (ip != null) tlv(0x87, ip) else tlv(0x82, sanHost.toByteArray(Charsets.US_ASCII))))
            }
        }
        exts += ext("2.5.29.14", false, octets(keyId(subjectKey.encoded)))
        if (issuerSpki != null) exts += ext("2.5.29.35", false, seq(tlv(0x80, keyId(issuerSpki))))
        val tbs = seq(
            explicit(0, int(BigInteger.valueOf(2))),
            int(serial),
            alg,
            name(issuerCn),
            seq(time(notBefore), time(notAfter)),
            name(subjectCn),
            subjectKey.encoded, // SubjectPublicKeyInfo, already DER
            explicit(3, seq(*exts.toTypedArray())),
        )
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(signer); update(tbs) }.sign()
        val der = seq(tbs, alg, bits(sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun ipLiteral(host: String): ByteArray? {
        val h = host.trim('[', ']')
        val looksIp = h.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || (h.contains(':') && h.all { it.isLetterOrDigit() || it == ':' || it == '.' })
        return if (looksIp) runCatching { InetAddress.getByName(h).address }.getOrNull() else null
    }
}

/** Cat GP's local certificate authority: one CA (kept by the app) + a cached leaf per host, all EC P-256. */
internal class CatGpCa private constructor(val caKey: PrivateKey, val caCert: X509Certificate) {
    private val leafPair: KeyPair = ecPair()
    private val contexts = object : LinkedHashMap<String, SSLContext>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SSLContext>?) = size > 200
    }

    val caDer: ByteArray get() = caCert.encoded
    val keyPkcs8: ByteArray get() = caKey.encoded

    fun leaf(host: String): X509Certificate {
        val now = System.currentTimeMillis()
        val notAfter = minOf(now + 397L * 86_400_000L, caCert.notAfter.time - 60_000L)
        return CatGpDer.certificate(
            host.take(64), CA_CN, leafPair.public, caKey, Date(now - 86_400_000L), Date(notAfter),
            isCa = false, sanHost = host, issuerSpki = caCert.publicKey.encoded,
        )
    }

    /** Server-side TLS context presenting a leaf for [host] (HTTP/1.1 — the proxy speaks HTTP/1.1). */
    fun serverContext(host: String): SSLContext = synchronized(contexts) {
        contexts.getOrPut(host.lowercase(Locale.US)) {
            val chain = arrayOf(leaf(host), caCert)
            SSLContext.getInstance("TLS").apply { init(arrayOf(OneKey(leafPair.private, chain)), null, SecureRandom()) }
        }
    }

    private class OneKey(val key: PrivateKey, val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        // The key is EC: offered only for EC key types so TLS 1.2 stacks never pair it with an RSA suite.
        private fun fits(keyType: String?) = keyType == null || keyType.startsWith("EC", ignoreCase = true)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = if (fits(keyType)) arrayOf("h") else null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = if (fits(keyType)) "h" else null
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = if (fits(keyType)) "h" else null
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
        override fun getPrivateKey(alias: String?): PrivateKey = key
    }

    companion object {
        const val CA_CN = "Cat GP Local CA"

        fun ecPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        fun create(): CatGpCa {
            val pair = ecPair()
            val now = System.currentTimeMillis()
            val cert = CatGpDer.certificate(
                CA_CN, CA_CN, pair.public, pair.private, Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L),
                isCa = true, sanHost = null, issuerSpki = null,
            )
            return CatGpCa(pair.private, cert)
        }

        fun load(keyPkcs8: ByteArray, certDer: ByteArray): CatGpCa {
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyPkcs8))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(certDer.inputStream()) as X509Certificate
            return CatGpCa(key, cert)
        }
    }
}

/**
 * The Google side: TLS to a Google front IP with a Google SNI, `Host: script.google.com`, POST to the deployment;
 * the 302 to script.googleusercontent.com is followed on the same connection. Idle connections are reused.
 */
internal class CatGpFronter(
    /** Deployment paths, e.g. `/macros/s/AKfy…/exec` (several = spread the daily quota). */
    private val scriptPaths: List<String>,
    private val frontIps: List<String>,
    private val port: Int = 443,
    private val snis: List<String> = DEFAULT_SNIS,
    private val tls: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val protect: (Socket) -> Unit = {},
    private val timeoutMs: Int = 35_000,
) {
    private class Conn(val ssl: SSLSocket, val input: InputStream, val output: OutputStream)

    private val idle = ArrayDeque<Conn>()
    private val nextScript = AtomicInteger(0)
    private val nextSni = AtomicInteger(0)
    @Volatile private var ipIndex = 0
    val calls = AtomicInteger(0)

    init {
        require(scriptPaths.isNotEmpty()) { "no Apps Script deployment" }
        require(frontIps.isNotEmpty()) { "no Google front IP" }
    }

    private fun open(): Conn {
        var last: Exception? = null
        repeat(minOf(frontIps.size, 3)) {
            val ip = frontIps[ipIndex % frontIps.size]
            val sni = snis[Math.floorMod(nextSni.getAndIncrement(), snis.size)]
            try {
                val raw = Socket()
                protect(raw)
                raw.connect(InetSocketAddress(InetAddress.getByName(ip), port), 10_000)
                raw.soTimeout = timeoutMs
                val ssl = tls.createSocket(raw, sni, port, true) as SSLSocket
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(sni))
                    // No ALPN on purpose: setApplicationProtocols is API 29+ (minSdk 26) and no ALPN = HTTP/1.1.
                }
                ssl.soTimeout = timeoutMs
                ssl.startHandshake()
                if (ON_ANDROID && !HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, ssl.session)) {
                    ssl.close()
                    throw IOException("certificate of $ip is not valid for $sni")
                }
                return Conn(ssl, ssl.inputStream.buffered(32 * 1024), ssl.outputStream.buffered(32 * 1024))
            } catch (e: Exception) {
                last = e
                ipIndex++ // next front IP
            }
        }
        throw IOException("Google front unreachable: " + (last?.message ?: "?"), last)
    }

    private fun take(): Conn? = synchronized(idle) { idle.pollFirst() }
    private fun give(c: Conn) = synchronized(idle) {
        if (idle.size < 6) idle.addFirst(c) else runCatching { c.ssl.close() }
    }

    fun close() = synchronized(idle) { while (idle.isNotEmpty()) runCatching { idle.poll()?.ssl?.close() } }

    private class Answer(val status: Int, val head: CatGpCodec.Head, val body: ByteArray, val keepAlive: Boolean)

    private fun exchange(c: Conn, method: String, host: String, path: String, body: ByteArray?): Answer {
        val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0 (Linux; Android) CatGP\r\n")
        sb.append("Accept-Encoding: gzip\r\nConnection: keep-alive\r\n")
        if (body != null) sb.append("Content-Type: application/json\r\nContent-Length: ${body.size}\r\n")
        sb.append("\r\n")
        c.output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) c.output.write(body)
        c.output.flush()
        val head = CatGpCodec.readHead(c.input) ?: throw EOFException("Google closed the connection")
        val status = head.firstLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line: ${head.firstLine.take(60)}")
        val keep = !head.header("connection").orEmpty().contains("close", ignoreCase = true)
        var data = CatGpCodec.readBody(c.input, head, untilClose = !keep && head.header("content-length") == null)
        val enc = head.header("content-encoding")
        if (enc?.contains("gzip", ignoreCase = true) == true || CatGpCodec.looksGzip(data)) data = CatGpCodec.gunzip(data, enc)
        return Answer(status, head, data, keep)
    }

    /** One relay round trip: payload JSON in, script answer text out. Retries once on a stale pooled connection. */
    fun relay(payloadJson: String): String {
        val body = payloadJson.toByteArray(Charsets.UTF_8)
        val path = scriptPaths[Math.floorMod(nextScript.getAndIncrement(), scriptPaths.size)]
        var attempt = 0
        var a: Answer
        while (true) {
            val pooled = take()
            val c = pooled ?: open()
            try {
                a = exchange(c, "POST", "script.google.com", path, body)
                var hops = 0
                var conn = c
                while (a.status in setOf(301, 302, 303, 307, 308) && hops < 5) {
                    val loc = a.head.header("location") ?: break
                    val u = URI(loc)
                    val host = u.host ?: "script.google.com"
                    val p = (u.rawPath ?: "/").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
                    if (!a.keepAlive) { runCatching { conn.ssl.close() }; conn = open() }
                    a = if (a.status == 307 || a.status == 308) exchange(conn, "POST", host, p, body) else exchange(conn, "GET", host, p, null)
                    hops++
                }
                if (a.keepAlive) give(conn) else runCatching { conn.ssl.close() }
                break
            } catch (e: IOException) {
                runCatching { c.ssl.close() }
                // A pooled connection Google already closed fails on first use: one retry on a fresh connection.
                if (pooled != null && attempt == 0) { attempt++; continue }
                throw e
            }
        }
        calls.incrementAndGet()
        if (a.status != 200) {
            val why = when (a.status) {
                403 -> "403 — the deployment is not public (“Who has access: Anyone”) or the URL is wrong"
                404 -> "404 — no such deployment; copy the …/exec address again"
                429 -> "429 — Google's daily quota for this account is used up"
                else -> "HTTP ${a.status}"
            }
            throw IOException("Apps Script $why")
        }
        return String(a.body, Charsets.UTF_8)
    }

    companion object {
        val DEFAULT_SNIS = listOf("www.google.com", "beacons.gvt2.com", "mail.google.com", "accounts.google.com")
        /** Google front-end IPs that serve script.google.com behind a Google SNI (checked in order; the first that works is kept). */
        val DEFAULT_FRONT_IPS = listOf(
            "216.239.38.120", "216.239.36.120", "216.239.34.120", "216.239.32.120",
            "142.250.80.142", "142.250.179.110", "172.217.16.142", "142.251.32.110",
        )
        private val ON_ANDROID = System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true)

        /** `https://script.google.com/macros/s/<id>/exec` (or a Workspace `/a/macros/<domain>/s/<id>/exec`, or a bare id) → path. */
        fun scriptPath(input: String): String? {
            val s = input.trim()
            Regex("""(/a/macros/[^/\s]+/s/[A-Za-z0-9_-]{20,}/(?:exec|dev))""").find(s)?.let { return it.groupValues[1] }
            Regex("""(/macros/s/[A-Za-z0-9_-]{20,}/(?:exec|dev))""").find(s)?.let { return it.groupValues[1] }
            if (s.matches(Regex("""[A-Za-z0-9_-]{30,}"""))) return "/macros/s/$s/exec"
            return null
        }
    }
}

/**
 * Google edge IP + SNI for Cat GP. Empty IP list = auto (built-in fronts). A typed Google SNI is pinned.
 * Scan is a real TLS handshake on this network — nothing is marked working without a probe.
 */
internal object CatGpEdge {
    data class Hit(val ip: String, val sni: String, val rttMs: Long)

    val SCAN_SNIS = listOf(
        "www.google.com",
        "beacons.gvt2.com",
        "accounts.google.com",
        "www.gstatic.com",
        "checkin.gstatic.com",
        "g3.gstatic.com",
        "meet.google.com",
        "gmail.com",
    )

    fun isGoogleHost(sni: String): Boolean {
        val h = sni.trim().lowercase(Locale.US).removePrefix("https://").substringBefore('/').trimEnd('.')
        if (h.isBlank() || '/' in h || ' ' in h || h.startsWith('.')) return false
        if (h in setOf("google.com", "gmail.com", "gstatic.com", "googleapis.com", "youtube.com")) return true
        val suffixes = listOf(
            ".google.com", ".gstatic.com", ".googleapis.com",
            ".gvt1.com", ".gvt2.com", ".gvt3.com",
            ".googleusercontent.com", ".gmail.com",
            ".youtube.com", ".googlevideo.com", ".withgoogle.com",
        )
        if (suffixes.any { h.endsWith(it) }) return true
        return h.matches(Regex("""^google\.co\.[a-z]{2}$"""))
    }

    fun isIp(raw: String): Boolean {
        val s = raw.trim().trim('[', ']')
        if (s.matches(Regex("""^(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}$"""))) return true
        return s.contains(':') && s.count { it == ':' } >= 2 &&
            s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    fun parseIps(text: String): List<String> =
        text.split('\n', ',', ';', ' ', '\t').map { it.trim().trim('[', ']') }.filter { it.isNotBlank() && isIp(it) }.distinct()

    fun parseSni(text: String): String? {
        val s = text.trim().lowercase(Locale.US).removePrefix("https://").substringBefore('/').substringBefore(':').trimEnd('.')
        if (s.isBlank()) return null
        return s.takeIf { isGoogleHost(it) }
    }

    fun ipsToScan(typed: String, extra: List<String> = emptyList()): List<String> =
        parseIps(typed).ifEmpty { (CatGpFronter.DEFAULT_FRONT_IPS + extra).distinct() }

    fun snisToScan(typed: String): List<String> {
        val pin = parseSni(typed)
        return if (pin != null) listOf(pin) + SCAN_SNIS.filter { it != pin } else SCAN_SNIS
    }

    fun line(hit: Hit): String = "${hit.ip} · ${hit.sni} · ${hit.rttMs} ms"

    fun sha256Colon(der: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(der)
        return d.joinToString(":") { b -> "%02X".format(b.toInt() and 0xFF) }
    }

    fun tlsMs(ip: String, sni: String, timeoutMs: Int = 2_500, protect: (Socket) -> Unit = {}): Long? {
        val t0 = System.nanoTime()
        val raw = Socket()
        return try {
            protect(raw)
            raw.connect(InetSocketAddress(InetAddress.getByName(ip), 443), timeoutMs)
            raw.soTimeout = timeoutMs
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, sni, 443, true) as SSLSocket
            tls.soTimeout = timeoutMs
            tls.sslParameters = tls.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(sni))
            }
            tls.startHandshake()
            val ok = runCatching { HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, tls.session) }.getOrDefault(true)
            runCatching { tls.close() }
            if (!ok) null else ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        } catch (_: Exception) {
            runCatching { raw.close() }
            null
        }
    }

    fun scan(
        ips: List<String>,
        snis: List<String>,
        probe: (String, String) -> Long? = { ip, sni -> tlsMs(ip, sni) },
        maxHits: Int = 12,
        maxProbes: Int = 36,
        parallel: Int = 1,
    ): List<Hit> {
        val pairs = ips.flatMap { ip -> snis.map { sni -> ip to sni } }.take(maxProbes)
        if (pairs.isEmpty()) return emptyList()
        if (parallel <= 1) {
            val out = ArrayList<Hit>()
            for ((ip, sni) in pairs) {
                if (out.size >= maxHits) break
                val ms = runCatching { probe(ip, sni) }.getOrNull() ?: continue
                out += Hit(ip, sni, ms)
            }
            return out.sortedBy { it.rttMs }
        }
        val pool = Executors.newFixedThreadPool(parallel.coerceIn(2, 8))
        return try {
            val futures = pairs.map { (ip, sni) ->
                pool.submit(Callable {
                    val ms = runCatching { probe(ip, sni) }.getOrNull() ?: return@Callable null
                    Hit(ip, sni, ms)
                })
            }
            futures.mapNotNull { runCatching { it.get(4, TimeUnit.SECONDS) }.getOrNull() }
                .sortedBy { it.rttMs }
                .take(maxHits)
        } finally {
            pool.shutdownNow()
        }
    }
}

/**
 * Local HTTP proxy (127.0.0.1) the VPN core uses as an `http` outbound. Plain `GET http://…` and `CONNECT host:port`
 * are accepted; after CONNECT, port 80 is read as plain HTTP and anything else as TLS opened with Cat GP's CA.
 * Every request is relayed through [relay]; WebSocket upgrades are refused (Apps Script cannot carry them).
 */
internal class CatGpProxy(
    private val port: Int,
    private val user: String,
    private val pass: String,
    private val ca: CatGpCa,
    private val relay: (key: String, url: String, method: String, headers: List<Pair<String, String>>, body: ByteArray?) -> CatGpCodec.Reply,
    private val key: String,
    private val bindHost: String = "127.0.0.1",
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "cat-gp").apply { isDaemon = true } }
    private val slots = Semaphore(96)
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    val boundPort: Int get() = server?.localPort ?: -1
    val isRunning: Boolean get() = running.get()

    fun start(): CatGpProxy {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName(bindHost), port), 128)
        server = ss
        running.set(true)
        Thread({
            while (running.get()) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                if (!slots.tryAcquire()) { runCatching { s.close() }; continue }
                open += s
                pool.execute {
                    try { handle(s) } catch (_: Exception) { } finally { open -= s; runCatching { s.close() }; slots.release() }
                }
            }
        }, "cat-gp-accept").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        open.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun authorised(head: CatGpCodec.Head): Boolean {
        if (user.isEmpty()) return true
        val want = "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
        return head.header("proxy-authorization")?.trim() == want
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 120_000
        sock.tcpNoDelay = true
        val rawIn = sock.getInputStream()
        val rawOut = sock.getOutputStream()
        val head = CatGpCodec.readHead(rawIn) ?: return
        val parts = head.firstLine.split(' ')
        if (parts.size < 3) return
        if (!authorised(head)) {
            rawOut.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"cat-gp\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            rawOut.flush()
            return
        }
        val method = parts[0].uppercase(Locale.US)
        if (method == "CONNECT") {
            val target = parts[1]
            val host = target.substringBeforeLast(':').trim('[', ']')
            val tport = target.substringAfterLast(':').toIntOrNull() ?: 443
            rawOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            rawOut.flush()
            if (tport == 80) {
                serve(rawIn.buffered(), rawOut, "http", host, tport, null)
            } else {
                val ssl = ca.serverContext(host).socketFactory.createSocket(sock, host, tport, true) as SSLSocket
                ssl.useClientMode = false // no ALPN offered → the browser speaks HTTP/1.1 (and API 26–28 have no ALPN setter)
                ssl.soTimeout = 120_000
                ssl.startHandshake()
                serve(ssl.inputStream.buffered(), ssl.outputStream, "https", host, tport, null)
            }
        } else {
            // Plain proxy request: absolute URI on the first line.
            serve(rawIn.buffered(), rawOut, "http", "", 80, head)
        }
    }

    /** Request loop on one client connection (keep-alive). */
    private fun serve(input: InputStream, output: OutputStream, scheme: String, host: String, tport: Int, firstHead: CatGpCodec.Head?) {
        var head = firstHead ?: CatGpCodec.readHead(input) ?: return
        while (true) {
            val parts = head.firstLine.split(' ')
            if (parts.size < 3) return
            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]
            val body = CatGpCodec.readBody(input, head, untilClose = false).takeIf { it.isNotEmpty() }
            val url = when {
                target.startsWith("http://", true) || target.startsWith("https://", true) -> target
                else -> {
                    val h = head.header("host")?.takeIf { it.isNotBlank() } ?: (if ((scheme == "https" && tport == 443) || (scheme == "http" && tport == 80)) host else "$host:$tport")
                    "$scheme://$h" + (if (target.startsWith("/")) target else "/$target")
                }
            }
            val closeAfter = head.header("connection")?.contains("close", ignoreCase = true) == true
            if (head.header("upgrade") != null) {
                output.write(CatGpCodec.errorHttp(501, "WebSocket / upgrade is not possible through Google Apps Script.")); output.flush()
                return
            }
            val reply = try {
                relay(key, url, method, head.headers, body)
            } catch (e: Exception) {
                output.write(CatGpCodec.errorHttp(502, "Relay failed: " + (e.message ?: e.javaClass.simpleName))); output.flush()
                return
            }
            output.write(CatGpCodec.toHttp(reply, head = method == "HEAD", keepAlive = !closeAfter))
            output.flush()
            if (closeAfter) return
            head = CatGpCodec.readHead(input) ?: return
        }
    }
}

/** Pure helpers for the phone glue (CatGpEngine) — kept here so they are unit-tested. */
internal object CatGpScripts {
    fun scriptPaths(text: String): List<String> = text.split('\n', ' ', ',', ';', '\t').mapNotNull { CatGpFronter.scriptPath(it) }.distinct()

    /** Why a pasted Google address cannot be used: empty / example (ellipsis) / not an /exec URL. */
    fun addressProblem(text: String): String? {
        if (scriptPaths(text).isNotEmpty()) return null
        val s = text.trim()
        if (s.isEmpty()) return "empty"
        if (s.contains('…') || s.contains("...")) return "example"
        return "not-exec"
    }

    /** Does the core config about to start use the Cat GP proxy on [port]? */
    fun referencedIn(yaml: String, port: Int): Boolean =
        Regex("""server\s*:\s*['"]?127\.0\.0\.1['"]?\s*\r?\n\s*port\s*:\s*$port\b""").containsMatchIn(yaml) ||
            Regex("""127\.0\.0\.1:$port\b""").containsMatchIn(yaml)

    /** Cat panels (Cloudflare / own domain, not *.vercel.app / *.pages.dev) → /gp/<uuid>/<key> hop (key optional for tests). */
    fun hopCandidates(panels: List<Pair<String, String>>, key: String = ""): List<String> =
        panels.filter { p ->
            val h = p.first.lowercase(Locale.US)
            !h.contains(".vercel.app") && !h.contains(".pages.dev")
        }.map { (base, uuid) ->
            val u = base.trimEnd('/') + "/gp/" + uuid.lowercase(Locale.US)
            if (key.isNotBlank()) "$u/$key" else u
        }.distinct()

    private val HOP_URL = Regex("""https://[A-Za-z0-9.-]+/gp/[0-9a-f-]{36}(?:/[A-Za-z0-9_-]{8,64})?""")

    fun isHopUrl(text: String): Boolean {
        val t = text.trim()
        return dedicatedWorkerUrl(t) != null || HOP_URL.matches(t)
    }

    /**
     * Dedicated Cat GP Cloudflare Worker (workers.dev, no path) — not the Cat panel `/gp/<uuid>`.
     * `https://name.workers.dev`, `name.subdomain.workers.dev` (account subdomain), or without https.
     */
    fun dedicatedWorkerUrl(text: String): String? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        val withScheme = when {
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("http://", ignoreCase = true) -> return null
            else -> "https://$raw"
        }
        val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (!host.endsWith(".workers.dev") || host.endsWith(".pages.dev")) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.isNotEmpty() && path != "/") return null
        if (!uri.rawQuery.isNullOrEmpty() || !uri.fragment.isNullOrEmpty() || uri.userInfo != null) return null
        return "https://$host"
    }

    fun pairLinked(googleExec: String, workerUrl: String): Boolean =
        scriptPaths(googleExec).isNotEmpty() && dedicatedWorkerUrl(workerUrl) != null

    /** Host of a typed panel / hop address, or null when it cannot be a Cat panel. */
    fun hopHost(text: String): String? {
        val s = text.trim().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':').trim().lowercase(Locale.US)
        if (s.isBlank() || '.' !in s || ' ' in s || '/' in s) return null
        if (s.endsWith(".vercel.app") || s.endsWith(".pages.dev")) return null
        return s
    }

    /**
     * Google Apps Script talks to Cloudflare from Google's network — the phone does not need to reach the Worker.
     * Dedicated `https://name.workers.dev` (Cat GP Worker, not the Cat panel) wins. A pasted `/gp/<uuid>` hop is
     * still accepted. Empty / unknown → no hop (never borrowed from the Cat panel).
     */
    fun normalizeHop(text: String, key: String, panels: List<Pair<String, String>> = emptyList()): String {
        val raw = text.trim()
        dedicatedWorkerUrl(raw)?.let { return it }
        if (HOP_URL.matches(raw)) {
            val hasKey = raw.substringAfter("/gp/").contains('/')
            return if (!hasKey && key.isNotBlank()) "$raw/$key" else raw
        }
        if (raw.isEmpty()) return ""
        return ""
    }

    /**
     * True when this row is Cat's own Google Apps Script relay (`http://127.0.0.1:10871`),
     * not a copied inbound from another app. The Cat core (mixed-port 2080) cannot health-check
     * it: GAS is HTTP only (no UDP, no CONNECT tunnel), and HTTPS through Mihomo fails the CA.
     */
    fun isLocalRelay(type: String?, server: String?, port: Int, shareLink: String?, relayPort: Int = 10871): Boolean {
        val loop = LoopbackProfileGuard.isLoopbackHost(server) && port == relayPort
        if (loop) {
            val t = type?.trim()?.lowercase().orEmpty()
            if (t.isEmpty() || t == "http" || t == "https") return true
        }
        val link = shareLink?.trim()?.lowercase().orEmpty()
        if (!link.startsWith("http://") && !link.startsWith("https://")) return false
        return link.contains("127.0.0.1:$relayPort") ||
            link.contains("localhost:$relayPort") ||
            link.contains("[::1]:$relayPort")
    }

    /** Fills assets/catgp/Code.gs: the key and the optional worker hop become JS string literals. */
    fun fill(template: String, key: String, workerUrl: String): String {
        val hop = workerUrl.trim().let { dedicatedWorkerUrl(it) ?: it.takeIf { u -> isHopUrl(u) }.orEmpty() }
        return template.replace("__CAT_KEY__", CatGpJson.quote(key)).replace("__CAT_WORKER__", CatGpJson.quote(hop))
    }

    /** Fills assets/catgp/worker.js: the same CAT_KEY as Code.gs. */
    fun fillWorker(template: String, key: String): String =
        template.replace("__CAT_KEY__", CatGpJson.quote(key))
}

/** Fronter + codec glued into the proxy's relay function (also used by the in-app test). */
internal fun catGpRelayFunction(fronter: CatGpFronter, onCall: () -> Unit = {}): (String, String, String, List<Pair<String, String>>, ByteArray?) -> CatGpCodec.Reply =
    { key, url, method, headers, body ->
        val answer = fronter.relay(CatGpCodec.payload(key, url, method, headers, body))
        onCall()
        CatGpCodec.parseReply(answer)
    }

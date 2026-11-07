package com.cat.client

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/*
 * Cat SSTP — a Microsoft SSTP client (PPP inside HTTPS on TCP), pure JVM, no extra libraries.
 *
 * Follows [MS-SSTP] and RFC 1661 (PPP/LCP), 1332 (IPCP), 1334 (PAP), 1994 (CHAP), 2759 (MS-CHAPv2) and
 * 3079 (MPPE keys). The MD4 routine and the HLAK / Compound-MAC derivation were written after Open SSTP Client
 * (Copyright (c) 2019 KOBAYASHI Ittoku, MIT License) — https://github.com/kittoku/Open-SSTP-Client
 *
 * Used for the public VPN Gate SSTP servers (user `vpn`, password `vpn`); any SSTP server with PAP, CHAP-MD5 or
 * MS-CHAPv2 works.
 */

/** One SSTP server: `sstp://user:pass@host:port?ip=1.2.3.4#remark`. */
internal data class SstpLink(
    val host: String,
    val port: Int = 443,
    val user: String = "vpn",
    val password: String = "vpn",
    /** Optional address to dial instead of resolving [host] (the host still goes into SNI / HTTP Host). */
    val ip: String = "",
    val remark: String = "",
) {
    val endpoint: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    fun toUri(): String = buildString {
        append("sstp://").append(enc(user)).append(':').append(enc(password)).append('@')
        append(if (host.contains(':')) "[$host]" else host).append(':').append(port)
        if (ip.isNotBlank()) append("?ip=").append(enc(ip))
        if (remark.isNotBlank()) append('#').append(enc(remark))
    }

    companion object {
        const val SCHEME = "sstp"
        /** Loopback placeholder port of SSTP rows in the Cat core config (nothing listens there). */
        const val PLACEHOLDER_PORT = 1453
        private val HOST_ONLY = Regex("""^([A-Za-z0-9-]+\.)*opengw\.net(:\d{1,5})?$""", RegexOption.IGNORE_CASE)

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun dec(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        /** `sstp://…` links, and bare VPN Gate host names (`vpn123.opengw.net:1270`) as pasted from server lists. */
        fun parse(raw: String): SstpLink? {
            val line = raw.trim()
            if (HOST_ONLY.matches(line)) return parse("sstp://vpn:vpn@$line")
            if (!line.startsWith("sstp://", ignoreCase = true)) return null
            var rest = line.substring(7)
            val remark = rest.substringAfter('#', "").let(::dec)
            rest = rest.substringBefore('#')
            val query = rest.substringAfter('?', "")
            rest = rest.substringBefore('?').trimEnd('/')
            var user = "vpn"
            var pass = "vpn"
            val at = rest.lastIndexOf('@')
            if (at >= 0) {
                val cred = rest.substring(0, at)
                rest = rest.substring(at + 1)
                user = dec(cred.substringBefore(':')).ifEmpty { "vpn" }
                pass = if (cred.contains(':')) dec(cred.substringAfter(':')) else "vpn"
            }
            val host: String
            var port = 443
            if (rest.startsWith("[")) {
                host = rest.substringAfter('[').substringBefore(']')
                rest.substringAfter("]:", "").toIntOrNull()?.let { port = it }
            } else {
                host = rest.substringBefore(':')
                rest.substringAfter(':', "").toIntOrNull()?.let { port = it }
            }
            if (host.isBlank() || port !in 1..65535) return null
            if (!host.matches(Regex("""[A-Za-z0-9.:_-]+"""))) return null
            val params = query.split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=').lowercase(Locale.US) to dec(it.substringAfter('=')) }
            val ip = params["ip"].orEmpty().takeIf { it.matches(Regex("""[0-9A-Fa-f.:]+""")) }.orEmpty()
            return SstpLink(host, port, user, pass, ip, remark)
        }
    }
}

/** What the server handed out over IPCP. */
internal data class SstpLease(val address: String, val dns: List<String>, val mtu: Int)

internal class SstpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** MD4, MS-CHAPv2 (RFC 2759), MPPE master keys (RFC 3079) and the SSTP crypto binding. */
internal object SstpCrypto {
    private fun rotl(x: Int, s: Int) = (x shl s) or (x ushr (32 - s))
    private fun f(x: Int, y: Int, z: Int) = (x and y) or (x.inv() and z)
    private fun g(x: Int, y: Int, z: Int) = (x and y) or (x and z) or (y and z)
    private fun h(x: Int, y: Int, z: Int) = x xor y xor z

    fun md4(msg: ByteArray): ByteArray {
        val bitLen = msg.size.toLong() * 8L
        val padLen = ((55 - msg.size) % 64 + 64) % 64
        val buf = ByteArray(msg.size + 1 + padLen + 8)
        msg.copyInto(buf)
        buf[msg.size] = 0x80.toByte()
        for (i in 0 until 8) buf[buf.size - 8 + i] = (bitLen ushr (8 * i)).toByte()
        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476
        val x = IntArray(16)
        for (block in 0 until buf.size / 64) {
            for (i in 0 until 16) {
                val o = block * 64 + i * 4
                x[i] = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8) or
                    ((buf[o + 2].toInt() and 0xFF) shl 16) or ((buf[o + 3].toInt() and 0xFF) shl 24)
            }
            val aa = a
            val bb = b
            val cc = c
            val dd = d
            for (i in 0 until 16 step 4) {
                a = rotl(a + f(b, c, d) + x[i], 3)
                d = rotl(d + f(a, b, c) + x[i + 1], 7)
                c = rotl(c + f(d, a, b) + x[i + 2], 11)
                b = rotl(b + f(c, d, a) + x[i + 3], 19)
            }
            for (i in 0 until 4) {
                a = rotl(a + g(b, c, d) + x[i] + 0x5A827999, 3)
                d = rotl(d + g(a, b, c) + x[i + 4] + 0x5A827999, 5)
                c = rotl(c + g(d, a, b) + x[i + 8] + 0x5A827999, 9)
                b = rotl(b + g(c, d, a) + x[i + 12] + 0x5A827999, 13)
            }
            for (i in intArrayOf(0, 2, 1, 3)) {
                a = rotl(a + h(b, c, d) + x[i] + 0x6ED9EBA1, 3)
                d = rotl(d + h(a, b, c) + x[i + 8] + 0x6ED9EBA1, 9)
                c = rotl(c + h(d, a, b) + x[i + 4] + 0x6ED9EBA1, 11)
                b = rotl(b + h(c, d, a) + x[i + 12] + 0x6ED9EBA1, 15)
            }
            a += aa
            b += bb
            c += cc
            d += dd
        }
        val out = ByteArray(16)
        intArrayOf(a, b, c, d).forEachIndexed { i, v -> for (k in 0 until 4) out[i * 4 + k] = (v ushr (8 * k)).toByte() }
        return out
    }

    private fun sha1(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").run { parts.forEach { update(it) }; digest() }

    fun ntPasswordHash(password: String): ByteArray = md4(password.toByteArray(Charsets.UTF_16LE))

    fun challengeHash(peerChallenge: ByteArray, authChallenge: ByteArray, user: String): ByteArray =
        sha1(peerChallenge, authChallenge, user.toByteArray(Charsets.US_ASCII)).copyOf(8)

    private fun desKey(k7: ByteArray): ByteArray {
        val k = IntArray(7) { k7[it].toInt() and 0xFF }
        return byteArrayOf(
            k[0], (k[0] shl 7) or (k[1] ushr 1), (k[1] shl 6) or (k[2] ushr 2), (k[2] shl 5) or (k[3] ushr 3),
            (k[3] shl 4) or (k[4] ushr 4), (k[4] shl 3) or (k[5] ushr 5), (k[5] shl 2) or (k[6] ushr 6), k[6] shl 1,
        ).let { raw -> ByteArray(8) { (raw[it].toInt() and 0xFE).toByte() } }
    }

    private fun byteArrayOf(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun desEncrypt(key7: ByteArray, block: ByteArray): ByteArray {
        val key = desKey(key7)
        return runCatching {
            Cipher.getInstance("DES/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
                doFinal(block)
            }
        }.getOrElse {
            // Some builds only ship 3DES; K1 = K2 = K3 is plain DES.
            Cipher.getInstance("DESede/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key + key + key, "DESede"))
                doFinal(block)
            }
        }
    }

    fun challengeResponse(challenge: ByteArray, passwordHash: ByteArray): ByteArray {
        val z = passwordHash.copyOf(21)
        return desEncrypt(z.copyOfRange(0, 7), challenge) + desEncrypt(z.copyOfRange(7, 14), challenge) +
            desEncrypt(z.copyOfRange(14, 21), challenge)
    }

    fun ntResponse(authChallenge: ByteArray, peerChallenge: ByteArray, user: String, password: String): ByteArray =
        challengeResponse(challengeHash(peerChallenge, authChallenge, user), ntPasswordHash(password))

    private val MAGIC1 = "Magic server to client signing constant".toByteArray(Charsets.US_ASCII)
    private val MAGIC2 = "Pad to make it do more than one iteration".toByteArray(Charsets.US_ASCII)

    fun authenticatorResponse(
        password: String,
        ntResponse: ByteArray,
        peerChallenge: ByteArray,
        authChallenge: ByteArray,
        user: String,
    ): String {
        val hashHash = md4(ntPasswordHash(password))
        val digest = sha1(sha1(hashHash, ntResponse, MAGIC1), challengeHash(peerChallenge, authChallenge, user), MAGIC2)
        return "S=" + digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    private val MPPE_MAGIC1 = "This is the MPPE Master Key".toByteArray(Charsets.US_ASCII)
    private val MPPE_MAGIC2 =
        "On the client side, this is the send key; on the server side, it is the receive key.".toByteArray(Charsets.US_ASCII)
    private val MPPE_MAGIC3 =
        "On the client side, this is the receive key; on the server side, it is the send key.".toByteArray(Charsets.US_ASCII)
    private val SHS_PAD1 = ByteArray(40)
    private val SHS_PAD2 = ByteArray(40) { 0xF2.toByte() }

    fun masterKey(password: String, ntResponse: ByteArray): ByteArray =
        sha1(md4(ntPasswordHash(password)), ntResponse, MPPE_MAGIC1).copyOf(16)

    /** Client send key (Magic2) and receive key (Magic3), 16 bytes each — the HLAK of [MS-SSTP] 3.2.5.2. */
    fun hlak(password: String, ntResponse: ByteArray): ByteArray {
        val mk = masterKey(password, ntResponse)
        return sha1(mk, SHS_PAD1, MPPE_MAGIC2, SHS_PAD2).copyOf(16) + sha1(mk, SHS_PAD1, MPPE_MAGIC3, SHS_PAD2).copyOf(16)
    }

    fun chapMd5(id: Int, secret: String, challenge: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").run {
            update(id.toByte())
            update(secret.toByteArray(Charsets.UTF_8))
            update(challenge)
            digest()
        }

    /** Compound MAC over a Call-Connected packet whose MAC field is zero ([MS-SSTP] 3.2.5.2.1). */
    fun compoundMac(sha256: Boolean, hlak: ByteArray, packetWithZeroMac: ByteArray): ByteArray {
        val alg = if (sha256) "HmacSHA256" else "HmacSHA1"
        val seed = "SSTP inner method derived CMK".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(if (sha256) 32 else 20, 0, 1)
        val mac = Mac.getInstance(alg)
        mac.init(SecretKeySpec(hlak, alg))
        val cmk = mac.doFinal(seed)
        mac.init(SecretKeySpec(cmk, alg))
        return mac.doFinal(packetWithZeroMac).copyOf(32)
    }
}

/**
 * One SSTP call over an already-open TLS stream: [handshake] brings PPP up and returns the IPv4 lease, then
 * [startDataPlane] moves IP packets between the server and the phone's TUN.
 */
internal class SstpConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val serverCertDer: ByteArray,
    private val link: SstpLink,
    private val log: (String) -> Unit = {},
    private val closeTransport: () -> Unit = {},
) {
    companion object {
        const val PPP_LCP = 0xC021
        const val PPP_PAP = 0xC023
        const val PPP_CHAP = 0xC223
        const val PPP_EAP = 0xC227
        const val PPP_IPCP = 0x8021
        const val PPP_IP = 0x0021
        const val CHAP_MD5 = 0x05
        const val CHAP_MSCHAPV2 = 0x81
        const val MSG_CONNECT_REQUEST = 1
        const val MSG_CONNECT_ACK = 2
        const val MSG_CONNECT_NAK = 3
        const val MSG_CONNECTED = 4
        const val MSG_ABORT = 5
        const val MSG_DISCONNECT = 6
        const val MSG_DISCONNECT_ACK = 7
        const val MSG_ECHO_REQUEST = 8
        const val MSG_ECHO_RESPONSE = 9
        const val DEFAULT_MRU = 1500
        private const val RETRY_MS = 3_000L
        private const val KEEPALIVE_MS = 20_000L
        private const val DEAD_MS = 70_000L

        fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    }

    private sealed class Event {
        class Ppp(val protocol: Int, val info: ByteArray) : Event()
        class Control(val type: Int, val body: ByteArray) : Event()
        class Closed(val reason: String) : Event()
    }

    private val events = LinkedBlockingQueue<Event>()
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private val random = SecureRandom()
    private var nextId = random.nextInt(200)
    @Volatile private var tunSink: ((ByteArray, Int, Int) -> Unit)? = null
    @Volatile private var lastRxAt = System.currentTimeMillis()
    @Volatile var closeReason: String = ""
        private set
    private var mru = DEFAULT_MRU
    private var authMethod = 0 // 0 none, PPP_PAP, CHAP_MD5, CHAP_MSCHAPV2
    private var hlak = ByteArray(32)
    private var useSha256 = true
    private var nonce = ByteArray(32)

    val isClosed: Boolean get() = closed.get()

    private fun id(): Int {
        nextId = (nextId + 1) and 0xFF
        return nextId
    }

    // ------------------------------------------------------------------ writing

    private fun writeRaw(bytes: ByteArray) {
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }

    private fun controlPacket(type: Int, attributes: List<ByteArray> = emptyList()): ByteArray {
        val len = 8 + attributes.sumOf { it.size }
        val out = ByteArrayOutputStream(len)
        out.write(byteArrayOf(0x10, 0x01, (len ushr 8).toByte(), len.toByte(), 0, type.toByte(), 0, attributes.size.toByte()))
        attributes.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** A PPP frame (address/control FF 03 + protocol + info) inside an SSTP data packet. */
    private fun sendPpp(protocol: Int, info: ByteArray, off: Int = 0, len: Int = info.size) {
        val total = 4 + 4 + len
        val pkt = ByteArray(total)
        pkt[0] = 0x10
        pkt[1] = 0x00
        pkt[2] = (total ushr 8).toByte()
        pkt[3] = total.toByte()
        pkt[4] = 0xFF.toByte()
        pkt[5] = 0x03
        pkt[6] = (protocol ushr 8).toByte()
        pkt[7] = protocol.toByte()
        System.arraycopy(info, off, pkt, 8, len)
        writeRaw(pkt)
    }

    /** LCP/IPCP/PAP/CHAP packet: code, id, length, data. */
    private fun sendCp(protocol: Int, code: Int, id: Int, data: ByteArray) {
        val len = 4 + data.size
        sendPpp(protocol, byteArrayOf(code.toByte(), id.toByte(), (len ushr 8).toByte(), len.toByte()) + data)
    }

    // ------------------------------------------------------------------ reading

    private fun readFully(buf: ByteArray, off: Int, len: Int) {
        var pos = off
        while (pos < off + len) {
            val n = input.read(buf, pos, off + len - pos)
            if (n < 0) throw SstpException("server closed the connection")
            pos += n
        }
    }

    private fun readerLoop() {
        val head = ByteArray(4)
        val body = ByteArray(4096)
        try {
            while (!closed.get()) {
                readFully(head, 0, 4)
                val len = ((head[2].toInt() and 0x0F) shl 8) or (head[3].toInt() and 0xFF)
                if (head[0].toInt() != 0x10 || len < 4) throw SstpException("not an SSTP packet (0x%02x)".format(head[0]))
                val n = len - 4
                readFully(body, 0, n)
                lastRxAt = System.currentTimeMillis()
                if ((head[1].toInt() and 0x01) != 0) {
                    val type = if (n >= 2) be16(body, 0) else 0
                    events.put(Event.Control(type, body.copyOf(n)))
                    continue
                }
                var p = 0
                if (n >= 2 && body[0] == 0xFF.toByte() && body[1].toInt() == 0x03) p = 2
                if (n - p < 2) continue
                val protocol: Int
                if ((body[p].toInt() and 0x01) != 0) { // compressed 1-byte protocol field
                    protocol = body[p].toInt() and 0xFF
                    p += 1
                } else {
                    protocol = be16(body, p)
                    p += 2
                }
                if (protocol == PPP_IP) {
                    tunSink?.invoke(body, p, n - p)
                } else {
                    events.put(Event.Ppp(protocol, body.copyOfRange(p, n)))
                }
            }
        } catch (e: Exception) {
            events.put(Event.Closed(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun next(deadline: Long, waitMs: Long): Event? {
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) throw SstpException("timed out")
        return events.poll(minOf(left, waitMs), TimeUnit.MILLISECONDS)?.also {
            if (it is Event.Closed) throw SstpException(it.reason)
            if (it is Event.Control) handleCommonControl(it)
        }
    }

    /** Echo / disconnect / abort can come at any time. */
    private fun handleCommonControl(e: Event.Control) {
        when (e.type) {
            MSG_ECHO_REQUEST -> writeRaw(controlPacket(MSG_ECHO_RESPONSE))
            MSG_DISCONNECT -> {
                runCatching { writeRaw(controlPacket(MSG_DISCONNECT_ACK)) }
                throw SstpException("the server ended the call")
            }
            MSG_ABORT -> throw SstpException("the server aborted the call")
        }
    }

    /** Answers LCP echo / terminate and refuses protocols we do not speak; returns true when handled. */
    private fun handleCommonPpp(e: Event.Ppp): Boolean {
        val info = e.info
        if (e.protocol == PPP_LCP && info.size >= 4) {
            val code = info[0].toInt() and 0xFF
            val id = info[1].toInt() and 0xFF
            when (code) {
                9 -> { // Echo-Request -> Echo-Reply (magic 0)
                    val len = minOf(be16(info, 2), info.size)
                    val data = if (len > 8) info.copyOfRange(8, len) else ByteArray(0)
                    sendCp(PPP_LCP, 10, id, ByteArray(4) + data)
                    return true
                }
                10, 11 -> return true // Echo-Reply, Discard-Request
                5 -> { // Terminate-Request
                    sendCp(PPP_LCP, 6, id, ByteArray(0))
                    throw SstpException("the server closed PPP (LCP terminate)")
                }
                8 -> return true // Protocol-Reject of something we sent (e.g. IPv6) — fine
            }
            return false
        }
        if (e.protocol !in setOf(PPP_LCP, PPP_PAP, PPP_CHAP, PPP_IPCP)) {
            // IPV6CP, CCP, … : LCP Protocol-Reject (RFC 1661 5.7)
            val data = byteArrayOf((e.protocol ushr 8).toByte(), e.protocol.toByte()) + info.copyOf(minOf(info.size, mru - 8))
            sendCp(PPP_LCP, 8, id(), data)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ handshake

    private fun httpHandshake() {
        val req = "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\r\n" +
            "Content-Length: 18446744073709551615\r\n" +
            "Host: ${link.host}\r\n" +
            "SSTPCORRELATIONID: {${UUID.randomUUID().toString().uppercase(Locale.US)}}\r\n\r\n"
        writeRaw(req.toByteArray(Charsets.US_ASCII))
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw SstpException("the server closed the connection before answering SSTP")
            head.append(b.toChar())
            if (head.length > 8192) throw SstpException("the server answered with something that is not SSTP")
        }
        val status = head.lineSequence().firstOrNull().orEmpty()
        if (!Regex("""^HTTP/1\.[01] 200\b""").containsMatchIn(status)) {
            throw SstpException("the server is not an SSTP server here (${status.take(60)})")
        }
    }

    private fun sstpConnect(deadline: Long) {
        val encapsulated = byteArrayOf(0, 1, 0, 6, 0, 1) // Encapsulated Protocol ID = PPP
        var sentAt = 0L
        while (true) {
            if (System.currentTimeMillis() - sentAt > 5_000L) {
                writeRaw(controlPacket(MSG_CONNECT_REQUEST, listOf(encapsulated)))
                sentAt = System.currentTimeMillis()
            }
            val e = next(deadline, 500L) ?: continue
            if (e !is Event.Control) continue
            when (e.type) {
                MSG_CONNECT_ACK -> {
                    // body: type(2) numAttr(2) | attr: 0, id=4, len(2)=40, 3 reserved, bitmask, nonce(32)
                    val b = e.body
                    if (b.size >= 4 + 40) {
                        val bitmask = b[4 + 7].toInt() and 0xFF
                        useSha256 = (bitmask and 0x02) != 0
                        nonce = b.copyOfRange(4 + 8, 4 + 40)
                    }
                    return
                }
                MSG_CONNECT_NAK -> throw SstpException("the server refused the SSTP call")
            }
        }
    }

    private fun parseOptions(data: ByteArray, from: Int): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var p = from
        while (p + 2 <= data.size) {
            val type = data[p].toInt() and 0xFF
            val len = data[p + 1].toInt() and 0xFF
            if (len < 2 || p + len > data.size) break
            out += type to data.copyOfRange(p + 2, p + len)
            p += len
        }
        return out
    }

    private fun encodeOptions(options: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        options.forEach { (t, v) ->
            out.write(t)
            out.write(v.size + 2)
            out.write(v)
        }
        return out.toByteArray()
    }

    /** Body of a CP packet (after code/id/length), honouring the length field. */
    private fun cpData(info: ByteArray): ByteArray {
        val len = minOf(be16(info, 2), info.size)
        return if (len > 4) info.copyOfRange(4, len) else ByteArray(0)
    }

    private fun negotiateLcp(deadline: Long) {
        var ourId = id()
        var ourAcked = false
        var peerAcked = false
        var sendMru = true
        var tries = 0
        var sentAt = 0L
        fun ourRequest(): List<Pair<Int, ByteArray>> =
            if (sendMru) listOf(1 to byteArrayOf((mru ushr 8).toByte(), mru.toByte())) else emptyList()
        while (!(ourAcked && peerAcked)) {
            if (!ourAcked && System.currentTimeMillis() - sentAt > RETRY_MS) {
                if (++tries > 10) throw SstpException("PPP (LCP) did not come up")
                ourId = id()
                sendCp(PPP_LCP, 1, ourId, encodeOptions(ourRequest()))
                sentAt = System.currentTimeMillis()
            }
            val e = next(deadline, 300L) ?: continue
            if (e !is Event.Ppp) continue
            if (e.protocol != PPP_LCP || e.info.size < 4) {
                handleCommonPpp(e)
                continue
            }
            val code = e.info[0].toInt() and 0xFF
            val pid = e.info[1].toInt() and 0xFF
            val data = cpData(e.info)
            when (code) {
                1 -> { // peer Configure-Request
                    val options = parseOptions(data, 0)
                    // Never accept PFC (7) / ACFC (8) or anything we do not understand.
                    val reject = options.filter { it.first !in setOf(1, 2, 3, 5) }
                    if (reject.isNotEmpty()) {
                        sendCp(PPP_LCP, 4, pid, encodeOptions(reject))
                        peerAcked = false
                        continue
                    }
                    val auth = options.firstOrNull { it.first == 3 }?.second
                    var nak: ByteArray? = null
                    authMethod = 0
                    if (auth != null && auth.size >= 2) {
                        val proto = be16(auth, 0)
                        when {
                            proto == PPP_PAP -> authMethod = PPP_PAP
                            proto == PPP_CHAP && auth.size >= 3 && (auth[2].toInt() and 0xFF) == CHAP_MSCHAPV2 ->
                                authMethod = CHAP_MSCHAPV2
                            proto == PPP_CHAP && auth.size >= 3 && (auth[2].toInt() and 0xFF) == CHAP_MD5 ->
                                authMethod = CHAP_MD5
                            else -> nak = byteArrayOf((PPP_CHAP ushr 8).toByte(), PPP_CHAP.toByte(), CHAP_MSCHAPV2.toByte())
                        }
                    }
                    if (nak != null) {
                        sendCp(PPP_LCP, 3, pid, encodeOptions(listOf(3 to nak)))
                        peerAcked = false
                        continue
                    }
                    sendCp(PPP_LCP, 2, pid, data)
                    peerAcked = true
                }
                2 -> if (pid == ourId) ourAcked = true
                3 -> if (pid == ourId) { // Nak: take the server's MRU
                    parseOptions(data, 0).firstOrNull { it.first == 1 && it.second.size == 2 }?.let {
                        mru = be16(it.second, 0).coerceIn(576, DEFAULT_MRU)
                    }
                    sentAt = 0L
                }
                4 -> if (pid == ourId) {
                    if (parseOptions(data, 0).any { it.first == 1 }) sendMru = false
                    sentAt = 0L
                }
                else -> handleCommonPpp(e)
            }
        }
        log("LCP up (mru=$mru auth=${authName()})")
    }

    private fun authName() = when (authMethod) {
        PPP_PAP -> "PAP"
        CHAP_MD5 -> "CHAP-MD5"
        CHAP_MSCHAPV2 -> "MS-CHAPv2"
        else -> "none"
    }

    private fun authenticate(deadline: Long) {
        when (authMethod) {
            0 -> return
            PPP_PAP -> {
                val user = link.user.toByteArray(Charsets.UTF_8)
                val pass = link.password.toByteArray(Charsets.UTF_8)
                val data = byteArrayOf(user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass
                var papId = id()
                var sentAt = 0L
                var tries = 0
                while (true) {
                    if (System.currentTimeMillis() - sentAt > RETRY_MS) {
                        if (++tries > 6) throw SstpException("no answer to the username/password")
                        papId = id()
                        sendCp(PPP_PAP, 1, papId, data)
                        sentAt = System.currentTimeMillis()
                    }
                    val e = next(deadline, 300L) as? Event.Ppp ?: continue
                    if (e.protocol != PPP_PAP || e.info.size < 4) {
                        handleCommonPpp(e)
                        continue
                    }
                    if ((e.info[1].toInt() and 0xFF) != papId) continue
                    when (e.info[0].toInt() and 0xFF) {
                        2 -> return
                        3 -> throw SstpException("the server rejected the username/password (PAP)")
                    }
                }
            }
            else -> {
                while (true) {
                    val e = next(deadline, 1_000L) as? Event.Ppp ?: continue
                    if (e.protocol != PPP_CHAP || e.info.size < 4) {
                        handleCommonPpp(e)
                        continue
                    }
                    val code = e.info[0].toInt() and 0xFF
                    val cid = e.info[1].toInt() and 0xFF
                    val data = cpData(e.info)
                    when (code) {
                        1 -> { // Challenge: value-size, value, name
                            if (data.isEmpty()) continue
                            val vs = data[0].toInt() and 0xFF
                            if (data.size < 1 + vs) continue
                            val challenge = data.copyOfRange(1, 1 + vs)
                            val name = link.user.toByteArray(Charsets.UTF_8)
                            if (authMethod == CHAP_MD5) {
                                val v = SstpCrypto.chapMd5(cid, link.password, challenge)
                                sendCp(PPP_CHAP, 2, cid, byteArrayOf(v.size.toByte()) + v + name)
                                pendingMsChap = null
                            } else {
                                if (vs != 16) throw SstpException("bad MS-CHAPv2 challenge")
                                val peer = ByteArray(16).also { random.nextBytes(it) }
                                val nt = SstpCrypto.ntResponse(challenge, peer, link.user, link.password)
                                val value = peer + ByteArray(8) + nt + byteArrayOf(0)
                                sendCp(PPP_CHAP, 2, cid, byteArrayOf(49) + value + name)
                                pendingMsChap = Triple(challenge, peer, nt)
                            }
                        }
                        3 -> { // Success
                            pendingMsChap?.let { (challenge, peer, nt) ->
                                val msg = String(data, Charsets.US_ASCII)
                                val expected = SstpCrypto.authenticatorResponse(link.password, nt, peer, challenge, link.user)
                                if (!msg.uppercase(Locale.US).startsWith(expected.uppercase(Locale.US))) {
                                    throw SstpException("the server could not prove it knows the password (MS-CHAPv2)")
                                }
                                hlak = SstpCrypto.hlak(link.password, nt)
                            }
                            return
                        }
                        4 -> throw SstpException("the server rejected the username/password (${authName()})")
                    }
                }
            }
        }
    }

    private var pendingMsChap: Triple<ByteArray, ByteArray, ByteArray>? = null

    /** Builds the 112-byte Call-Connected packet with its crypto binding. */
    internal fun buildCallConnected(): ByteArray {
        val certHash = MessageDigest.getInstance(if (useSha256) "SHA-256" else "SHA-1").digest(serverCertDer).copyOf(32)
        val attr = ByteArray(104)
        attr[1] = 3
        attr[2] = 0
        attr[3] = 104
        attr[7] = (if (useSha256) 2 else 1).toByte()
        nonce.copyInto(attr, 8)
        certHash.copyInto(attr, 40)
        val pkt = controlPacket(MSG_CONNECTED, listOf(attr))
        val mac = SstpCrypto.compoundMac(useSha256, hlak, pkt)
        mac.copyInto(pkt, 8 + 72)
        return pkt
    }

    private fun negotiateIpcp(deadline: Long): SstpLease {
        var address = ByteArray(4)
        var dns1: ByteArray? = ByteArray(4)
        var dns2: ByteArray? = ByteArray(4)
        var ourId = id()
        var ourAcked = false
        var peerAcked = false
        var sentAt = 0L
        var tries = 0
        fun request(): List<Pair<Int, ByteArray>> = buildList {
            add(3 to address)
            dns1?.let { add(129 to it) }
            dns2?.let { add(131 to it) }
        }
        while (!(ourAcked && peerAcked)) {
            if (!ourAcked && System.currentTimeMillis() - sentAt > RETRY_MS) {
                if (++tries > 10) throw SstpException("the server did not hand out an IP address (IPCP)")
                ourId = id()
                sendCp(PPP_IPCP, 1, ourId, encodeOptions(request()))
                sentAt = System.currentTimeMillis()
            }
            val e = next(deadline, 300L) as? Event.Ppp ?: continue
            if (e.protocol != PPP_IPCP || e.info.size < 4) {
                handleCommonPpp(e)
                continue
            }
            val code = e.info[0].toInt() and 0xFF
            val pid = e.info[1].toInt() and 0xFF
            val data = cpData(e.info)
            when (code) {
                1 -> {
                    val options = parseOptions(data, 0)
                    val reject = options.filter { it.first != 3 }
                    if (reject.isNotEmpty()) {
                        sendCp(PPP_IPCP, 4, pid, encodeOptions(reject))
                    } else {
                        sendCp(PPP_IPCP, 2, pid, data)
                        peerAcked = true
                    }
                }
                2 -> if (pid == ourId) {
                    if (address.all { it.toInt() == 0 }) sentAt = 0L else ourAcked = true
                }
                3 -> if (pid == ourId) {
                    parseOptions(data, 0).forEach { (t, v) ->
                        if (v.size == 4) when (t) {
                            3 -> address = v
                            129 -> dns1 = v
                            131 -> dns2 = v
                        }
                    }
                    sentAt = 0L
                }
                4 -> if (pid == ourId) {
                    parseOptions(data, 0).forEach { (t, _) ->
                        when (t) {
                            3 -> throw SstpException("the server refused to assign an IP address")
                            129 -> dns1 = null
                            131 -> dns2 = null
                        }
                    }
                    sentAt = 0L
                }
                else -> handleCommonPpp(e)
            }
        }
        fun ip(b: ByteArray?) = b?.takeIf { it.size == 4 && it.any { x -> x.toInt() != 0 } }
            ?.let { InetAddress.getByAddress(it).hostAddress }
        val dns = listOfNotNull(ip(dns1), ip(dns2)).distinct()
        val lease = SstpLease(ip(address) ?: throw SstpException("no IP address"), dns, minOf(mru, 1400))
        log("IPCP up: ${lease.address} dns=${lease.dns}")
        return lease
    }

    /** HTTP → SSTP call → LCP → auth → crypto binding → IPCP. */
    fun handshake(timeoutMs: Long = 20_000L): SstpLease {
        val deadline = System.currentTimeMillis() + timeoutMs
        val done = AtomicBoolean(false)
        // A blocked TLS read cannot see the deadline; the watchdog closes the transport instead.
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs + 500L)
                if (!done.get()) close("timed out")
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        try {
            httpHandshake()
            Thread(null, { readerLoop() }, "sstp-read", 256 * 1024L).apply { isDaemon = true; start() }
            sstpConnect(deadline)
            negotiateLcp(deadline)
            authenticate(deadline)
            writeRaw(buildCallConnected())
            val lease = negotiateIpcp(deadline)
            done.set(true)
            watchdog.interrupt()
            return lease
        } catch (e: Exception) {
            done.set(true)
            watchdog.interrupt()
            val reason = if (closed.get() && closeReason == "timed out") "timed out" else e.message
            close("handshake failed: $reason")
            if (reason == "timed out") throw SstpException("timed out", e)
            throw if (e is SstpException) e else SstpException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    /**
     * Pumps packets: server → [tunOut] on the reader thread, [tunIn] → server on its own thread, and a control
     * thread for echo/keep-alive. [onClosed] runs once when the call ends for any reason.
     */
    fun startDataPlane(tunIn: InputStream, tunOut: OutputStream, onClosed: (String) -> Unit) {
        val fired = AtomicBoolean(false)
        val finish: (String) -> Unit = { reason ->
            close(reason)
            if (fired.compareAndSet(false, true)) onClosed(closeReason.ifBlank { reason })
        }
        tunSink = { buf, off, len ->
            try {
                tunOut.write(buf, off, len)
            } catch (e: IOException) {
                finish("TUN closed: ${e.message}")
            }
        }
        Thread(null, {
            val buf = ByteArray(65_536)
            try {
                while (!closed.get()) {
                    val n = tunIn.read(buf)
                    if (n < 0) break
                    if (n == 0) { // non-blocking TUN (Android < 10): nothing queued yet
                        Thread.sleep(3L)
                        continue
                    }
                    if ((buf[0].toInt() ushr 4) != 4) continue // IPv4 only (IPV6CP is refused)
                    if (n > mru) continue
                    sendPpp(PPP_IP, buf, 0, n)
                }
                finish("TUN closed")
            } catch (e: Exception) {
                finish(e.message ?: "send failed")
            }
        }, "sstp-up", 256 * 1024L).apply { isDaemon = true; start() }
        Thread(null, {
            var lastEchoAt = System.currentTimeMillis()
            try {
                while (!closed.get()) {
                    val e = events.poll(1_000L, TimeUnit.MILLISECONDS)
                    when (e) {
                        is Event.Closed -> throw SstpException(e.reason)
                        is Event.Control -> handleCommonControl(e)
                        is Event.Ppp -> handleCommonPpp(e)
                        null -> Unit
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastRxAt > DEAD_MS) throw SstpException("the server stopped answering")
                    if (now - lastEchoAt > KEEPALIVE_MS) {
                        writeRaw(controlPacket(MSG_ECHO_REQUEST))
                        lastEchoAt = now
                    }
                }
            } catch (e: Exception) {
                finish(e.message ?: "control failed")
            }
        }, "sstp-ctl", 128 * 1024L).apply { isDaemon = true; start() }
    }

    fun close(reason: String = "closed") {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        tunSink = null
        runCatching { writeRaw(controlPacket(MSG_DISCONNECT)) }
        runCatching { closeTransport() }
        events.offer(Event.Closed(reason))
    }
}

/** Opens the TLS connection to an SSTP server. VPN Gate servers use self-signed certificates, so none is pinned. */
internal object SstpDialer {
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    class Opened(val socket: SSLSocket, val certDer: ByteArray, val connection: SstpConnection)

    fun open(
        link: SstpLink,
        protect: (Socket) -> Unit,
        resolve: (String) -> InetAddress,
        timeoutMs: Int = 8_000,
        log: (String) -> Unit = {},
    ): Opened {
        val raw = Socket()
        try {
            // Android creates a Socket's file descriptor lazily (first option / bind / connect); VpnService.protect()
            // on a descriptor-less socket returns false. Touch an option first, bind as a last resort.
            raw.tcpNoDelay = true
            raw.keepAlive = true
            try {
                protect(raw)
            } catch (first: Exception) {
                runCatching { raw.bind(null) }
                protect(raw)
            }
            val target = if (link.ip.isNotBlank()) InetAddress.getByName(link.ip) else resolve(link.host)
            raw.connect(InetSocketAddress(target, link.port), timeoutMs)
            raw.soTimeout = timeoutMs
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
            val ssl = ctx.socketFactory.createSocket(raw, link.host, link.port, true) as SSLSocket
            if (!link.host.matches(Regex("""[0-9.]+|.*:.*"""))) {
                ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(link.host)) }
            }
            ssl.startHandshake()
            val cert = ssl.session.peerCertificates.firstOrNull()?.encoded
                ?: throw SstpException("the server sent no certificate")
            ssl.soTimeout = 0
            val conn = SstpConnection(ssl.inputStream, ssl.outputStream, cert, link, log) { runCatching { ssl.close() } }
            return Opened(ssl, cert, conn)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw if (e is SstpException) e else SstpException("${link.endpoint}: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}

/**
 * Public SSTP server list. The VPN Gate site itself is filtered in Iran, so the list comes from the
 * `sstp.wireconf.workers.dev` mirror (JSON at `/api`, plain host names at `/api?format=txt`).
 * Parsed without a JSON library so it stays testable on a plain JVM.
 */
internal object SstpServerList {
    const val MIRROR_API = "https://sstp.wireconf.workers.dev/api"
    val SORTS = listOf("site", "speed", "uptime", "new")

    data class Server(
        val host: String,
        val port: Int,
        val ip: String = "",
        val countryCode: String = "",
        val country: String = "",
        val flag: String = "",
        val mbps: Double? = null,
        val uptimeDays: Int? = null,
    ) {
        /** Remark used as the row name: country first (for the Cat label), then the server's own line speed. */
        fun toLink(): SstpLink {
            val parts = ArrayList<String>()
            parts += listOf(country.ifBlank { countryCode.ifBlank { "SSTP" } }, flag).filter { it.isNotBlank() }.joinToString(" ")
            mbps?.let { parts += "${it.toInt()} Mbps" }
            parts += host.substringBefore('.')
            return SstpLink(host = host, port = port, ip = ip, remark = parts.joinToString(" · "))
        }
    }

    fun apiUrl(sort: String = "site", limit: Int = 30, minSpeed: Int? = null, txt: Boolean = false): String = buildString {
        append(MIRROR_API).append("?sort=").append(if (sort in SORTS) sort else "site")
        append("&limit=").append(limit.coerceIn(1, 200))
        if (minSpeed != null && minSpeed > 0) append("&min_speed=").append(minSpeed)
        if (txt) append("&format=txt")
    }

    private fun splitHost(raw: String): Pair<String, Int>? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        val host = v.substringBefore(':')
        val port = if (v.contains(':')) v.substringAfter(':').toIntOrNull() ?: return null else 443
        if (port !in 1..65535 || !host.matches(Regex("""[A-Za-z0-9.-]+"""))) return null
        return host.lowercase(Locale.US) to port
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                if (n == 'u' && i + 5 < s.length) {
                    s.substring(i + 2, i + 6).toIntOrNull(16)?.let { out.append(it.toChar()) }
                    i += 6
                    continue
                }
                out.append(
                    when (n) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> n
                    },
                )
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private val objectPattern = Regex("""\{[^{}]*"host"\s*:[^{}]*\}""")

    private fun field(obj: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|-?[0-9.]+)").find(obj) ?: return null
        return if (m.groupValues[2].isNotEmpty() || m.groupValues[1].startsWith("\"")) unescape(m.groupValues[2]) else m.groupValues[1]
    }

    fun parseJson(body: String): List<Server> = objectPattern.findAll(body).mapNotNull { m ->
        val obj = m.value
        val (host, port) = splitHost(field(obj, "host").orEmpty()) ?: return@mapNotNull null
        Server(
            host = host,
            port = field(obj, "port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: port,
            ip = field(obj, "ip").orEmpty().takeIf { it.matches(Regex("""[0-9A-Fa-f.:]+""")) }.orEmpty(),
            countryCode = field(obj, "cc").orEmpty().take(3),
            country = field(obj, "country").orEmpty().take(40),
            flag = field(obj, "flag").orEmpty().take(8),
            mbps = field(obj, "speed_mbps")?.toDoubleOrNull(),
            uptimeDays = field(obj, "uptime_days")?.toDoubleOrNull()?.toInt(),
        )
    }.distinctBy { "${it.host}:${it.port}" }.toList()

    fun parseTxt(body: String): List<Server> = body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line -> splitHost(line)?.let { (h, p) -> Server(h, p) } }
        .distinctBy { "${it.host}:${it.port}" }
        .toList()

    /** Plain-text subscription body: one `sstp://` link per line. */
    fun subscriptionText(servers: List<Server>): String = servers.joinToString("\n") { it.toLink().toUri() }

    fun httpGet(url: String, timeoutMs: Int = 12_000): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json, text/plain")
            conn.setRequestProperty("User-Agent", "CatClient-SSTP")
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /** JSON first (country, IP, speed); the plain host list if the JSON cannot be read. */
    fun fetch(sort: String, limit: Int): List<Server> {
        val json = runCatching { parseJson(httpGet(apiUrl(sort, limit))) }
        json.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val txt = runCatching { parseTxt(httpGet(apiUrl(sort, limit, txt = true))) }
        txt.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        throw IOException(
            json.exceptionOrNull()?.message ?: txt.exceptionOrNull()?.message ?: "the server list was empty",
        )
    }
}

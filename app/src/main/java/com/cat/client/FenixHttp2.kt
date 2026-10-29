package com.cat.client

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * beta211: a small, dependency-free HTTP/2 client for Cat Fox VPN (RFC 9113 + HPACK RFC 7541).
 *
 * Mozilla's Firefox VPN proxies (`*.m1.fastly-masque.net:2499`) are reached the way Firefox itself reaches them:
 * TLS with ALPN `h2`, then one ordinary HTTP/2 `CONNECT host:port` stream per website connection, authorised with
 * `proxy-authorization: Bearer <proxy pass>`. Every maintained client (Firefox's own `newProxyInfo("https")`, and
 * the independent Android/desktop clients) uses HTTP/2; Cat's old bridge only offered `http/1.1` ALPN with an
 * HTTP/1.1 CONNECT line, which the edge no longer serves reliably — «Cat Fox VPN pings but never connects».
 *
 * One TLS connection carries many tunnels (multiplexed streams), so a page that opens 20 connections costs one
 * handshake instead of 20 — much faster on filtered Iranian lines where every handshake is slow.
 *
 * Pure JVM (no Android APIs) so it is unit-tested on the JVM.
 */
internal class FenixH2StatusException(val status: Int, message: String) : IOException(message)

internal object FenixHpack {
    private val HUFFMAN_CODES = intArrayOf(
        0x1ff8, 0x7fffd8, 0xfffffe2, 0xfffffe3, 0xfffffe4, 0xfffffe5, 0xfffffe6, 0xfffffe7,
        0xfffffe8, 0xffffea, 0x3ffffffc, 0xfffffe9, 0xfffffea, 0x3ffffffd, 0xfffffeb, 0xfffffec,
        0xfffffed, 0xfffffee, 0xfffffef, 0xffffff0, 0xffffff1, 0xffffff2, 0x3ffffffe, 0xffffff3,
        0xffffff4, 0xffffff5, 0xffffff6, 0xffffff7, 0xffffff8, 0xffffff9, 0xffffffa, 0xffffffb,
        0x14, 0x3f8, 0x3f9, 0xffa, 0x1ff9, 0x15, 0xf8, 0x7fa,
        0x3fa, 0x3fb, 0xf9, 0x7fb, 0xfa, 0x16, 0x17, 0x18,
        0x0, 0x1, 0x2, 0x19, 0x1a, 0x1b, 0x1c, 0x1d,
        0x1e, 0x1f, 0x5c, 0xfb, 0x7ffc, 0x20, 0xffb, 0x3fc,
        0x1ffa, 0x21, 0x5d, 0x5e, 0x5f, 0x60, 0x61, 0x62,
        0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a,
        0x6b, 0x6c, 0x6d, 0x6e, 0x6f, 0x70, 0x71, 0x72,
        0xfc, 0x73, 0xfd, 0x1ffb, 0x7fff0, 0x1ffc, 0x3ffc, 0x22,
        0x7ffd, 0x3, 0x23, 0x4, 0x24, 0x5, 0x25, 0x26,
        0x27, 0x6, 0x74, 0x75, 0x28, 0x29, 0x2a, 0x7,
        0x2b, 0x76, 0x2c, 0x8, 0x9, 0x2d, 0x77, 0x78,
        0x79, 0x7a, 0x7b, 0x7ffe, 0x7fc, 0x3ffd, 0x1ffd, 0xffffffc,
        0xfffe6, 0x3fffd2, 0xfffe7, 0xfffe8, 0x3fffd3, 0x3fffd4, 0x3fffd5, 0x7fffd9,
        0x3fffd6, 0x7fffda, 0x7fffdb, 0x7fffdc, 0x7fffdd, 0x7fffde, 0xffffeb, 0x7fffdf,
        0xffffec, 0xffffed, 0x3fffd7, 0x7fffe0, 0xffffee, 0x7fffe1, 0x7fffe2, 0x7fffe3,
        0x7fffe4, 0x1fffdc, 0x3fffd8, 0x7fffe5, 0x3fffd9, 0x7fffe6, 0x7fffe7, 0xffffef,
        0x3fffda, 0x1fffdd, 0xfffe9, 0x3fffdb, 0x3fffdc, 0x7fffe8, 0x7fffe9, 0x1fffde,
        0x7fffea, 0x3fffdd, 0x3fffde, 0xfffff0, 0x1fffdf, 0x3fffdf, 0x7fffeb, 0x7fffec,
        0x1fffe0, 0x1fffe1, 0x3fffe0, 0x1fffe2, 0x7fffed, 0x3fffe1, 0x7fffee, 0x7fffef,
        0xfffea, 0x3fffe2, 0x3fffe3, 0x3fffe4, 0x7ffff0, 0x3fffe5, 0x3fffe6, 0x7ffff1,
        0x3ffffe0, 0x3ffffe1, 0xfffeb, 0x7fff1, 0x3fffe7, 0x7ffff2, 0x3fffe8, 0x1ffffec,
        0x3ffffe2, 0x3ffffe3, 0x3ffffe4, 0x7ffffde, 0x7ffffdf, 0x3ffffe5, 0xfffff1, 0x1ffffed,
        0x7fff2, 0x1fffe3, 0x3ffffe6, 0x7ffffe0, 0x7ffffe1, 0x3ffffe7, 0x7ffffe2, 0xfffff2,
        0x1fffe4, 0x1fffe5, 0x3ffffe8, 0x3ffffe9, 0xffffffd, 0x7ffffe3, 0x7ffffe4, 0x7ffffe5,
        0xfffec, 0xfffff3, 0xfffed, 0x1fffe6, 0x3fffe9, 0x1fffe7, 0x1fffe8, 0x7ffff3,
        0x3fffea, 0x3fffeb, 0x1ffffee, 0x1ffffef, 0xfffff4, 0xfffff5, 0x3ffffea, 0x7ffff4,
        0x3ffffeb, 0x7ffffe6, 0x3ffffec, 0x3ffffed, 0x7ffffe7, 0x7ffffe8, 0x7ffffe9, 0x7ffffea,
        0x7ffffeb, 0xffffffe, 0x7ffffec, 0x7ffffed, 0x7ffffee, 0x7ffffef, 0x7fffff0, 0x3ffffee,
        0x3fffffff,
    )
    private val HUFFMAN_LENGTHS = byteArrayOf(
        13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28, 28, 28, 28, 28,
        28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28, 6, 10, 10, 12, 13, 6, 8, 11,
        10, 10, 8, 11, 8, 6, 6, 6, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8,
        15, 6, 12, 10, 13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6, 15, 5, 6, 5,
        6, 5, 6, 6, 6, 5, 7, 7, 6, 6, 6, 5, 6, 7, 6, 5, 5, 6, 7, 7,
        7, 7, 7, 15, 11, 14, 13, 28, 20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23,
        23, 23, 24, 23, 24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24,
        22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23, 21, 21, 22, 21,
        23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23, 26, 26, 20, 19, 22, 23, 22, 25,
        26, 26, 26, 27, 27, 26, 24, 25, 19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26,
        28, 27, 27, 27, 20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23,
        26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26, 30,
    )
    val STATIC_TABLE: Array<Pair<String, String>> = arrayOf(
        ":authority" to "",
        ":method" to "GET",
        ":method" to "POST",
        ":path" to "/",
        ":path" to "/index.html",
        ":scheme" to "http",
        ":scheme" to "https",
        ":status" to "200",
        ":status" to "204",
        ":status" to "206",
        ":status" to "304",
        ":status" to "400",
        ":status" to "404",
        ":status" to "500",
        "accept-charset" to "",
        "accept-encoding" to "gzip, deflate",
        "accept-language" to "",
        "accept-ranges" to "",
        "accept" to "",
        "access-control-allow-origin" to "",
        "age" to "",
        "allow" to "",
        "authorization" to "",
        "cache-control" to "",
        "content-disposition" to "",
        "content-encoding" to "",
        "content-language" to "",
        "content-length" to "",
        "content-location" to "",
        "content-range" to "",
        "content-type" to "",
        "cookie" to "",
        "date" to "",
        "etag" to "",
        "expect" to "",
        "expires" to "",
        "from" to "",
        "host" to "",
        "if-match" to "",
        "if-modified-since" to "",
        "if-none-match" to "",
        "if-range" to "",
        "if-unmodified-since" to "",
        "last-modified" to "",
        "link" to "",
        "location" to "",
        "max-forwards" to "",
        "proxy-authenticate" to "",
        "proxy-authorization" to "",
        "range" to "",
        "referer" to "",
        "refresh" to "",
        "retry-after" to "",
        "server" to "",
        "set-cookie" to "",
        "strict-transport-security" to "",
        "transfer-encoding" to "",
        "user-agent" to "",
        "vary" to "",
        "via" to "",
        "www-authenticate" to "",
    )

    private const val DEFAULT_TABLE_SIZE = 4096

    // Huffman decoding trie: node -> child index for bit 0 / bit 1; leaves carry symbol + 1 (negative marker).
    private val trieZero: IntArray
    private val trieOne: IntArray
    private val trieSymbol: IntArray

    init {
        val maxNodes = 1024
        val zero = IntArray(maxNodes) { -1 }
        val one = IntArray(maxNodes) { -1 }
        val sym = IntArray(maxNodes) { -1 }
        var count = 1
        for (s in 0 until 257) {
            val code = HUFFMAN_CODES[s]
            val len = HUFFMAN_LENGTHS[s].toInt()
            var node = 0
            for (i in len - 1 downTo 0) {
                val bit = (code ushr i) and 1
                val next = if (bit == 0) zero[node] else one[node]
                node = if (next >= 0) {
                    next
                } else {
                    val created = count++
                    if (bit == 0) zero[node] = created else one[node] = created
                    created
                }
            }
            sym[node] = s
        }
        trieZero = zero
        trieOne = one
        trieSymbol = sym
    }

    fun huffmanDecode(src: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len * 2)
        var node = 0
        var bitsSinceSymbol = 0
        for (i in off until off + len) {
            val b = src[i].toInt() and 0xFF
            for (shift in 7 downTo 0) {
                val bit = (b ushr shift) and 1
                node = if (bit == 0) trieZero[node] else trieOne[node]
                if (node < 0) throw IOException("hpack: bad huffman code")
                bitsSinceSymbol++
                val s = trieSymbol[node]
                if (s >= 0) {
                    if (s == 256) throw IOException("hpack: EOS in string")
                    out.write(s)
                    node = 0
                    bitsSinceSymbol = 0
                }
            }
        }
        if (bitsSinceSymbol > 7) throw IOException("hpack: huffman padding too long")
        return out.toByteArray()
    }

    fun encodeInt(out: ByteArrayOutputStream, firstByteFlags: Int, prefixBits: Int, value: Int) {
        val max = (1 shl prefixBits) - 1
        if (value < max) {
            out.write(firstByteFlags or value)
            return
        }
        out.write(firstByteFlags or max)
        var rest = value - max
        while (rest >= 0x80) {
            out.write((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest)
    }

    private fun encodeString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        encodeInt(out, 0x00, 7, bytes.size)
        out.write(bytes, 0, bytes.size)
    }

    /**
     * Encodes a header block without touching any dynamic table (literals only, never Huffman): always valid, and
     * the decoder on the far side keeps no state for us. Pseudo-headers use their static-table name index.
     * `proxy-authorization` is sent «never indexed» so no intermediary may cache the token.
     */
    fun encode(headers: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream(256)
        for ((rawName, value) in headers) {
            val name = rawName.lowercase()
            val sensitive = name == "proxy-authorization" || name == "authorization" || name == "cookie"
            val staticIndex = STATIC_TABLE.indexOfFirst { it.first == name } + 1
            val flags = if (sensitive) 0x10 else 0x00
            if (staticIndex > 0) {
                encodeInt(out, flags, 4, staticIndex)
            } else {
                out.write(flags)
                encodeString(out, name)
            }
            encodeString(out, value)
        }
        return out.toByteArray()
    }

    /** Stateful HPACK decoder (one per connection, header blocks must be fed in order). */
    class Decoder(private var maxTableSize: Int = DEFAULT_TABLE_SIZE) {
        private val dynamic = ArrayDeque<Pair<String, String>>() // first = newest
        private var tableSize = 0
        private val settingsLimit = maxTableSize

        fun dynamicEntries(): List<Pair<String, String>> = dynamic.toList()

        private fun entrySize(e: Pair<String, String>) =
            32 + e.first.toByteArray(Charsets.UTF_8).size + e.second.toByteArray(Charsets.UTF_8).size

        private fun evictTo(limit: Int) {
            while (tableSize > limit && dynamic.isNotEmpty()) {
                tableSize -= entrySize(dynamic.removeLast())
            }
        }

        private fun add(e: Pair<String, String>) {
            val size = entrySize(e)
            if (size > maxTableSize) {
                dynamic.clear()
                tableSize = 0
                return
            }
            evictTo(maxTableSize - size)
            dynamic.addFirst(e)
            tableSize += size
        }

        private fun lookup(index: Int): Pair<String, String> {
            if (index <= 0) throw IOException("hpack: index 0")
            if (index <= STATIC_TABLE.size) return STATIC_TABLE[index - 1]
            val d = index - STATIC_TABLE.size - 1
            if (d >= dynamic.size) throw IOException("hpack: index $index out of range")
            return dynamic.elementAt(d)
        }

        private class Cursor(val buf: ByteArray, var pos: Int, val end: Int) {
            fun next(): Int {
                if (pos >= end) throw IOException("hpack: truncated block")
                return buf[pos++].toInt() and 0xFF
            }
        }

        private fun readInt(c: Cursor, first: Int, prefixBits: Int): Int {
            val max = (1 shl prefixBits) - 1
            var value = first and max
            if (value < max) return value
            var shift = 0
            while (true) {
                val b = c.next()
                value += (b and 0x7F) shl shift
                if (value < 0 || shift > 28) throw IOException("hpack: integer overflow")
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        private fun readString(c: Cursor): String {
            val first = c.next()
            val huffman = first and 0x80 != 0
            val len = readInt(c, first, 7)
            if (len < 0 || c.pos + len > c.end) throw IOException("hpack: string past end")
            val bytes = if (huffman) huffmanDecode(c.buf, c.pos, len) else c.buf.copyOfRange(c.pos, c.pos + len)
            c.pos += len
            return String(bytes, Charsets.UTF_8)
        }

        fun decode(block: ByteArray, off: Int = 0, len: Int = block.size): List<Pair<String, String>> {
            val c = Cursor(block, off, off + len)
            val out = ArrayList<Pair<String, String>>()
            while (c.pos < c.end) {
                val b = c.next()
                when {
                    b and 0x80 != 0 -> out += lookup(readInt(c, b, 7))
                    b and 0x40 != 0 -> {
                        val idx = readInt(c, b, 6)
                        val name = if (idx == 0) readString(c) else lookup(idx).first
                        val e = name to readString(c)
                        add(e)
                        out += e
                    }
                    b and 0x20 != 0 -> {
                        val newSize = readInt(c, b, 5)
                        if (newSize > settingsLimit) throw IOException("hpack: table size $newSize > $settingsLimit")
                        maxTableSize = newSize
                        evictTo(maxTableSize)
                    }
                    else -> { // 0000xxxx without indexing, 0001xxxx never indexed
                        val idx = readInt(c, b, 4)
                        val name = if (idx == 0) readString(c) else lookup(idx).first
                        out += name to readString(c)
                    }
                }
            }
            return out
        }
    }
}

/**
 * One HTTP/2 connection to a proxy, carrying many CONNECT tunnels. Thread-safe; frames are written under one lock,
 * one reader thread dispatches incoming frames, one keep-alive thread pings an idle connection and drops a dead
 * one (mobile networks silently lose connections).
 */
internal class FenixH2Session(
    private val socket: Socket,
    val label: String,
    private val log: (String) -> Unit = {},
    private val streamWindow: Int = 1 shl 20,
    private val connectionWindow: Int = 16 shl 20,
    private val pingIdleMs: Long = 25_000L,
    private val pingTimeoutMs: Long = 15_000L,
) : Closeable {
    companion object {
        private val PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII)
        const val TYPE_DATA = 0
        const val TYPE_HEADERS = 1
        const val TYPE_PRIORITY = 2
        const val TYPE_RST_STREAM = 3
        const val TYPE_SETTINGS = 4
        const val TYPE_PUSH_PROMISE = 5
        const val TYPE_PING = 6
        const val TYPE_GOAWAY = 7
        const val TYPE_WINDOW_UPDATE = 8
        const val TYPE_CONTINUATION = 9
        const val FLAG_END_STREAM = 0x1
        const val FLAG_ACK = 0x1
        const val FLAG_END_HEADERS = 0x4
        const val FLAG_PADDED = 0x8
        const val FLAG_PRIORITY = 0x20
        const val ERR_NO_ERROR = 0
        const val ERR_PROTOCOL = 1
        const val ERR_FLOW_CONTROL = 3
        const val ERR_CANCEL = 8
        private const val MAX_ACCEPTED_FRAME = 1 shl 20
        private const val DEFAULT_WINDOW = 65_535
    }

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 32 * 1024))
    private val output = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)
    private val writeLock = Any()
    private val flowLock = Object()
    private val decoder = FenixHpack.Decoder()
    private val streams = ConcurrentHashMap<Int, FenixH2Stream>()
    private val closed = AtomicBoolean(false)
    private val settingsReceived = CountDownLatch(1)

    @Volatile private var nextStreamId = 1
    @Volatile private var goAway = false
    @Volatile private var goAwayLastStream = Int.MAX_VALUE
    @Volatile var closeReason: String = ""
        private set

    // Peer settings
    @Volatile private var peerMaxFrame = 16_384
    @Volatile private var peerInitialWindow = DEFAULT_WINDOW
    @Volatile private var peerMaxConcurrent = Int.MAX_VALUE

    // Send-side connection window (guarded by flowLock)
    private var connSendWindow = DEFAULT_WINDOW.toLong()
    // Receive-side connection bytes not yet returned to the peer
    private var connUnacked = 0L
    private val connUnackedLock = Any()

    @Volatile private var lastFrameAt = System.currentTimeMillis()
    @Volatile private var pingSentAt = 0L

    val isClosed: Boolean get() = closed.get()

    /** Can take one more tunnel right now. */
    val hasCapacity: Boolean
        get() = !closed.get() && !goAway && streams.size < peerMaxConcurrent && nextStreamId < 0x7FFF_0000

    val activeStreams: Int get() = streams.size

    fun start(handshakeTimeoutMs: Long = 10_000L) {
        synchronized(writeLock) {
            output.write(PREFACE)
            writeFrameLocked(
                TYPE_SETTINGS, 0, 0,
                settingsPayload(
                    2 to 0, // ENABLE_PUSH off
                    3 to 0, // MAX_CONCURRENT_STREAMS the server may open towards us
                    4 to streamWindow, // INITIAL_WINDOW_SIZE
                ),
            )
            val extra = connectionWindow - DEFAULT_WINDOW
            if (extra > 0) writeFrameLocked(TYPE_WINDOW_UPDATE, 0, 0, int32(extra))
            output.flush()
        }
        Thread(null, { readLoop() }, "fenix-h2-read", 256 * 1024L).apply { isDaemon = true; start() }
        Thread(null, { keepAliveLoop() }, "fenix-h2-ping", 128 * 1024L).apply { isDaemon = true; start() }
        if (!settingsReceived.await(handshakeTimeoutMs, TimeUnit.MILLISECONDS)) {
            close("no SETTINGS from $label")
            throw IOException("HTTP/2: no SETTINGS from $label within ${handshakeTimeoutMs}ms")
        }
        if (closed.get()) throw IOException("HTTP/2 to $label closed during setup: $closeReason")
    }

    /**
     * Opens `CONNECT authority` and waits for the proxy's answer. 2xx → a live tunnel; any other status →
     * [FenixH2StatusException] (401/407 = pass rejected, 429 = quota, 403 = location locked).
     */
    fun openConnect(authority: String, extraHeaders: List<Pair<String, String>>, timeoutMs: Long = 15_000L): FenixH2Stream {
        if (closed.get()) throw IOException("HTTP/2 to $label is closed: $closeReason")
        if (goAway) throw IOException("HTTP/2 to $label is going away")
        val stream: FenixH2Stream
        synchronized(writeLock) {
            if (!hasCapacity) throw IOException("HTTP/2 to $label has no free stream")
            val id = nextStreamId
            nextStreamId += 2
            stream = FenixH2Stream(this, id, peerInitialWindow.toLong(), streamWindow)
            streams[id] = stream
            val headers = ArrayList<Pair<String, String>>(extraHeaders.size + 2)
            headers += ":method" to "CONNECT"
            headers += ":authority" to authority
            headers += extraHeaders
            val block = FenixHpack.encode(headers)
            var offset = 0
            var first = true
            while (first || offset < block.size) {
                val n = minOf(peerMaxFrame, block.size - offset)
                val last = offset + n >= block.size
                writeFrameLocked(
                    if (first) TYPE_HEADERS else TYPE_CONTINUATION,
                    if (last) FLAG_END_HEADERS else 0,
                    id,
                    block, offset, n,
                )
                offset += n
                first = false
            }
            output.flush()
        }
        val status = try {
            stream.awaitResponse(timeoutMs)
        } catch (e: Throwable) {
            stream.reset(ERR_CANCEL)
            throw e
        }
        if (status !in 200..299) {
            stream.reset(ERR_CANCEL)
            throw FenixH2StatusException(status, "proxy $label answered $status for CONNECT $authority")
        }
        return stream
    }

    // ---------------------------------------------------------------- writing

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun settingsPayload(vararg pairs: Pair<Int, Int>): ByteArray {
        val out = ByteArray(pairs.size * 6)
        pairs.forEachIndexed { i, (id, value) ->
            out[i * 6] = (id ushr 8).toByte()
            out[i * 6 + 1] = id.toByte()
            int32(value).copyInto(out, i * 6 + 2)
        }
        return out
    }

    private fun writeFrameLocked(type: Int, flags: Int, streamId: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size) {
        val header = ByteArray(9)
        header[0] = (len ushr 16).toByte()
        header[1] = (len ushr 8).toByte()
        header[2] = len.toByte()
        header[3] = type.toByte()
        header[4] = flags.toByte()
        int32(streamId and 0x7FFF_FFFF).copyInto(header, 5)
        output.write(header)
        if (len > 0) output.write(payload, off, len)
    }

    internal fun writeFrame(type: Int, flags: Int, streamId: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size) {
        if (closed.get()) throw IOException("HTTP/2 to $label is closed: $closeReason")
        try {
            synchronized(writeLock) {
                writeFrameLocked(type, flags, streamId, payload, off, len)
                output.flush()
            }
        } catch (e: IOException) {
            close("write failed: ${e.message}")
            throw e
        }
    }

    internal fun sendRst(streamId: Int, code: Int) {
        runCatching { writeFrame(TYPE_RST_STREAM, 0, streamId, int32(code)) }
    }

    internal fun sendWindowUpdate(streamId: Int, increment: Int) {
        if (increment <= 0) return
        runCatching { writeFrame(TYPE_WINDOW_UPDATE, 0, streamId, int32(increment)) }
    }

    /** Sends [len] bytes of tunnel data, waiting for flow-control credit (connection and stream). */
    internal fun sendData(stream: FenixH2Stream, buf: ByteArray, off: Int, len: Int, endStream: Boolean) {
        var pos = off
        val end = off + len
        if (len == 0) {
            writeFrame(TYPE_DATA, if (endStream) FLAG_END_STREAM else 0, stream.id, buf, off, 0)
            return
        }
        while (pos < end) {
            var n: Int
            synchronized(flowLock) {
                while (true) {
                    if (closed.get()) throw IOException("HTTP/2 to $label closed: $closeReason")
                    stream.throwIfReset()
                    val credit = minOf(connSendWindow, stream.sendWindow)
                    if (credit > 0) {
                        n = minOf(credit, (end - pos).toLong(), peerMaxFrame.toLong()).toInt()
                        connSendWindow -= n
                        stream.sendWindow -= n
                        break
                    }
                    flowLock.wait(1_000L)
                }
            }
            val last = pos + n >= end
            writeFrame(TYPE_DATA, if (endStream && last) FLAG_END_STREAM else 0, stream.id, buf, pos, n)
            pos += n
        }
    }

    internal fun streamFinished(stream: FenixH2Stream) {
        streams.remove(stream.id, stream)
        if (goAway && streams.isEmpty()) close("drained after GOAWAY")
    }

    // ---------------------------------------------------------------- reading

    private fun readLoop() {
        var headerStream = 0
        var headerFlags = 0
        val headerBlock = ByteArrayOutputStream()
        try {
            val head = ByteArray(9)
            while (!closed.get()) {
                input.readFully(head)
                lastFrameAt = System.currentTimeMillis()
                pingSentAt = 0L
                val length = ((head[0].toInt() and 0xFF) shl 16) or ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
                val type = head[3].toInt() and 0xFF
                val flags = head[4].toInt() and 0xFF
                val streamId = (((head[5].toInt() and 0x7F) shl 24) or ((head[6].toInt() and 0xFF) shl 16) or
                    ((head[7].toInt() and 0xFF) shl 8) or (head[8].toInt() and 0xFF))
                if (length > MAX_ACCEPTED_FRAME) throw IOException("frame of $length bytes")
                val payload = ByteArray(length)
                input.readFully(payload)
                if (headerStream != 0 && type != TYPE_CONTINUATION) throw IOException("expected CONTINUATION")
                when (type) {
                    TYPE_SETTINGS -> onSettings(flags, payload)
                    TYPE_PING -> if (flags and FLAG_ACK == 0) writeFrame(TYPE_PING, FLAG_ACK, 0, payload)
                    TYPE_WINDOW_UPDATE -> onWindowUpdate(streamId, payload)
                    TYPE_DATA -> onData(streamId, flags, payload)
                    TYPE_HEADERS -> {
                        var pos = 0
                        var end = payload.size
                        if (flags and FLAG_PADDED != 0) {
                            val pad = payload[0].toInt() and 0xFF
                            pos = 1
                            end -= pad
                        }
                        if (flags and FLAG_PRIORITY != 0) pos += 5
                        if (end < pos) throw IOException("bad HEADERS padding")
                        headerBlock.reset()
                        headerBlock.write(payload, pos, end - pos)
                        if (flags and FLAG_END_HEADERS != 0) {
                            onHeaderBlock(streamId, flags, headerBlock.toByteArray())
                        } else {
                            headerStream = streamId
                            headerFlags = flags
                        }
                    }
                    TYPE_CONTINUATION -> {
                        if (streamId != headerStream) throw IOException("CONTINUATION for wrong stream")
                        headerBlock.write(payload)
                        if (flags and FLAG_END_HEADERS != 0) {
                            headerStream = 0
                            onHeaderBlock(streamId, headerFlags, headerBlock.toByteArray())
                        }
                    }
                    TYPE_RST_STREAM -> {
                        val code = if (payload.size >= 4) readInt32(payload, 0) else ERR_PROTOCOL
                        streams[streamId]?.onReset(code)
                    }
                    TYPE_GOAWAY -> {
                        val last = if (payload.size >= 4) readInt32(payload, 0) and 0x7FFF_FFFF else 0
                        val code = if (payload.size >= 8) readInt32(payload, 4) else 0
                        goAway = true
                        goAwayLastStream = last
                        log("GOAWAY from $label last=$last code=$code")
                        streams.values.filter { it.id > last }.forEach { it.onReset(ERR_CANCEL) }
                        if (streams.isEmpty()) close("GOAWAY code=$code")
                    }
                    TYPE_PUSH_PROMISE -> throw IOException("PUSH_PROMISE although push is disabled")
                    TYPE_PRIORITY -> Unit
                    else -> Unit // unknown frame types must be ignored
                }
            }
        } catch (e: Throwable) {
            close(if (e is EOFException) "connection closed by $label" else "read: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun readInt32(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun onSettings(flags: Int, payload: ByteArray) {
        if (flags and FLAG_ACK != 0) return
        if (payload.size % 6 != 0) throw IOException("bad SETTINGS length")
        var i = 0
        while (i < payload.size) {
            val id = ((payload[i].toInt() and 0xFF) shl 8) or (payload[i + 1].toInt() and 0xFF)
            val value = readInt32(payload, i + 2)
            when (id) {
                3 -> peerMaxConcurrent = if (value < 0) Int.MAX_VALUE else value
                4 -> {
                    if (value < 0) throw IOException("INITIAL_WINDOW_SIZE too large")
                    synchronized(flowLock) {
                        val delta = value.toLong() - peerInitialWindow
                        peerInitialWindow = value
                        streams.values.forEach { it.sendWindow += delta }
                        flowLock.notifyAll()
                    }
                }
                5 -> if (value in 16_384..16_777_215) peerMaxFrame = value
            }
            i += 6
        }
        writeFrame(TYPE_SETTINGS, FLAG_ACK, 0, ByteArray(0))
        settingsReceived.countDown()
    }

    private fun onWindowUpdate(streamId: Int, payload: ByteArray) {
        if (payload.size < 4) return
        val inc = readInt32(payload, 0) and 0x7FFF_FFFF
        synchronized(flowLock) {
            if (streamId == 0) connSendWindow += inc else streams[streamId]?.let { it.sendWindow += inc }
            flowLock.notifyAll()
        }
    }

    private fun onData(streamId: Int, flags: Int, payload: ByteArray) {
        // Return connection credit as soon as data arrives (stream credit is returned when the app reads it),
        // so one slow tunnel can never stall the others.
        synchronized(connUnackedLock) {
            connUnacked += payload.size
            if (connUnacked >= connectionWindow / 4) {
                sendWindowUpdate(0, connUnacked.toInt())
                connUnacked = 0
            }
        }
        var pos = 0
        var end = payload.size
        if (flags and FLAG_PADDED != 0 && payload.isNotEmpty()) {
            val pad = payload[0].toInt() and 0xFF
            pos = 1
            end -= pad
            if (end < pos) throw IOException("bad DATA padding")
        }
        val stream = streams[streamId] ?: return
        stream.onData(payload, pos, end - pos, payload.size - (end - pos), flags and FLAG_END_STREAM != 0)
    }

    private fun onHeaderBlock(streamId: Int, flags: Int, block: ByteArray) {
        val headers = decoder.decode(block) // always decode: keeps the HPACK table in sync
        val stream = streams[streamId] ?: return
        val status = headers.firstOrNull { it.first == ":status" }?.second?.trim()?.toIntOrNull()
        stream.onHeaders(status, flags and FLAG_END_STREAM != 0)
    }

    private fun keepAliveLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(5_000L)
            } catch (_: InterruptedException) {
                return
            }
            val now = System.currentTimeMillis()
            val sent = pingSentAt
            if (sent > 0 && now - sent > pingTimeoutMs) {
                close("no PING answer from $label in ${pingTimeoutMs}ms")
                return
            }
            if (sent == 0L && now - lastFrameAt > pingIdleMs) {
                pingSentAt = now
                runCatching { writeFrame(TYPE_PING, 0, 0, ByteArray(8)) }
            }
        }
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        log("HTTP/2 $label closed: $reason")
        runCatching {
            synchronized(writeLock) {
                writeFrameLocked(TYPE_GOAWAY, 0, 0, int32(0) + int32(ERR_NO_ERROR))
                output.flush()
            }
        }
        runCatching { socket.close() }
        streams.values.forEach { it.onSessionClosed(reason) }
        streams.clear()
        synchronized(flowLock) { flowLock.notifyAll() }
    }

    override fun close() = close("closed by Cat")
}

/** One CONNECT tunnel (an HTTP/2 stream). [input]/[output] behave like a TCP socket's streams. */
internal class FenixH2Stream(
    private val session: FenixH2Session,
    val id: Int,
    initialSendWindow: Long,
    private val receiveWindow: Int,
) : Closeable {
    /** Guarded by the session's flow lock. */
    internal var sendWindow: Long = initialSendWindow

    private val lock = Object()
    private val chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var remoteEnded = false
    private var localEnded = false
    private var failure: String? = null
    private var status: Int? = null
    private var unackedRead = 0
    private val responseLatch = CountDownLatch(1)
    private val finished = AtomicBoolean(false)

    fun awaitResponse(timeoutMs: Long): Int {
        if (!responseLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw InterruptedIOException("no CONNECT answer from ${session.label} in ${timeoutMs}ms")
        }
        synchronized(lock) {
            status?.let { return it }
            throw IOException(failure ?: "stream closed before the CONNECT answer")
        }
    }

    internal fun throwIfReset() {
        synchronized(lock) { failure?.let { throw IOException(it) } }
    }

    internal fun onHeaders(statusCode: Int?, endStream: Boolean) {
        synchronized(lock) {
            if (status == null && statusCode != null && statusCode >= 200) status = statusCode
            if (endStream) remoteEnded = true
            lock.notifyAll()
        }
        if (statusCode == null || statusCode >= 200) responseLatch.countDown()
        if (endStream) maybeFinish()
    }

    internal fun onData(buf: ByteArray, off: Int, len: Int, paddingBytes: Int, endStream: Boolean) {
        synchronized(lock) {
            if (len > 0) chunks.addLast(buf.copyOfRange(off, off + len))
            unackedRead += paddingBytes
            if (endStream) remoteEnded = true
            lock.notifyAll()
        }
        if (endStream) maybeFinish()
    }

    internal fun onReset(code: Int) {
        synchronized(lock) {
            if (failure == null) failure = "tunnel reset by ${session.label} (code $code)"
            lock.notifyAll()
        }
        responseLatch.countDown()
        finish()
    }

    internal fun onSessionClosed(reason: String) {
        synchronized(lock) {
            if (failure == null) failure = "HTTP/2 connection lost: $reason"
            lock.notifyAll()
        }
        responseLatch.countDown()
    }

    private fun maybeFinish() {
        val done = synchronized(lock) { remoteEnded && localEnded }
        if (done) finish()
    }

    private fun finish() {
        if (finished.compareAndSet(false, true)) session.streamFinished(this)
    }

    fun reset(code: Int = FenixH2Session.ERR_CANCEL) {
        val already = finished.get()
        synchronized(lock) {
            if (failure == null) failure = "tunnel closed"
            lock.notifyAll()
        }
        if (!already) session.sendRst(id, code)
        finish()
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var credit = 0
            val n: Int
            synchronized(lock) {
                while (chunks.isEmpty()) {
                    if (remoteEnded) return -1
                    failure?.let { throw IOException(it) }
                    lock.wait(1_000L)
                }
                val head = chunks.first()
                n = minOf(len, head.size - headOffset)
                System.arraycopy(head, headOffset, b, off, n)
                headOffset += n
                if (headOffset >= head.size) {
                    chunks.removeFirst()
                    headOffset = 0
                }
                unackedRead += n
                if (unackedRead >= receiveWindow / 2 && !remoteEnded) {
                    credit = unackedRead
                    unackedRead = 0
                }
            }
            if (credit > 0) session.sendWindowUpdate(id, credit)
            return n
        }

        override fun available(): Int = synchronized(lock) { chunks.sumOf { it.size } - headOffset }

        override fun close() = this@FenixH2Stream.close()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            synchronized(lock) { if (localEnded) throw IOException("tunnel output closed") }
            session.sendData(this@FenixH2Stream, b, off, len, endStream = false)
        }

        override fun flush() = Unit

        /** Half-close (END_STREAM), like shutting down a TCP socket's output. */
        override fun close() {
            val send = synchronized(lock) {
                if (localEnded || failure != null) false else { localEnded = true; true }
            }
            if (send) runCatching { session.sendData(this@FenixH2Stream, ByteArray(0), 0, 0, endStream = true) }
            maybeFinish()
        }
    }

    /** Closes both directions; a tunnel that is not cleanly finished is reset (RST_STREAM CANCEL). */
    override fun close() {
        val clean = synchronized(lock) { remoteEnded && localEnded }
        if (clean) finish() else reset(FenixH2Session.ERR_CANCEL)
    }
}

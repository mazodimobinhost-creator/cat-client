package com.cat.client

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Real ping and jitter measured from THIS phone, two ways, for one exact `ip:port`.
 *
 *  🏠 line   — bare TCP connects (SYN → SYN/ACK). Without root a phone cannot send ICMP, and
 *              one TCP handshake is exactly one round trip to the edge: this is your line.
 *  ☁️ worker — ONE TLS connection that carries the worker's SNI, then N keep-alive HTTP
 *              requests on it. Each is timed from writing the request to the first response
 *              byte, so the TLS handshake is left out and what remains is: your line to that
 *              Cloudflare edge + the edge running your worker. The difference between the two
 *              numbers is what Cloudflare/the worker adds, per IP.
 *
 * The worker path is `/robots.txt`: every Cat panel answers it from memory (no KV, no auth)
 * and it says nothing about the panel to a curious probe. Any other worker still produces an
 * answer for it (often a 404) — that answer also comes from the worker, so the timing holds.
 * Without a known worker host the request goes to `/cdn-cgi/trace` instead, which Cloudflare's
 * edge answers itself: that is labelled «edge», never «worker».
 *
 * Jitter = mean |difference between CONSECUTIVE samples| — what speed tests and RFC 3550 call
 * jitter — not the distance from the average, which hides a path that flips on every packet.
 */
object PingJitterMeter {

    data class Stats(val sent: Int, val samples: List<Long>) {
        val answered: Int get() = samples.size
        val lossPct: Float get() = if (sent <= 0) 0f else (sent - samples.size) * 100f / sent
        val minMs: Long? get() = samples.minOrNull()
        val maxMs: Long? get() = samples.maxOrNull()
        val medianMs: Long? get() = percentile(50)
        val p90Ms: Long? get() = percentile(90)
        val jitterMs: Long? get() =
            if (samples.size < 2) null else samples.zipWithNext { a, b -> abs(b - a) }.average().roundToLong()

        /** Nearest-rank percentile (no interpolation: every value shown was really measured). */
        fun percentile(p: Int): Long? {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            val rank = ceil(p.coerceIn(1, 100) / 100.0 * sorted.size).toInt() - 1
            return sorted[rank.coerceIn(0, sorted.lastIndex)]
        }

        companion object {
            /** `null` entries are samples that never answered (they count as loss). */
            fun of(raw: List<Long?>): Stats = Stats(raw.size, raw.filterNotNull())
        }
    }

    enum class WorkerState {
        /** Your worker answered through this IP. */
        OK,
        /** No worker host known: Cloudflare's own edge answered (`/cdn-cgi/trace`). */
        EDGE,
        /** TCP never opened. */
        CONNECT,
        /** TCP opened, the TLS handshake with the worker's SNI failed (usually: SNI filtered). */
        TLS,
        /** Cloudflare answered with an error page (403 «error code: 10xx» etc.). */
        BLOCKED,
        /** The worker/edge answered 5xx or the reply could not be read. */
        HTTP,
        /** The panel host is on Vercel: a Cloudflare IP cannot carry it, nothing to measure. */
        NOT_CF,
    }

    data class WorkerResult(
        val host: String,
        val path: String,
        val state: WorkerState,
        val handshakeMs: Long?,
        val stats: Stats,
        val colo: String?,
        val status: Int?,
    )

    data class Report(val ip: String, val port: Int, val line: Stats, val worker: WorkerResult?)

    /** Hosts that never sit behind a Cloudflare IP (the panel can also run on Vercel). */
    fun isNonCloudflareHost(host: String): Boolean {
        val h = host.trim().lowercase()
        return h.endsWith(".vercel.app") || h == "vercel.app"
    }

    fun measureLine(ip: String, port: Int, count: Int = 10, gapMs: Long = 60, timeoutMs: Int = 1500): Stats {
        val n = count.coerceIn(1, 60)
        val raw = ArrayList<Long?>(n)
        repeat(n) { i ->
            if (i > 0) pause(gapMs)
            raw += IpScanner.tcpConnect(ip, port, timeoutMs)
        }
        return Stats.of(raw)
    }

    /**
     * [host] = the worker host (SNI + Host header). [edgeOnly] = no worker is known: ask the
     * Cloudflare edge itself. Plain Cloudflare ports (80/8080/…) are measured over plain HTTP.
     */
    fun measureWorker(
        ip: String,
        port: Int,
        host: String,
        edgeOnly: Boolean = false,
        count: Int = 8,
        gapMs: Long = 80,
        timeoutMs: Int = 4000,
    ): WorkerResult {
        val path = if (edgeOnly) "/cdn-cgi/trace" else "/robots.txt"
        if (isNonCloudflareHost(host)) return WorkerResult(host, path, WorkerState.NOT_CF, null, Stats(0, emptyList()), null, null)
        val plain = port in IpScanner.PLAIN_HTTP_PORTS
        val n = count.coerceIn(1, 60)
        val raw = ArrayList<Long?>(n)
        var conn: Conn? = null
        var handshake: Long? = null
        var colo: String? = null
        var status: Int? = null
        var anyOk = false
        var failure: WorkerState? = null
        var consecutiveFails = 0
        try {
            for (i in 0 until n) {
                if (i > 0) pause(gapMs)
                if (conn == null) {
                    val started = System.nanoTime()
                    conn = try {
                        open(ip, port, host, plain, timeoutMs)
                    } catch (e: SSLException) {
                        failure = WorkerState.TLS; null
                    } catch (e: Exception) {
                        if (failure == null) failure = WorkerState.CONNECT; null
                    }
                    if (conn == null) {
                        raw += null
                        consecutiveFails += 1
                        // Nothing ever worked and two attempts in a row failed: stop spending time.
                        if (!anyOk && consecutiveFails >= 2) break
                        continue
                    }
                    if (handshake == null) handshake = (System.nanoTime() - started) / 1_000_000
                }
                val active = conn!!
                val reply = try {
                    request(active, host, path + "?_=" + (System.nanoTime() % 1_000_000_007L), timeoutMs)
                } catch (e: Exception) {
                    null
                }
                if (reply == null) {
                    raw += null
                    failure = failure ?: WorkerState.HTTP
                    consecutiveFails += 1
                    active.close(); conn = null
                    if (!anyOk && consecutiveFails >= 2) break
                    continue
                }
                status = reply.status
                colo = reply.colo ?: colo
                if (isCloudflareError(reply)) {
                    raw += null
                    failure = WorkerState.BLOCKED
                    break
                }
                if (reply.status >= 500) {
                    raw += null
                    failure = WorkerState.HTTP
                    consecutiveFails += 1
                    if (!reply.reusable) { active.close(); conn = null }
                    if (!anyOk && consecutiveFails >= 2) break
                    continue
                }
                raw += reply.ttfbMs
                anyOk = true
                consecutiveFails = 0
                if (!reply.reusable) { active.close(); conn = null }
            }
        } finally {
            conn?.close()
        }
        // Samples that were never attempted (early stop) still count as lost.
        while (raw.size < n && !anyOk) raw += null
        val stats = Stats.of(raw)
        val state = when {
            anyOk -> if (edgeOnly) WorkerState.EDGE else WorkerState.OK
            else -> failure ?: WorkerState.CONNECT
        }
        return WorkerResult(host, path, state, handshake, stats, colo, status)
    }

    fun measure(
        ip: String,
        port: Int,
        workerHost: String?,
        lineSamples: Int = 10,
        workerSamples: Int = 8,
        edgeSni: String = IpScanner.SPEED_TEST_SNI,
    ): Report {
        val line = measureLine(ip, port, lineSamples)
        val worker = if (!workerHost.isNullOrBlank()) {
            measureWorker(ip, port, workerHost, edgeOnly = false, count = workerSamples)
        } else {
            measureWorker(ip, port, edgeSni, edgeOnly = true, count = workerSamples)
        }
        return Report(ip, port, line, worker)
    }

    /**
     * Folds a measurement into a scanner row. The scan's own numbers stay when the new
     * pass got no answer at all, so a flaky second look never erases a working result.
     */
    fun applyTo(row: IpScanner.ScanResult, report: Report): IpScanner.ScanResult {
        val line = report.line
        val w = report.worker
        val wOk = w != null && w.stats.answered > 0
        return row.copy(
            lineMs = line.medianMs ?: row.lineMs,
            jitterMs = if (line.answered >= 2) line.jitterMs ?: row.jitterMs else row.jitterMs,
            lossPct = if (line.sent > 0 && line.answered > 0) line.lossPct else row.lossPct,
            sampleCount = if (line.answered > 0) line.answered else row.sampleCount,
            workerMs = if (wOk) w!!.stats.medianMs else null,
            workerJitterMs = if (wOk) w!!.stats.jitterMs else null,
            workerLossPct = if (wOk) w!!.stats.lossPct else null,
            workerColo = w?.colo ?: row.workerColo,
            workerState = w?.state?.name,
        )
    }

    /** `1.2.3.4:2053`, `[2606:4700::1]:443`, `host:8443`, a URL; a bare address → 443. */
    fun parseTarget(raw: String): Pair<String, Int>? {
        val s = raw.trim().substringBefore('#').trim()
            .removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (s.isEmpty()) return null
        val (host, port) = when {
            s.startsWith("[") -> {
                val end = s.indexOf(']').takeIf { it > 1 } ?: return null
                val p = s.substring(end + 1).removePrefix(":")
                s.substring(1, end) to (if (p.isEmpty()) 443 else p.toIntOrNull() ?: return null)
            }
            s.count { it == ':' } > 1 -> s to 443
            s.contains(':') -> s.substringBefore(':') to (s.substringAfter(':').toIntOrNull() ?: return null)
            else -> s to 443
        }
        if (port !in 1..65535) return null
        if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }) return null
        return host to port
    }

    // ---------------------------------------------------------------- verdicts

    /** The numbers that decide the verdict: the worker when it answered, else the line. */
    fun decisive(report: Report): Stats =
        report.worker?.stats?.takeIf { it.answered > 0 } ?: report.line

    /** Plain words: «what is this good for». */
    fun verdict(report: Report, farsi: Boolean): String {
        val s = decisive(report)
        val med = s.medianMs
            ?: return if (farsi) "❌ جواب نداد — این آی‌پی/پورت الان روی اینترنت تو باز نیست."
            else "❌ No answer — this IP/port is not open on your internet right now."
        val jit = s.jitterMs ?: 0L
        val loss = s.lossPct
        return when {
            loss >= 20f -> if (farsi) "🔴 خیلی ناپایدار — بیشتر بسته‌ها گم می‌شوند؛ آی‌پی دیگری را امتحان کن." else "🔴 Very unstable — many samples are lost; try another IP."
            loss >= 5f -> if (farsi) "🟠 گاهی قطع می‌شود — برای تماس و بازی مناسب نیست؛ پیام‌رسان و وب‌گردی شاید." else "🟠 Drops now and then — not for calls or games; messaging and browsing may be fine."
            med <= 150 && jit <= 20 -> if (farsi) "🟢 عالی — بازی آنلاین، تماس تصویری، همه‌چیز." else "🟢 Excellent — online games, video calls, everything."
            med <= 300 && jit <= 50 -> if (farsi) "🟢 خوب — تماس صوتی و تصویری، یوتیوب، وب‌گردی." else "🟢 Good — voice and video calls, YouTube, browsing."
            med <= 600 && jit <= 120 -> if (farsi) "🟡 متوسط — وب‌گردی و پیام‌رسان خوب؛ بازی و تماس تصویری اذیت می‌کند." else "🟡 Fair — browsing and messaging are fine; games and video calls will suffer."
            else -> if (farsi) "🔴 ضعیف — فقط پیام‌رسان و صفحه‌های سبک." else "🔴 Poor — messaging and light pages only."
        }
    }

    /** Milliseconds Cloudflare + the worker add on top of the bare line (median vs median). */
    fun workerOverheadMs(report: Report): Long? {
        val w = report.worker?.stats?.medianMs ?: return null
        val l = report.line.medianMs ?: return null
        return (w - l).coerceAtLeast(0L)
    }

    fun shortStats(stats: Stats): String {
        val med = stats.medianMs ?: return "✗"
        val jit = stats.jitterMs
        return buildString {
            append(med).append("ms")
            if (jit != null) append(" ±").append(jit)
            if (stats.lossPct >= 1f) append(" · ").append(stats.lossPct.roundToLong()).append("%✗")
        }
    }

    fun stateLabel(state: WorkerState, farsi: Boolean): String = when (state) {
        WorkerState.OK -> if (farsi) "ورکر جواب داد" else "worker answered"
        WorkerState.EDGE -> if (farsi) "لبهٔ کلودفلر (ورکری معرفی نشده)" else "Cloudflare edge (no worker known)"
        WorkerState.CONNECT -> if (farsi) "وصل نشد" else "no connection"
        WorkerState.TLS -> if (farsi) "دست‌دادن TLS با دامنهٔ ورکر رد شد (احتمالاً SNI فیلتر است)" else "TLS with the worker domain refused (SNI likely filtered)"
        WorkerState.BLOCKED -> if (farsi) "کلودفلر خطا داد (این آی‌پی دامنهٔ تو را سرو نمی‌کند)" else "Cloudflare error page (this IP does not serve your domain)"
        WorkerState.HTTP -> if (farsi) "ورکر جواب درست نداد" else "the worker gave no proper answer"
        WorkerState.NOT_CF -> if (farsi) "پنل روی Vercel است؛ با آی‌پی کلودفلر قابل سنجش نیست" else "panel is on Vercel; a Cloudflare IP cannot carry it"
    }

    // ---------------------------------------------------------------- HTTP/1.1 plumbing

    internal class Conn(val socket: Socket, val input: InputStream, val output: OutputStream) {
        fun close() { runCatching { socket.close() } }
    }

    internal data class Reply(val status: Int, val ttfbMs: Long, val colo: String?, val reusable: Boolean, val bodyPrefix: String)

    internal data class Head(val status: Int, val headers: Map<String, String>)

    private fun open(ip: String, port: Int, host: String, plain: Boolean, timeoutMs: Int): Conn {
        if (plain) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                return Conn(socket, BufferedInputStream(socket.getInputStream()), socket.getOutputStream())
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress(ip, port), timeoutMs)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(raw, host, port, true) as SSLSocket
            tls.soTimeout = timeoutMs
            val params = tls.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            params.endpointIdentificationAlgorithm = null
            tls.sslParameters = params
            tls.startHandshake()
            return Conn(tls, BufferedInputStream(tls.inputStream), tls.outputStream)
        } catch (e: Exception) {
            runCatching { raw.close() }
            if (e is SSLException) throw e
            throw SSLException(e.message ?: "tls", e)
        }
    }

    private fun request(conn: Conn, host: String, path: String, timeoutMs: Int): Reply? {
        val req = "GET $path HTTP/1.1\r\nHost: $host\r\n" +
            "User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\r\n" +
            "Accept: */*\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n"
        conn.socket.soTimeout = timeoutMs
        val started = System.nanoTime()
        conn.output.write(req.toByteArray(Charsets.US_ASCII))
        conn.output.flush()
        val first = conn.input.read()
        if (first < 0) return null
        val ttfb = (System.nanoTime() - started) / 1_000_000
        val head = readHead(conn.input, first) ?: return null
        val body = readBody(conn.input, head)
        val colo = coloFrom(head.headers["cf-ray"]) ?: Regex("(?m)^colo=([A-Z]{3})").find(body.text)?.groupValues?.get(1)
        val keepAlive = !head.headers["connection"].orEmpty().contains("close", ignoreCase = true)
        return Reply(head.status, ttfb, colo, keepAlive && body.complete, body.text.take(512))
    }

    /** Reads the rest of the status line + headers (the first byte was already consumed). */
    internal fun readHead(input: InputStream, firstByte: Int): Head? {
        val buf = StringBuilder()
        buf.append(firstByte.toChar())
        while (buf.length < 32 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            if (buf.endsWith("\r\n\r\n")) return parseHead(buf.toString())
        }
        return null
    }

    internal fun parseHead(text: String): Head? {
        val lines = text.split("\r\n")
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val c = line.indexOf(':')
            if (c <= 0) continue
            headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
        }
        return Head(status, headers)
    }

    internal data class Body(val text: String, val complete: Boolean)

    /** Consumes the body so the connection can carry the next sample; keeps the first 8 KB as text. */
    internal fun readBody(input: InputStream, head: Head): Body {
        val keep = StringBuilder()
        fun take(n: Long): Boolean {
            var left = n
            val chunk = ByteArray(4096)
            while (left > 0) {
                val r = input.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
                if (r < 0) return false
                if (keep.length < 8192) keep.append(String(chunk, 0, r, Charsets.ISO_8859_1))
                left -= r
            }
            return true
        }
        fun line(): String? {
            val sb = StringBuilder()
            while (sb.length < 1024) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
            return null
        }
        if (head.status in 100..199 || head.status == 204 || head.status == 304) return Body("", true)
        val te = head.headers["transfer-encoding"].orEmpty()
        if (te.contains("chunked", ignoreCase = true)) {
            var total = 0L
            while (true) {
                val sizeLine = line() ?: return Body(keep.toString(), false)
                val size = sizeLine.substringBefore(';').trim().toLongOrNull(16) ?: return Body(keep.toString(), false)
                if (size == 0L) {
                    // trailers until the empty line
                    while (true) { val t = line() ?: return Body(keep.toString(), false); if (t.isEmpty()) break }
                    return Body(keep.toString(), true)
                }
                total += size
                if (total > MAX_BODY_BYTES) return Body(keep.toString(), false)
                if (!take(size)) return Body(keep.toString(), false)
                line() ?: return Body(keep.toString(), false)
            }
        }
        val length = head.headers["content-length"]?.trim()?.toLongOrNull()
        if (length != null) {
            if (length > MAX_BODY_BYTES) return Body(keep.toString(), false)
            val complete = take(length) // before keep.toString(): arguments are evaluated left to right
            return Body(keep.toString(), complete)
        }
        // close-delimited body: unusable for keep-alive; the caller reconnects for the next sample
        return Body(keep.toString(), false)
    }

    /** `cf-ray: 8d2f…-FRA` → FRA. */
    internal fun coloFrom(cfRay: String?): String? {
        val tail = cfRay?.substringAfterLast('-', "")?.trim().orEmpty()
        return tail.takeIf { it.length == 3 && it.all { c -> c in 'A'..'Z' } }
    }

    private fun isCloudflareError(reply: Reply): Boolean =
        (reply.status == 403 || reply.status == 409 || reply.status == 421 || reply.status == 530) &&
            Regex("error code:\\s*1\\d{3}", RegexOption.IGNORE_CASE).containsMatchIn(reply.bodyPrefix)

    private const val MAX_BODY_BYTES = 256L * 1024L

    private fun pause(ms: Long) {
        if (ms > 0) runCatching { Thread.sleep(ms) }
    }
}

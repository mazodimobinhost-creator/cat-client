package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket

class PingJitterMeterTest {
    private fun stats(vararg raw: Long?) = PingJitterMeter.Stats.of(raw.toList())

    @Test
    fun jitterIsTheMeanOfConsecutiveDifferencesNotTheSpread() {
        // 40,60,40,60: the old «distance from the average» gave 10; consecutive jumps are 20 each.
        val s = stats(40, 60, 40, 60)
        assertEquals(20L, s.jitterMs)
        // A steady climb has jitter = the step, not the distance from its average.
        assertEquals(10L, stats(10, 20, 30, 40, 50).jitterMs)
        assertNull(stats(42).jitterMs)
    }

    @Test
    fun lostSamplesCountAsLossAndAreLeftOutOfTheNumbers() {
        val s = stats(50, null, 70, null, 60)
        assertEquals(5, s.sent)
        assertEquals(3, s.answered)
        assertEquals(40f, s.lossPct, 0.001f)
        assertEquals(50L, s.minMs)
        assertEquals(60L, s.medianMs)
        assertEquals(70L, s.maxMs)
    }

    @Test
    fun percentilesAreRealMeasuredValues() {
        val s = stats(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
        assertEquals(50L, s.medianMs)
        assertEquals(90L, s.p90Ms)
        assertNull(stats(null, null).medianMs)
    }

    @Test
    fun verdictUsesTheWorkerWhenItAnsweredAndTheThresholdsInOrder() {
        fun report(line: PingJitterMeter.Stats, worker: PingJitterMeter.Stats?) = PingJitterMeter.Report(
            "104.16.1.1", 443, line,
            worker?.let { PingJitterMeter.WorkerResult("w.example", "/robots.txt", PingJitterMeter.WorkerState.OK, 30, it, "FRA", 200) },
        )
        val fastLine = stats(40, 42, 41, 43)
        assertTrue(PingJitterMeter.verdict(report(fastLine, null), false).startsWith("🟢 Excellent"))
        // The line is fast but the worker is slow: the verdict follows the worker.
        val slowWorker = stats(500, 520, 480, 510)
        assertTrue(PingJitterMeter.verdict(report(fastLine, slowWorker), false).startsWith("🟡"))
        assertEquals(459L, PingJitterMeter.workerOverheadMs(report(fastLine, slowWorker)))
        assertTrue(PingJitterMeter.verdict(report(stats(200, 230, 210, 220), null), false).startsWith("🟢 Good"))
        assertTrue(PingJitterMeter.verdict(report(stats(50, null, 50, 50, 50, 50, 50, 50, 50, 50), null), false).startsWith("🟠"))
        assertTrue(PingJitterMeter.verdict(report(stats(50, null, null, 50), null), false).startsWith("🔴 Very"))
        assertTrue(PingJitterMeter.verdict(report(stats(900, 1200, 800), null), false).startsWith("🔴 Poor"))
        assertTrue(PingJitterMeter.verdict(report(stats(null, null), null), true).startsWith("❌"))
    }

    @Test
    fun shortStatsReadsLikeTheScannerRow() {
        assertEquals("41ms ±2", PingJitterMeter.shortStats(stats(40, 42, 41, 43)))
        assertEquals("✗", PingJitterMeter.shortStats(stats(null)))
        assertEquals("50ms ±0 · 50%✗", PingJitterMeter.shortStats(stats(50, null, 50, null)))
    }

    @Test
    fun keepAliveReplyIsConsumedExactlySoTheNextSampleCanReuseTheConnection() {
        val wire = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\ncf-ray: 8d2f00aa11-FRA\r\n\r\nhello" +
            "HTTP/1.1 404 Not Found\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\nNEXT"
        val input = ByteArrayInputStream(wire.toByteArray(Charsets.ISO_8859_1))
        val head1 = PingJitterMeter.readHead(input, input.read())!!
        assertEquals(200, head1.status)
        assertEquals("FRA", PingJitterMeter.coloFrom(head1.headers["cf-ray"]))
        val body1 = PingJitterMeter.readBody(input, head1)
        assertEquals("hello", body1.text)
        assertTrue(body1.complete)
        val head2 = PingJitterMeter.readHead(input, input.read())!!
        assertEquals(404, head2.status)
        val body2 = PingJitterMeter.readBody(input, head2)
        assertEquals("abcde", body2.text)
        assertTrue(body2.complete)
        assertEquals("NEXT", String(input.readBytes(), Charsets.ISO_8859_1))
    }

    @Test
    fun closeDelimitedBodyIsNotReusedAndBadColoIsIgnored() {
        val head = PingJitterMeter.parseHead("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n")!!
        val body = PingJitterMeter.readBody(ByteArrayInputStream("colo=FRA\n".toByteArray()), head)
        assertFalse(body.complete)
        assertNull(PingJitterMeter.coloFrom("abc-fr"))
        assertNull(PingJitterMeter.coloFrom(null))
    }

    @Test
    fun vercelPanelIsReportedAsNotMeasurableInsteadOfFailing() {
        val r = PingJitterMeter.measureWorker("104.16.1.1", 443, "cat-panel.vercel.app", count = 3)
        assertEquals(PingJitterMeter.WorkerState.NOT_CF, r.state)
        assertEquals(0, r.stats.sent)
    }

    @Test
    fun lineSamplesHitARealListener() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val acceptor = Thread {
                runCatching { repeat(4) { server.accept().close() } }
            }.apply { isDaemon = true; start() }
            val s = PingJitterMeter.measureLine("127.0.0.1", server.localPort, count = 4, gapMs = 5, timeoutMs = 1000)
            assertEquals(4, s.sent)
            assertEquals(4, s.answered)
            assertEquals(0f, s.lossPct, 0.001f)
            acceptor.join(2000)
        }
    }

    @Test
    fun scannerSampleUsesConsecutiveJitterAndMedian() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            Thread { runCatching { repeat(3) { server.accept().close() } } }.apply { isDaemon = true; start() }
            val p = IpScanner.sampleTcp("127.0.0.1", server.localPort, 3, 1000)!!
            assertEquals(3, p.answered)
            assertTrue(p.medianMs >= 0)
        }
    }

    @Test
    fun measurementFoldsIntoTheScannerRowWithoutLosingTheScan() {
        val row = IpScanner.ScanResult(ip = "104.16.1.1", pingMs = 50, sni = "w.example", port = 2053, jitterMs = 7, lossPct = 0f, sampleCount = 4, tlsOk = true)
        val ok = PingJitterMeter.applyTo(row, PingJitterMeter.Report("104.16.1.1", 2053, stats(40, 44, 42),
            PingJitterMeter.WorkerResult("w.example", "/robots.txt", PingJitterMeter.WorkerState.OK, 60, stats(70, 90, 80), "FRA", 200)))
        assertEquals(42L, ok.lineMs)
        assertEquals(3L, ok.jitterMs)
        assertEquals(80L, ok.workerMs)
        assertEquals(15L, ok.workerJitterMs)
        assertEquals("FRA", ok.workerColo)
        assertEquals("OK", ok.workerState)
        assertEquals(2053, ok.port)
        // Nothing answered this time: the scan's own line numbers survive, the worker is marked failed.
        val dead = PingJitterMeter.applyTo(row, PingJitterMeter.Report("104.16.1.1", 2053, stats(null, null),
            PingJitterMeter.WorkerResult("w.example", "/robots.txt", PingJitterMeter.WorkerState.TLS, null, stats(null, null), null, null)))
        assertEquals(7L, dead.jitterMs)
        assertEquals(4, dead.sampleCount)
        assertNull(dead.workerMs)
        assertEquals("TLS", dead.workerState)
    }

    @Test
    fun pageAcceptsScannerStyleTargetsWithTheirExactPort() {
        assertEquals("104.16.1.1" to 2053, PingJitterMeter.parseTarget("104.16.1.1:2053#DE"))
        assertEquals("2606:4700::1" to 8443, PingJitterMeter.parseTarget("[2606:4700::1]:8443"))
        assertEquals("2606:4700::1" to 443, PingJitterMeter.parseTarget("2606:4700::1"))
        assertEquals("w.example.workers.dev" to 443, PingJitterMeter.parseTarget("https://w.example.workers.dev/panel"))
        assertNull(PingJitterMeter.parseTarget("104.16.1.1:99999"))
        assertNull(PingJitterMeter.parseTarget(""))
        assertNull(PingJitterMeter.parseTarget("bad host!:443"))
    }
}

package com.cat.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class Beta211FenixHttp2Test {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    @Test
    fun hpackDecodesRfc7541HuffmanRequestsWithDynamicTable() {
        val d = FenixHpack.Decoder()
        assertEquals(
            listOf(":method" to "GET", ":scheme" to "http", ":path" to "/", ":authority" to "www.example.com"),
            d.decode(hex("828684418cf1e3c2e5f23a6ba0ab90f4ff")),
        )
        assertEquals(
            listOf(":method" to "GET", ":scheme" to "http", ":path" to "/", ":authority" to "www.example.com", "cache-control" to "no-cache"),
            d.decode(hex("828684be5886a8eb10649cbf")),
        )
        assertEquals(
            listOf(":method" to "GET", ":scheme" to "https", ":path" to "/index.html", ":authority" to "www.example.com", "custom-key" to "custom-value"),
            d.decode(hex("828785bf408825a849e95ba97d7f8925a849e95bb8e8b4bf")),
        )
        assertEquals(
            listOf("custom-key" to "custom-value", "cache-control" to "no-cache", ":authority" to "www.example.com"),
            d.dynamicEntries(),
        )
    }

    @Test
    fun hpackDecodesRfc7541HuffmanResponse() {
        val d = FenixHpack.Decoder()
        assertEquals(
            listOf(":status" to "302", "cache-control" to "private", "date" to "Mon, 21 Oct 2013 20:13:21 GMT", "location" to "https://www.example.com"),
            d.decode(hex("488264025885aec3771a4b6196d07abe941054d444a8200595040b8166e082a62d1bff6e919d29ad171863c78f0b97c8e9ae82ae43d3")),
        )
    }

    @Test
    fun hpackEncodeRoundTripsAndNeverIndexesThePass() {
        val headers = listOf(
            ":method" to "CONNECT",
            ":authority" to "www.google.com:443",
            "proxy-authorization" to "Bearer " + "x".repeat(300),
            "user-agent" to "Mozilla/5.0",
            "x-custom" to "v",
        )
        val block = FenixHpack.encode(headers)
        assertEquals(headers, FenixHpack.Decoder().decode(block))
        // proxy-authorization (static index 49) as «never indexed»: 0001 1111 + continuation byte (49 - 15 = 34)
        val idx = block.indexOfFirst { (it.toInt() and 0xFF) == 0x1F }
        assertTrue(idx >= 0)
        assertEquals(34, block[idx + 1].toInt() and 0xFF)
    }

    // ---- a tiny raw-frame HTTP/2 CONNECT server (plaintext) to drive the real session code ----------------------

    private fun frame(out: OutputStream, type: Int, flags: Int, stream: Int, payload: ByteArray = ByteArray(0)) {
        synchronized(out) {
            out.write(byteArrayOf((payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte(), type.toByte(), flags.toByte()))
            out.write(byteArrayOf((stream ushr 24).toByte(), (stream ushr 16).toByte(), (stream ushr 8).toByte(), stream.toByte()))
            out.write(payload)
            out.flush()
        }
    }

    private fun startFakeProxy(server: ServerSocket, requiredPass: String) = thread(isDaemon = true) {
        val s = server.accept()
        val inp = DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val preface = ByteArray(24)
        inp.readFully(preface)
        frame(out, 4, 0, 0, byteArrayOf(0, 3, 0, 0, 0, 100)) // SETTINGS MAX_CONCURRENT_STREAMS=100
        val decoder = FenixHpack.Decoder()
        val head = ByteArray(9)
        try {
            while (true) {
                inp.readFully(head)
                val len = ((head[0].toInt() and 0xFF) shl 16) or ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
                val type = head[3].toInt() and 0xFF
                val flags = head[4].toInt() and 0xFF
                val id = ((head[5].toInt() and 0x7F) shl 24) or ((head[6].toInt() and 0xFF) shl 16) or ((head[7].toInt() and 0xFF) shl 8) or (head[8].toInt() and 0xFF)
                val payload = ByteArray(len)
                inp.readFully(payload)
                when (type) {
                    4 -> if (flags and 1 == 0) frame(out, 4, 1, 0)
                    1 -> {
                        val h = decoder.decode(payload).toMap()
                        val ok = h[":method"] == "CONNECT" && h["proxy-authorization"] == "Bearer $requiredPass"
                        // 0x88 = indexed :status 200; 0x08 0x03 "407" = literal :status 407
                        val block = if (ok) byteArrayOf(0x88.toByte()) else byteArrayOf(0x08, 0x03) + "407".toByteArray()
                        frame(out, 1, if (ok) 0x4 else 0x5, id, block)
                    }
                    0 -> { // echo, then mirror the half-close
                        if (payload.isNotEmpty()) {
                            frame(out, 0, 0, id, payload)
                            frame(out, 8, 0, 0, byteArrayOf(0, 0, (payload.size ushr 8).toByte(), payload.size.toByte()))
                            frame(out, 8, 0, id, byteArrayOf(0, 0, (payload.size ushr 8).toByte(), payload.size.toByte()))
                        }
                        if (flags and 1 != 0) frame(out, 0, 1, id)
                    }
                    6 -> if (flags and 1 == 0) frame(out, 6, 1, 0, payload)
                }
            }
        } catch (_: Exception) {
            s.close()
        }
    }

    @Test
    fun sessionOpensConnectTunnelsEchoesAndReportsRejectedPass() {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        startFakeProxy(server, requiredPass = "good")
        val session = FenixH2Session(Socket(InetAddress.getLoopbackAddress(), server.localPort), "fake")
        session.start(5_000)
        try {
            try {
                session.openConnect("example.com:443", listOf("proxy-authorization" to "Bearer bad"), 5_000)
                fail("a rejected pass must not open a tunnel")
            } catch (e: FenixH2StatusException) {
                assertEquals(407, e.status)
            }
            repeat(5) { round ->
                val tunnel = session.openConnect("example.com:443", listOf("proxy-authorization" to "Bearer good"), 5_000)
                val msg = ByteArray(40_000) { (it * 31 + round).toByte() }
                tunnel.output.write(msg)
                tunnel.output.close() // END_STREAM (half-close)
                val got = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = tunnel.input.read(buf)
                    if (n < 0) break
                    got.write(buf, 0, n)
                }
                assertArrayEquals(msg, got.toByteArray())
                tunnel.close()
            }
            TimeUnit.MILLISECONDS.sleep(100)
            assertEquals(0, session.activeStreams)
            assertTrue(session.hasCapacity)
        } finally {
            session.close()
            server.close()
        }
        assertTrue(session.isClosed)
    }

    @Test
    fun engineUsesHttp2WithHttp1FallbackAndClearErrors() {
        val engine = src("FirefoxVpnEngine.kt")
        assertTrue(engine.contains("arrayOf(\"h2\", \"http/1.1\")"))
        assertTrue(engine.contains("if (alpn != \"h2\") return null to ssl"))
        assertTrue(engine.contains("return H2TunnelSocket(stream)"))
        assertTrue(engine.contains("\"proxy-authorization\" to \"Bearer \$pass\""))
        assertTrue(engine.contains("throw (statusError ?: lastError"))
        assertTrue(engine.contains("raw.connect(InetSocketAddress(resolveEdgeAddress(connectHost), connectPort), timeoutMs)"))
        assertTrue(VpnServiceSources.combined().contains("FirefoxVpnEngine.resetUpstreamSessions(\"vpn stopped\")"))
    }

    @Test
    fun lockedCountriesQuotaErrorsAndResetParsing() {
        val byCode = FirefoxVpnEngine.BUILTIN_COUNTRIES.associateBy { it.code }
        assertTrue(byCode.getValue("REC").locked.not())
        assertTrue(byCode.getValue("DE").locked.not())
        assertTrue(byCode.getValue("NL").locked)
        assertTrue(FirefoxVpnEngine.guardianTokenError(200, null).isEmpty())
        assertTrue(FirefoxVpnEngine.guardianTokenError(429, "3600").contains("3600"))
        assertTrue(FirefoxVpnEngine.guardianTokenError(451, null).contains("451"))
        assertEquals(1_700_000_000L, FirefoxVpnEngine.parseQuotaReset("1700000000"))
        assertEquals(1_700_000_000L, FirefoxVpnEngine.parseQuotaReset("1700000000000"))
        assertEquals(1_798_761_600L, FirefoxVpnEngine.parseQuotaReset("2027-01-01T00:00:00Z"))
        assertEquals(1_798_761_600L, FirefoxVpnEngine.parseQuotaReset("2027-01-01T00:00:00.000+00:00"))
        assertEquals(null, FirefoxVpnEngine.parseQuotaReset("soon"))
        // The pass lifetime comes from the JWT itself (exp - nbf), so a phone clock that is an hour off does not matter.
        // (org.json is a stub in JVM unit tests, so the JWT decode itself is checked by source.)
        assertTrue(src("FirefoxVpnEngine.kt").contains("val expSec = jwtLifetimeSeconds(passJwt)?.let { nowSec + it }"))
        assertTrue(src("FirefoxVpnEngine.kt").contains("val exp = passLocalExpiry[current] ?: run {"))
        val engine = src("FirefoxVpnEngine.kt")
        assertTrue(engine.contains("only carries web ports 80/443"))
        assertTrue(engine.contains("filter { !it.locked }"))
    }
}

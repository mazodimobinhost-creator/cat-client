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
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class Beta212SstpTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    @Test
    fun md4MatchesRfc1320Vectors() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", SstpCrypto.md4(ByteArray(0)).hex())
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", SstpCrypto.md4("abc".toByteArray()).hex())
        assertEquals("d79e1c308aa5bbcdeea8ed63df412da9", SstpCrypto.md4("abcdefghijklmnopqrstuvwxyz".toByteArray()).hex())
        assertEquals(
            "e33b4ddc9c38f2199c3e7b164fcc0536",
            SstpCrypto.md4("12345678901234567890123456789012345678901234567890123456789012345678901234567890".toByteArray()).hex(),
        )
    }

    @Test
    fun msChapV2MatchesRfc2759AndMppeMatchesRfc3079() {
        val auth = hex("5B5D7C7D7B3F2F3E3C2C602132262628")
        val peer = hex("21402324255E262A28295F2B3A337C7E")
        assertEquals("d02e4386bce91226", SstpCrypto.challengeHash(peer, auth, "User").hex())
        assertEquals("44ebba8d5312b8d611474411f56989ae", SstpCrypto.ntPasswordHash("clientPass").hex())
        val nt = SstpCrypto.ntResponse(auth, peer, "User", "clientPass")
        assertEquals("82309ecd8d708b5ea08faa3981cd83544233114a3d85d6df", nt.hex())
        assertEquals("S=407A5589115FD0D6209F510FE9C04566932CDA56", SstpCrypto.authenticatorResponse("clientPass", nt, peer, auth, "User"))
        assertEquals("fdece3717a8c838cb388e527ae3cdd31", SstpCrypto.masterKey("clientPass", nt).hex())
        assertEquals(32, SstpCrypto.hlak("clientPass", nt).size)
        // RFC 3079 3.5.3 derives the *server* send key (Magic3) = the client's receive key = HLAK's second half.
        assertEquals("8b7cdc149b993a1ba118cb153f56dccb", SstpCrypto.hlak("clientPass", nt).copyOfRange(16, 32).hex())
    }

    @Test
    fun linksRoundTripAndBareVpnGateHostsAreAccepted() {
        val l = SstpLink.parse("sstp://vpn:vpn@vpn149232523.opengw.net:1270?ip=125.195.49.116#%F0%9F%87%AF%F0%9F%87%B5%20Japan")!!
        assertEquals("vpn149232523.opengw.net", l.host)
        assertEquals(1270, l.port)
        assertEquals("125.195.49.116", l.ip)
        assertEquals("🇯🇵 Japan", l.remark)
        assertEquals(l, SstpLink.parse(l.toUri()))
        val bare = SstpLink.parse("public-vpn-79.opengw.net")!!
        assertEquals(443, bare.port)
        assertEquals("vpn", bare.user)
        assertEquals("vpn", bare.password)
        assertEquals(995, SstpLink.parse("vpn390088297.opengw.net:995")!!.port)
        assertEquals(null, SstpLink.parse("example.com:443"))
        assertEquals(null, SstpLink.parse("sstp://vpn:vpn@:443"))
        val v6 = SstpLink.parse("sstp://a%40b:p%3Aw@[2001:db8::1]:8443")!!
        assertEquals("2001:db8::1", v6.host)
        assertEquals("a@b", v6.user)
        assertEquals("p:w", v6.password)
        assertEquals(v6, SstpLink.parse(v6.toUri()))
    }

    // ---------------------------------------------------------------- fake SSTP server

    private class Wire(val inp: DataInputStream, val out: OutputStream) {
        fun read(): Pair<Boolean, ByteArray> {
            val h = ByteArray(4)
            inp.readFully(h)
            val len = ((h[2].toInt() and 0x0F) shl 8) or (h[3].toInt() and 0xFF)
            val b = ByteArray(len - 4)
            inp.readFully(b)
            return ((h[1].toInt() and 1) == 1) to b
        }

        /** Next PPP frame (protocol, info) skipping control packets into [controls]. */
        fun ppp(controls: MutableList<Int>? = null): Pair<Int, ByteArray> {
            while (true) {
                val (control, b) = read()
                if (control) {
                    controls?.add(SstpConnection.be16(b, 0))
                    continue
                }
                check(b[0] == 0xFF.toByte() && b[1].toInt() == 3) { "missing FF 03" }
                return SstpConnection.be16(b, 2) to b.copyOfRange(4, b.size)
            }
        }

        fun control(type: Int, vararg attrs: ByteArray) {
            val len = 8 + attrs.sumOf { it.size }
            val o = ByteArrayOutputStream()
            o.write(byteArrayOf(0x10, 1, (len shr 8).toByte(), len.toByte(), 0, type.toByte(), 0, attrs.size.toByte()))
            attrs.forEach { o.write(it) }
            out.write(o.toByteArray())
            out.flush()
        }

        fun sendPpp(proto: Int, info: ByteArray) {
            val len = 8 + info.size
            out.write(byteArrayOf(0x10, 0, (len shr 8).toByte(), len.toByte(), -1, 3, (proto shr 8).toByte(), proto.toByte()) + info)
            out.flush()
        }

        fun cp(proto: Int, code: Int, id: Int, data: ByteArray) =
            sendPpp(proto, byteArrayOf(code.toByte(), id.toByte(), ((data.size + 4) shr 8).toByte(), (data.size + 4).toByte()) + data)
    }

    private fun opts(vararg o: Pair<Int, ByteArray>) =
        o.fold(ByteArray(0)) { acc, (t, v) -> acc + byteArrayOf(t.toByte(), (v.size + 2).toByte()) + v }

    private fun cpOptions(info: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var p = 4
        val len = SstpConnection.be16(info, 2)
        while (p + 2 <= len) {
            out += info[p].toInt() and 0xFF
            p += info[p + 1].toInt() and 0xFF
        }
        return out
    }

    private fun runFakeServer(ss: ServerSocket, password: String, cert: ByteArray, errors: MutableList<String>, ipPacket: ByteArray, gotUp: LinkedBlockingQueue<ByteArray>) =
        thread(isDaemon = true) {
            try {
                ss.accept().use { s ->
                    val w = Wire(DataInputStream(s.getInputStream()), s.getOutputStream())
                    val req = StringBuilder()
                    while (!req.endsWith("\r\n\r\n")) req.append(w.inp.read().toChar())
                    if (!req.startsWith("SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1")) errors += "bad http: $req"
                    if (!req.contains("Host: fake.opengw.net")) errors += "no host header"
                    w.out.write("HTTP/1.1 200\r\nContent-Length: 18446744073709551615\r\nServer: Microsoft-HTTPAPI/2.0\r\n\r\n".toByteArray())
                    val (c1, cr) = w.read()
                    if (!c1 || SstpConnection.be16(cr, 0) != 1) errors += "expected CONNECT_REQUEST"
                    val nonce = ByteArray(32) { (it * 7).toByte() }
                    w.control(2, byteArrayOf(0, 4, 0, 40, 0, 0, 0, 3) + nonce)

                    // LCP: offer PFC/ACFC first (must be rejected), then a clean MS-CHAPv2 request.
                    val chap = byteArrayOf(0xC2.toByte(), 0x23, 0x81.toByte())
                    w.cp(0xC021, 1, 1, opts(1 to byteArrayOf(5, 0xDC.toByte()), 3 to chap, 5 to byteArrayOf(1, 2, 3, 4), 7 to ByteArray(0), 8 to ByteArray(0)))
                    var clientReqAcked = false
                    var peerAcked = false
                    while (!(clientReqAcked && peerAcked)) {
                        val (proto, info) = w.ppp()
                        if (proto != 0xC021) { errors += "unexpected proto $proto in LCP"; continue }
                        when (info[0].toInt()) {
                            1 -> { w.cp(0xC021, 2, info[1].toInt() and 0xFF, info.copyOfRange(4, SstpConnection.be16(info, 2))); clientReqAcked = true }
                            4 -> {
                                if (cpOptions(info) != listOf(7, 8)) errors += "reject list ${cpOptions(info)}"
                                w.cp(0xC021, 1, 2, opts(1 to byteArrayOf(5, 0xDC.toByte()), 3 to chap, 5 to byteArrayOf(1, 2, 3, 4)))
                            }
                            2 -> peerAcked = true
                            else -> errors += "LCP code ${info[0]}"
                        }
                    }
                    // MS-CHAPv2
                    val authChal = ByteArray(16) { (it + 100).toByte() }
                    w.cp(0xC223, 1, 9, byteArrayOf(16) + authChal + "fake".toByteArray())
                    val (cproto, resp) = w.ppp()
                    if (cproto != 0xC223 || resp[0].toInt() != 2 || resp[1].toInt() != 9) errors += "bad CHAP response"
                    val value = resp.copyOfRange(5, 5 + 49)
                    val peer = value.copyOfRange(0, 16)
                    val nt = value.copyOfRange(24, 48)
                    val user = String(resp.copyOfRange(5 + 49, SstpConnection.be16(resp, 2)))
                    if (user != "vpn") errors += "user $user"
                    if (!nt.contentEquals(SstpCrypto.ntResponse(authChal, peer, user, password))) {
                        w.cp(0xC223, 4, 9, "E=691 R=0 C=00 V=3 M=Access denied".toByteArray())
                        return@use
                    }
                    w.cp(0xC223, 3, 9, (SstpCrypto.authenticatorResponse(password, nt, peer, authChal, user) + " M=Access granted").toByteArray())

                    // Call-Connected + crypto binding
                    val controls = ArrayList<Int>()
                    var cc: ByteArray? = null
                    while (cc == null) {
                        val (isControl, b) = w.read()
                        if (isControl && SstpConnection.be16(b, 0) == 4) cc = b
                        else if (!isControl) errors += "data before CALL_CONNECTED"
                    }
                    val pkt = byteArrayOf(0x10, 1, 0, 112) + cc
                    if (pkt.size != 112) errors += "CALL_CONNECTED size ${pkt.size}"
                    if (pkt[8 + 7].toInt() != 2) errors += "hash proto ${pkt[15]}"
                    if (!pkt.copyOfRange(16, 48).contentEquals(nonce)) errors += "nonce echo"
                    if (!pkt.copyOfRange(48, 80).contentEquals(MessageDigest.getInstance("SHA-256").digest(cert))) errors += "cert hash"
                    val mac = pkt.copyOfRange(80, 112)
                    val zeroed = pkt.copyOf().also { java.util.Arrays.fill(it, 80, 112, 0) }
                    val expected = SstpCrypto.compoundMac(true, SstpCrypto.hlak(password, nt), zeroed)
                    if (!mac.contentEquals(expected)) errors += "compound MAC mismatch"

                    // IPCP + an IPV6CP request that must be protocol-rejected
                    w.cp(0x8057, 1, 3, opts(1 to ByteArray(8) { 1 }))
                    w.cp(0x8021, 1, 4, opts(3 to byteArrayOf(10, 211.toByte(), 254.toByte(), 254.toByte()), 2 to byteArrayOf(0, 0x2d, 15, 1)))
                    var ipAcked = false
                    var ipPeerAcked = false
                    var sawProtoReject = false
                    while (!(ipAcked && ipPeerAcked)) {
                        val (proto, info) = w.ppp()
                        if (proto == 0xC021 && info[0].toInt() == 8) { sawProtoReject = SstpConnection.be16(info, 4) == 0x8057; continue }
                        if (proto != 0x8021) { errors += "proto $proto in IPCP"; continue }
                        val id = info[1].toInt() and 0xFF
                        val o = cpOptions(info)
                        when (info[0].toInt()) {
                            1 -> if (o.contains(131)) w.cp(0x8021, 4, id, opts(131 to ByteArray(4)))
                            else if (info[6].toInt() == 0) w.cp(0x8021, 3, id, opts(3 to byteArrayOf(10, 211.toByte(), 1, 5), 129 to byteArrayOf(8, 8, 8, 8)))
                            else { w.cp(0x8021, 2, id, info.copyOfRange(4, SstpConnection.be16(info, 2))); ipAcked = true }
                            4 -> if (o != listOf(2)) errors += "IPCP reject $o"
                            else w.cp(0x8021, 1, 5, opts(3 to byteArrayOf(10, 211.toByte(), 254.toByte(), 254.toByte())))
                            2 -> ipPeerAcked = true
                        }
                    }
                    if (!sawProtoReject) errors += "IPV6CP was not protocol-rejected"
                    // data plane: echo + one packet each way, then terminate
                    w.control(8)
                    w.sendPpp(0x0021, ipPacket)
                    val seen = ArrayList<Int>()
                    while (true) {
                        val (proto, info) = w.ppp(seen)
                        if (proto == 0x0021) { gotUp.put(info); break }
                    }
                    if (9 !in seen) {
                        val (isC, b) = w.read()
                        if (!(isC && SstpConnection.be16(b, 0) == 9)) errors += "no echo response"
                    }
                    w.cp(0xC021, 5, 77, ByteArray(0))
                    val (tp, ti) = w.ppp()
                    if (tp != 0xC021 || ti[0].toInt() != 6) errors += "no terminate-ack"
                    Thread.sleep(200)
                }
            } catch (e: Exception) {
                errors += "server: $e"
            }
        }

    private fun ipv4(src: Int, dst: Int): ByteArray = ByteArray(28).also {
        it[0] = 0x45
        it[3] = 28
        it[9] = 17
        it[15] = src.toByte()
        it[19] = dst.toByte()
    }

    @Test
    fun fullCallAgainstFakeServerCarriesPacketsBothWays() {
        val ss = ServerSocket(0)
        val errors = java.util.Collections.synchronizedList(ArrayList<String>())
        val cert = "fake certificate".toByteArray()
        val down = ipv4(1, 2)
        val up = LinkedBlockingQueue<ByteArray>()
        val server = runFakeServer(ss, "vpn", cert, errors, down, up)
        val sock = Socket("127.0.0.1", ss.localPort)
        val conn = SstpConnection(sock.getInputStream(), sock.getOutputStream(), cert, SstpLink("fake.opengw.net"), {}) { sock.close() }
        val lease = conn.handshake(10_000L)
        assertEquals("10.211.1.5", lease.address)
        assertEquals(listOf("8.8.8.8"), lease.dns)
        assertEquals(1400, lease.mtu)

        // Like a TUN fd: one read() returns exactly one packet.
        val tunPackets = LinkedBlockingQueue<ByteArray>()
        val tunIn = object : java.io.InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val p = tunPackets.take()
                if (p.isEmpty()) return -1
                p.copyInto(b, off)
                return p.size
            }
        }
        val tunOut = LinkedBlockingQueue<ByteArray>()
        val closed = CountDownLatch(1)
        var reason = ""
        conn.startDataPlane(tunIn, object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) { tunOut.put(b.copyOfRange(off, off + len)) }
        }) { reason = it; closed.countDown() }
        assertArrayEquals(down, tunOut.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no packet from server"))
        tunPackets.put(ByteArray(40).also { it[0] = 0x60 }) // IPv6 is dropped
        val sent = ipv4(9, 8)
        tunPackets.put(sent)
        assertArrayEquals(sent, up.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no packet to server: $errors"))
        assertTrue("closed on terminate", closed.await(5, TimeUnit.SECONDS))
        assertTrue(reason, reason.contains("terminate"))
        server.join(3000)
        assertTrue(errors.toString(), errors.isEmpty())
        ss.close()
    }

    @Test
    fun wrongPasswordFailsWithPlainReason() {
        val ss = ServerSocket(0)
        val errors = java.util.Collections.synchronizedList(ArrayList<String>())
        runFakeServer(ss, "other-password", ByteArray(8), errors, ipv4(1, 2), LinkedBlockingQueue())
        val sock = Socket("127.0.0.1", ss.localPort)
        val conn = SstpConnection(sock.getInputStream(), sock.getOutputStream(), ByteArray(8), SstpLink("fake.opengw.net"), {}) { sock.close() }
        try {
            conn.handshake(8_000L)
            fail("handshake should fail")
        } catch (e: SstpException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("username/password"))
        }
        assertTrue(conn.isClosed)
        ss.close()
    }

    @Test
    fun silentServerTimesOut() {
        val ss = ServerSocket(0)
        val accepted = thread(isDaemon = true) { runCatching { ss.accept().getInputStream().read(ByteArray(4096)); Thread.sleep(5000) } }
        val sock = Socket("127.0.0.1", ss.localPort)
        val conn = SstpConnection(sock.getInputStream(), sock.getOutputStream(), ByteArray(8), SstpLink("fake.opengw.net"), {}) { sock.close() }
        val t0 = System.currentTimeMillis()
        try {
            conn.handshake(1_500L)
            fail("should time out")
        } catch (e: SstpException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("timed out"))
        }
        assertTrue(System.currentTimeMillis() - t0 < 4_000L)
        accepted.interrupt()
        ss.close()
    }
}

class Beta212SstpListTest {
    private val sample = """
        {"updated":"2026-10-29T06:40:28.374Z","count":2,"sources":{"vpngate":{"ok":true,"count":79}},"servers":[
        {"rank":1,"host":"public-vpn-79.opengw.net","cc":"JP","country":"Japan","ip":"219.100.37.27","flag":"🇯🇵","uptime":"4 days","uptime_min":5760,"uptime_days":4,"ping_ms":14,"speed_mbps":227.82,"traffic_gb":682696.29},
        {"rank":43,"host":"vpn149232523.opengw.net:1270","cc":"KR","country":"Korea \u0052epublic","ip":"125.195.49.116","flag":"🇰🇷","uptime":"0 mins","uptime_min":0,"uptime_days":0,"ping_ms":7,"speed_mbps":839.07,"traffic_gb":12148.09}
        ]}
    """.trimIndent()

    @org.junit.Test
    fun mirrorJsonBecomesSstpLinksWithExactPorts() {
        val list = SstpServerList.parseJson(sample)
        org.junit.Assert.assertEquals(2, list.size)
        org.junit.Assert.assertEquals(443, list[0].port)
        org.junit.Assert.assertEquals("219.100.37.27", list[0].ip)
        org.junit.Assert.assertEquals(1270, list[1].port)
        org.junit.Assert.assertEquals("vpn149232523.opengw.net", list[1].host)
        org.junit.Assert.assertEquals("Korea Republic", list[1].country)
        val link = list[1].toLink()
        org.junit.Assert.assertEquals("Korea Republic 🇰🇷 · 839 Mbps · vpn149232523", link.remark)
        val text = SstpServerList.subscriptionText(list)
        val back = text.lines().map { SstpLink.parse(it)!! }
        org.junit.Assert.assertEquals(listOf(443, 1270), back.map { it.port })
        org.junit.Assert.assertEquals("125.195.49.116", back[1].ip)
        org.junit.Assert.assertEquals("vpn", back[1].user)
        org.junit.Assert.assertTrue(ConnectionLabelPolicy.displayName(link.remark, "socks5", "127.0.0.1", SstpLink.PLACEHOLDER_PORT).contains("SSTP 839 Mbps"))
    }

    @org.junit.Test
    fun mirrorTxtAndUrls() {
        val list = SstpServerList.parseTxt("public-vpn-61.opengw.net\nvpn390088297.opengw.net:995\n\nbad host:x\n")
        org.junit.Assert.assertEquals(listOf(443, 995), list.map { it.port })
        org.junit.Assert.assertEquals("https://sstp.wireconf.workers.dev/api?sort=speed&limit=30&format=txt", SstpServerList.apiUrl("speed", 30, txt = true))
        org.junit.Assert.assertEquals("https://sstp.wireconf.workers.dev/api?sort=site&limit=200&min_speed=50", SstpServerList.apiUrl("x", 999, 50))
    }
}

class Beta212SstpWiringTest {
    private fun src(name: String): String =
        listOf(java.io.File("src/main/java/com/cat/client/$name"), java.io.File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    @org.junit.Test
    fun serviceRunsSstpStraightIntoTheTunAndClosesItOnEveryStopPath() {
        val svc = src("CatClientVpnService.kt")
        org.junit.Assert.assertTrue(svc.contains("runSstpStartup(eventPrefix, selectedSubscriptionId, candidates)"))
        org.junit.Assert.assertTrue(svc.contains("SstpDialer.open("))
        org.junit.Assert.assertTrue(svc.contains("connection.startDataPlane("))
        org.junit.Assert.assertTrue(svc.contains(".addRoute(\"::\", 0)"))
        listOf("closeSstp(\"service destroyed\")", "closeSstp(\"disconnect\")", "closeSstp(\"failure\")").forEach {
            org.junit.Assert.assertTrue(it, svc.contains(it))
        }
        org.junit.Assert.assertTrue(svc.contains("if (scheme == SstpLink.SCHEME)"))
        org.junit.Assert.assertTrue(svc.contains("connection.switch.sstpRestart"))
    }

    @org.junit.Test
    fun dialerCreatesTheDescriptorBeforeProtecting() {
        // A fresh Android Socket has no fd until an option/bind/connect; protect() on it returns false (beta212 bug).
        val dialer = src("SstpClient.kt").substringAfter("internal object SstpDialer")
        val option = dialer.indexOf("raw.tcpNoDelay = true")
        val protect = dialer.indexOf("protect(raw)")
        org.junit.Assert.assertTrue(option in 0 until protect)
        org.junit.Assert.assertTrue(dialer.contains("raw.bind(null)"))
    }

    @org.junit.Test
    fun subscriptionsAndUiKnowSstp() {
        val conv = src("SubConvConverter.kt")
        org.junit.Assert.assertTrue(conv.contains("\"sstp://\""))
        org.junit.Assert.assertTrue(conv.contains("SstpLink.SCHEME -> parseSstp(line, names)"))
        org.junit.Assert.assertTrue(conv.contains(".put(\"sstp-uri\", link.toUri())"))
        org.junit.Assert.assertTrue(src("MihomoShareLink.kt").contains("proxy.str(\"sstp-uri\")"))
        org.junit.Assert.assertTrue(src("MihomoRuntime.kt").contains("joined.contains(\"sstp-uri:\")"))
        org.junit.Assert.assertTrue(src("IspProtocolMatrix.kt").contains("\"sstp-uri\""))
        val ui = src("MainActivity.kt")
        org.junit.Assert.assertTrue(ui.contains("private fun showCatSstpDialog()"))
        org.junit.Assert.assertTrue(ui.contains("{ showCatSstpDialog() }"))
        org.junit.Assert.assertTrue(ui.contains("SstpServerList.fetch(sort, CAT_SSTP_LIST_LIMIT)"))
    }
}

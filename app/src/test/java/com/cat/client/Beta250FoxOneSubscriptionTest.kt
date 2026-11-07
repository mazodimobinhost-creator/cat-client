package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64

/** beta250: ONE «🦊 Cat Fox VPN» subscription, every country a row routed by its SOCKS user; Fanoos «every country». */
class Beta250FoxOneSubscriptionTest {

    @Test fun countryRidesInTheSocksUserAndNothingElseIsMistakenForIt() {
        assertEquals("fox-DE", FoxCountryRouting.userFor("de"))
        assertEquals("DE", FoxCountryRouting.codeFromUser("fox-de"))
        assertEquals("REC", FoxCountryRouting.codeFromUser("FOX-REC"))
        assertNull(FoxCountryRouting.codeFromUser(null))
        assertNull(FoxCountryRouting.codeFromUser("catgp"))
        assertNull(FoxCountryRouting.codeFromUser("fox-"))
        assertNull(FoxCountryRouting.codeFromUser("fox-1234"))
    }

    @Test fun greetingAnswersUserPassOnlyWhenOffered() {
        assertEquals(0x02, FoxCountryRouting.chooseSocksMethod(byteArrayOf(0x02)))
        assertEquals(0x02, FoxCountryRouting.chooseSocksMethod(byteArrayOf(0x00, 0x02)))
        assertEquals(0x00, FoxCountryRouting.chooseSocksMethod(byteArrayOf(0x00)))
        assertEquals(0x00, FoxCountryRouting.chooseSocksMethod(ByteArray(0)))
        val ok = byteArrayOf(1, 6) + "fox-JP".toByteArray() + byteArrayOf(3) + "cat".toByteArray()
        assertEquals("fox-JP" to "cat", FoxCountryRouting.readUserPass(ByteArrayInputStream(ok)))
        assertNull(FoxCountryRouting.readUserPass(ByteArrayInputStream(byteArrayOf(5, 6, 1))))
        assertNull(FoxCountryRouting.readUserPass(ByteArrayInputStream(byteArrayOf(1, 6) + "fox".toByteArray())))
    }

    @Test fun httpConnectSideReadsTheCountryFromProxyAuthorization() {
        val basic = Base64.getEncoder().encodeToString("fox-US:cat".toByteArray())
        assertEquals("US", FoxCountryRouting.codeFromProxyAuthorization(listOf("Host: x", "Proxy-Authorization: Basic $basic")))
        assertNull(FoxCountryRouting.codeFromProxyAuthorization(listOf("Host: x")))
        assertNull(FoxCountryRouting.codeFromProxyAuthorization(listOf("Proxy-Authorization: Bearer abc")))
    }

    /** What mihomo does for a socks5 row with username/password, against the bridge's side of the handshake. */
    @Test fun socksHandshakeOverARealSocketHandsTheBridgeTheRowCountry() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        var seen: String? = "unset"
        val t = Thread {
            server.accept().use { c ->
                val inp = c.getInputStream()
                val out = c.getOutputStream()
                check(inp.read() == 5)
                val n = inp.read()
                val methods = ByteArray(n).also { var o = 0; while (o < n) o += inp.read(it, o, n - o) }
                val m = FoxCountryRouting.chooseSocksMethod(methods)
                out.write(byteArrayOf(5, m.toByte())); out.flush()
                seen = if (m == 2) {
                    val (u, _) = FoxCountryRouting.readUserPass(inp)!!
                    out.write(byteArrayOf(1, 0)); out.flush()
                    FoxCountryRouting.codeFromUser(u)
                } else null
            }
        }.apply { start() }
        Socket("127.0.0.1", server.localPort).use { s ->
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            out.write(byteArrayOf(5, 1, 2)); out.flush()
            assertEquals(5, inp.read()); assertEquals(2, inp.read())
            val u = FoxCountryRouting.userFor("AU").toByteArray()
            val p = FoxCountryRouting.PASSWORD.toByteArray()
            out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); out.flush()
            assertEquals(1, inp.read()); assertEquals(0, inp.read())
        }
        t.join(5_000)
        server.close()
        assertEquals("AU", seen)
    }

    @Test fun rowsAreOrderedRecFreeThenLockedAndTheChosenRowIsFound() {
        data class C(val code: String, val locked: Boolean)
        val list = listOf(C("NL", true), C("DE", false), C("REC", false), C("JP", false), C("de", false), C("GB", true))
        val ordered = FoxCountryRouting.ordered(list, { it.code }, { it.locked })
        assertEquals(listOf("REC", "DE", "JP", "NL", "GB"), ordered.map { it.code })
        val links = ordered.map { "fenix://fox-${it.code}:cat@127.0.0.1:10829/?country=${it.code}&up_host=h&up_port=2499&frag=1#x" }
        assertEquals(2, FoxCountryRouting.pickRow(links, "jp"))
        assertEquals(0, FoxCountryRouting.pickRow(links, "ZZ"))
        assertEquals(0, FoxCountryRouting.pickRow(links, null))
        assertEquals("NL", FoxCountryRouting.codeFromFenixUri(links[3]))
        assertNull(FoxCountryRouting.codeFromFenixUri("fenix://127.0.0.1:10829/#x"))
        val text = FoxCountryRouting.subscriptionText(links)
        assertTrue(text.startsWith("#")) // never read as a subscription URL
        assertEquals(links.size + 1, text.lines().size)
    }

    @Test fun fanoosEveryCountryKeepsFewServersPerCountryInOneList() {
        val rows = listOf("a" to "DE", "b" to "DE", "c" to "DE", "a" to "DE", "d" to "US", "e" to "", "f" to "", "g" to "DE")
        val limited = LanternPlanner.limitPerCountry(rows, perCountry = 2)
        assertEquals(listOf("a", "b", "d", "e", "f"), limited.map { it.first })
        assertEquals(listOf("DE", "US"), LanternPlanner.countriesOf(limited))
        val merged = LanternPlanner.merge(listOf("old" to "JP", "b" to "DE"), limited, cap = LanternPlanner.ALL_COUNTRIES_CAP)
        assertTrue(merged.any { it.first == "old" }) // other countries stay
        assertEquals(merged.size, merged.distinctBy { it.first }.size)
    }
}

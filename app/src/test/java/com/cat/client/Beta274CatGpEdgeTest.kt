package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta274 — Cat GP takes Google edge IP + SNI from a real scan on this network and saves them. */
class Beta274CatGpEdgeTest {

    @Test
    fun versionIsBeta274() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 356"))
        assertTrue(gradle.contains("1.10.0-beta289"))
    }

    @Test
    fun googleHostAndIpParsing() {
        assertTrue(CatGpEdge.isGoogleHost("beacons.gvt2.com"))
        assertTrue(CatGpEdge.isGoogleHost("www.google.com"))
        assertTrue(CatGpEdge.isGoogleHost("checkin.gstatic.com"))
        assertTrue(CatGpEdge.isGoogleHost("gmail.com"))
        assertFalse(CatGpEdge.isGoogleHost("python.org"))
        assertFalse(CatGpEdge.isGoogleHost("cloudflare.com"))
        assertEquals("beacons.gvt2.com", CatGpEdge.parseSni("https://beacons.gvt2.com/x"))
        assertNull(CatGpEdge.parseSni("python.org"))
        assertNull(CatGpEdge.parseSni(""))
        assertEquals(listOf("216.239.38.120", "142.250.154.101"), CatGpEdge.parseIps("216.239.38.120\n142.250.154.101"))
        assertTrue(CatGpEdge.parseIps("not-an-ip\nscript.google.com").isEmpty())
        assertTrue(CatGpEdge.ipsToScan("").contains("216.239.38.120"))
        assertEquals(listOf("216.239.38.120"), CatGpEdge.ipsToScan("216.239.38.120"))
        assertEquals("beacons.gvt2.com", CatGpEdge.snisToScan("beacons.gvt2.com").first())
        assertEquals(
            "E3:B0:C4:42:98:FC:1C:14:9A:FB:F4:C8:99:6F:B9:24:27:AE:41:E4:64:9B:93:4C:A4:95:99:1B:78:52:B8:55",
            CatGpEdge.sha256Colon(ByteArray(0)),
        )
    }

    @Test
    fun scanKeepsOnlyProbedHitsAndRanksByMs() {
        val hits = CatGpEdge.scan(
            listOf("1.1.1.1", "216.239.38.120"),
            listOf("www.google.com", "beacons.gvt2.com"),
            probe = { ip, sni ->
                when {
                    ip == "216.239.38.120" && sni == "beacons.gvt2.com" -> 289L
                    ip == "216.239.38.120" && sni == "www.google.com" -> 120L
                    else -> null
                }
            },
        )
        assertEquals(listOf("www.google.com", "beacons.gvt2.com"), hits.map { it.sni })
        assertTrue(hits.all { it.ip == "216.239.38.120" })
        assertEquals("216.239.38.120 · beacons.gvt2.com · 289 ms", CatGpEdge.line(hits[1]))
        assertTrue(CatGpEdge.scan(listOf("9.9.9.9"), listOf("www.google.com"), probe = { _, _ -> null }).isEmpty())
    }

    @Test
    fun hubWiresScanIntoCatGpAndSavesIpSni() {
        val ui = src("MainActivityCatGp.kt")
        assertTrue(ui.contains("scanEdges"))
        assertTrue(ui.contains("saveEdgeIps"))
        assertTrue(ui.contains("saveEdgeSni"))
        assertTrue(ui.contains("اسکن این شبکه برای لبهٔ سالم"))
        assertTrue(ui.contains("Scan this network for a working edge"))
        assertTrue(ui.contains("Google edge address"))
        assertTrue(ui.contains("Edge SNI"))
        assertTrue(ui.contains("caSha256"))
        assertTrue(ui.contains("CAT_KEY"))
        assertFalse("no other-app name", ui.contains("GUTS") || ui.contains("Nguts") || ui.contains("AUTH_KEY"))
        val engine = src("CatGpEngine.kt")
        assertTrue(engine.contains("frontSnis"))
        assertTrue(engine.contains("snis = frontSnis"))
        assertTrue(engine.contains("scanEdges"))
        assertFalse(engine.contains("python.org"))
        val edge = src("CatGpRelay.kt")
        assertTrue(edge.contains("beacons.gvt2.com"))
        assertTrue(edge.contains("isGoogleHost"))
        assertTrue(edge.contains("tlsMs"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta287 — Cat imports Voidly/Proton WireGuard .conf; official WireGuard app not required; OpenVPN not run. */
class Beta287VoidlyWireGuardTest {

    @Test
    fun versionIsBeta287() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 372"))
        assertTrue(gradle.contains("1.10.0-beta305"))
    }

    @Test
    fun voidlyConfBecomesWireGuardUri() {
        val conf = """
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.7.0.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Endpoint = 203.0.113.10:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        assertTrue(WireGuardConfText.looksLikeConf(conf))
        val uris = WireGuardConfText.toUris(conf)
        assertEquals(1, uris.size)
        assertTrue(uris[0].startsWith("wireguard://"))
        assertTrue(uris[0].contains("203.0.113.10:51820"))
        assertEquals("Voidly 203.0.113.10:51820", VoidlyPlanner.rowLabel(conf))
        assertEquals("https://voidly.ai/vpn", VoidlyPlanner.PAGE)
        assertTrue(VoidlyPlanner.subscriptionText(uris).contains("Cat Voidly"))
    }

    @Test
    fun hubImportsConfWithoutOfficialAppAndDoesNotRunOpenVpn() {
        val hub = src("MainActivityFreeNetworks.kt")
        assertTrue(hub.contains("showVoidlyDialog"))
        assertTrue(hub.contains("اپ رسمی WireGuard لازم نیست"))
        assertTrue(hub.contains("اپن‌وی‌پی‌ان پروتون در Cat اجرا نمی‌شود"))
        assertTrue(hub.contains("UDP"))
        val amnezia = src("MainActivityAmneziaSstp.kt")
        assertTrue(amnezia.contains("voidly.ai/vpn"))
        assertTrue(amnezia.contains("اپ رسمی WireGuard لازم نیست"))
        assertFalse(amnezia.contains("OpenVPN را اجرا"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

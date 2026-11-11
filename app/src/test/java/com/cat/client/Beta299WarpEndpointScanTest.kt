package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta299 — on-device Cloudflare WARP endpoint scan inside Cat Warp; exact IP:port. */
class Beta299WarpEndpointScanTest {

    @Test
    fun versionIsBeta299() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 370"))
        assertTrue(gradle.contains("1.10.0-beta303"))
    }

    @Test
    fun scannedPeerKeepsExactPortAndBuildsAmneziaOnUdpOnly() {
        assertEquals("162.159.192.1" to 2408, CatWarpFreePlanner.splitPeer("162.159.192.1:2408"))
        assertEquals("162.159.192.1:2408", CatWarpFreePlanner.keepExactPeer("162.159.192.1:2408"))
        assertEquals("162.159.192.1:500", CatWarpFreePlanner.keepExactPeer("162.159.192.1:500"))
        assertEquals("162.159.192.1:1701", CatWarpFreePlanner.keepExactPeer("162.159.192.1:1701"))
        assertEquals("162.159.192.1:4500", CatWarpFreePlanner.keepExactPeer("162.159.192.1:4500"))
        assertEquals("162.159.198.1:443", CatWarpFreePlanner.keepExactPeer("162.159.198.1:443"))
        assertNull(CatWarpFreePlanner.keepExactPeer("162.159.192.1:80"))
        assertNull(CatWarpFreePlanner.keepExactPeer("162.159.192.1:1080"))
        assertNull(CatWarpFreePlanner.splitPeer("127.0.0.1:2408"))
        assertEquals("[2606:4700:d0::a29f:c001]:2408", CatWarpFreePlanner.keepExactPeer("[2606:4700:d0::a29f:c001]:2408"))
        assertEquals("v6", CatWarpFreePlanner.ipScanForPeer("[2606:4700:d0::a29f:c001]:2408"))
        assertEquals("v4", CatWarpFreePlanner.ipScanForPeer("162.159.192.1:2408"))

        val awg = CatWarpFreePlanner.awgUri("162.159.192.1:2408", "Cat Warp 162.159.192.1:2408")
        assertNotNull(awg)
        assertTrue(awg!!, awg.startsWith("awg://auto@162.159.192.1:2408?"))
        assertTrue(awg, awg.contains(":2408"))
        assertFalse(awg, awg.contains(":80") || awg.contains(":1080"))
        assertTrue(awg, awg.contains("jc=5"))
        assertNull(CatWarpFreePlanner.awgUri("162.159.198.1:443", "masque"))
        assertNull(CatWarpFreePlanner.awgUri("1.2.3.4:80", "no"))
        assertNull(CatWarpFreePlanner.wireguardUri("162.159.198.1:443", "masque"))
        val wg = CatWarpFreePlanner.wireguardUri("188.114.98.193:500", "Cat Warp 188.114.98.193:500")
        assertNotNull(wg)
        assertTrue(wg!!, wg.startsWith("wireguard://auto@188.114.98.193:500?"))
        assertFalse(wg, wg.contains(":80"))

        val links = CatWarpFreePlanner.awgLinksFromPeers(
            listOf("162.159.192.1:2408", "162.159.198.1:443", "10.0.0.1:80", "162.159.192.1:2408"),
        )
        assertEquals(1, links.size)
        assertTrue(links.single().contains("162.159.192.1:2408"))
    }

    @Test
    fun catWarpPickerScansThisLineWithoutExtraHomeButtonOrForeignScanner() {
        val hub = src("MainActivityFreeNetworks.kt")
        assertTrue(hub.contains("fun MainActivity.scanCatWarpOnThisLine"))
        assertTrue(hub.contains("setNeutralButton(t(\"🔍 اسکن این خط\""))
        assertTrue(hub.contains("AetherOperations.scan(AetherMode.WireGuard"))
        assertTrue(hub.contains("AetherOperations.scan(AetherMode.MasqueHttp2"))
        assertTrue(hub.contains("VpnState.Stopped"))
        assertTrue(hub.contains("awgLinksFromPeers"))
        assertTrue(hub.contains("wireguardUri"))
        assertTrue(hub.contains("keepExactPeer"))
        assertFalse(hub.lowercase().contains("warpscout"))
        assertFalse(hub.contains("openwarpkit"))
        val start = hub.indexOf("internal fun MainActivity.showCatWarpFreeDialog")
        val end = hub.indexOf("internal fun MainActivity.scanCatWarpOnThisLine")
        assertTrue(start >= 0 && end > start)
        val dialog = hub.substring(start, end)
        assertTrue(dialog.contains("scanCatWarpOnThisLine()"))
        assertFalse(dialog.contains("entry("))
        val planner = src("CatWarpFree.kt")
        assertFalse(planner.contains("WarpScout") || planner.contains("warpscout"))
        assertEquals(4, CatWarpFreePlanner.PROTOS.size)
        val guides = src("MainActivityGuides.kt")
        assertTrue(guides.contains("scanCatWarpOnThisLine") || guides.contains("showCatWarpFreeDialog()"))
        assertFalse(guides.contains("WarpScout") || guides.contains("warpscout"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

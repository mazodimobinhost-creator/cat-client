package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * beta265 — YouTube methods that actually work on Iran: Cloudflare tunnel (Cat Warp picker
 * with live ping) and Cat Wind WireGuard on listed :443. No registry wipe, no extra-account
 * farming, no skip-cert-verify / python.org SNI, no copy of other apps.
 */
class Beta265IranMethodsTest {

    @Test
    fun warpPickerKeepsListedPortsAndCloudflareHosts() {
        val byId = CatWarpFreePlanner.PROTOS.associateBy { it.id }
        assertEquals(4, CatWarpFreePlanner.PROTOS.size)
        assertEquals("162.159.192.1" to 2408, byId.getValue("wg4").host to byId.getValue("wg4").port)
        assertEquals("2606:4700:d0::a29f:c001" to 2408, byId.getValue("wg6").host to byId.getValue("wg6").port)
        assertEquals("162.159.198.1" to 443, byId.getValue("h2").host to byId.getValue("h2").port)
        assertEquals("162.159.198.1" to 443, byId.getValue("mim").host to byId.getValue("mim").port)
        assertEquals("162.159.192.1:2408", byId.getValue("wg4").endpoint)
        assertEquals("[2606:4700:d0::a29f:c001]:2408", byId.getValue("wg6").endpoint)
        assertEquals(AetherMode.WireGuard, byId.getValue("wg4").mode)
        assertEquals(AetherMode.MasqueHttp2, byId.getValue("h2").mode)
        assertEquals(AetherMode.MasqueInMasque, byId.getValue("mim").mode)
        assertTrue(CatWarpFreePlanner.PROTOS.all { it.port in 1..65535 })
        assertFalse(CatWarpFreePlanner.PROTOS.any { it.port == 80 || it.port == 1080 })
    }

    @Test
    fun windParsesIp3AndBuildsWireGuardOnListed443() {
        val json = """{"data":[
            {"name":"Netherlands","country_code":"NL","premium_only":0,"groups":[
                {"city":"Amsterdam","wg_pubkey":"$PEER",
                 "nodes":[{"hostname":"nl.windscribe.com","ip":"1.2.3.4","ip3":"5.6.7.8","wg_pubkey":"$PEER"}]}
            ]}
        ]}"""
        val loc = CatWindPlanner.locations(json).single()
        assertEquals("5.6.7.8", loc.ip3)
        assertEquals(PEER, loc.wgPubKey)
        assertTrue(loc.canWireGuard())
        assertEquals("5.6.7.8", loc.pingHostFor(CatWindPlanner.Proto.WireGuard))
        assertEquals("1.2.3.4", loc.pingHostFor(CatWindPlanner.Proto.Stealth))
        assertEquals(443, CatWindPlanner.PORT)
        assertEquals(443, CatWindPlanner.WG_PORT)

        val init = CatWindPlanner.wgInit("""{"data":{"config":{"PresharedKey":"$PSK","AllowedIPs":"0.0.0.0/0"}}}""")
        assertEquals(PSK to "0.0.0.0/0", init)
        assertNull(CatWindPlanner.wgInit("""{"errorCode":701,"errorMessage":"bad"}"""))
        val assigned = CatWindPlanner.wgAddress("""{"data":{"config":{"Address":"100.64.0.2/32","DNS":"10.255.255.3"}}}""")
        assertEquals("100.64.0.2/32" to "10.255.255.3", assigned)
        assertEquals(
            "100.80.1.2/32" to "10.255.255.3",
            CatWindPlanner.wgAddress("""{"data":{"config":"100.80.1.2/32"}}"""),
        )
        assertNull(CatWindPlanner.wgAddress("""{"errorCode":1}"""))

        val (priv, _) = CatWindPlanner.keyPair("", "")
        val link = CatWindPlanner.wireguardLink(priv, loc, "100.64.0.2/32", "10.255.255.3", PSK, "0.0.0.0/0", fa = false)
        assertNotNull(link)
        assertTrue(link!!, link.startsWith("wireguard://"))
        assertEquals("5.6.7.8" to 443, LiveNetPing.hostPortFromShare(link))
        assertFalse(link.contains("51820"))
        assertFalse(link.contains("insecure"))
        assertFalse(link.contains("python.org"))
        val stealth = CatWindPlanner.httpsLink("u", "p", loc, fa = false)
        assertTrue(stealth.contains("sni=nl.windscribe.com"))
        assertFalse(stealth.contains("python.org"))
        assertFalse(stealth.contains("insecure"))
    }

    @Test
    fun hubAndFinderWireWarpPickerAndWindProtocolNoFarming() {
        val hub = src("MainActivityFreeNetworks.kt")
        assertTrue(hub.contains("showCatWarpFreeDialog()"))
        assertTrue(hub.contains("Cat Warp — تونل کلودفلر با پینگ زنده"))
        assertTrue(hub.contains("connectCatWarpFree"))
        assertTrue(hub.contains("LiveNetPing.tcpMs(p.host, p.port)"))
        assertTrue(hub.contains("pickWindProtocol"))
        assertTrue(hub.contains("CatWindPlanner.Proto.WireGuard"))
        assertTrue(hub.contains("client.wgLink"))
        assertTrue(hub.contains("CatWindPlanner.WG_PORT") || hub.contains("UDP روی ۴۴۳"))
        assertFalse(hub.contains("HKEY_"))
        assertFalse(hub.contains("reg delete"))
        assertFalse(hub.contains("atomic.mail"))
        assertFalse(hub.contains("RIX-AETHER") || hub.contains("rix-reza"))
        val engine = src("CatWindEngine.kt")
        assertTrue(engine.contains("WgConfigs/init"))
        assertTrue(engine.contains("WgConfigs/connect"))
        assertTrue(engine.contains("skip-cert-verify"))
        assertFalse(engine.contains("skip-cert-verify\", true"))
        assertTrue(engine.contains("independent Kotlin, no copy"))
        assertFalse(engine.contains("Bearer"))
        val finder = src("MainActivityGuides.kt")
        assertTrue(finder.contains("showCatWarpFreeDialog()"))
        assertTrue(finder.contains("showCatWindDialog()"))
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 337"))
        assertTrue(gradle.contains("1.10.0-beta270"))
    }

    companion object {
        private val PEER: String
        private val PSK: String
        init {
            val b64 = Base64.getEncoder()
            val peer = ByteArray(32) { (it + 3).toByte() }
            val psk = ByteArray(32) { (it + 9).toByte() }
            WarpRegistration.clamp(peer)
            WarpRegistration.clamp(psk)
            PEER = b64.encodeToString(peer)
            PSK = b64.encodeToString(psk)
        }
        private fun src(name: String): String = listOf(
            File("src/main/java/com/cat/client/$name"),
            File("app/src/main/java/com/cat/client/$name"),
        ).first { it.isFile }.readText()
    }
}

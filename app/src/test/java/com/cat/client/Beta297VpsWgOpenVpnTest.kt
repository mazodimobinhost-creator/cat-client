package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta297VpsWgOpenVpnTest {
    private fun src(name: String): String =
        listOf("src/main/java/com/cat/client/$name", "app/src/main/java/com/cat/client/$name")
            .map(::File).first { it.isFile }.readText()

    @Test
    fun versionIsBeta297() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 364"))
        assertTrue(gradle.contains("1.10.0-beta297"))
    }

    @Test
    fun relayAndXrayForwardUdpNotJustTcp() {
        val hops = CatVpsRelay.parseHops("", "203.0.113.8", 443)
        val nat = CatVpsRelay.script("10.10.10.10", hops)!!
        assertTrue(nat, nat.contains("-p tcp --dport 443 -j DNAT --to-destination 203.0.113.8:443"))
        assertTrue(nat, nat.contains("-p udp --dport 443 -j DNAT --to-destination 203.0.113.8:443"))
        val json = CatVpsRelay.xrayConfig(hops)!!
        assertTrue(json, json.contains("\"network\":\"tcp,udp\""))
    }

    @Test
    fun wireGuardScriptIsDistroWgNotPanelAndCatCanImportShape() {
        assertNull(CatVpsTunnels.wireGuardBundle("", "127.0.0.1"))
        assertNull(CatVpsTunnels.wireGuardBundle("10.10.10.10", "203.0.113.8", 443))
        val wg = CatVpsTunnels.wireGuardBundle("10.10.10.10", "203.0.113.8")
        assertNotNull(wg)
        assertTrue(wg!!, wg.contains("wg genkey"))
        assertTrue(wg, wg.contains("[Interface]"))
        assertTrue(wg, wg.contains("[Peer]"))
        assertTrue(wg, wg.contains("Endpoint = 10.10.10.10:51820"))
        assertTrue(wg, wg.contains("ListenPort = 51820"))
        assertTrue(wg, wg.contains("/etc/wireguard/wg0.conf"))
        assertTrue(wg, wg.contains("echo 'apt-get update && apt-get install -y wireguard'"))
        assertFalse(wg.lineSequence().any { it.trim().startsWith("apt-get") || it.trim().startsWith("curl ") })
        assertFalse(wg, wg.contains("wg-easy", ignoreCase = true) || wg.contains("3x-ui", ignoreCase = true))
        assertTrue(wg, wg.contains("-p udp --dport 51820"))
        val local = CatVpsTunnels.wireGuardBundle("", "203.0.113.8")!!
        assertTrue(local, local.contains("Endpoint = 203.0.113.8:51820"))
        assertFalse(local, local.contains("IRAN VPS"))
    }

    @Test
    fun openVpnIsTcpOnVpsAndSaysCatCannotConnect() {
        assertNull(CatVpsTunnels.openVpnTcpBundle("", "127.0.0.1"))
        assertNull(CatVpsTunnels.openVpnTcpBundle("1.1.1.1", "203.0.113.8", 80))
        val ov = CatVpsTunnels.openVpnTcpBundle("10.10.10.10", "203.0.113.8")
        assertNotNull(ov)
        assertTrue(ov!!, ov.contains("proto tcp-server"))
        assertTrue(ov, ov.contains("proto tcp-client"))
        assertTrue(ov, ov.contains("remote 10.10.10.10 1194"))
        assertTrue(ov, ov.contains("Cat cannot import") || ov.contains("use WireGuard in Cat"))
        assertTrue(ov, ov.contains("echo 'apt-get update && apt-get install -y openvpn'"))
        assertFalse(ov.lineSequence().any { it.trim().startsWith("apt-get") })
        assertFalse(ov, ov.contains("3x-ui", ignoreCase = true) || ov.contains("openvpn-panel", ignoreCase = true))
    }

    @Test
    fun workshopHasWgAndOpenVpnOnVpsNotWorker() {
        val guides = src("MainActivityGuides.kt")
        assertTrue(guides.contains("CatVpsTunnels.wireGuardBundle"))
        assertTrue(guides.contains("CatVpsTunnels.openVpnTcpBundle"))
        assertFalse(guides.contains("freeb5d"))
        val tunnels = src("CatVpsTunnels.kt")
        assertFalse(tunnels.contains("catclient.worker.js"))
        assertTrue(tunnels.contains("Cat has no OpenVPN engine"))
    }
}

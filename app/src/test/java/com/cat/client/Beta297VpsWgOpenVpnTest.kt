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
        assertTrue(gradle.contains("versionCode = 368"))
        assertTrue(gradle.contains("1.10.0-beta301"))
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
        assertTrue(wg, wg.contains("apt-get install -y wireguard"))
        assertTrue(wg, wg.contains("wg-quick up wg0"))
        assertTrue(wg, wg.contains("/root/cat-wg-phone.conf"))
        assertFalse(wg, wg.contains("Then run this script again"))
        assertFalse(wg, wg.contains("wg-easy", ignoreCase = true) || wg.contains("3x-ui", ignoreCase = true))
        assertTrue(wg, wg.contains("-p udp --dport 51820"))
        assertTrue(wg, wg.contains("cat > /root/cat-iran-dnat.sh"))
        val local = CatVpsTunnels.wireGuardBundle("", "203.0.113.8")!!
        assertTrue(local, local.contains("Endpoint = 203.0.113.8:51820"))
        assertFalse(local, local.contains("cat-iran-dnat.sh"))
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
        assertTrue(ov, ov.contains("apt-get install -y openvpn"))
        assertTrue(ov, ov.contains("systemctl enable --now cat-openvpn"))
        assertFalse(ov, ov.contains("Then run this script again"))
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

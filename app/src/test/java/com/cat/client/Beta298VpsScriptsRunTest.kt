package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta298VpsScriptsRunTest {
    @Test
    fun versionIsBeta298() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 368"))
        assertTrue(gradle.contains("1.10.0-beta301"))
    }

    @Test
    fun scriptsInstallPersistAndStart() {
        val hops = CatVpsRelay.parseHops("", "203.0.113.8", 443)
        val nat = CatVpsRelay.script("10.10.10.10", hops)!!
        assertTrue(nat, nat.contains("apt-get install -y iptables"))
        assertTrue(nat, nat.contains("/etc/sysctl.d/99-cat-vps.conf"))
        assertTrue(nat, nat.contains("iptables-save > /etc/iptables/rules.v4"))
        assertTrue(nat, nat.contains("cat-iptables.service"))
        assertFalse(nat, nat.contains("Then run this script again"))
        val xray = CatVpsRelay.xrayScript("10.10.10.10", hops)!!
        assertTrue(xray, xray.contains("apt-get install -y curl"))
        assertTrue(xray.lineSequence().any { "bash -c" in it && "Xray-install" in it && !it.trim().startsWith("echo") })
        val wg = CatVpsTunnels.wireGuardBundle("10.10.10.10", "203.0.113.8")!!
        assertTrue(wg, wg.contains("apt-get install -y wireguard"))
        assertTrue(wg, wg.contains("wg-quick up wg0"))
        assertTrue(wg, wg.contains("run as root"))
        val ov = CatVpsTunnels.openVpnTcpBundle("10.10.10.10", "203.0.113.8")!!
        assertTrue(ov, ov.contains("ExecStart=/usr/sbin/openvpn"))
        assertTrue(ov, ov.contains("enable --now cat-openvpn"))
    }
}

package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Beta210WarpAutoKeyTest {
    private fun src(name: String): String =
        if (name == "MainActivity.kt") MainActivitySources.combined()
        else listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    private val peer = WarpRegistration.WARP_PEER_PUBLIC_KEY
    private val account = WarpRegistration.Account(
        privateKey = "OURKEYOURKEYOURKEYOURKEYOURKEYOURKEYOURKE0=",
        publicKey = "PUB=",
        peerPublicKey = peer,
        ipv4 = "172.16.0.2",
        ipv6 = "2606:4700:110:8a36::1",
        reserved = listOf(11, 22, 33),
        accountId = "acc",
    )

    private val yaml = listOf(
        "mixed-port: 7890",
        "proxies:",
        "  - name: 'Feed WARP'",
        "    type: 'wireguard'",
        "    server: '162.159.192.1'",
        "    port: 2408",
        "    private-key: 'SOMEONEELSE='",
        "    public-key: '$peer'",
        "    ip: '172.16.0.9'",
        "    reserved: [9, 9, 9]",
        "    mtu: 1280",
        "    amnezia-wg-option:",
        "      jc: 5",
        "      ip: 'nested-must-stay'",
        "  - name: 'Own WG'",
        "    type: wireguard",
        "    server: 5.6.7.8",
        "    port: 51820",
        "    private-key: 'MINE='",
        "    public-key: 'OTHERSERVER='",
        "    ip: 10.0.0.2",
        "  - {name: 'Flow WARP', type: wireguard, server: 1.2.3.4, port: 2408, private-key: OLD, public-key: '$peer', ip: 172.16.0.5, reserved: [7, 7, 7], udp: true}",
        "  - name: 'Placeholder'",
        "    type: wireguard",
        "    private-key: '${WarpRegistration.PLACEHOLDER_PRIVATE_KEY}'",
        "    public-key: '$peer'",
        "    reserved:",
        "      - 1",
        "      - 2",
        "      - 3",
        "proxy-groups:",
        "  - name: G",
        "    proxies: ['Feed WARP', 'Own WG']",
    ).joinToString("\n")

    @Test
    fun detectsWarpPeers() {
        assertTrue(WarpRegistration.containsWarpPeer(yaml))
        assertFalse(WarpRegistration.containsWarpPeer("proxies:\n  - name: x\n    type: wireguard\n    public-key: 'X='"))
    }

    @Test
    fun blockStyleWarpRowGetsOurKeyAddressAndReserved() {
        val out = WarpRegistration.adoptWarpPeers(yaml, account)
        val feed = out.substringAfter("- name: 'Feed WARP'").substringBefore("- name: 'Own WG'")
        assertTrue(feed, feed.contains("    private-key: '${account.privateKey}'"))
        assertTrue(feed, feed.contains("    ip: '172.16.0.2'"))
        assertTrue(feed, feed.contains("    reserved: [11, 22, 33]"))
        assertTrue(feed, feed.contains("    ipv6: '2606:4700:110:8a36::1'"))
        assertTrue(feed, feed.contains("    server: '162.159.192.1'"))
        assertTrue(feed, feed.contains("    mtu: 1280"))
        assertTrue(feed, feed.contains("      ip: 'nested-must-stay'"))
        assertFalse(feed, feed.contains("SOMEONEELSE"))
        assertFalse(feed, feed.contains("[9, 9, 9]"))
    }

    @Test
    fun nonWarpWireGuardIsUntouched() {
        val out = WarpRegistration.adoptWarpPeers(yaml, account)
        val own = out.substringAfter("- name: 'Own WG'").substringBefore("- {name")
        assertTrue(own.contains("private-key: 'MINE='"))
        assertTrue(own.contains("ip: 10.0.0.2"))
        assertTrue(out.contains("proxy-groups:\n  - name: G\n    proxies: ['Feed WARP', 'Own WG']"))
        assertTrue(out.startsWith("mixed-port: 7890\nproxies:\n"))
    }

    @Test
    fun flowStyleWarpRowIsRewrittenInPlace() {
        val out = WarpRegistration.adoptWarpPeers(yaml, account)
        val flow = out.lines().first { it.contains("Flow WARP") }
        assertTrue(flow, flow.startsWith("  - {name: 'Flow WARP'"))
        assertTrue(flow, flow.endsWith("}"))
        assertTrue(flow, flow.contains("private-key: '${account.privateKey}'"))
        assertTrue(flow, flow.contains("ip: '172.16.0.2'"))
        assertTrue(flow, flow.contains("reserved: [11, 22, 33]"))
        assertTrue(flow, flow.contains("ipv6: '2606:4700:110:8a36::1'"))
        assertTrue(flow, flow.contains("udp: true"))
        assertFalse(flow, flow.contains("OLD,"))
        assertFalse(flow, flow.contains("[7, 7, 7]"))
    }

    @Test
    fun placeholderRowWithBlockListReservedIsReplaced() {
        val out = WarpRegistration.adoptWarpPeers(yaml, account)
        val ph = out.substringAfter("- name: 'Placeholder'").substringBefore("proxy-groups:")
        assertFalse(ph, ph.contains(WarpRegistration.PLACEHOLDER_PRIVATE_KEY))
        assertTrue(ph, ph.contains("reserved: [11, 22, 33]"))
        assertFalse(ph, ph.contains("      - 1"))
        assertTrue(ph, ph.contains("    ip: '172.16.0.2'"))
    }

    @Test
    fun accountWithoutReservedDropsForeignReserved() {
        val out = WarpRegistration.adoptWarpPeers(yaml, account.copy(reserved = null, ipv6 = null))
        val feed = out.substringAfter("- name: 'Feed WARP'").substringBefore("- name: 'Own WG'")
        assertFalse(feed, feed.contains("reserved"))
        assertFalse(feed, feed.contains("ipv6"))
        val flow = out.lines().first { it.contains("Flow WARP") }
        assertFalse(flow, flow.contains("reserved"))
        assertTrue(flow, flow.contains("udp: true}"))
    }

    @Test
    fun yamlWithoutWarpIsReturnedUnchanged() {
        val plain = "proxies:\n  - name: a\n    type: vless\n"
        assertEquals(plain, WarpRegistration.adoptWarpPeers(plain, account))
    }

    @Test
    fun connectAndAppWiring() {
        val svc = VpnServiceSources.combined()
        assertTrue(svc.contains("WarpRegistration.adoptsWarpConfigs(applicationContext)"))
        assertTrue(svc.contains("WarpRegistration.adoptWarpPeers(yaml, account)"))
        assertTrue(svc.contains("WarpRegistration.ensureAccountAutomatic(applicationContext)"))
        val main = src("MainActivity.kt")
        assertTrue(main.contains("runCatching { prewarmWarpKeyInBackground() }"))
        assertTrue(main.contains("WarpRegistration.setAdoptsWarpConfigs(this, false)"))
        val reg = src("WarpRegistration.kt")
        assertTrue(reg.contains("getBoolean(KEY_ADOPT, true)"))
        assertTrue(reg.contains("AUTO_RETRY_AFTER_MS = 10L * 60 * 1000"))
    }
}

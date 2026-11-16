package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** beta260: when adding a country/server, show a real TCP ping on this network — never a made-up delay. */
class Beta260LivePingTest {

    @Test
    fun shareParserKeepsTheListedPortAndNeverGuesses() {
        assertEquals(
            "s1.example.com" to 11111,
            LiveNetPing.hostPortFromShare("https://u:p@s1.example.com:11111?sni=s1.example.com#🆓"),
        )
        assertEquals(
            "184.75.221.82" to 3118,
            LiveNetPing.hostPortFromShare("https://u:p@184.75.221.82:3118#x"),
        )
        assertEquals(
            "103.237.102.191" to 11111,
            LiveNetPing.hostPortFromShare("socks5://u:p@103.237.102.191:11111"),
        )
        assertEquals(
            "2001:db8::1" to 8443,
            LiveNetPing.hostPortFromShare("https://u:p@[2001:db8::1]:8443"),
        )
        assertEquals(
            "1.2.3.4" to 51820,
            LiveNetPing.hostPortFromShare("wireguard://k@1.2.3.4:51820?publickey=p#n"),
        )
        assertNull(LiveNetPing.hostPortFromShare("https://u:p@s1.example.com"))
        assertNull(LiveNetPing.hostPortFromShare("not-a-link"))
        assertTrue(LiveNetPing.isLoopback("127.0.0.1"))
        assertTrue(LiveNetPing.isLoopback("::1"))
        assertFalse(LiveNetPing.isLoopback("s1.example.com"))
        assertEquals("🟢 87 ms روی خط شما", LiveNetPing.labelFa(87))
        assertEquals("⚪ closed on this line", LiveNetPing.labelEn(null))
        val icmpOnly = SharePing("awg://auto@8.6.112.208:2408", "8.6.112.208", 2408, rttMs = null, hostMs = 127)
        assertTrue(LiveNetPing.labelShareFa(icmpOnly).contains("127"))
        assertTrue(LiveNetPing.labelShareFa(icmpOnly).contains("UDP"))
        assertTrue(LiveNetPing.labelShareFa(icmpOnly).startsWith("🟡"))
        val tunnel = SharePing("awg://auto@8.6.112.208:2408", "8.6.112.208", 2408, rttMs = 45, hostMs = 127)
        assertTrue(LiveNetPing.labelShareFa(tunnel).startsWith("🟢"))
        assertTrue(LiveNetPing.isUdpShare("awg://auto@8.6.112.208:2408?publickey=x#n"))
        assertTrue(LiveNetPing.isUdpShare("wireguard://auto@162.159.192.1:2408?publickey=x"))
        assertTrue(LiveNetPing.isUdpShare("hysteria2://pw@203.0.113.9:443?sni=x"))
        assertFalse(LiveNetPing.isUdpShare("vmess://abc"))
        assertFalse(LiveNetPing.isUdpShare("https://u:p@s1.example.com:443"))
        assertEquals(
            "8.6.112.208" to 2408,
            LiveNetPing.hostPortFromShare("awg://auto@8.6.112.208:2408?publickey=x#Amnezia"),
        )
        val ping = src("LiveNetPing.kt")
        assertTrue(ping.contains("udpReplyMs"))
        assertTrue(ping.contains("isUdpShare(link)"))
        assertTrue(ping.contains("onEach"))
        assertTrue(ping.contains("if (udp) return udpReplyMs(host, port, timeoutMs)"))
        assertFalse(ping.contains("udpReplyMs(host, port, timeoutMs) ?: icmpMs"))
        assertTrue(ping.contains("labelShareFa"))
        assertTrue(ping.contains("icmpMs"))
        val hub = src("MainActivityFreeNetworks.kt")
        assertTrue(hub.contains("LiveNetPing.rankShares(links) { hit, i, n ->"))
        assertTrue(hub.contains("labelShareFa"))
    }

    @Test
    fun foxPickerPingsLiveAndFreeHubShowsMsWhenAdding() {
        val fox = src("MainActivityFox.kt")
        assertTrue(fox.contains("showFirefoxCountryPickerDialog"))
        assertTrue(fox.contains("FirefoxVpnEngine.pingCountries"))
        assertTrue(fox.contains("LiveNetPing.waitingFa"))
        assertTrue(fox.contains("پینگ زنده روی خط شما"))
        assertTrue(fox.contains("live ping on your line"))
        val engine = src("FirefoxVpnEngine.kt")
        assertTrue(engine.contains("onEach: ((CountryPingResult) -> Unit)?"))
        assertTrue(engine.contains("probeCountryLatency"))
        assertTrue(engine.contains("rememberCountryPing"))
        assertTrue(engine.contains("never a made-up delay"))
        val hub = src("MainActivityFreeNetworks.kt")
        assertTrue(hub.contains("کشورها با پینگ روی خط شما"))
        assertTrue(hub.contains("rankLinksByLivePing"))
        assertTrue(hub.contains("LiveNetPing.rankShares"))
        assertTrue(hub.contains("showFirefoxCountryPickerDialog()"))
        assertTrue(hub.contains("showCatWindDialog()"))
        assertTrue(hub.contains("client.ping("))
        assertFalse("no fake paid VPN", hub.contains("Privado") || hub.contains("TunnelBear") || hub.contains("NordVPN"))
        val proxy = src("MainActivityFreeNetworks.kt")
        assertTrue(proxy.contains("rankLinksByLivePing(result.links"))
        val sstp = src("MainActivityAmneziaSstp.kt")
        assertTrue(sstp.contains("LiveNetPing.tcpMs(target, s.port)"))
        assertTrue(sstp.contains("s.ip.ifBlank { s.host }"))
    }

    @Test
    fun versionIsBeta263() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 390"))
        assertTrue(gradle.contains("1.10.0-beta323"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

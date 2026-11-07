package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta254: Fanoos talks to the service the way the official client does (timezone + the phone's
 * public IP, looked up off the VPN). Proton is the service's own free plan (account created on
 * the official page, like Fox) — Plus is not faked. Amnezia has no free cloud account.
 */
class Beta254FreeVpnsTest {

    @Test
    fun publicIpParserAcceptsTraceAndRejectsPrivate() {
        val trace = "fl=123\nip=5.127.10.20\nloc=IR\ncolo=THR\n"
        assertEquals("5.127.10.20", LanternPlanner.parsePublicIp(trace))
        assertEquals("8.8.8.8", LanternPlanner.parsePublicIp("8.8.8.8\n"))
        assertNull(LanternPlanner.parsePublicIp("ip=10.0.0.1\n"))
        assertNull(LanternPlanner.parsePublicIp("ip=192.168.1.1\n"))
        assertNull(LanternPlanner.parsePublicIp("ip=127.0.0.1\n"))
        assertNull(LanternPlanner.parsePublicIp("ip=172.16.5.1\n"))
        assertTrue(LanternPlanner.isPublicIp("2a06:98c1:3101::1"))
        assertFalse(LanternPlanner.isPublicIp("::1"))
        assertFalse(LanternPlanner.isPublicIp("not-an-ip"))
    }

    @Test
    fun iranTimeZoneAndClientIpHeaderMatchTheOfficialClient() {
        assertTrue(LanternPlanner.looksLikeIranTimeZone("Asia/Tehran"))
        assertTrue(LanternPlanner.looksLikeIranTimeZone("Iran"))
        assertFalse(LanternPlanner.looksLikeIranTimeZone("Europe/Berlin"))
        val withIp = LanternPlanner.headers("dev", "1", "tok", clientIp = "5.127.10.20")
        assertEquals("5.127.10.20", withIp["X-Lantern-Config-Client-IP"])
        assertTrue(withIp["X-Lantern-Time-Zone"]!!.isNotBlank())
        val privateIp = LanternPlanner.headers("dev", "1", "tok", clientIp = "10.0.0.9")
        assertFalse(privateIp.containsKey("X-Lantern-Config-Client-IP"))
        val none = LanternPlanner.headers("dev", "1", "tok")
        assertFalse(none.containsKey("X-Lantern-Config-Client-IP"))
    }

    @Test
    fun protonFreePlanListsThePublishedCountriesAndIgnoresPlusServers() {
        assertEquals(8, ProtonFreePlanner.FREE_COUNTRIES.size)
        assertEquals(setOf("NL", "JP", "US", "CA", "RO", "PL", "NO", "SG"), ProtonFreePlanner.FREE_COUNTRIES.map { it.code }.toSet())
        val json = """{"LogicalServers":[
            {"Name":"NL-FREE#1","EntryCountry":"NL","ExitCountry":"NL","City":"Amsterdam","Tier":0,"Status":1,"Load":11,
             "Servers":[{"EntryIP":"1.2.3.4","Status":1,"X25519":"abcd="}]},
            {"Name":"DE#10","EntryCountry":"DE","ExitCountry":"DE","City":"Frankfurt","Tier":2,"Status":1,"Load":5,
             "Servers":[{"EntryIP":"5.6.7.8","Status":1,"X25519":"efgh="}]},
            {"Name":"JP-FREE#2","EntryCountry":"JP","ExitCountry":"JP","City":"Tokyo","Tier":0,"Status":0,
             "Servers":[{"EntryIP":"9.9.9.9","Status":1,"X25519":"zzzz="}]}
        ]}"""
        val all = ProtonFreePlanner.servers(json)
        assertEquals(listOf("NL", "DE"), all.map { it.country })
        val free = ProtonFreePlanner.freeServers(all)
        assertEquals(listOf("NL"), free.map { it.country })
        assertEquals(0, free.single().tier)
        assertEquals("NL", ProtonFreePlanner.countryOfConf("[Interface]\n# NL-FREE#1\nPrivateKey=x", "NL-FREE#1.conf"))
        assertEquals("JP", ProtonFreePlanner.countryOfConf("PrivateKey=x\n# Japan", "wireguard.conf"))
        assertTrue(ProtonFreePlanner.looksLikeConf("[Interface]\nPrivateKey = a\n[Peer]\nPublicKey = b\nEndpoint = 1.2.3.4:51820"))
        assertFalse(ProtonFreePlanner.looksLikeConf("vless://x"))
        val sub = ProtonFreePlanner.subscriptionText(listOf("wireguard://k@1.2.3.4:51820?publickey=p#n"))
        assertTrue(sub.startsWith("#"))
        assertFalse(sub.startsWith("http", ignoreCase = true))
        assertEquals(0, ProtonFreePlanner.maxTier("""{"VPN":{"MaxTier":0}}"""))
        assertEquals(2, ProtonFreePlanner.maxTier("""{"VPN":{"MaxTier":2}}"""))
    }

    @Test
    fun screenSendsFanoosOffTheVpnAndWiresProtonAndAmneziaWithoutFakingPlus() {
        val f = listOf(
            File("src/main/java/com/cat/client/MainActivityFreeNetworks.kt"),
            File("app/src/main/java/com/cat/client/MainActivityFreeNetworks.kt"),
        ).first { it.isFile }
        val src = f.readText()
        assertTrue(src.contains("withPhysicalNet"))
        assertTrue(src.contains("FreeNetHttp.openOnPhysical"))
        assertTrue(src.contains("looksLikeIranTimeZone"))
        assertTrue(src.contains("showProtonDialog()"))
        assertTrue(src.contains("showAmneziaAndProtonWgHubDialog()"))
        assertTrue(src.contains("ProtonFreePlanner.SIGNUP"))
        assertTrue(src.contains("WireGuardConfText.toUris"))
        assertFalse("Plus is not faked", src.contains("MaxTier\":2") || src.contains("fakePlus") || src.contains("pro_token\", \"plus"))
        val engine = listOf(
            File("src/main/java/com/cat/client/FreeNetworkEngines.kt"),
            File("app/src/main/java/com/cat/client/FreeNetworkEngines.kt"),
        ).first { it.isFile }.readText()
        assertTrue(engine.contains("X-Lantern-Config-Client-IP"))
        assertTrue(engine.contains("openOnPhysical"))
    }
}

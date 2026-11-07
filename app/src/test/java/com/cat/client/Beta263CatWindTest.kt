package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * beta263 🌬️ Cat Wind — public city list, live TCP ping on the listed :443, user's own free
 * account, ordinary https:// CONNECT rows. Premium cities are not unlocked. Ports are never rewritten.
 */
class Beta263CatWindTest {

    @Test
    fun sessionReadsBothHashSpellingsAndRejectsEmpty() {
        val a = CatWindPlanner.session("""{"data":{"session_auth_hash":"h1","username":"u","is_premium":0,"status":1}}""")
        assertEquals("h1", a!!.auth)
        assertFalse(a.premium)
        assertTrue(a.usable)
        val b = CatWindPlanner.session("""{"data":{"auth_hash":"h2","username":"u","is_premium":1,"status":1,"proxy_username":"px","proxy_password":"pw"}}""")
        assertEquals("h2", b!!.auth)
        assertTrue(b.premium)
        assertEquals("px", b.proxyUser)
        assertNull(CatWindPlanner.session("""{"data":{}}"""))
        assertFalse(CatWindPlanner.session("""{"data":{"session_auth_hash":"x","status":3}}""")!!.usable)
        assertTrue(CatWindPlanner.sessionError("""{"errorMessage":"bad pass"}""").contains("bad pass"))
    }

    @Test
    fun serverCredentialsDecodeBase64AndKeepPlain() {
        val u = Base64.getEncoder().encodeToString("wind-user".toByteArray())
        val p = Base64.getEncoder().encodeToString("wind-pass".toByteArray())
        assertEquals("wind-user" to "wind-pass", CatWindPlanner.serverCredentials("""{"data":{"username":"$u","password":"$p"}}"""))
        assertEquals("plain" to "text", CatWindPlanner.serverCredentials("""{"data":{"username":"plain","password":"text"}}"""))
        assertNull(CatWindPlanner.serverCredentials("""{"data":{}}"""))
    }

    @Test
    fun cityListKeepsFreeCitiesDropsPremiumAndNeverRewritesPort() {
        val json = """{"data":[
            {"name":"Netherlands","country_code":"NL","premium_only":0,"groups":[
                {"city":"Amsterdam","nodes":[{"hostname":"nl.windscribe.com","ip":"1.2.3.4"}]}
            ]},
            {"name":"Japan","country_code":"JP","premium_only":1,"groups":[
                {"city":"Tokyo","nodes":[{"hostname":"jp.windscribe.com","ip":"9.9.9.9"}]}
            ]},
            {"name":"US Central","groups":[
                {"city":"Dallas","nodes":[{"hostname":"us-central.windscribe.com","ip":"5.6.7.8"}]}
            ]}
        ]}"""
        val all = CatWindPlanner.locations(json)
        assertEquals(listOf("NL", "JP", "US"), all.map { it.country })
        assertEquals("nl.windscribe.com", all[0].hostname)
        assertEquals("1.2.3.4", all[0].pingHost)
        assertEquals(443, CatWindPlanner.PORT)
        val free = CatWindPlanner.freeLocations(all, premium = false)
        assertEquals(listOf("NL", "US"), free.map { it.country })
        assertEquals(listOf("NL"), CatWindPlanner.pick(all, setOf("NL"), premium = false).map { it.country })
        assertEquals("NL", CatWindPlanner.countryOf("", "Netherlands"))
        assertEquals("US", CatWindPlanner.countryOf("", "US West"))
        assertEquals("GB", CatWindPlanner.countryOf("", "United Kingdom"))
        val loc = free.first { it.country == "NL" }
        val link = CatWindPlanner.httpsLink("u 1", "p@ss:/1", loc, fa = false)
        assertTrue(link, link.startsWith("https://u%201:p%40ss%3A%2F1@nl.windscribe.com:443?sni=nl.windscribe.com#"))
        assertTrue(link.contains("Cat%20Wind"))
        assertFalse(link.contains("insecure"))
        assertEquals("nl.windscribe.com" to 443, LiveNetPing.hostPortFromShare(link))
        val sub = CatWindPlanner.subscriptionText(listOf(link))
        assertTrue(sub.startsWith("#"))
        assertFalse(sub.startsWith("http", ignoreCase = true))
        assertEquals(12, CatWindPlanner.FALLBACK.size)
        assertTrue(CatWindPlanner.FALLBACK.all { it.hostname.endsWith(".windscribe.com") && !it.premiumOnly })
    }

    @Test
    fun hubPickerAndFinderWireLivePingAndOwnAccountNoFakePaid() {
        val hub = src("MainActivityFreeNetworks.kt")
        assertTrue(hub.contains("showCatWindDialog()"))
        assertTrue(hub.contains("Cat Wind — کشورها با پینگ روی خط شما"))
        assertTrue(hub.contains("LiveNetPing.waitingFa"))
        assertTrue(hub.contains("LiveNetPing.labelFa"))
        assertTrue(hub.contains("client.ping(loc)"))
        assertTrue(hub.contains("CatWindPlanner.PORT"))
        assertTrue(hub.contains("rankLinksByLivePing"))
        assertTrue(hub.contains("connectCatWind"))
        assertTrue(hub.contains("CatWindPlanner.SIGNUP"))
        assertFalse("no fake paid VPN", hub.contains("Privado") || hub.contains("TunnelBear") || hub.contains("NordVPN"))
        assertFalse("Plus is not faked", hub.contains("fakePlus") || hub.contains("is_premium\\\":1, \\\"unlock"))
        val engine = src("CatWindEngine.kt")
        assertTrue(engine.contains("never rewritten"))
        assertTrue(engine.contains("assets.windscribe.com/serverlist/mob-v2/0/"))
        assertTrue(engine.contains("https://api.windscribe.com/Session"))
        assertTrue(engine.contains("independent Kotlin, no copy"))
        assertTrue(engine.contains("put(\"Authorization\", auth)"))
        assertFalse(engine.contains("Bearer"))
        val finder = src("MainActivityGuides.kt")
        assertTrue(finder.contains("showCatWindDialog()"))
        assertTrue(finder.contains("ویندسکرایب"))
        val brand = src("CatBranding.kt")
        assertTrue(brand.contains("Windscribe\" to \"Cat Wind\""))
    }

    @Test
    fun versionIsBeta263() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 339"))
        assertTrue(gradle.contains("1.10.0-beta272"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

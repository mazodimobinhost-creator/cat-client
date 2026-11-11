package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta295VpsRelayAndUsageTest {
    private fun src(name: String): String =
        listOf("src/main/java/com/cat/client/$name", "app/src/main/java/com/cat/client/$name")
            .map(::File).first { it.isFile }.readText()

    @Test
    fun versionIsBeta295() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 367"))
        assertTrue(gradle.contains("1.10.0-beta300"))
    }

    @Test
    fun iranForeignRelayScriptIsIptablesNot3xui() {
        val hops = CatVpsRelay.parseHops("de 9.9.9.9\nau 1.2.3.4:8443", "203.0.113.8", 443)
        assertEquals(3, hops.size)
        assertEquals("exit", hops[0].tag)
        assertEquals(443, hops[0].listenPort)
        assertEquals("de", hops[1].tag)
        assertEquals("9.9.9.9", hops[1].foreignHost)
        assertEquals("au", hops[2].tag)
        assertEquals(8443, hops[2].foreignPort)
        val script = CatVpsRelay.script("10.10.10.10", hops)
        assertNotNull(script)
        assertTrue(script!!, script.contains("DNAT --to-destination 203.0.113.8:443"))
        assertTrue(script, script.contains("DNAT --to-destination 9.9.9.9:"))
        assertTrue(script, script.contains("address=10.10.10.10"))
        assertFalse(script, script.contains("3x-ui", ignoreCase = true) || script.contains("regionhop", ignoreCase = true))
        assertNull(CatVpsRelay.script("10.10.10.10", emptyList()))
        assertFalse(CatVpsRelay.isHost("127.0.0.1"))
    }

    @Test
    fun usageHeaderStillParsedAndWorkshopIsInFinder() {
        val usage = SubscriptionUsagePolicy.parse("upload=100; download=400; total=1000; expire=2000000000")
        assertNotNull(usage)
        assertEquals(500L, usage!!.usedBytes)
        assertEquals(500L, usage.remainingBytes)
        val guides = src("MainActivityGuides.kt")
        assertTrue(guides.contains("showCatVpsWorkshopDialog"))
        assertTrue(guides.contains("CatVpsRelay.script"))
        assertFalse(guides.contains("freeb5d"))
        val cards = src("MainActivitySubscriptions.kt")
        assertTrue(cards.contains("subscription_usage_left"))
        assertTrue(cards.contains("subscription_usage_expired"))
    }
}

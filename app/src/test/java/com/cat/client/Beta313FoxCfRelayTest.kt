package com.cat.client

import java.io.File
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta313 — Cat Fox + Cloudflare Worker relay (control-plane only). */
class Beta313FoxCfRelayTest {

    @Test
    fun versionIsBeta313() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 388"))
        assertTrue(gradle.contains("1.10.0-beta321"))
    }

    @Test
    fun workersDevSecretUrlNormalizesAndHealthMatchesReadyJson() {
        val raw = "https://your-worker.workers.dev/?secret=YOUR_SECRET"
        val relay = FoxMozillaRelay.normalize(raw)
        assertEquals("https://your-worker.workers.dev/?secret=YOUR_SECRET", relay)
        val health = FoxMozillaRelay.healthUrl(relay)
        assertEquals("https://your-worker.workers.dev/?secret=YOUR_SECRET&health=1", health)
        assertTrue(FoxMozillaRelay.parseHealth(200, """{"relay":"cat-fox","version":1,"ready":true}""").ok)
        assertTrue(FoxMozillaRelay.parseHealth(200, """{"relay":"other","version":1,"ready":true}""").ok)
        assertFalse(FoxMozillaRelay.parseHealth(200, "Spider Firefox VPN relay: Working").ok)
    }

    @Test
    fun wrapSendsMozillaAndGithubThroughRelayNotVpnHosts() {
        val relay = "https://your-worker.workers.dev/?secret=abc12345"
        val mozilla = FoxMozillaRelay.wrap("https://vpn.mozilla.org/api/v1/fpn/token", relay)
        assertNotNull(mozilla)
        assertEquals(
            "https://vpn.mozilla.org/api/v1/fpn/token",
            URLDecoder.decode(mozilla!!.substringAfter("url="), "UTF-8"),
        )
        val gh = FoxMozillaRelay.wrap("https://raw.githubusercontent.com/org/repo/main/file", relay)
        assertNotNull(gh)
        assertNull(FoxMozillaRelay.wrap("https://openai.com/", relay))
        assertNull(FoxMozillaRelay.wrap("http://vpn.mozilla.org/x", relay))
    }

    @Test
    fun copiedWorkerStaysCatBrandedAndAllowsTheSameHosts() {
        val js = FoxMozillaRelay.workerScript("abc12345")
        assertTrue(js.contains("cat-fox"))
        assertTrue(js.contains("github.com"))
        assertTrue(js.contains("jsdelivr.net"))
        assertFalse(js.contains("Arman"))
        assertFalse(js.contains("Spider"))
        val fox = src("MainActivityFox.kt")
        assertTrue(fox.contains("رله کلادفلر — اگر ورود موزیلا بسته است") || fox.contains("Cloudflare relay — if Mozilla sign-in is blocked"))
        assertTrue(fox.contains("showFoxMozillaRelayDialog"))
        assertFalse(fox.contains("Spider"))
        assertFalse(fox.contains("temomail"))
        val free = src("MainActivityFreeNetworks.kt")
        assertTrue(free.contains("Cat Fox + رله کلادفلر") || free.contains("Cat Fox + Cloudflare relay"))
        val guides = src("MainActivityGuides.kt")
        assertTrue(guides.contains("Cat Fox + رله کلادفلر") || guides.contains("Cat Fox + Cloudflare relay"))
    }

    companion object {
        private fun src(name: String): String = listOf(
            File("src/main/java/com/cat/client/$name"),
            File("app/src/main/java/com/cat/client/$name"),
        ).first { it.isFile }.readText()
    }
}

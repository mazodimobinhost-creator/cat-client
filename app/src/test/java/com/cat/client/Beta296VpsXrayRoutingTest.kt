package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta296VpsXrayRoutingTest {
    private fun src(name: String): String =
        listOf("src/main/java/com/cat/client/$name", "app/src/main/java/com/cat/client/$name")
            .map(::File).first { it.isFile }.readText()

    @Test
    fun versionIsBeta296() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 370"))
        assertTrue(gradle.contains("1.10.0-beta303"))
    }

    @Test
    fun xrayConfigIsInboundOutboundRoutingNot3xui() {
        val hops = CatVpsRelay.parseHops("de 9.9.9.9\nau 1.2.3.4:8443", "203.0.113.8", 443)
        assertEquals(3, hops.size)
        val json = CatVpsRelay.xrayConfig(hops)
        assertNotNull(json)
        assertTrue(json!!, json.contains("\"protocol\":\"dokodemo-door\""))
        assertTrue(json, json.contains("\"tag\":\"in-exit\""))
        assertTrue(json, json.contains("\"tag\":\"out-exit\""))
        assertTrue(json, json.contains("\"inboundTag\":[\"in-exit\"]"))
        assertTrue(json, json.contains("\"outboundTag\":\"out-exit\""))
        assertTrue(json, json.contains("\"tag\":\"in-de\""))
        assertTrue(json, json.contains("\"address\":\"9.9.9.9\""))
        assertTrue(json, json.contains("\"port\":8443"))
        assertTrue(json, json.contains("\"tag\":\"direct\""))
        assertTrue(json, json.contains("\"tag\":\"block\""))
        assertFalse(json, json.contains("3x-ui", ignoreCase = true) || json.contains("regionhop", ignoreCase = true))
        assertNull(CatVpsRelay.xrayConfig(emptyList()))
        val script = CatVpsRelay.xrayScript("10.10.10.10", hops)
        assertNotNull(script)
        assertTrue(script!!, script.contains("/usr/local/etc/xray/config.json"))
        assertTrue(script, script.contains("config.json.bak.cat"))
        assertTrue(script, script.contains("address=10.10.10.10"))
        assertTrue(script, script.contains("in-exit: 10.10.10.10:443"))
        assertTrue(script, script.contains("Xray-install"))
        assertTrue(
            script,
            script.contains("bash -c \"$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)\" @ install"),
        )
        assertFalse(script, script.contains("Then run this script again"))
        assertTrue(script, script.contains("systemctl restart xray"))
        assertFalse(script, script.contains("3x-ui", ignoreCase = true) || script.contains("regionhop", ignoreCase = true))
    }

    @Test
    fun workshopCopiesXrayOnVpsNotWorker() {
        val guides = src("MainActivityGuides.kt")
        assertTrue(guides.contains("showCatVpsWorkshopDialog"))
        assertTrue(guides.contains("CatVpsRelay.xrayScript"))
        assertTrue(guides.contains("CatVpsRelay.script"))
        assertFalse(guides.contains("freeb5d"))
        val relay = src("CatVpsRelay.kt")
        assertTrue(relay.contains("dokodemo-door"))
        assertFalse(relay.contains("catclient.worker.js"))
    }
}

package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** beta227 — every free-host install asks «📍 Server country»; what can be applied depends on the host. */
class Beta227HostCountryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "node-panel/index.js").exists() }
    private fun read(path: String) = File(root, path).readText()

    @Test
    fun everyHostHasACountryRule() {
        NodeHostPanel.HOSTS.forEach { h -> assertTrue(h.id, HostRegions.choices(h.id).isNotEmpty()) }
        assertEquals(HostRegions.Mode.APP_SETS, HostRegions.mode(NodeHostPanel.RAILWAY))
        assertEquals(HostRegions.Mode.YOU_PICK, HostRegions.mode(NodeHostPanel.SHIPRR))
        assertEquals(HostRegions.Mode.YOU_PICK, HostRegions.mode(NodeHostPanel.SMARTERASP))
        assertEquals(HostRegions.Mode.FIXED, HostRegions.mode(NodeHostPanel.KATABUMP))
        assertEquals(HostRegions.Mode.FIXED, HostRegions.mode(NodeHostPanel.LUCITY))
        assertEquals(HostRegions.Mode.HOST_DECIDES, HostRegions.mode(NodeHostPanel.BOTHOSTING))
        assertEquals(4, HostRegions.choices(NodeHostPanel.RAILWAY).size)
        assertEquals(listOf("eu", "us", "asia", "oc"), HostRegions.choices(NodeHostPanel.SHIPRR).map { it.site })
        assertEquals(listOf("United States (Las Vegas)", "Europe"), HostRegions.choices(NodeHostPanel.SMARTERASP).map { it.site })
    }

    @Test
    fun validationFlagsAndNames() {
        assertEquals("eu", HostRegions.valid(NodeHostPanel.SHIPRR, "mars"))
        assertEquals("asia", HostRegions.valid(NodeHostPanel.SHIPRR, "asia"))
        assertEquals(ApiHostPlan.RAILWAY_DEFAULT_REGION, HostRegions.valid(NodeHostPanel.RAILWAY, null))
        assertEquals("🇸🇬", HostRegions.flag(NodeHostPanel.RAILWAY, "asia-southeast1-eqsg3a"))
        assertEquals("🇫🇷", HostRegions.flag(NodeHostPanel.KATABUMP, null))
        assertEquals("", HostRegions.flag(NodeHostPanel.BOTHOSTING, "auto"))
        assertEquals("cat-panel · Railway 🇳🇱", HostRegions.withFlag("cat-panel · Railway", "🇳🇱"))
        assertEquals("x 🇳🇱", HostRegions.withFlag("x 🇳🇱", "🇳🇱"))
        assertEquals("x", HostRegions.withFlag("x", ""))
        assertFalse(HostRegions.selectable(NodeHostPanel.KATABUMP))
        assertTrue(HostRegions.selectable(NodeHostPanel.SHIPRR))
    }

    @Test
    fun controllerAsksBeforeEveryInstall() {
        val c = read("app/src/main/java/com/cat/client/NodeHostController.kt")
        val auto = c.substringAfter("fun autoInstall(kind: String) {").substringBefore("\n    }\n")
        assertTrue(auto.trimStart().startsWith("askCountry(kind) { region ->"))
        assertTrue(auto.contains("NodeHostPanel.RAILWAY -> askRailwayToken(region)"))
        val start = c.substringAfter("fun showStart(kind: String, startAt: Int = 0) {").substringBefore("\n    }\n")
        assertTrue(start.contains("askCountry(kind) { wizard("))
        val change = c.substringAfter("private fun changeRailwayRegion(").substringBefore("\n    }\n")
        assertTrue(change.indexOf("rwSetRegion") in 0 until change.indexOf("rwDeploy"))
        assertTrue(c.contains("{ changeRailwayRegion(item) }"))
        assertFalse(c.contains("regionGroup"))
        val store = read("app/src/main/java/com/cat/client/NodeHost.kt")
        assertTrue(store.contains("prefs.getString(\"region_\$kind\", null)"))
    }
}

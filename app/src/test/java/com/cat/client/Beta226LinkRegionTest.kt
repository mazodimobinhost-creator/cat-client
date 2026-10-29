package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta226 — «can a KataBump panel really sit behind a Cloudflare panel?» and «which country is each host in?»
 *  • the panel's chain had two wire bugs (no VLESS addons byte, a WebSocket key Node's `ws` refuses) — the real
 *    end-to-end proof is scripts/panels/katabump-cf-link.test.mjs; here only the pure Kotlin side is pinned;
 *  • Railway: the app puts the service in the region the user picked (multiRegionConfig, one replica);
 *  • Bot-Hosting: cannot be chosen — the node name says where it landed;
 *  • every guide names the country rule of its host.
 */
class Beta226LinkRegionTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "node-panel/index.js").exists() }
    private fun read(path: String) = File(root, path).readText()

    @Test
    fun railwayRegions() {
        assertEquals(4, ApiHostPlan.RAILWAY_REGIONS.size)
        assertEquals(ApiHostPlan.RAILWAY_DEFAULT_REGION, ApiHostPlan.RAILWAY_REGIONS.first().first)
        assertEquals("europe-west4-drams3a", ApiHostPlan.RAILWAY_DEFAULT_REGION)
        assertEquals("{\"us-west2\":{\"numReplicas\":1}}", ApiHostPlan.railwayRegionConfig("us-west2"))
        assertNull(ApiHostPlan.railwayRegionConfig("mars-north1"))
        assertNull(ApiHostPlan.railwayRegionConfig("\"}"))
    }

    @Test
    fun botHostingNodeCountry() {
        assertEquals("🇫🇮 Finland", ApiHostPlan.botHostingNodeCountry("fi3.bot-hosting.net")?.second)
        assertEquals("🇩🇪 Germany", ApiHostPlan.botHostingNodeCountry("de1.bot-hosting.net")?.second)
        assertEquals("🇺🇸 USA", ApiHostPlan.botHostingNodeCountry("US2.bot-hosting.net")?.second)
        assertEquals("🇪🇺 Europe", ApiHostPlan.botHostingNodeCountry("prem-eu4.bot-hosting.net")?.second)
        assertNull(ApiHostPlan.botHostingNodeCountry(null))
        assertNull(ApiHostPlan.botHostingNodeCountry("node7.example"))
    }

    @Test
    fun controllerAppliesTheRegionAndNamesCountries() {
        val c = read("app/src/main/java/com/cat/client/NodeHostController.kt")
        val install = c.substringAfter("private fun installRailway(").substringBefore("private fun ")
        assertTrue(install.indexOf("rwCreateService") in 0 until install.indexOf("rwSetRegion"))
        assertTrue(install.indexOf("rwSetRegion") < install.indexOf("rwUpsertVariables"))
        assertTrue(c.contains("confirmApiInstall(NodeHostPanel.RAILWAY) { installRailway(token, region) }"))
        assertTrue(c.contains("ApiHostPlan.botHostingNodeCountry(node)"))
        assertTrue(c.contains("France only (OVH data center in Gravelines)"))
        assertTrue(c.contains("EU only (Lucity is hosted in the European Union"))
        assertTrue(c.contains("set the data center to «\" + smarterAspSite() + \"»"))
        assertEquals(6, Regex("shiprrRegionHint\\(\\)").findAll(c).count() - 1)
        assertTrue(!c.contains("region Europe") && !c.contains("منطقه Europe"))
        val api = read("app/src/main/java/com/cat/client/ApiHostDeploy.kt")
        assertTrue(api.contains("\\\"input\\\":{\\\"multiRegionConfig\\\":"))
        val store = read("app/src/main/java/com/cat/client/NodeHost.kt")
        assertTrue(store.contains("prefs.getString(\"railway_region\", ApiHostPlan.RAILWAY_DEFAULT_REGION)"))
    }
}

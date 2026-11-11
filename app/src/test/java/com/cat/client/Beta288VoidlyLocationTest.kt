package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta288 — pick a Voidly location in Cat; WARP Amnezia auto-builds without another app. */
class Beta288VoidlyLocationTest {

    @Test
    fun versionIsBeta288() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 371"))
        assertTrue(gradle.contains("1.10.0-beta304"))
    }

    @Test
    fun availabilityJsonBecomesLocationList() {
        val json = """
            {"schema":"voidly-vpn-availability/v1","status":"available","regions":[
              {"id":"eu-west-1","city":"Frankfurt","country":"Germany","status":"available"},
              {"id":"us-east-1","city":"New York","country":"United States","status":"unavailable"}
            ]}
        """.trimIndent()
        val regions = VoidlyPlanner.parseAvailability(json)
        assertEquals(2, regions.size)
        assertTrue(regions[0].ok)
        assertFalse(regions[1].ok)
        assertEquals("Frankfurt · آلمان", regions[0].label(true))
        assertEquals("New York · United States", regions[1].label(false))
        assertEquals("https://api.voidly.ai/v1/vpn/availability", VoidlyPlanner.AVAILABILITY)
    }

    @Test
    fun hubPicksLocationAndBuildsWarpAmnezia() {
        val hub = src("MainActivityFreeNetworks.kt")
        assertTrue(hub.contains("showVoidlyGenerate"))
        assertTrue(hub.contains("VoidlyPlanner.fetchRegions"))
        assertTrue(hub.contains("addWarpAmneziaAuto"))
        assertTrue(hub.contains("BUILTIN_AWG_AND_HY2_URIS"))
        assertFalse(hub.contains("Varvara"))
        val planner = src("VoidlyFree.kt")
        assertTrue(planner.contains("SCAN_JS"))
        assertTrue(planner.contains("[Interface]"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

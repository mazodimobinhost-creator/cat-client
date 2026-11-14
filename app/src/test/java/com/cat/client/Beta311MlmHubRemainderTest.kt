package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * beta311 — every MLM CONNECT tile Cat can actually run is on the hub and launches that engine.
 * OpenVPN / Geph / native Lantern / URnetwork / GitHub-Tunnel / Gereh stay off the board.
 */
class Beta311MlmHubRemainderTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    @Test
    fun versionIsBeta311() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 378"))
        assertTrue(gradle.contains("1.10.0-beta311"))
    }

    @Test
    fun everyCatalogIdHasALaunchBranchAndTheNewOnesAreTheOnesCatRuns() {
        val catalog = src("MlmAndNextGenVpnHub.kt")
            .substringAfter("val ALL_VPNS_2026_CATALOG")
            .substringBefore("data class FeedSource")
        val ids = Regex("""id = "([^"]+)"""").findAll(catalog).map { it.groupValues[1] }.toList()
        val launch = src("MainActivityAntiFilterTools.kt")
            .substringAfter("internal fun MainActivity.launchMlmHubEntry")
            .substringBefore("internal fun MainActivity.showAllVpns2026ComparisonAndMlmHubDialog")
        ids.forEach { id ->
            assertTrue("hub id $id must launch", launch.contains("\"$id\" ->"))
        }
        assertEquals(23, ids.size)
        listOf(
            "cat_wind" to "showCatWindDialog()",
            "voidly" to "showVoidlyDialog()",
            "sstp_vpngate" to "showCatSstpDialog()",
            "cat_gp" to "showCatGpDialog()",
            "telegram_mtproto" to "appTools.showTelegramProxyFinder()",
            "warp_gool" to "showCatWarpFreeDialog()",
            "flux_doctor" to "showSmartConfigDoctorAndFluxDialog()",
            "cat_feed" to "showDeltaKroneckerHubDialog()",
            "free_github_configs" to "runFetchSevenGitHubFreeConfigSourcesDialog()",
            "psiphon_free" to "showFreePsiphonDialog()",
            "sni_lab" to "showNetMelli2026BypassLabDialog()",
        ).forEach { (id, call) ->
            assertTrue("$id -> $call", launch.contains("\"$id\" -> $call"))
        }
        assertFalse("no fake OpenVPN engine", launch.contains("OpenVpnService"))
        assertFalse(launch.contains("Geph"))
        assertFalse(launch.contains("Urnetwork"))
        assertFalse(launch.contains("liblantern"))
    }
}

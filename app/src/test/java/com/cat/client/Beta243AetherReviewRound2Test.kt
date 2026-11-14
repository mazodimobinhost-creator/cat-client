package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

/** beta243: review round 2 — behaviour of the shared fallback stages, the learned-mode window and the network helpers. */
class Beta243AetherReviewRound2Test {
    private val id = File("/data/cat/aether.toml")
    private fun cfg(mode: AetherMode, endpoint: String? = null, ipScan: String = "both", exit: String = "auto", budget: Long = 180_000L) =
        AetherRuntimeConfig(mode = mode, identityFile = id, endpoint = endpoint, ipScan = ipScan, exitLocation = exit, scanBudgetMillis = budget)

    @Test
    fun aFailedHintDoesNotShortenTheRealSearch() {
        val hinted = cfg(AetherMode.WireGuard, endpoint = "162.159.192.1:2408", budget = AetherNetworkPlanner.HINT_BUDGET_MS)
        val discovery = AetherFallbackStages.autoDiscovery(hinted)
        assertNotNull(discovery)
        assertNull(discovery!!.endpoint)
        assertEquals("both", discovery.ipScan)
        assertEquals(AetherNetworkPlanner.SEARCH_BUDGET_MS, discovery.scanBudgetMillis)
        assertEquals(AetherNetworkPlanner.SEARCH_BUDGET_MS, AetherFallbackStages.alternate(hinted).scanBudgetMillis)
    }

    @Test
    fun discoveryIsNotRepeatedWhenTheFirstAttemptAlreadyWasDiscovery() {
        assertNull(AetherFallbackStages.autoDiscovery(cfg(AetherMode.MasqueHttp2)))
        assertNotNull("v6-only first attempt still widens to both families", AetherFallbackStages.autoDiscovery(cfg(AetherMode.MasqueHttp2, ipScan = "v6")))
    }

    @Test
    fun theAlternateIsReallyADifferentTransportWithTheSameExitKind() {
        for (mode in listOf(AetherMode.WireGuard, AetherMode.MasqueHttp2, AetherMode.WarpInWarp, AetherMode.MasqueInMasque, AetherMode.WireGuardOverMasque)) {
            for (exit in listOf("auto", "iran", "foreign")) {
                val first = cfg(mode, exit = exit)
                val alt = AetherFallbackStages.alternate(first)
                assertNotEquals("$mode/$exit: retrying the same transport is a wasted retry",
                    first.resolvedNativeTransport(), alt.resolvedNativeTransport())
                assertEquals("$mode/$exit hop count", AetherNetworkPlanner.isTwoHop(mode, exit), AetherNetworkPlanner.isTwoHop(alt.mode, alt.exitLocation))
            }
        }
        // The old carrier table mapped WoM → MASQUE-H2 + foreign = «wom» again; the shared one tries WARP-in-WARP.
        assertEquals("wiw", AetherFallbackStages.alternate(cfg(AetherMode.WireGuardOverMasque, exit = "foreign")).resolvedNativeTransport())
    }

    @Test
    fun theLearnedModeWindowDoesNotSlide() {
        val day = 24L * 60 * 60 * 1000
        val t0 = 1_000_000_000_000L
        val first = AetherNetworkPlanner.nextWorkingModeRecord(null, AetherMode.WireGuard, AetherMode.MasqueHttp2, t0)
        assertEquals("${AetherMode.MasqueHttp2.wireName}|$t0", first)
        val again = AetherNetworkPlanner.nextWorkingModeRecord(first, AetherMode.WireGuard, AetherMode.MasqueHttp2, t0 + 3 * day)
        assertEquals("a later success keeps the first savedAt", first, again)
        assertEquals(AetherMode.MasqueHttp2, AetherNetworkPlanner.parseWorkingModeRecord(again, t0 + 6 * day))
        assertNull("after 7 days the requested mode is tried again", AetherNetworkPlanner.parseWorkingModeRecord(again, t0 + 8 * day))
        val relearned = AetherNetworkPlanner.nextWorkingModeRecord(again, AetherMode.WireGuard, AetherMode.MasqueHttp2, t0 + 8 * day)
        assertEquals("an expired record starts a fresh window", "${AetherMode.MasqueHttp2.wireName}|${t0 + 8 * day}", relearned)
        assertNull("the requested mode working again clears it", AetherNetworkPlanner.nextWorkingModeRecord(again, AetherMode.WireGuard, AetherMode.WireGuard, t0))
        assertNull(AetherNetworkPlanner.parseWorkingModeRecord("garbage", t0))
        assertNull("a clock set backwards does not keep a record forever", AetherNetworkPlanner.parseWorkingModeRecord(first, t0 - day))
    }

    @Test
    fun onlyGlobalIpv6AddressesCountAsIpv6() {
        fun v6(s: String) = AetherNetworkPlanner.isGlobalIpv6Address(InetAddress.getByName(s))
        assertTrue(v6("2a06:98c1:3101::6812:202f"))
        assertTrue(v6("2001:4860:4860::8888"))
        assertFalse("link-local", v6("fe80::1"))
        assertFalse("unique-local", v6("fd12:3456::1"))
        assertFalse("unique-local fc", v6("fc00::1"))
        assertFalse("site-local", v6("fec0::1"))
        assertFalse("loopback", v6("::1"))
        assertFalse("unspecified", v6("::"))
        assertFalse("IPv4 is not IPv6", v6("188.114.98.0"))
    }

    @Test
    fun theUnderlyingNetworkRankingPrefersAWorkingNetwork() {
        val s = AetherNetworkPlanner::underlyingNetworkScore
        assertTrue("validated mobile data beats an unvalidated Wi-Fi (captive portal)", s(true, false, true) > s(false, true, false))
        assertTrue(s(true, true, false) > s(true, false, true))
        assertTrue(s(false, false, true) > s(false, false, false))
    }

    @Test
    fun theReviewFixesAreWiredIn() {
        val main = File("src/main/java/com/cat/client")
        val wizard = File(main, "MainActivityCatWarp.kt").readText()
        assertTrue("an unanswered fixed peer never enters network memory", wizard.contains("takeIf { winner.reachable && winner.confirmed }"))
        val startup = File(main, "CatClientVpnServiceCatWarp.kt").readText()
        assertTrue("the failover session has its own watchdog", startup.contains("val retryWatchdog = launchAetherWatchdog(retrySession)"))
        assertEquals("both chains get the plan's budget", 3, startup.split(", plan.scanBudgetMillis)").size - 1)
        val carrier = File(main, "AetherProxyCarrier.kt").readText()
        assertTrue(carrier.contains("scanBudgetMillis = scanBudgetMillis,"))
        assertFalse("no second alternate table", carrier.contains("val fallbackMode = when (requestedMode)"))
        assertTrue(File(main, "AetherPreferenceStore.kt").readText().contains("AetherNetworkPlanner.nextWorkingModeRecord("))
    }
}

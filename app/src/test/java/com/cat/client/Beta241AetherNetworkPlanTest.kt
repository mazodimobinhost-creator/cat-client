package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** beta241 — Cat Warp configs built on one line must also work on another line / phone. */
class Beta241AetherNetworkPlanTest {
    private val none: (AetherMode, String) -> String? = { _, _ -> null }

    @Test
    fun anIpv6ConfigOnANetworkWithoutIpv6SearchesIpv4AndDropsTheIpv6Endpoint() {
        val plan = AetherNetworkPlanner.plan(
            requestedMode = AetherMode.WireGuard, requestedExitLocation = "auto", requestedIpScan = "v6",
            linkEndpoint = "[2606:4700:d0::a29f:c001]:2408", hasIpv6 = false, learnedMode = null, verifiedFor = none,
        )
        assertEquals("v4", plan.ipScan)
        assertNull("an IPv6 endpoint cannot be dialled without IPv6", plan.endpoint)
        assertTrue(plan.notes.contains("no-ipv6"))
        assertEquals(AetherNetworkPlanner.SEARCH_BUDGET_MS, plan.scanBudgetMillis)
    }

    @Test
    fun unknownIpv6StateChangesNothing() {
        val plan = AetherNetworkPlanner.plan(
            AetherMode.WireGuard, "auto", "v6", "[2606:4700:d0::a29f:c001]:2408", hasIpv6 = null, learnedMode = null, verifiedFor = none,
        )
        assertEquals("v6", plan.ipScan)
        assertEquals("[2606:4700:d0::a29f:c001]:2408", plan.endpoint)
    }

    @Test
    fun anEndpointScannedOnAnotherLineIsOnlyAShortHint() {
        val plan = AetherNetworkPlanner.plan(
            AetherMode.MasqueHttp2, "auto", "both", "162.159.198.235:443", hasIpv6 = true, learnedMode = null, verifiedFor = none,
        )
        assertEquals("162.159.198.235:443", plan.endpoint)
        assertEquals(AetherNetworkPlanner.HINT_BUDGET_MS, plan.scanBudgetMillis)
        assertTrue(plan.notes.contains("link-hint"))
    }

    @Test
    fun anEndpointThatReallyWorkedOnThisNetworkWins() {
        val plan = AetherNetworkPlanner.plan(
            AetherMode.MasqueHttp2, "auto", "v4", "162.159.198.235:443", hasIpv6 = false, learnedMode = null,
            verifiedFor = { mode, family -> if (mode == AetherMode.MasqueHttp2 && family == "both") "162.159.198.2:443" else null },
        )
        assertEquals("verified under «both» is found for a v4 plan", "162.159.198.2:443", plan.endpoint)
        assertEquals(AetherNetworkPlanner.VERIFIED_BUDGET_MS, plan.scanBudgetMillis)
        assertTrue(plan.notes.contains("verified-here"))
    }

    @Test
    fun aVerifiedIpv6EndpointIsNotUsedWhenTheNetworkHasNoIpv6() {
        val plan = AetherNetworkPlanner.plan(
            AetherMode.WireGuard, "auto", "both", null, hasIpv6 = false, learnedMode = null,
            verifiedFor = { _, _ -> "[2606:4700:d0::a29f:c002]:2408" },
        )
        assertNull(plan.endpoint)
    }

    @Test
    fun aModeLearnedOnThisNetworkIsUsedAndTheOtherModesEndpointIsDropped() {
        val plan = AetherNetworkPlanner.plan(
            AetherMode.WireGuard, "auto", "v4", "162.159.192.1:2408", hasIpv6 = false,
            learnedMode = AetherMode.MasqueHttp2, verifiedFor = none,
        )
        assertEquals(AetherMode.MasqueHttp2, plan.mode)
        assertNull("a WireGuard port is not a MASQUE endpoint", plan.endpoint)
        assertTrue(plan.notes.contains("learned-mode"))
        assertTrue(plan.notes.contains("link-endpoint-dropped"))
    }

    @Test
    fun aLearnedAlternateOfATwoHopConfigKeepsTheForeignExit() {
        // WARP-in-WARP with the «auto» exit resolves to two-hop wiw; its learned alternate (beta243: WARP-over-MASQUE) stays two-hop.
        val plan = AetherNetworkPlanner.plan(
            AetherMode.WarpInWarp, "auto", "both", null, hasIpv6 = true, learnedMode = AetherMode.WireGuardOverMasque, verifiedFor = none,
        )
        assertEquals(AetherMode.WireGuardOverMasque, plan.mode)
        assertEquals("foreign", plan.exitLocation)
        assertTrue(AetherNetworkPlanner.isTwoHop(plan.mode, plan.exitLocation))
        // single-hop stays single-hop (Iran exit is the point of it)
        val single = AetherNetworkPlanner.plan(
            AetherMode.WireGuard, "iran", "both", null, hasIpv6 = true, learnedMode = AetherMode.MasqueHttp2, verifiedFor = none,
        )
        assertEquals("iran", single.exitLocation)
        assertFalse(AetherNetworkPlanner.isTwoHop(single.mode, single.exitLocation))
    }

    @Test
    fun everyAlternateKeepsTheHopCountOfItsMode() {
        for (mode in AetherMode.entries.filter { it != AetherMode.MasquePsiphon }) {
            for (exit in listOf("auto", "iran", "foreign")) {
                val before = AetherNetworkPlanner.isTwoHop(mode, exit)
                val alt = AetherNetworkPlanner.alternateMode(mode)
                val after = AetherNetworkPlanner.isTwoHop(alt, AetherNetworkPlanner.exitForAlternate(mode, exit))
                assertEquals("$mode/$exit → $alt", before, after)
            }
        }
    }

    @Test
    fun aRememberedModeThatIsNotTheAlternateIsIgnored() {
        val plan = AetherNetworkPlanner.plan(
            AetherMode.WireGuard, "auto", "both", null, hasIpv6 = true, learnedMode = AetherMode.MasqueInMasque, verifiedFor = none,
        )
        assertEquals(AetherMode.WireGuard, plan.mode)
        assertFalse(plan.notes.contains("learned-mode"))
    }

    @Test
    fun reviewFindingsAreWiredIntoTheApp() {
        val main = File("src/main/java/com/cat/client")
        val session = File(main, "AetherSession.kt").readText()
        // beta243: both prepare paths build their fallback stages from AetherFallbackStages (behaviour: Beta243 test).
        assertTrue(session.contains("AetherFallbackStages.alternate(config)"))
        assertTrue(File(main, "AetherProxyCarrier.kt").readText().contains("AetherFallbackStages.alternate(config, requestedMode)"))
        assertTrue(File(main, "CatClientVpnServiceCatWarp.kt").readText().contains("exitLocation = plan.exitLocation"))
        val store = File(main, "AetherPreferenceStore.kt").readText()
        assertTrue("both network functions use the same picker", store.split("pickUnderlyingNetwork(cm)").size == 3)
        assertTrue(store.contains("NET_CAPABILITY_VALIDATED"))
        val wizard = File(main, "MainActivityCatWarp.kt").readText()
        assertFalse("an unconfirmed scan hit is never silently «confirmed»", wizard.contains("} ?: hits.firstOrNull()"))
        assertEquals(3, wizard.split("confirmed = confirmedNative != null").size - 1)
        assertEquals("TCP / UDP probe answers are not confirmations", 3, wizard.split("confirmed = false,").size - 1)
        assertTrue(wizard.contains("thenBy { !it.confirmed }"))
        assertTrue(wizard.contains("اتصال کامل تأیید نشد"))
    }

    @Test
    fun theServiceRemembersOnlyAfterATunnelReallyWorks() {
        val main = File("src/main/java/com/cat/client")
        val startup = File(main, "CatClientVpnServiceCatWarp.kt").readText()
            .substringAfter("fun CatClientVpnService.runAetherStartup(").substringBefore("var result = try")
        val beforeWatchdog = startup.substringBefore("fun launchAetherWatchdog(")
        assertFalse("no write into this network's memory before the connection is tried", beforeWatchdog.contains("rememberEndpointForNetwork"))
        assertTrue(startup.contains("AetherNetworkPlanner.plan("))
        assertTrue(startup.contains("scanBudgetMillis = plan.scanBudgetMillis"))
        val afterStart = startup.substringAfter("state == VpnState.Started && live.lastMode != null) {")
        assertTrue(afterStart.contains("rememberEndpointForNetwork(networkKey, ranMode"))
        assertTrue(afterStart.contains("rememberWorkingModeForNetwork(networkKey, selection.mode, ranMode)"))
        val session = File(main, "AetherSession.kt").readText()
        assertTrue(session.contains("lastPeer = prepared.peer"))
        assertTrue("the duplicate discovery stage is skipped", session.contains("if (autoDiscoveryCfg == null) Result.failure(firstError)"))
        val store = File(main, "AetherPreferenceStore.kt").readText()
        assertTrue("old, possibly unverified memories are not trusted", store.contains("\"netpool2_") && !store.contains("\"netpool_"))
        assertTrue(store.contains("fun underlyingNetworkHasIpv6("))
    }
}

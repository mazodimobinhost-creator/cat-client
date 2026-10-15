package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartConnectionAdvisorTest {
    private val now = 10_000_000L
    private val network = SmartNetworkSnapshot("cell:43235", true, false, true, false, true, true, true, true)

    private fun route(
        id: String,
        country: String,
        rtt: Long,
        success: Double,
        speed: Long = 4_000,
        verdict: SmartRouteVerdict = SmartRouteVerdict.WORKS,
        measuredAt: Long = now - 1_000,
        setup: Boolean = false,
        udp: Boolean = true,
    ) = SmartRouteMeasurement(
        id = id,
        kind = SmartRouteKind.CONFIG,
        label = id,
        verdict = verdict,
        countryCode = country,
        exitIp = "203.0.113.1",
        medianRttMs = rtt,
        p95RttMs = rtt + 40,
        throughputKbps = speed,
        successRate = success,
        sampleCount = 6,
        measuredAtMs = measuredAt,
        supportsUdp = udp,
        requiresSetup = setup,
    )

    @Test fun refusesToLearnOnCaptiveOrUnvalidatedNetwork() {
        val captive = network.copy(captivePortal = true)
        val result = SmartConnectionAdvisor.recommend(captive, listOf(route("a", "DE", 90, 1.0)), nowMs = now)
        assertNull(result.selected)
        assertEquals("network-not-validated", result.blockedReason)
    }

    @Test fun aPortOnlyOrFailedProbeIsNeverRecommended() {
        val routes = listOf(
            route("port-only", "DE", 40, 1.0, verdict = SmartRouteVerdict.INCONCLUSIVE),
            route("failed", "NL", 30, 1.0, verdict = SmartRouteVerdict.FAILED),
        )
        val result = SmartConnectionAdvisor.recommend(network, routes, nowMs = now)
        assertNull(result.selected)
        assertEquals("no-proven-route", result.blockedReason)
    }

    @Test fun staleEvidenceIsNotUsed() {
        val stale = route("old", "DE", 30, 1.0, measuredAt = now - 7 * 60 * 60 * 1000L)
        assertNull(SmartConnectionAdvisor.recommend(network, listOf(stale), nowMs = now).selected)
    }

    @Test fun stabilityCanBeatAFlakyFastRoute() {
        val stable = route("stable", "DE", 145, 1.0, speed = 6_000)
        val flaky = route("flaky", "NL", 70, 0.45, speed = 8_000)
        val result = SmartConnectionAdvisor.recommend(network, listOf(flaky, stable), nowMs = now)
        assertEquals("stable", result.selected?.id)
        assertTrue(SmartRecommendationReason.HIGHEST_STABILITY in result.reasons)
    }

    @Test fun preferredCountryWinsWhenItHasAProvenRoute() {
        val result = SmartConnectionAdvisor.recommend(
            network,
            listOf(route("nl", "NL", 80, 1.0), route("de", "DE", 150, 0.98)),
            SmartRoutePreference(CountrySelectionMode.PREFER, "de"),
            now,
        )
        assertEquals("de", result.selected?.id)
        assertTrue(SmartRecommendationReason.PREFERRED_COUNTRY in result.reasons)
    }

    @Test fun preferredCountryFallsBackButLockedCountryDoesNot() {
        val routes = listOf(route("nl", "NL", 80, 1.0))
        val preferred = SmartConnectionAdvisor.recommend(
            network, routes, SmartRoutePreference(CountrySelectionMode.PREFER, "CA"), now,
        )
        assertEquals("nl", preferred.selected?.id)
        assertTrue(SmartRecommendationReason.FALLBACK_COUNTRY in preferred.reasons)

        val locked = SmartConnectionAdvisor.recommend(
            network, routes, SmartRoutePreference(CountrySelectionMode.LOCK, "CA"), now,
        )
        assertNull(locked.selected)
        assertEquals("locked-country-unavailable", locked.blockedReason)
    }

    @Test fun setupOnlyRouteIsOfferedButNeverAutoSelected() {
        val worker = route("worker", "DE", 100, 1.0, verdict = SmartRouteVerdict.NEEDS_SETUP, setup = true)
        val result = SmartConnectionAdvisor.recommend(network, listOf(worker), nowMs = now)
        assertNull(result.selected)
        assertEquals(listOf("worker"), result.alternatives.map { it.id })
    }

    @Test fun requiredUdpFiltersTcpOnlyRoutes() {
        val tcp = route("worker", "DE", 50, 1.0, udp = false)
        val quic = route("hy2", "NL", 100, 0.98, udp = true)
        val result = SmartConnectionAdvisor.recommend(
            network, listOf(tcp, quic), SmartRoutePreference(requireUdp = true), now,
        )
        assertEquals("hy2", result.selected?.id)
    }

    @Test fun deterministicTieBreakPreventsSelectionFlapping() {
        val a = route("a", "DE", 100, 1.0)
        val b = route("b", "DE", 100, 1.0)
        repeat(4) { index ->
            val input = if (index % 2 == 0) listOf(b, a) else listOf(a, b)
            assertEquals("a", SmartConnectionAdvisor.recommend(network, input, nowMs = now).selected?.id)
        }
    }
}

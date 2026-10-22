package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartRouteHistoryTest {
    private fun sample(at: Long, verdict: SmartRouteVerdict, rtt: Long? = null) = SmartRouteMeasurement(
        id = "route", kind = SmartRouteKind.DIRECT, label = "Direct", verdict = verdict,
        medianRttMs = rtt, measuredAtMs = at,
    )

    @Test fun aggregatesSuccessLatencyAndFailureStreak() {
        val stats = SmartRouteHistory.aggregate(listOf(
            sample(1, SmartRouteVerdict.WORKS, 100), sample(2, SmartRouteVerdict.WORKS, 200),
            sample(3, SmartRouteVerdict.FAILED), sample(4, SmartRouteVerdict.FAILED),
        ))!!
        assertEquals(4, stats.measurement.sampleCount)
        assertEquals(0.5, stats.measurement.successRate, 0.0)
        assertEquals(100L, stats.measurement.medianRttMs)
        assertEquals(200L, stats.measurement.p95RttMs)
        assertEquals(100L, stats.jitterMs)
        assertEquals(2, stats.failureStreak)
        assertEquals(SmartRouteVerdict.FAILED, stats.measurement.verdict)
    }

    @Test fun boundsHistoryToRecentSamples() {
        val stats = SmartRouteHistory.aggregate((1L..30L).map { sample(it, SmartRouteVerdict.WORKS, it) })!!
        assertEquals(20, stats.measurement.sampleCount)
        assertEquals(20L, stats.measurement.medianRttMs)
        assertEquals(29L, stats.measurement.p95RttMs)
    }

    @Test fun networksUseDifferentHistoryKeys() {
        org.junit.Assert.assertNotEquals(SmartRouteHistory.storageKey("wifi:a"), SmartRouteHistory.storageKey("mobile:b"))
    }

    @Test fun emptyHistoryHasNoStats() = assertNull(SmartRouteHistory.aggregate(emptyList()))
}

package com.whitedns.vpn

import com.cat.client.AutoConnectionCandidate
import com.cat.client.AutoConnectionDecision
import com.cat.client.AutoConnectionPlanner
import com.cat.client.MobileCarrier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectionPlannerTest {
    private val now = 1_000_000L

    private fun candidate(
        id: String,
        latency: Long?,
        carrier: MobileCarrier = MobileCarrier.UNKNOWN,
        success: Boolean = true,
        age: Long = 1_000L,
    ) = AutoConnectionCandidate(
        id = id,
        endpoint = "$id.example:443",
        source = "device-scan",
        carrier = carrier,
        latencyMs = latency,
        verifiedAtMs = now - age,
        succeeded = success,
    )

    @Test fun refusesToGuessWithoutFreshEvidence() {
        assertTrue(AutoConnectionPlanner.decide(emptyList(), MobileCarrier.MCI, now, true) is AutoConnectionDecision.Inconclusive)
        val failed = listOf(candidate("failed", 20, success = false), candidate("stale", 10, age = 999_999))
        val result = AutoConnectionPlanner.decide(failed, MobileCarrier.MCI, now, true)
        assertEquals("no-fresh-verified-candidate", (result as AutoConnectionDecision.Inconclusive).reason)
    }

    @Test fun networkFailureIsInconclusiveNotFakeSuccess() {
        val result = AutoConnectionPlanner.decide(listOf(candidate("ok", 10)), MobileCarrier.MCI, now, false)
        assertEquals("network-unavailable", (result as AutoConnectionDecision.Inconclusive).reason)
    }

    @Test fun matchingCarrierWinsOverFasterGenericCandidate() {
        val result = AutoConnectionPlanner.decide(
            listOf(candidate("generic", 5), candidate("mci", 30, MobileCarrier.MCI)),
            MobileCarrier.MCI,
            now,
            true,
        ) as AutoConnectionDecision.Ready
        assertEquals("mci", result.candidate.id)
        assertEquals("verified-carrier-match", result.reason)
    }

    @Test fun unknownCarrierSelectsLowestVerifiedLatency() {
        val result = AutoConnectionPlanner.decide(
            listOf(candidate("slow", 80), candidate("fast", 12)),
            MobileCarrier.UNKNOWN,
            now,
            true,
        ) as AutoConnectionDecision.Ready
        assertEquals("fast", result.candidate.id)
        assertEquals("verified-lowest-latency", result.reason)
    }

    @Test fun tieBreakIsDeterministic() {
        val result = AutoConnectionPlanner.decide(
            listOf(candidate("b", 20), candidate("a", 20)),
            MobileCarrier.UNKNOWN,
            now,
            true,
        ) as AutoConnectionDecision.Ready
        assertEquals("a", result.candidate.id)
    }
}

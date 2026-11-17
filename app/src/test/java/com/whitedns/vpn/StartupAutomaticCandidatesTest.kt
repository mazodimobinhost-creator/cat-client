package com.whitedns.vpn

import com.cat.client.CleanIpResult
import com.cat.client.MobileCarrier
import com.cat.client.StartupScanPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupAutomaticCandidatesTest {
    private val now = 2_000_000L

    private fun result(ip: String, latency: Long, checkedAt: Long = now - 1_000, loss: Double = 0.0) =
        CleanIpResult(ip, 443, latency, loss, checkedAt)

    @Test fun automaticCandidatesRequireFreshDeviceEvidence() {
        val selected = StartupScanPolicy.automaticCandidates(
            subscriptionPorts = listOf(443),
            lastEndpoint = null,
            cachedResults = listOf(
                result("1.1.1.1", 10, checkedAt = 0),
                result("2.2.2.2", 20, loss = 1.0),
            ),
            detectedCarrier = MobileCarrier.UNKNOWN,
            nowMs = now,
        )
        assertTrue(selected.isEmpty())
    }

    @Test fun automaticCandidatesAreRankedByMeasuredLatency() {
        val selected = StartupScanPolicy.automaticCandidates(
            subscriptionPorts = listOf(443),
            lastEndpoint = null,
            cachedResults = listOf(result("1.1.1.1", 80), result("2.2.2.2", 15)),
            detectedCarrier = MobileCarrier.MCI,
            nowMs = now,
        )
        assertEquals(listOf("2.2.2.2", "1.1.1.1"), selected.map { it.ip })
    }

    @Test fun excludedEndpointCannotBeSelected() {
        val blocked = result("2.2.2.2", 5)
        val selected = StartupScanPolicy.automaticCandidates(
            subscriptionPorts = listOf(443),
            lastEndpoint = null,
            cachedResults = listOf(result("1.1.1.1", 20), blocked),
            detectedCarrier = MobileCarrier.UNKNOWN,
            nowMs = now,
            excludedEndpoint = blocked,
        )
        assertEquals(listOf("1.1.1.1"), selected.map { it.ip })
    }
}

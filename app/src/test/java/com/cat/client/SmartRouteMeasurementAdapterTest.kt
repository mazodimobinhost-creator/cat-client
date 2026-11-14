package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartRouteMeasurementAdapterTest {
    private fun profile(tag: String = "DE · Hysteria2", type: String = "hysteria2") = ConnectionProfile(
        tag = tag,
        type = type,
        server = "203.0.113.2",
        port = 443,
        transport = "udp",
        validationHost = "example.com",
    )

    @Test fun successfulHttpsRecordBecomesProvenEvidence() {
        val profile = profile()
        val record = ConnectionDelayRecord("sub", profile.fingerprint, 123, ConnectionDelayStatus.Success, 9_000, 6_500)
        val result = SmartRouteMeasurementAdapter.fromProfiles(
            listOf(profile), mapOf(profile.fingerprint to record), mapOf(profile.fingerprint to "DE"),
        ).single()
        assertEquals(SmartRouteVerdict.WORKS, result.verdict)
        assertEquals(123L, result.medianRttMs)
        assertEquals(6_500L, result.throughputKbps)
        assertEquals("DE", result.countryCode)
        assertEquals(true, result.supportsUdp)
    }

    @Test fun absentRecordStaysInconclusive() {
        val result = SmartRouteMeasurementAdapter.fromProfiles(listOf(profile()), emptyMap()).single()
        assertEquals(SmartRouteVerdict.INCONCLUSIVE, result.verdict)
        assertEquals(0, result.sampleCount)
        assertNull(result.medianRttMs)
    }

    @Test fun failedHttpsRecordIsNeverPromotedByAnOldSpeed() {
        val profile = profile()
        val failed = ConnectionDelayRecord("sub", profile.fingerprint, null, ConnectionDelayStatus.Failure, 9_000, 9_999)
        val result = SmartRouteMeasurementAdapter.fromProfiles(listOf(profile), mapOf(profile.fingerprint to failed)).single()
        assertEquals(SmartRouteVerdict.FAILED, result.verdict)
        assertNull(result.throughputKbps)
    }

    @Test fun resultFromAnotherNetworkIsNotUsed() {
        val profile = profile()
        val wifiRecord = ConnectionDelayRecord(
            "sub", profile.fingerprint, 80, ConnectionDelayStatus.Success, 9_000, 8_000, "wifi",
        )
        val result = SmartRouteMeasurementAdapter.fromProfiles(
            listOf(profile),
            mapOf(profile.fingerprint to wifiRecord),
            currentNetworkKey = "cell:43235",
        ).single()
        assertEquals(SmartRouteVerdict.INCONCLUSIVE, result.verdict)
        assertEquals(0, result.sampleCount)
        assertNull(result.medianRttMs)
    }

    @Test fun storageKeepsLatestResultForEveryNetwork() {
        val profile = profile()
        val wifiOld = ConnectionDelayRecord("sub", profile.fingerprint, 100, ConnectionDelayStatus.Success, 1_000, networkKey = "wifi")
        val wifiNew = wifiOld.copy(delayMs = 80, testedAt = 2_000)
        val cell = wifiOld.copy(delayMs = 160, testedAt = 3_000, networkKey = "cell:43235")
        val kept = ConnectionDelayRecordPolicy.latestPerNetwork(listOf(wifiOld, wifiNew, cell))
        assertEquals(2, kept.size)
        assertEquals(80, kept.first { it.networkKey == "wifi" }.delayMs)
        assertEquals(160, kept.first { it.networkKey == "cell:43235" }.delayMs)
    }

    @Test fun routeKindIsDerivedWithoutChangingRuntimeType() {
        val worker = profile("Cloudflare Worker NL", "vless")
        val orbot = profile("Tor via Orbot", "socks5")
        val result = SmartRouteMeasurementAdapter.fromProfiles(listOf(worker, orbot), emptyMap())
        assertEquals(SmartRouteKind.WORKER, result[0].kind)
        assertEquals(SmartRouteKind.ORBOT, result[1].kind)
    }
}

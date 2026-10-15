package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SmartRouteProbePolicyTest {
    private fun verdict(status: Int?, timedOut: Boolean = false, before: String = "wifi:a", after: String = before) =
        SmartRouteProbePolicy.httpVerdict(status, timedOut, before, after, true, true, false, false)

    @Test fun workerRequiresReal2xx() {
        assertEquals(SmartRouteVerdict.WORKS, verdict(204))
        assertEquals(SmartRouteVerdict.FAILED, verdict(302))
        assertEquals(SmartRouteVerdict.FAILED, verdict(503))
        assertEquals(SmartRouteVerdict.FAILED, verdict(null, timedOut = true))
        assertEquals(SmartRouteVerdict.FAILED, verdict(null)) // A port without HTTP evidence.
    }

    @Test fun networkChangeIsInconclusiveAndNotPersisted() {
        val result = verdict(204, before = "wifi:a", after = "mobile:b")
        assertEquals(SmartRouteVerdict.INCONCLUSIVE, result)
        assertFalse(SmartRouteProbePolicy.shouldPersist(result, "wifi:a", "mobile:b"))
    }

    @Test fun captivePortalIsInconclusive() {
        assertEquals(
            SmartRouteVerdict.INCONCLUSIVE,
            SmartRouteProbePolicy.httpVerdict(200, false, "wifi:a", "wifi:a", true, true, true, true),
        )
    }

    @Test fun dnsttStatesRequireTransportEvidence() {
        assertEquals(SmartRouteVerdict.NOT_CONFIGURED, SmartRouteProbePolicy.dnsttVerdict(true, DnsttTransportEvidence.NOT_CONFIGURED, null))
        assertEquals(SmartRouteVerdict.ORBOT_OFF, SmartRouteProbePolicy.dnsttVerdict(false, DnsttTransportEvidence.CONFIGURED, null))
        assertEquals(SmartRouteVerdict.UNKNOWN, SmartRouteProbePolicy.dnsttVerdict(true, DnsttTransportEvidence.UNKNOWN, SmartRouteVerdict.WORKS))
        assertEquals(SmartRouteVerdict.WORKS, SmartRouteProbePolicy.dnsttVerdict(true, DnsttTransportEvidence.CONFIGURED, SmartRouteVerdict.WORKS))
    }

    @Test fun probeIntervalIsEnforced() {
        assertFalse(SmartRouteScout.canProbe(1_000L, 30_999L))
        assertEquals(true, SmartRouteScout.canProbe(1_000L, 31_000L))
    }

    @Test fun operatorIsUnknownWithoutUnambiguousAccess() {
        assertEquals(null, SmartOperatorPolicy.displayName(false, false, listOf("Irancell")))
        assertEquals(null, SmartOperatorPolicy.displayName(true, true, listOf("Irancell")))
        assertEquals(null, SmartOperatorPolicy.displayName(true, false, listOf("Irancell", "MCI")))
        assertEquals("Irancell", SmartOperatorPolicy.displayName(true, false, listOf("Irancell")))
    }
}

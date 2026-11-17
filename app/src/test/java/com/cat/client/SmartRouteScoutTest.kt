package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartRouteScoutTest {
    @Test fun endToEndHttpRequiresARealResponse() {
        assertEquals(SmartRouteVerdict.WORKS, SmartScoutVerdictPolicy.endToEndHttp(204))
        assertEquals(SmartRouteVerdict.WORKS, SmartScoutVerdictPolicy.endToEndHttp(302))
        assertEquals(SmartRouteVerdict.FAILED, SmartScoutVerdictPolicy.endToEndHttp(500))
        assertEquals(SmartRouteVerdict.FAILED, SmartScoutVerdictPolicy.endToEndHttp(null))
    }

    @Test fun healthyWorkerControlPlaneIsNotCalledATunnelSuccess() {
        assertEquals(SmartRouteVerdict.INCONCLUSIVE, SmartScoutVerdictPolicy.workerControlPlane("HEALTHY"))
        assertEquals(SmartRouteVerdict.NEEDS_SETUP, SmartScoutVerdictPolicy.workerControlPlane("PENDING"))
        assertEquals(SmartRouteVerdict.FAILED, SmartScoutVerdictPolicy.workerControlPlane("BLOCKED"))
    }
}

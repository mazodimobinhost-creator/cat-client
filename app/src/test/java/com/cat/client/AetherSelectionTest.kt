package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AetherSelectionTest {
    @Test fun endpointExpiresAndFutureMeasurementsAreRejected() {
        val now = 2_000_000_000L
        assertEquals("1.1.1.1:443", AetherSelection(
            endpoint = "1.1.1.1:443",
            endpointScannedAt = now - 1_000,
        ).freshEndpoint(now))
        assertNull(AetherSelection(
            endpoint = "1.1.1.1:443",
            endpointScannedAt = now - AetherSelection.ENDPOINT_MAX_AGE_MS - 1,
        ).freshEndpoint(now))
        assertNull(AetherSelection(
            endpoint = "1.1.1.1:443",
            endpointScannedAt = now + 1,
        ).freshEndpoint(now))
    }
}

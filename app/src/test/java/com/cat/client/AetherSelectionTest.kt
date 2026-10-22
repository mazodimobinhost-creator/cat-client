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

    @Test fun psiphonAndTorChainsEnforceSimpleCarrierProtocols() {
        assertEquals(
            AetherMode.WireGuard,
            AetherSelection.simplifyModeForChain(AetherMode.WireGuardOverMasque, "psiphon"),
        )
        assertEquals(
            AetherMode.WireGuard,
            AetherSelection.simplifyModeForChain(AetherMode.WireGuardOverMasque, "tor"),
        )
        assertEquals(
            AetherMode.MasqueHttp2,
            AetherSelection.simplifyModeForChain(AetherMode.MasqueHttp2, "psiphon"),
        )
        assertEquals(
            AetherMode.WireGuardOverMasque,
            AetherSelection.simplifyModeForChain(AetherMode.WireGuardOverMasque, "none"),
        )
    }

    @Test fun effectiveExitLocationReflectsIranVsForeignChoice() {
        assertEquals(
            "iran",
            AetherSelection(mode = AetherMode.WireGuard, exitLocation = "iran").effectiveExitLocation(),
        )
        assertEquals(
            "foreign",
            AetherSelection(mode = AetherMode.WireGuard, exitLocation = "foreign").effectiveExitLocation(),
        )
        assertEquals(
            "foreign",
            AetherSelection(mode = AetherMode.WireGuard, exitLocation = "iran", chainTarget = "psiphon").effectiveExitLocation(),
        )
        assertEquals(
            "foreign",
            AetherSelection(mode = AetherMode.WireGuardOverMasque, exitLocation = "auto").effectiveExitLocation(),
        )
        assertEquals(
            "iran",
            AetherSelection(mode = AetherMode.MasqueHttp2, exitLocation = "auto").effectiveExitLocation(),
        )
    }
}

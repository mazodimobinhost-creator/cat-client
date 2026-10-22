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

    @Test fun aetherUriRoundTripsForIranForeignAndChainConfigs() {
        val mciSelection = AetherSelection(
            enabled = true,
            mode = AetherMode.WireGuard,
            endpoint = "[2606:4700:d0::a29f:c001]:2408",
            ipScan = "v6",
            profileName = "MCI-WARP-IPv6",
            chainTarget = "psiphon",
            exitLocation = "foreign",
        )
        val uri = mciSelection.toAetherUri()
        org.junit.Assert.assertTrue(uri.startsWith("aether://?"))
        org.junit.Assert.assertTrue(uri.contains("protocol=wg"))
        org.junit.Assert.assertTrue(uri.contains("ip=v6"))
        org.junit.Assert.assertTrue(uri.contains("chain=psiphon"))
        org.junit.Assert.assertTrue(uri.contains("exit_loc=!IR"))
        org.junit.Assert.assertTrue(uri.endsWith("#MCI-WARP-IPv6"))

        val parsed = AetherSelection.fromAetherUri(uri, nowMs = 123456L)!!
        assertEquals(true, parsed.enabled)
        assertEquals(AetherMode.WireGuard, parsed.mode)
        assertEquals("[2606:4700:d0::a29f:c001]:2408", parsed.endpoint)
        assertEquals("v6", parsed.ipScan)
        assertEquals("psiphon", parsed.chainTarget)
        assertEquals("foreign", parsed.exitLocation)
        assertEquals("MCI-WARP-IPv6", parsed.profileName)

        val iranMasque = AetherSelection(
            enabled = true,
            mode = AetherMode.MasqueHttp2,
            endpoint = "162.159.198.1:443",
            ipScan = "both",
            profileName = "MASQUE-IR",
            chainTarget = "none",
            exitLocation = "iran",
        )
        val iranUri = iranMasque.toAetherUri()
        org.junit.Assert.assertTrue(iranUri.contains("protocol=masque"))
        org.junit.Assert.assertTrue(iranUri.contains("exit_loc=IR"))
        val parsedIran = AetherSelection.fromAetherUri(iranUri, nowMs = 999L)!!
        assertEquals("iran", parsedIran.exitLocation)
        assertEquals(AetherMode.MasqueHttp2, parsedIran.mode)
    }
}

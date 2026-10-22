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
            psiphonRegion = "DE",
            profileName = "MCI-WARP-IPv6",
            chainTarget = "psiphon",
            exitLocation = "foreign",
        )
        val uri = mciSelection.toAetherUri()
        org.junit.Assert.assertTrue(uri.startsWith("aether://[2606:4700:d0::a29f:c001]:2408?"))
        org.junit.Assert.assertTrue(uri.contains("protocol=wg"))
        org.junit.Assert.assertTrue(uri.contains("ip=v6"))
        org.junit.Assert.assertTrue(uri.contains("psiphon=chain"))
        org.junit.Assert.assertTrue(uri.contains("psiphon_mode=auto"))
        org.junit.Assert.assertTrue(uri.contains("region=DE"))
        org.junit.Assert.assertFalse(uri.contains("exit_loc="))
        org.junit.Assert.assertTrue(uri.endsWith("#MCI-WARP-IPv6"))

        val parsed = AetherSelection.fromAetherUri(uri, nowMs = 123456L)!!
        assertEquals(true, parsed.enabled)
        assertEquals(AetherMode.WireGuard, parsed.mode)
        assertEquals("[2606:4700:d0::a29f:c001]:2408", parsed.endpoint)
        assertEquals("v6", parsed.ipScan)
        assertEquals("DE", parsed.psiphonRegion)
        assertEquals("psiphon", parsed.chainTarget)
        assertEquals("foreign", parsed.exitLocation)
        assertEquals("MCI-WARP-IPv6", parsed.profileName)

        val foreignGool = AetherSelection(
            enabled = true,
            mode = AetherMode.WireGuard,
            endpoint = "162.159.192.156:945",
            ipScan = "v4",
            profileName = "Auto-Gool-WARP",
            chainTarget = "none",
            exitLocation = "foreign",
        )
        val goolUri = foreignGool.toAetherUri()
        org.junit.Assert.assertTrue(goolUri.startsWith("aether://?"))
        org.junit.Assert.assertTrue(goolUri.contains("protocol=gool"))
        org.junit.Assert.assertTrue(goolUri.contains("outer=162.159.192.156%3A945"))
        org.junit.Assert.assertFalse(goolUri.contains("exit_loc="))
        val parsedGool = AetherSelection.fromAetherUri(goolUri, nowMs = 555L)!!
        assertEquals("foreign", parsedGool.exitLocation)
        assertEquals("162.159.192.156:945", parsedGool.endpoint)

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
        org.junit.Assert.assertTrue(iranUri.startsWith("aether://162.159.198.1:443?"))
        org.junit.Assert.assertTrue(iranUri.contains("protocol=masque"))
        org.junit.Assert.assertTrue(iranUri.contains("fragment_size=16-32"))
        org.junit.Assert.assertTrue(iranUri.contains("fragment_delay=2-10"))
        org.junit.Assert.assertFalse(iranUri.contains("exit_loc="))
        val parsedIran = AetherSelection.fromAetherUri(iranUri, nowMs = 999L)!!
        assertEquals("iran", parsedIran.exitLocation)
        assertEquals("162.159.198.1:443", parsedIran.endpoint)
        assertEquals(AetherMode.MasqueHttp2, parsedIran.mode)

        // Backward compatibility with legacy query-only URIs
        val legacy = AetherSelection.fromAetherUri(
            "aether://?protocol=wg&ip=both&scan=balanced&endpoint=162.159.192.156%3A945&cfil=psiphon&chain=psiphon&region=US&exit_loc=!IR#Auto-US-WARP",
            nowMs = 777L,
        )!!
        assertEquals("162.159.192.156:945", legacy.endpoint)
        assertEquals("psiphon", legacy.chainTarget)
        assertEquals("US", legacy.psiphonRegion)
        assertEquals("foreign", legacy.exitLocation)
    }

    @Test fun smartRecommendationsProvideActionableCombosForEachTopic() {
        val mciHint = AetherSelection(mode = AetherMode.WireGuard, ipScan = "v6").smartRecommendation("mci")
        assertEquals("psiphon", mciHint.recommendedChain)
        assertEquals("foreign", mciHint.recommendedExitLocation)

        val iranHint = AetherSelection(mode = AetherMode.WireGuard, exitLocation = "iran").smartRecommendation("iran")
        assertEquals("iran", iranHint.recommendedExitLocation)
        assertEquals("none", iranHint.recommendedChain)

        val psiphonHint = AetherSelection(mode = AetherMode.WireGuard, chainTarget = "psiphon", psiphonRegion = "NL").smartRecommendation("psiphon")
        assertEquals("NL", psiphonHint.recommendedPsiphonRegion)
        assertEquals(AetherMode.WireGuard, psiphonHint.recommendedMode)
    }
}

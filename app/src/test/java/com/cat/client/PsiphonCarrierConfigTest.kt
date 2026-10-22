package com.cat.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class PsiphonCarrierConfigTest {
    @Test fun carrierIsProxyOnlyLoopbackAndBounded() {
        val json = JSONObject(PsiphonCarrierConfig.render(File("/tmp/psiphon"), 185, 1819, "DE"))
        assertEquals("socks5://127.0.0.1:1819", json.getString("UpstreamProxyUrl"))
        assertEquals("DE", json.getString("EgressRegion"))
        assertEquals(0, json.getInt("LocalSocksProxyPort"))
        assertEquals(true, json.getBoolean("DisableLocalHTTPProxy"))
        assertFalse(json.has("VpnMode"))
    }

    @Test fun invalidRegionPortAndRelativeStorageFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            PsiphonCarrierConfig.render(File("relative"), 1, 1819, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PsiphonCarrierConfig.render(File("/tmp/p"), 1, 80, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PsiphonCarrierConfig.render(File("/tmp/p"), 1, 1819, "Germany")
        }
    }

    @Test fun defaultRegionsListAndLabelsCover29Countries() {
        assertEquals(29, PsiphonCarrierConfig.DEFAULT_REGIONS.size)
        org.junit.Assert.assertTrue(PsiphonCarrierConfig.DEFAULT_REGIONS.containsAll(listOf("DE", "NL", "FI", "US", "GB", "FR", "CH")))
        org.junit.Assert.assertTrue(PsiphonCarrierConfig.regionDisplayLabel("DE", true).contains("آلمان"))
        org.junit.Assert.assertTrue(PsiphonCarrierConfig.regionDisplayLabel("DE", false).contains("Germany"))
        assertEquals("🇩🇪 آلمان (DE)", PsiphonCarrierConfig.regionShortLabel("DE", true))
    }
}

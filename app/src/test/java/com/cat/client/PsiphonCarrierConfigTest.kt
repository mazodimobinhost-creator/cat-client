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
}

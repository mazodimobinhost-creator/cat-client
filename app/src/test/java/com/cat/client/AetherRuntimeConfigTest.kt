package com.cat.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AetherRuntimeConfigTest {
    @Test fun createsBoundedTunConfigsForNativeTransports() {
        val h2 = JSONObject(AetherRuntimeConfig(AetherMode.MasqueHttp2, File("/tmp/aether.json")).toNativeJson())
        assertEquals("tun", h2.getString("mode"))
        assertEquals("h2", h2.getString("transport"))
        assertEquals("both", h2.getString("ipScan"))
        assertTrue(h2.getBoolean("validationEnabled"))
        assertFalse(h2.getBoolean("autoReprovision"))
        assertFalse(h2.getBoolean("lanSharing"))

        val wom = JSONObject(AetherRuntimeConfig(
            AetherMode.WireGuardOverMasque,
            File("/tmp/aether-wom.json"),
            "1.1.1.1:443",
        ).toNativeJson())
        assertEquals("wom", wom.getString("transport"))
        assertEquals("1.1.1.1:443", wom.getString("peer"))

        val provision = JSONObject(AetherRuntimeConfig(
            AetherMode.MasqueHttp2,
            File("/tmp/aether.json"),
            upstreamProxy = "socks5://127.0.0.1:7890",
        ).toNativeJson())
        assertEquals("socks5://127.0.0.1:7890", provision.getString("upstreamProxy"))
    }

    @Test fun refusesUnreadyCarrierUnsafePathAndInvalidEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            AetherRuntimeConfig(AetherMode.MasquePsiphon, File("/tmp/aether.json"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AetherRuntimeConfig(AetherMode.MasqueHttp2, File("relative.json"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AetherRuntimeConfig(AetherMode.MasqueHttp2, File("/tmp/aether.json"), "bad endpoint")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AetherRuntimeConfig(AetherMode.MasqueHttp2, File("/tmp/aether.json"), "192.168.1.1:443")
        }
    }
}

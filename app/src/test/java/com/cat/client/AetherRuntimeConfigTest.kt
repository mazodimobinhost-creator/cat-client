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

        val wgV6 = JSONObject(AetherRuntimeConfig(
            AetherMode.WireGuard,
            File("/tmp/aether-wg.json"),
            "[2606:4700:d0::a29f:c001]:2408",
            ipScan = "v6",
        ).toNativeJson())
        assertEquals("wg", wgV6.getString("transport"))
        assertEquals("v6", wgV6.getString("ipScan"))
        assertEquals("[2606:4700:d0::a29f:c001]:2408", wgV6.getString("peer"))
        assertTrue(wgV6.getBoolean("peerFallback"))

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

        val proxy = JSONObject(AetherRuntimeConfig(
            AetherMode.MasqueHttp2,
            File("/tmp/aether.json"),
            runtimeEndpoint = AetherRuntimeEndpoint.Proxy,
            listenPort = 1819,
        ).toNativeJson())
        assertEquals("proxy", proxy.getString("mode"))
        assertEquals(1819, proxy.getInt("listenPort"))

        // Foreign exit location upgrades WireGuard to wiw (WARP-in-WARP) and MasqueHttp2 to wom
        val wgForeign = JSONObject(AetherRuntimeConfig(
            AetherMode.WireGuard,
            File("/tmp/aether-wg-foreign.json"),
            "[2606:4700:d0::a29f:c001]:2408",
            ipScan = "v6",
            exitLocation = "foreign",
        ).toNativeJson())
        assertEquals("wiw", wgForeign.getString("transport"))
        assertEquals("v6", wgForeign.getString("ipScan"))

        val h2Foreign = JSONObject(AetherRuntimeConfig(
            AetherMode.MasqueHttp2,
            File("/tmp/aether-h2-foreign.json"),
            exitLocation = "foreign",
        ).toNativeJson())
        assertEquals("wom", h2Foreign.getString("transport"))

        // Iran exit location forces single-hop transport so exit IP remains IR
        val womIran = JSONObject(AetherRuntimeConfig(
            AetherMode.WireGuardOverMasque,
            File("/tmp/aether-iran.json"),
            exitLocation = "iran",
        ).toNativeJson())
        assertEquals("h2", womIran.getString("transport"))

        // PattNG MASQUE-in-MASQUE (mim) with HTTP/2 framing
        val mimCfg = AetherRuntimeConfig(
            mode = AetherMode.MasqueInMasque,
            identityFile = File("/tmp/aether-mim.toml"),
            endpoint = "162.159.198.1:443",
            innerEndpoint = "162.159.198.2:443",
            ipScan = "v4",
            masqueTransport = "h2",
        )
        val mimJson = JSONObject(mimCfg.toNativeJson())
        assertEquals("mim", mimJson.getString("transport"))
        assertEquals("162.159.198.1:443", mimJson.getString("peer"))
        assertTrue(mimCfg.usesMasqueHttp2)

        // WARP-in-WARP (wiw)
        val wiwJson = JSONObject(AetherRuntimeConfig(
            mode = AetherMode.WarpInWarp,
            identityFile = File("/tmp/aether-wiw.toml"),
            endpoint = "162.159.192.1:2408",
            innerEndpoint = "162.159.193.1:2408",
            ipScan = "v4",
        ).toNativeJson())
        assertEquals("wiw", wiwJson.getString("transport"))
        assertEquals("162.159.192.1:2408", wiwJson.getString("peer"))
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

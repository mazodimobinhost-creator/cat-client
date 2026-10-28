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

        val mimCfg = AetherRuntimeConfig(
            mode = AetherMode.MasqueInMasque,
            identityFile = File("/tmp/aether-mim.toml"),
            endpoint = "162.159.198.1:443",
            innerEndpoint = "162.159.198.2:443",
            ipScan = "v4",
            masqueTransport = "h2",
            scanMode = "ironclad",
            noizePreset = "aggressive",
            fingerprint = "chrome-120",
            masqueSni = "custom-masque.cloudflareclient.com",
            echEnabled = true,
            echDns = "https://8.8.8.8/dns-query",
            echDomain = "cloudflare-ech.com",
        )
        val mimJson = JSONObject(mimCfg.toNativeJson())
        assertEquals("mim", mimJson.getString("transport"))
        assertEquals("162.159.198.1:443", mimJson.getString("peer"))
        assertEquals("162.159.198.2:443", mimJson.getString("innerPeer"))
        assertEquals("ironclad", mimJson.getString("scan"))
        assertEquals("aggressive", mimJson.getString("noize"))
        assertEquals("chrome-120", mimJson.getString("fingerprint"))
        assertEquals("custom-masque.cloudflareclient.com", mimJson.getString("masque_sni"))
        assertEquals("custom-masque.cloudflareclient.com", mimJson.getString("masqueSni"))
        assertTrue(mimJson.getBoolean("ech"))
        // chrome-120 capture: no ML-KEM, old ALPS codepoint, no GREASE among sigalgs, GREASE ECH.
        assertEquals("X25519:P-256:P-384", mimJson.getString("tlsGroups"))
        assertEquals("old", mimJson.getString("tlsAlps"))
        assertFalse(mimJson.getBoolean("enableGreaseSigalgs"))
        assertTrue(mimJson.getBoolean("enableEchGrease"))
        assertFalse(mimJson.has("tlsKeyShares"))
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

    @Test fun beta206DefaultsToPattngChromeAndNeverSendsAllGroupsAsKeyShares() {
        val json = JSONObject(AetherRuntimeConfig(AetherMode.MasqueInMasque, File("/tmp/a.toml")).toNativeJson())
        assertEquals("chrome", json.getString("fingerprint"))
        assertEquals("X25519MLKEM768:X25519:P-256:P-384", json.getString("tlsGroups"))
        assertFalse("Chrome sends two key shares, chosen by BoringSSL", json.has("tlsKeyShares"))
        assertEquals("new", json.getString("tlsAlps"))
        assertEquals("6000", json.getString("tlsServerPadding"))
        assertTrue(json.getBoolean("enableGreaseSigalgs"))
        assertFalse(json.getBoolean("disableGrease"))
        val firefox = JSONObject(AetherRuntimeConfig(AetherMode.MasqueHttp2, File("/tmp/a.toml"), fingerprint = "firefox").toNativeJson())
        assertEquals("X25519MLKEM768:X25519:P-256", firefox.getString("tlsKeyShares"))
        assertTrue(firefox.getBoolean("disableSessionTicket"))
        assertEquals("chrome", AetherTlsFingerprint.of("nonsense").type)
    }

    @Test fun beta206DropsValuesAether24WouldRejectTheWholeConfigFor() {
        val json = JSONObject(AetherRuntimeConfig(
            AetherMode.MasqueHttp2,
            File("/tmp/a.toml"),
            masqueSni = "162.159.198.1",
            apiHost = "https://api.example.com/x",
            apiSni = "www.cloudflare.com.",
            fragmentSize = "900000",
            fragmentDelay = "10-2",
        ).toNativeJson())
        assertFalse(json.has("masqueSni"))
        assertFalse(json.has("apiHost"))
        assertEquals("www.cloudflare.com", json.getString("apiSni"))
        assertEquals("16-32", json.getString("fragmentSize"))
        assertEquals("2-10", json.getString("fragmentDelay"))
        assertEquals("5-40", AetherRuntimeConfig.safeRange(" 5-40 ", 1, 16_384))
        assertEquals(null, AetherRuntimeConfig.safeTlsName("1.2.3.4"))
        assertEquals("www.cloudflare.com", AetherRuntimeConfig.safeTlsName("www.cloudflare.com"))
    }

    @Test fun beta206PattngLinkWithoutFragmentMeansNoFragmentAndIsKeptVerbatim() {
        val link = "aether://?protocol=mim&scan=balanced&ip=v4&transport=h2&outer=162.159.198.1%3A443&inner=162.159.198.2%3A443#PattNG-MIM-H2-DE"
        val sel = AetherSelection.fromAetherUri(link)!!
        assertEquals(AetherMode.MasqueInMasque, sel.mode)
        assertFalse(sel.fragmentEnabled)
        assertTrue(AetherSelection.fromAetherUri(link.replace("#", "&fragment=1#"))!!.fragmentEnabled)
        val converted = SubConvConverter.convert(link)
        assertEquals(link, converted.single().getString("aether-uri"))
    }
}

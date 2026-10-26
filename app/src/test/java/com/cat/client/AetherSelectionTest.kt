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

        val autoChain = AetherSelection(
            enabled = true,
            mode = AetherMode.WireGuard,
            endpoint = "162.159.192.1:2408",
            ipScan = "both",
            psiphonRegion = "DE",
            profileName = "Auto-Conditional-Chain",
            chainTarget = "auto-psiphon",
            exitLocation = "foreign",
        )
        val autoUri = autoChain.toAetherUri()
        org.junit.Assert.assertTrue(autoUri.contains("psiphon=auto"))
        val parsedAuto = AetherSelection.fromAetherUri(autoUri, nowMs = 888L)!!
        assertEquals("auto-psiphon", parsedAuto.chainTarget)
        assertEquals("foreign", parsedAuto.effectiveExitLocation())
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

        val mimHint = AetherSelection(mode = AetherMode.MasqueInMasque, psiphonRegion = "DE").smartRecommendation("mim")
        assertEquals(AetherMode.MasqueInMasque, mimHint.recommendedMode)
        assertEquals("psiphon", mimHint.recommendedChain)
        assertEquals("DE", mimHint.recommendedPsiphonRegion)
    }

    @Test fun pattNgMasqueInMasqueWithHttp2AndPsiphonRoundTripsAndConvertsToMihomo() {
        val mimSelection = AetherSelection(
            enabled = true,
            mode = AetherMode.MasqueInMasque,
            endpoint = "162.159.198.1:443",
            innerEndpoint = "162.159.198.2:443",
            ipScan = "v4",
            psiphonRegion = "DE",
            profileName = "PattNG-MIM-Psiphon-DE",
            chainTarget = "psiphon",
            exitLocation = "foreign",
            masqueTransport = "h2",
        )
        assertEquals(AetherMode.MasqueInMasque, mimSelection.effectiveCarrierMode())
        assertEquals(
            AetherMode.MasqueInMasque,
            AetherSelection.simplifyModeForChain(AetherMode.MasqueInMasque, "psiphon"),
        )
        val uri = mimSelection.toAetherUri()
        org.junit.Assert.assertTrue(uri.startsWith("aether://?"))
        org.junit.Assert.assertTrue(uri.contains("protocol=mim"))
        org.junit.Assert.assertTrue(uri.contains("transport=h2"))
        org.junit.Assert.assertTrue(uri.contains("outer=162.159.198.1%3A443"))
        org.junit.Assert.assertTrue(uri.contains("inner=162.159.198.2%3A443"))
        org.junit.Assert.assertTrue(uri.contains("psiphon=chain"))
        org.junit.Assert.assertTrue(uri.contains("region=DE"))

        val parsed = AetherSelection.fromAetherUri(uri, nowMs = 321000L)!!
        assertEquals(AetherMode.MasqueInMasque, parsed.mode)
        assertEquals("h2", parsed.masqueTransport)
        assertEquals("162.159.198.1:443", parsed.endpoint)
        assertEquals("162.159.198.2:443", parsed.innerEndpoint)
        assertEquals("psiphon", parsed.chainTarget)
        assertEquals("DE", parsed.psiphonRegion)
        assertEquals("PattNG-MIM-Psiphon-DE", parsed.profileName)

        // SubConvConverter & MihomoShareLink round-trip for main config list
        val converted = SubConvConverter.convert(uri)
        assertEquals(1, converted.size)
        assertEquals("PattNG-MIM-Psiphon-DE", converted[0].getString("name"))
        assertEquals("socks5", converted[0].getString("type"))
        org.junit.Assert.assertTrue(converted[0].getString("aether-uri").contains("protocol=mim"))

        // Reverse chain (Psiphon > WARP) + Scan/Noize/CDN/Tor Bridges round-trip
        val reverseSelection = AetherSelection(
            enabled = true,
            mode = AetherMode.MasqueInMasque,
            endpoint = "162.159.198.1:443",
            innerEndpoint = "162.159.198.2:443",
            ipScan = "v4",
            psiphonRegion = "NL",
            profileName = "PattNG-Reverse-NL",
            chainTarget = "psiphon-reverse",
            exitLocation = "foreign",
            masqueTransport = "h2",
            scanMode = "ironclad",
            noizePreset = "firewall",
            fingerprint = "chrome-120",
            psiphonMode = "cdn",
            psiphonCdnIps = "104.16.1.1,104.16.1.2",
            psiphonCdnSni = "cdn.psiphon.ca",
            torBridgesMode = "first",
        )
        val revUri = reverseSelection.toAetherUri()
        org.junit.Assert.assertTrue(revUri.contains("psiphon=reverse"))
        org.junit.Assert.assertTrue(revUri.contains("psiphon_mode=cdn"))
        org.junit.Assert.assertTrue(revUri.contains("scan=ironclad"))
        org.junit.Assert.assertTrue(revUri.contains("noize=firewall"))
        org.junit.Assert.assertTrue(revUri.contains("fingerprint=chrome-120"))
        val parsedRev = AetherSelection.fromAetherUri(revUri, nowMs = 999000L)!!
        assertEquals("psiphon-reverse", parsedRev.chainTarget)
        assertEquals("NL", parsedRev.psiphonRegion)
        assertEquals("ironclad", parsedRev.scanMode)
        assertEquals("firewall", parsedRev.noizePreset)
        assertEquals("chrome-120", parsedRev.fingerprint)
        assertEquals("cdn", parsedRev.psiphonMode)
        assertEquals("104.16.1.1,104.16.1.2", parsedRev.psiphonCdnIps)
        assertEquals("cdn.psiphon.ca", parsedRev.psiphonCdnSni)
        assertEquals("first", parsedRev.torBridgesMode)
    }

    @Test fun shadowsocks2022PreservesPlusInBase64KeyAndRejectsMalformedKey() {
        // 32-byte key in standard base64 containing '+' at index 34:
        // "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+AAAAAAAA=" (32 bytes = 44 base64 chars)
        val validKey32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+AAAAAAAA="
        val ss2022Uri = "ss://2022-blake3-aes-256-gcm:$validKey32@206.1.100.83:25178#SS2022-Valid"
        val converted = SubConvConverter.convert(ss2022Uri)
        assertEquals(1, converted.size)
        assertEquals("2022-blake3-aes-256-gcm", converted[0].getString("cipher"))
        assertEquals(validKey32, converted[0].getString("password"))

        // Malformed SS-2022 key (not 32 bytes) must be rejected so Mihomo doesn't fail the whole subscription
        val invalidSs2022 = "ss://2022-blake3-aes-256-gcm:not-a-32-byte-base64-key@206.1.100.83:25178#SS2022-Bad"
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SubConvConverter.convert(invalidSs2022)
        }
    }

    @Test fun multiChunkSniSplitAndRealConfigTargetRecovery() {
        val ch = LocalSniSpoofProxy.buildFakeClientHelloRecord("my-secret-worker.user.workers.dev")
        val chunks = LocalSniSpoofProxy.splitClientHelloMultiChunkAtSni(ch)
        org.junit.Assert.assertTrue(chunks.size >= 4)
        // Reconstructed payloads must equal the original ClientHello payload byte-for-byte
        val origPayload = ch.copyOfRange(5, ch.size)
        val joined = java.io.ByteArrayOutputStream()
        for (rec in chunks) {
            assertEquals(0x16, rec[0].toInt() and 0xFF)
            val len = ((rec[3].toInt() and 0xFF) shl 8) or (rec[4].toInt() and 0xFF)
            assertEquals(rec.size - 5, len)
            joined.write(rec, 5, len)
        }
        org.junit.Assert.assertArrayEquals(origPayload, joined.toByteArray())

        // Recover real remoteHost:remotePort from 127.0.0.1 link carrying sni_fwd_target
        val repairedUri = "vless://a1b2c3d4-e5f6-4789-abcd-ef0123456789@127.0.0.1:41922?encryption=none&security=tls&type=ws&host=my.worker.dev&path=%2Fws&sni=my.worker.dev&sni_fwd_target=188.114.98.0%3A2053#Repaired"
        val tpl = LocalSniSpoofProxy.extractRealConfigTemplate(listOf(repairedUri))!!
        assertEquals("188.114.98.0", tpl.remoteHost)
        assertEquals(2053, tpl.remotePort)
        assertEquals("my.worker.dev", tpl.hostHeader)
    }

    @Test fun fourPowerFeaturesHotspotCleanerAndGamingBooster() {
        val parsed = LocalSniSpoofProxy.extractHostAndPortFromShareUri(
            "vless://a1b2c3d4-e5f6-4789-abcd-ef0123456789@188.114.98.0:2053?security=tls&sni=speed.cloudflare.com#Test",
        )!!
        assertEquals("188.114.98.0", parsed.first)
        assertEquals(2053, parsed.second)
        assertEquals("speed.cloudflare.com", parsed.third)

        val lanIps = LocalSniSpoofProxy.detectDeviceLanIpAddresses()
        org.junit.Assert.assertTrue(lanIps.isNotEmpty())

        // Test unpadded URL-safe Base64 VMess parsing and ping annotation without corrupting JSON
        val rawVmessJson = """{"v":"2","ps":"MyNode","add":"162.159.198.235","port":"2083","id":"a1b2c3d4-e5f6-4789-abcd-ef0123456789","aid":"0","net":"ws","type":"none","host":"my.worker.dev","path":"/ws","tls":"tls","sni":"speed.cloudflare.com"}"""
        val urlSafeUnpadded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(rawVmessJson.toByteArray(Charsets.UTF_8))
        val vmessUri = "vmess://$urlSafeUnpadded"
        val vmessParsed = LocalSniSpoofProxy.extractHostAndPortFromShareUri(vmessUri)!!
        assertEquals("162.159.198.235", vmessParsed.first)
        assertEquals(2083, vmessParsed.second)
        assertEquals("speed.cloudflare.com", vmessParsed.third)

        val annotatedVmess = LocalSniSpoofProxy.annotateShareUriWithPing(vmessUri, 64L)
        org.junit.Assert.assertTrue(annotatedVmess.startsWith("vmess://"))
        org.junit.Assert.assertFalse(annotatedVmess.contains("#"))
        val decodedAfter = String(LocalSniSpoofProxy.decodeLenientBase64(annotatedVmess.removePrefix("vmess://"))!!, Charsets.UTF_8)
        org.junit.Assert.assertTrue(decodedAfter.contains("(64ms) MyNode"))

        val port = LocalSniSpoofProxy.startLanHotspotShareProxy(0)
        org.junit.Assert.assertTrue(port > 0)
        org.junit.Assert.assertTrue(LocalSniSpoofProxy.isLanHotspotProxyRunning())
        LocalSniSpoofProxy.stopLanHotspotShareProxy()
        org.junit.Assert.assertFalse(LocalSniSpoofProxy.isLanHotspotProxyRunning())

        // Test Serverless-v52 fragA & fragB 2-stage ClientHello record splitting byte fidelity
        val ch = LocalSniSpoofProxy.buildFakeClientHelloRecord("challenges.cloudflare.com")
        val origPayload = ch.copyOfRange(5, ch.size)
        for (variant in listOf("fragA", "fragB")) {
            val v52Chunks = LocalSniSpoofProxy.splitClientHelloServerlessV52(ch, variant)
            org.junit.Assert.assertTrue(v52Chunks.size >= 3)
            val reassembled = java.io.ByteArrayOutputStream()
            for (rec in v52Chunks) {
                assertEquals(0x16, rec[0].toInt() and 0xFF)
                val len = ((rec[3].toInt() and 0xFF) shl 8) or (rec[4].toInt() and 0xFF)
                assertEquals(rec.size - 5, len)
                reassembled.write(rec, 5, len)
            }
            org.junit.Assert.assertArrayEquals(origPayload, reassembled.toByteArray())
        }

        val v52Json = LocalSniSpoofProxy.buildServerlessV52ConfigJson("fragA")
        org.junit.Assert.assertTrue(v52Json.contains("Serverless-v52-fragA"))
        org.junit.Assert.assertTrue(v52Json.contains("challenges.cloudflare.com"))

        val cottenToml = LocalSniSpoofProxy.buildCottenMaxClientConfigToml("t.whitedns.ir", "0123456789abcdef", emptyList())
        org.junit.Assert.assertTrue(cottenToml.contains("TUNNEL_MODE = \"cottenmax\""))
        org.junit.Assert.assertTrue(cottenToml.contains("RESOLVER_TRANSPORT = \"auto\""))
    }
}

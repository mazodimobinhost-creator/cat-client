package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteDnsSmartScannerTest {
    @Test fun cloudflarePortSniHitPreservesExactVerifiedPortForIpv4AndIpv6() {
        val v4Hit = CloudflarePortSniHit("188.114.98.0", 2053, "speed.cloudflare.com", 62L)
        assertEquals("188.114.98.0:2053", v4Hit.endpoint)

        val v6Hit = CloudflarePortSniHit("[2a06:98c1:3101::6812:202f]", 8443, "www.cloudflare.com", 75L)
        assertEquals("[2a06:98c1:3101::6812:202f]:8443", v6Hit.endpoint)

        assertEquals(listOf(443, 2053, 2083, 2087, 2096, 8443), WhiteDnsSmartScanner.CLOUDFLARE_TLS_PORTS)
    }

    @Test fun dnsPacketAndResponseValidationWorkCorrectly() {
        val pkt = WhiteDnsSmartScanner.buildDnsQueryPacket(0x4341)
        assertTrue(pkt.size > 12)
        // Query has QR=0 so it is not a response
        assertFalse(WhiteDnsSmartScanner.isValidDnsResponse(pkt, pkt.size, 0x4341))

        val validResp = pkt.copyOf().also { it[2] = 0x81.toByte() }
        assertTrue(WhiteDnsSmartScanner.isValidDnsResponse(validResp, validResp.size, 0x4341))
        assertFalse(WhiteDnsSmartScanner.isValidDnsResponse(validResp, validResp.size, 0x1234))
    }

    @Test fun dnsttBridgeBuilderUsesVerifiedIranResolver() {
        val lines = WhiteDnsSmartScanner.buildDnsttBridgesForResolver("185.212.51.144")
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.contains("udp=185.212.51.144:53") })

        val dotLines = WhiteDnsSmartScanner.buildDnsttBridgesForResolver("178.22.122.100", "dot")
        assertTrue(dotLines.all { it.contains("dot=178.22.122.100:853") })

        val dohLines = WhiteDnsSmartScanner.buildDnsttBridgesForResolver("178.22.122.100", "doh")
        assertTrue(dohLines.all { it.contains("doh=https://178.22.122.100/dns-query") })
    }

    @Test fun whiteDnsTunnelBundleAndCatalogSupportAllTransportsRecordsAndIranPresets() {
        assertTrue(WhiteDnsSmartScanner.RESOLVER_CATALOG.size >= 16)
        assertEquals(6, WhiteDnsSmartScanner.IRAN_AUTO_TUNE_PRESETS.size)

        val tcpFrame = WhiteDnsSmartScanner.buildTcpDnsQueryFrame(0x4341, qtype = 65)
        val udpPkt = WhiteDnsSmartScanner.buildDnsQueryPacket(0x4341, qtype = 65)
        assertEquals(udpPkt.size + 2, tcpFrame.size)
        assertEquals(udpPkt.size, ((tcpFrame[0].toInt() and 0xff) shl 8) or (tcpFrame[1].toInt() and 0xff))

        val bundle = WhiteDnsSmartScanner.buildWhiteDnsTunnelBundle(
            resolverIp = "178.22.122.100",
            transport = "auto",
            recordMode = WhiteDnsRecordMode.TXT_HTTPS,
            preset = WhiteDnsSmartScanner.IRAN_AUTO_TUNE_PRESETS.first(),
        )
        assertTrue(bundle.stormDnsUri.startsWith("stormdns://178.22.122.100:53/"))
        assertTrue(bundle.stormDnsUri.contains("delivery=txt-https"))
        assertTrue(bundle.stormDnsUri.contains("preset=iran-fast-low-mtu"))
        assertTrue(bundle.cottenMaxUri.startsWith("cottendns://178.22.122.100:53/?mode=cottenmax"))
        assertTrue(bundle.cottenMaxUri.contains("fec=4:1"))
        assertTrue(bundle.soarUri.startsWith("soar://178.22.122.100:53/?profile=IR"))
    }

    @Test fun socks5HubBuiltInSeedsAndCountryParserPreserveExplicitPortsAndCountryCodes() {
        assertTrue(ProxyScannerPage.BUILTIN_SOCKS5_SEEDS.size >= 50)
        assertTrue(ProxyScannerPage.BUILTIN_SOCKS5_SEEDS.all { ':' in it })
        assertEquals("US", ProxyScannerPage.countryCodeFromNameOrIso("United States"))
        assertEquals("DE", ProxyScannerPage.countryCodeFromNameOrIso("Germany"))
        assertEquals("NL", ProxyScannerPage.countryCodeFromNameOrIso("Netherlands"))
        assertEquals("SG", ProxyScannerPage.countryCodeFromNameOrIso("Singapore"))
        assertEquals("FR", ProxyScannerPage.countryCodeFromNameOrIso("fr"))
    }

    @Test fun moatBridgesJsonParserExtractsBridgeStrings() {
        val sampleJson = """
            {"settings":[
              {"bridges":{"type":"obfs4","bridge_strings":["obfs4 1.2.3.4:443 ABC cert=XYZ iat-mode=0","obfs4 5.6.7.8:8443 DEF cert=UVW iat-mode=1"]}}
            ]}
        """.trimIndent()
        val bridges = AetherOperations.parseMoatBridgesJson(sampleJson)
        assertEquals(2, bridges.size)
        assertTrue(bridges.first().startsWith("obfs4 1.2.3.4:443"))
    }

    @Test fun proguardRulesKeepAetherJniCallbacksAgainstR8Stripping() {
        val candidates = listOf(
            java.io.File("proguard-rules.pro"),
            java.io.File("app/proguard-rules.pro"),
        )
        val rulesFile = candidates.firstOrNull { it.isFile } ?: error("proguard-rules.pro not found")
        val rules = rulesFile.readText()
        assertTrue(rules.contains("com.whitedns.whiteaesther.core.NativeSocketProtector"))
        assertTrue(rules.contains("protectSocket(int)"))
        assertTrue(rules.contains("com.whitedns.whiteaesther.core.NativeEngineListener"))
        assertTrue(rules.contains("onNativeReady()"))
    }
}

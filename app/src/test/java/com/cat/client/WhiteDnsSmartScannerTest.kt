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
}

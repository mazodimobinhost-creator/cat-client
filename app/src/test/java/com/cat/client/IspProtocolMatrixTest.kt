package com.cat.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IspProtocolMatrixTest {

    private val vless = "vless://a1b2c3d4-e5f6-4789-abcd-ef0123456789@104.16.1.1:443?encryption=none&security=tls&type=ws&host=w.example.com&sni=w.example.com&path=%2F#Worker-A"
    private val trojan = "trojan://secretpass@188.114.98.0:2053?security=tls&type=ws&host=w.example.com&sni=w.example.com&path=%2Ft#Trojan-B"
    private val hy2 = "hysteria2://pass@198.51.100.7:8443?sni=h.example.com#HY2-C"

    @Test fun catFusionRoundTripKeepsEveryLinkAndTitle() {
        val link = CatFusionLink.encode(listOf(vless, trojan, hy2, vless), "تانل من")
        assertNotNull(link)
        assertTrue(link!!.startsWith("catfusion://"))
        assertFalse(link.substringBefore('#').contains(' '))
        val decoded = CatFusionLink.decode(link)
        assertEquals(listOf(vless, trojan, hy2), decoded)
        assertEquals("تانل من", CatFusionLink.title(link))
    }

    @Test fun catFusionRejectsGarbageAndCapsSize() {
        assertNull(CatFusionLink.decode("catfusion://not-base64!!"))
        assertNull(CatFusionLink.decode("vless://x@y:1"))
        assertNull(CatFusionLink.encode(emptyList()))
        val many = (1..30).map { "socks5://10.0.0.$it:1080#S$it" }
        val decoded = CatFusionLink.decode(CatFusionLink.encode(many)!!)
        assertEquals(CatFusionLink.MAX_LINKS, decoded!!.size)
    }

    @Test fun catFusionIsUnderstoodByConverterAndImporter() {
        val link = CatFusionLink.encode(listOf(vless, trojan), "Fused")!!
        val proxies = SubConvConverter.convert("📤 از دوستم:\n$link")
        assertEquals(2, proxies.size)
        assertEquals(setOf("vless", "trojan"), proxies.map { it.optString("type") }.toSet())
        assertEquals(2, SubConvConverter.inspectUniversalConfigInput(link).totalCount)

        val imported = UserSubscriptionImporter.import(link)
        assertEquals(2, imported.connectionCount)
        assertTrue(imported.yaml.contains("type: load-balance"))
        assertTrue(imported.yaml.contains("type: fallback"))
    }

    @Test fun fusedBundleExportsShareUrisAndDedupesSameEndpoint() {
        val bundle = LocalSniSpoofProxy.buildFusedMultiTunnelSubscription(
            listOf("$vless\n$trojan", vless.replace("#Worker-A", "#Worker-A-copy")),
        )
        assertNotNull(bundle)
        assertEquals(2, bundle!!.totalProxiesFused)
        assertEquals(2, bundle.shareUris.size)
        assertTrue(bundle.shareUris.any { it.startsWith("vless://") && "104.16.1.1:443" in it })
        assertTrue(bundle.shareUris.any { it.startsWith("trojan://") && "188.114.98.0:2053" in it })
    }

    @Test fun fusionReadsCachedMihomoYamlSnapshots() {
        val yaml = MihomoLinkConfigBuilder.build(SubConvConverter.convert("$vless\n$hy2"))
        val proxies = IspProtocolMatrix.proxiesFromRaw(yaml)
        assertEquals(2, proxies.size)
        val vlessProxy = proxies.first { it.optString("type") == "vless" }
        assertEquals(443, vlessProxy.optInt("port"))
        assertEquals(IspProtocolMatrix.Family.TLS, IspProtocolMatrix.familyOf(vlessProxy))
        assertEquals(IspProtocolMatrix.Family.QUIC, IspProtocolMatrix.familyOf(proxies.first { it.optString("type") == "hysteria2" }))
    }

    @Test fun familiesFollowTheRealTransport() {
        fun fam(uri: String) = IspProtocolMatrix.familyOf(SubConvConverter.convert(uri).first())
        assertEquals(IspProtocolMatrix.Family.TLS, fam(vless))
        assertEquals(IspProtocolMatrix.Family.TLS, fam(trojan))
        assertEquals(IspProtocolMatrix.Family.TCP, fam("vless://a1b2c3d4-e5f6-4789-abcd-ef0123456789@1.2.3.4:80?encryption=none&security=none&type=ws&path=%2F#Plain"))
        assertEquals(IspProtocolMatrix.Family.TCP, fam("socks5://1.2.3.4:1080#S"))
        assertEquals(IspProtocolMatrix.Family.SSH, fam("ssh://root:pw@203.0.113.5:2222#SSH"))
        assertEquals(IspProtocolMatrix.Family.QUIC, fam(hy2))
        assertEquals(IspProtocolMatrix.Family.DNS_TUNNEL, IspProtocolMatrix.familyOf(JSONObject().put("type", "socks5").put("whitedns-uri", "dnstt://1.1.1.1:53/t.example.com")))
        assertEquals(IspProtocolMatrix.Family.WIREGUARD, IspProtocolMatrix.familyOf(JSONObject().put("type", "wireguard")))
    }

    @Test fun unverifiableFamiliesAreNeverReportedGreen() {
        val wg = JSONObject().put("name", "WG").put("type", "wireguard").put("server", "162.159.192.1").put("port", 2408)
        val row = IspProtocolMatrix.probeConfig("", wg, timeoutMs = 200)
        assertNull(row.ok)
    }

    @Test fun dnsQueryWireFormatIsValid() {
        val q = IspProtocolMatrix.buildDnsQuery(0x1234, "abc.t.example.com", IspProtocolMatrix.QTYPE_TXT)
        assertEquals(0x12, q[0].toInt() and 0xFF)
        assertEquals(0x34, q[1].toInt() and 0xFF)
        assertEquals(1, q[5].toInt()) // QDCOUNT
        assertEquals(3, q[12].toInt()) // "abc"
        assertEquals(16, q[q.size - 3].toInt()) // TXT
        assertEquals(1, q[q.size - 1].toInt()) // IN
        val resp = q.copyOf()
        resp[2] = (resp[2].toInt() or 0x80).toByte()
        assertTrue(IspProtocolMatrix.isDnsResponseFor(0x1234, resp, resp.size))
        assertFalse(IspProtocolMatrix.isDnsResponseFor(0x1234, q, q.size)) // a query is not a response
        assertFalse(IspProtocolMatrix.isDnsResponseFor(0x9999, resp, resp.size))
    }

    @Test fun quicProbeIsAValidLongHeaderAndVersionNegotiationIsRecognised() {
        val pkt = IspProtocolMatrix.buildQuicVersionProbe(java.util.Random(7))
        assertEquals(1200, pkt.size)
        assertEquals(0xC0, pkt[0].toInt() and 0xC0)
        assertEquals(listOf(0x1a, 0x2a, 0x3a, 0x4a), (1..4).map { pkt[it].toInt() })
        assertEquals(8, pkt[5].toInt())
        val vn = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 8, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 0, 1)
        assertTrue(IspProtocolMatrix.isQuicVersionNegotiation(vn, vn.size))
        assertFalse(IspProtocolMatrix.isQuicVersionNegotiation(pkt, pkt.size))
    }

    @Test fun verdictExplainsUdpBlockAndNationalIntranet() {
        fun b(key: String, ok: Boolean) = IspProtocolMatrix.BaselineRow(key, key, key, ok, if (ok) 50 else null, "")
        val report = IspProtocolMatrix.Report(
            baseline = listOf(b("ir-tls", true), b("cf-tls", false), b("google-tls", false), b("quic", false),
                b("dns-udp-foreign", false), b("dns-udp-ir", true), b("dns-tcp", false), b("doh", false), b("ipv6", false)),
            configs = emptyList(),
            durationMs = 1000,
        )
        val v = report.verdict(isFarsi = true).joinToString("\n")
        assertTrue(v.contains("نت ملی"))
        assertTrue(v.contains("UDP/QUIC"))
        assertTrue(v.contains("ریزولور داخلی"))
    }
}

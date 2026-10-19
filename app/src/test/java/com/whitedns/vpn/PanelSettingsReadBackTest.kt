package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelSettingsReadBackTest {
    private val now = 5_000L

    private fun proxyEndpoint(host: String, port: Int) =
        VerifiedEndpoint(host, port, EndpointRole.PROXY_IP, EndpointFamily.IPV4, EndpointVerdict.VERIFIED, now, source = "app")

    @Test fun exactUserProxyIpPortsMustAppearInReadBack() {
        val p11111 = proxyEndpoint("103.237.102.191", 11111)
        val p3118 = proxyEndpoint("184.75.221.82", 3118)
        val before = """{"ok":true,"kv":true,"settings":{"ips":[],"proxyIps":[]}}"""
        val after = """{"ok":true,"persisted":true,"settings":{"ips":[],"proxyIps":["103.237.102.191:11111","184.75.221.82:3118"]}}"""
        val result = PanelSettingsReadBack.verifyBatch(listOf(p11111, p3118), before, after, now)
        assertEquals(2, result.saved)
        assertEquals(0, result.failed)
    }

    @Test fun portRewriteOrBareHostInReadBackFailsVerification() {
        val p11111 = proxyEndpoint("103.237.102.191", 11111)
        val rewritten = """{"ok":true,"persisted":true,"settings":{"proxyIps":["103.237.102.191:443","103.237.102.191"]}}"""
        val result = PanelSettingsReadBack.verifyBatch(listOf(p11111), null, rewritten, now)
        assertEquals(0, result.saved)
        assertEquals(1, result.failed)
        assertEquals(PanelEndpointSyncStatus.READ_BACK_MISMATCH, result.results.single().status)
    }

    @Test fun memoryOnlyWriteWithoutKvIsReportedAsNoKv() {
        val p11111 = proxyEndpoint("103.237.102.191", 11111)
        val unpersisted = """{"ok":true,"persisted":false,"settings":{"proxyIps":["103.237.102.191:11111"]}}"""
        val result = PanelSettingsReadBack.verifyBatch(listOf(p11111), null, unpersisted, now)
        assertEquals(0, result.saved)
        assertEquals(1, result.failed)
        assertEquals(PanelEndpointSyncStatus.NO_KV, result.results.single().status)
    }

    @Test fun parsesCleanIpsTypedRelaysIpv6AndChainsSeparately() {
        val raw = """
            {
              "ok": true,
              "kv": true,
              "ignored": ["junk"],
              "capped": ["extra:443"],
              "settings": {
                "ips": ["104.16.1.1:8443#DE", "[2606:4700::1]:2053#NL", "104.16.1.2"],
                "proxyIps": ["103.237.102.191:11111", "socks5h://user:pass@1.2.3.4:1080#SOCKS", "http://184.75.221.82:3118"],
                "chain": "trojan://secret@chain.example:2083?sni=worker.example#chain"
              }
            }
        """.trimIndent()
        val snap = PanelSettingsReadBack.parse(raw, now)!!
        assertEquals(listOf("junk"), snap.ignored)
        assertEquals(listOf("extra:443"), snap.capped)
        assertEquals(
            listOf(
                EndpointRole.CLEAN_IP to "104.16.1.1:8443",
                EndpointRole.CLEAN_IP to "[2606:4700::1]:2053",
                EndpointRole.PROXY_IP to "103.237.102.191:11111",
                EndpointRole.SOCKS5 to "1.2.3.4:1080",
                EndpointRole.HTTP_RELAY to "184.75.221.82:3118",
                EndpointRole.TROJAN_CHAIN to "chain.example:2083",
            ),
            snap.endpoints.map { it.role to it.authority },
        )
    }

    @Test fun malformedOrFailedJsonReturnsNull() {
        assertNull(PanelSettingsReadBack.parse("not-json", now))
        assertNull(PanelSettingsReadBack.parse("""{"ok":false}""", now))
    }
}

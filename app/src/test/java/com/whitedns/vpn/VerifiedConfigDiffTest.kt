package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VerifiedConfigDiffTest {
    private fun endpoint(host: String, port: Int, role: EndpointRole = EndpointRole.CLEAN_IP) =
        VerifiedEndpoint(host, port, role, EndpointFamily.IPV4, EndpointVerdict.VERIFIED, 1L, source = "test")

    @Test fun failoverPreviewShowsExactServerPortOnly() {
        val before = VerifiedConfigUri(TunnelProtocol.VLESS, "uuid", endpoint("1.1.1.1", 443),
            "worker.example", "/vl", proxyIp = endpoint("3.3.3.3", 11111, EndpointRole.PROXY_IP))
        val after = before.copy(endpoint = endpoint("2.2.2.2", 8443))
        assertEquals(listOf(
            ConfigFieldChange("server", "1.1.1.1", "2.2.2.2"),
            ConfigFieldChange("port", "443", "8443"),
        ), VerifiedConfigDiff.between(before, after))
    }

    @Test fun proxyIpChangeShowsItsIndependentAuthority() {
        val before = VerifiedConfigUri(TunnelProtocol.TROJAN, "secret", endpoint("1.1.1.1", 443), "worker.example", "/tr")
        val after = before.copy(proxyIp = endpoint("184.75.221.82", 3118, EndpointRole.PROXY_IP))
        assertEquals(listOf(ConfigFieldChange("proxyIp", null, "184.75.221.82:3118")), VerifiedConfigDiff.between(before, after))
    }

    @Test fun sniChangeIsReportedSeparatelyFromWorkerHost() {
        val before = VerifiedConfigUri(TunnelProtocol.VLESS, "uuid", endpoint("1.1.1.1", 443), "worker.example", "/vl")
        val after = before.copy(serverName = "scanner.worker.example")
        assertEquals(
            listOf(ConfigFieldChange("sni", "worker.example", "scanner.worker.example")),
            VerifiedConfigDiff.between(before, after),
        )
    }

    @Test fun diffNeverLeaksCredentials() {
        val before = VerifiedConfigUri(TunnelProtocol.TROJAN, "old-secret", endpoint("1.1.1.1", 443), "worker.example", "/tr")
        val after = before.copy(credential = "new-secret")
        val diff = VerifiedConfigDiff.between(before, after)
        assertEquals(listOf(ConfigFieldChange("credential", "changed", "changed")), diff)
        assertFalse(diff.toString().contains("old-secret"))
        assertFalse(diff.toString().contains("new-secret"))
    }
}

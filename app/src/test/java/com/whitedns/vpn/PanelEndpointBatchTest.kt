package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelEndpointBatchTest {
    private fun endpoint(host: String, port: Int, role: EndpointRole, verdict: EndpointVerdict = EndpointVerdict.VERIFIED) =
        VerifiedEndpoint(host, port, role, EndpointFamily.DOMAIN, verdict, 1L, source = "bot")

    @Test fun batchKeepsRolesAndPortsSeparated() {
        val result = PanelEndpointBatch.build(listOf(
            endpoint("relay.example", 443, EndpointRole.CLEAN_IP) to null,
            endpoint("relay.example", 11111, EndpointRole.PROXY_IP) to null,
            endpoint("relay.example", 1080, EndpointRole.SOCKS5) to null,
        ))
        assertEquals(listOf("relay.example:443"), result.payload.cleanIps)
        assertEquals(listOf("relay.example:11111"), result.payload.proxyIps)
        assertEquals(listOf("socks5://relay.example:1080"), result.payload.relayUris)
        assertEquals(3, result.accepted)
    }

    @Test fun sameHostDifferentPortIsAcceptedNotDuplicate() {
        val result = PanelEndpointBatch.build(listOf(
            endpoint("relay.example", 11111, EndpointRole.PROXY_IP) to null,
            endpoint("relay.example", 3118, EndpointRole.PROXY_IP) to null,
        ))
        assertEquals(2, result.accepted)
        assertEquals(0, result.duplicates)
    }

    @Test fun exactDuplicateAndUnverifiedAreReportedHonestly() {
        val item = endpoint("relay.example", 11111, EndpointRole.PROXY_IP)
        val result = PanelEndpointBatch.build(listOf(
            item to null, item to null,
            endpoint("other.example", 443, EndpointRole.PROXY_IP, EndpointVerdict.PARTIAL) to null,
        ))
        assertEquals(1, result.accepted)
        assertEquals(1, result.duplicates)
        assertEquals(1, result.rejected)
    }
}

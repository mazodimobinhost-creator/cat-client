package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayRuntimePromotionTest {
    private fun row(role: EndpointRole, port: Int) = RelayCatalogEntry(
        VerifiedEndpoint("103.237.102.191", port, role, EndpointFamily.IPV4,
            EndpointVerdict.PARTIAL, 1L, 80, source = "scanner"),
        "DE", "Germany", "FRA", null, RelayRisk.UNKNOWN)

    @Test fun WorkerRuntimePromotesExactProxyIpPort() {
        val promoted = RelayRuntimePromotion.promote(row(EndpointRole.PROXY_IP, 11111), PortProbeKind.PROXY_IP,
            EndpointProbeEvidence(true, protocolHandshakeCompleted = true, runtimeCompleted = true, latencyMs = 55), 100L)!!
        assertEquals(EndpointVerdict.VERIFIED, promoted.endpoint.verdict)
        assertEquals("103.237.102.191:11111", promoted.endpoint.authority)
        assertEquals(55L, promoted.endpoint.latencyMs)
        assertEquals(100L, promoted.endpoint.checkedAtMs)
    }

    @Test fun tcpOrHandshakeOnlyCannotPromote() {
        assertNull(RelayRuntimePromotion.promote(row(EndpointRole.SOCKS5, 1080), PortProbeKind.SOCKS5,
            EndpointProbeEvidence(true, protocolHandshakeCompleted = true), 100L))
    }

    @Test fun wrongProbeTypeCannotPromoteAnotherRole() {
        val runtime = EndpointProbeEvidence(true, protocolHandshakeCompleted = true, runtimeCompleted = true)
        assertNull(RelayRuntimePromotion.promote(row(EndpointRole.PROXY_IP, 11111), PortProbeKind.SOCKS5, runtime, 100L))
    }

    @Test fun cleanTlsNeedsTlsWebsocketAndRuntime() {
        val evidence = EndpointProbeEvidence(true, tlsCompleted = true, websocketCompleted = true, runtimeCompleted = true)
        val promoted = RelayRuntimePromotion.promote(row(EndpointRole.CLEAN_IP, 8443), PortProbeKind.CLEAN_TLS, evidence, 100L)
        assertEquals("103.237.102.191:8443", promoted?.endpoint?.authority)
    }
}

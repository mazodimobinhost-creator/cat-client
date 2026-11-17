package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointProbeEvidenceTest {
    @Test fun tcpOnlyIsPartialNotHealthy() {
        val evidence = EndpointProbeEvidence(tcpConnected = true, latencyMs = 20)
        assertEquals(EndpointVerdict.PARTIAL, EndpointProbeEvaluator.verdict(PortProbeKind.SOCKS5, evidence))
        assertNull(EndpointProbeEvaluator.verifiedEndpointOrNull(
            PortProbeCandidate("relay.example", 1080, PortProbeKind.SOCKS5), evidence, 1L, "device"))
    }

    @Test fun socksRequiresHandshakeAndRuntime() {
        val candidate = PortProbeCandidate("relay.example", 1080, PortProbeKind.SOCKS5)
        val handshakeOnly = EndpointProbeEvidence(true, protocolHandshakeCompleted = true)
        assertEquals(EndpointVerdict.PARTIAL, EndpointProbeEvaluator.verdict(candidate.kind, handshakeOnly))
        val runtime = handshakeOnly.copy(runtimeCompleted = true, latencyMs = 42)
        assertEquals("relay.example:1080", EndpointProbeEvaluator.verifiedEndpointOrNull(candidate, runtime, 1L, "device")?.authority)
    }

    @Test fun proxyIpRequiresRuntimeNotJustOpenPort() {
        val evidence = EndpointProbeEvidence(tcpConnected = true, protocolHandshakeCompleted = true, runtimeCompleted = null)
        assertEquals(EndpointVerdict.PARTIAL, EndpointProbeEvaluator.verdict(PortProbeKind.PROXY_IP, evidence))
    }

    @Test fun cleanTlsRequiresTlsWebsocketAndRuntime() {
        val partial = EndpointProbeEvidence(true, tlsCompleted = true, websocketCompleted = true)
        assertEquals(EndpointVerdict.PARTIAL, EndpointProbeEvaluator.verdict(PortProbeKind.CLEAN_TLS, partial))
        assertEquals(EndpointVerdict.VERIFIED, EndpointProbeEvaluator.verdict(PortProbeKind.CLEAN_TLS,
            partial.copy(runtimeCompleted = true)))
    }

    @Test fun explicitFailureAlwaysFails() {
        assertEquals(EndpointVerdict.FAILED, EndpointProbeEvaluator.verdict(PortProbeKind.HTTP_CONNECT,
            EndpointProbeEvidence(true, protocolHandshakeCompleted = false)))
    }
}

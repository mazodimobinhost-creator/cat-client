package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointPortDiscoveryRunnerTest {
    @Test fun onlyRuntimeVerifiedPortSurvivesDiscovery() {
        val plan = EndpointPortDiscovery.plan("103.237.102.191", setOf(PortProbeKind.PROXY_IP), listOf(80, 443, 11111))
        val outcomes = EndpointPortDiscoveryRunner.run(plan, 10L, "device") { candidate ->
            when (candidate.port) {
                80 -> EndpointProbeEvidence(tcpConnected = false, error = "refused")
                443 -> EndpointProbeEvidence(tcpConnected = true, protocolHandshakeCompleted = true)
                11111 -> EndpointProbeEvidence(tcpConnected = true, protocolHandshakeCompleted = true,
                    runtimeCompleted = true, latencyMs = 55)
                else -> error("unexpected")
            }
        }
        assertEquals(listOf(EndpointVerdict.FAILED, EndpointVerdict.PARTIAL, EndpointVerdict.VERIFIED), outcomes.map { it.verdict })
        assertEquals(listOf("103.237.102.191:11111"), EndpointPortDiscoveryRunner.verified(outcomes).map { it.authority })
    }

    @Test fun probeExceptionBecomesFailedEvidenceInsteadOfHealthyResult() {
        val candidate = PortProbeCandidate("relay.example", 1080, PortProbeKind.SOCKS5)
        val outcome = EndpointPortDiscoveryRunner.run(listOf(candidate), 10L, "device") { error("timeout") }.single()
        assertEquals(EndpointVerdict.FAILED, outcome.verdict)
        assertEquals("timeout", outcome.evidence.error)
        assertEquals(null, outcome.endpoint)
    }

    @Test fun duplicateCandidatesDoNotCreateDuplicateEndpoints() {
        val candidate = PortProbeCandidate("relay.example", 3128, PortProbeKind.HTTP_CONNECT)
        val outcomes = EndpointPortDiscoveryRunner.run(listOf(candidate, candidate), 10L, "device") {
            EndpointProbeEvidence(true, protocolHandshakeCompleted = true, runtimeCompleted = true)
        }
        assertEquals(1, outcomes.size)
        assertEquals(1, EndpointPortDiscoveryRunner.verified(outcomes).size)
    }
}

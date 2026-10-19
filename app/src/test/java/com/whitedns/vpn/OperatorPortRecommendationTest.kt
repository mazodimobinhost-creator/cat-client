package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class OperatorPortRecommendationTest {
    private val now = 100_000L
    private fun row(network: String, host: String, port: Int, runtime: Boolean?, latency: Long, checked: Long = now - 10) =
        CachedEndpointEvidence(EndpointEvidenceKey(network, EndpointRole.CLEAN_IP, host, port, "vless", "worker.example", true),
            EndpointProbeEvidence(true, tlsCompleted = true, websocketCompleted = true, runtimeCompleted = runtime, latencyMs = latency), checked)

    @Test fun recommendationsComeOnlyFromCurrentNetworkRuntimeSuccess() {
        val rows = listOf(
            row("cell:43235", "1.1.1.1", 443, true, 70),
            row("cell:43235", "2.2.2.2", 443, true, 50),
            row("cell:43235", "3.3.3.3", 8443, true, 40),
            row("wifi:home", "4.4.4.4", 2053, true, 10),
            row("cell:43235", "5.5.5.5", 80, null, 20),
        )
        val out = OperatorPortRecommendation.fromEvidence(rows, "cell:43235", now, 60_000)
        assertEquals(listOf(443, 8443), out.map { it.port })
        assertEquals(2, out.first().successfulEndpoints)
        assertEquals(70L, out.first().medianLatencyMs)
    }

    @Test fun staleAndFailedEvidenceNeverProducesSuggestion() {
        val rows = listOf(
            row("cell:43211", "1.1.1.1", 443, false, 10),
            row("cell:43211", "2.2.2.2", 8443, true, 10, now - 60_001),
        )
        assertEquals(emptyList<VerifiedPortRecommendation>(),
            OperatorPortRecommendation.fromEvidence(rows, "cell:43211", now, 60_000))
    }

    @Test fun sameHostDoesNotInflateSuccessCount() {
        val rows = listOf(row("cell:43220", "1.1.1.1", 2053, true, 90), row("cell:43220", "1.1.1.1", 2053, true, 70))
        assertEquals(1, OperatorPortRecommendation.fromEvidence(rows, "cell:43220", now, 60_000).single().successfulEndpoints)
    }
}

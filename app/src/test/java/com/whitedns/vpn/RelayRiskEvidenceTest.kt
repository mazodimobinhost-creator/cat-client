package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayRiskEvidenceTest {
    private val row = RelayCatalogEntry(
        VerifiedEndpoint("1.1.1.1", 443, EndpointRole.CLEAN_IP, EndpointFamily.IPV4,
            EndpointVerdict.PARTIAL, 1L, source = "scan"),
        "DE", "Germany", "Example DC", null, RelayRisk.UNKNOWN)

    @Test fun untestedIsUnknownNotClean() {
        assertEquals(RelayRisk.UNKNOWN, RelayRiskEvidence.classify(null))
        assertEquals(RelayRisk.UNKNOWN, RelayRiskEvidence.classify(WorkerRuntimeOutcome(false)))
    }

    @Test fun actual1101ResponseMarksRisk() {
        assertEquals(RelayRisk.RISK_1101, RelayRiskEvidence.classify(
            WorkerRuntimeOutcome(true, httpStatus = 500, cloudflareErrorCode = 1101)))
    }

    @Test fun successfulRuntimeMarksClean() {
        assertEquals(RelayRisk.CLEAN, RelayRiskEvidence.classify(
            WorkerRuntimeOutcome(true, httpStatus = 200, runtimeCompleted = true)))
    }

    @Test fun tcpOrHttpResponseWithoutRuntimeDoesNotMarkClean() {
        assertEquals(RelayRisk.UNKNOWN, RelayRiskEvidence.classify(
            WorkerRuntimeOutcome(true, httpStatus = 200, runtimeCompleted = false)))
    }

    @Test fun applyingRiskKeepsExactEndpoint() {
        val updated = RelayRiskEvidence.apply(row, WorkerRuntimeOutcome(true, 500, 1101))
        assertEquals("1.1.1.1:443", updated.endpoint.authority)
        assertEquals(RelayRisk.RISK_1101, updated.risk)
    }
}

package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelEndpointBatchSyncTest {
    private fun endpoint(host: String, port: Int, role: EndpointRole = EndpointRole.PROXY_IP,
                         verdict: EndpointVerdict = EndpointVerdict.VERIFIED) =
        VerifiedEndpoint(host, port, role, EndpointFamily.DOMAIN, verdict, 1L, source = "app")

    @Test fun mixedBatchReportsSavedDuplicateRejectedAndMismatch() {
        val duplicate = endpoint("old.example", 443)
        val saved = endpoint("new.example", 11111)
        val mismatch = endpoint("missing.example", 3118)
        val partial = endpoint("partial.example", 8443, verdict = EndpointVerdict.PARTIAL)
        val result = PanelEndpointBatchSync.verify(
            listOf(duplicate, saved, mismatch, partial),
            before = listOf(duplicate),
            after = listOf(duplicate, saved),
            kvAvailable = true,
        )
        assertEquals(1, result.saved)
        assertEquals(1, result.duplicates)
        assertEquals(1, result.rejected)
        assertEquals(1, result.failed)
    }

    @Test fun readBackMustContainEveryDistinctPort() {
        val p11111 = endpoint("relay.example", 11111)
        val p3118 = endpoint("relay.example", 3118)
        val result = PanelEndpointBatchSync.verify(listOf(p11111, p3118), emptyList(), listOf(p11111), true)
        assertEquals(1, result.saved)
        assertEquals(1, result.failed)
    }

    @Test fun noKvFailsEveryUniqueRequest() {
        val result = PanelEndpointBatchSync.verify(listOf(
            endpoint("one.example", 443), endpoint("two.example", 8443)), emptyList(), null, false)
        assertEquals(2, result.failed)
        assertEquals(0, result.saved)
    }

    @Test fun duplicateInputIsReportedOnce() {
        val item = endpoint("relay.example", 11111)
        val result = PanelEndpointBatchSync.verify(listOf(item, item), emptyList(), listOf(item), true)
        assertEquals(1, result.results.size)
        assertEquals(1, result.saved)
    }
}

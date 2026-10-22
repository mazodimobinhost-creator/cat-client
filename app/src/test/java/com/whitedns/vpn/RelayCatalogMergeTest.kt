package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayCatalogMergeTest {
    private fun row(role: EndpointRole, port: Int, checked: Long, latency: Long = 50) = RelayCatalogEntry(
        VerifiedEndpoint("relay.example", port, role, EndpointFamily.DOMAIN, EndpointVerdict.VERIFIED,
            checked, latency, source = "test"), "DE", "Germany", "FRA", null, RelayRisk.UNKNOWN)

    @Test fun cleanProxyAndSocksOnSameHostRemainIndependent() {
        val merged = RelayCatalogMerge.merge(
            listOf(row(EndpointRole.CLEAN_IP, 443, 1)),
            listOf(row(EndpointRole.PROXY_IP, 443, 2), row(EndpointRole.SOCKS5, 1080, 3)),
        )
        assertEquals(setOf(EndpointRole.CLEAN_IP, EndpointRole.PROXY_IP, EndpointRole.SOCKS5),
            merged.map { it.endpoint.role }.toSet())
    }

    @Test fun sameHostOnTwoPortsIsNeverCollapsed() {
        val merged = RelayCatalogMerge.merge(listOf(row(EndpointRole.PROXY_IP, 11111, 1),
            row(EndpointRole.PROXY_IP, 3118, 2)))
        assertEquals(setOf(11111, 3118), merged.map { it.endpoint.port }.toSet())
    }

    @Test fun newestEvidenceWinsOnlyForExactTypedEndpoint() {
        val old = row(EndpointRole.PROXY_IP, 11111, 1, 90)
        val fresh = row(EndpointRole.PROXY_IP, 11111, 2, 40)
        val merged = RelayCatalogMerge.merge(listOf(old), listOf(fresh))
        assertEquals(1, merged.size)
        assertEquals(2L, merged.single().endpoint.checkedAtMs)
        assertEquals(40L, merged.single().endpoint.latencyMs)
    }
}

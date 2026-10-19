package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayCatalogTest {
    private fun entry(host: String, port: Int, family: EndpointFamily, country: String, dc: String,
                      risk: RelayRisk, latency: Long) = RelayCatalogEntry(
        VerifiedEndpoint(host, port, EndpointRole.CLEAN_IP, family, EndpointVerdict.VERIFIED, 1L, latency, source = "scan"),
        country, country, dc, null, risk)

    private val entries = listOf(
        entry("1.1.1.1", 443, EndpointFamily.IPV4, "DE", "Hetzner", RelayRisk.CLEAN, 70),
        entry("2.2.2.2", 8443, EndpointFamily.IPV4, "DE", "OVH", RelayRisk.RISK_1101, 40),
        entry("2606:4700::1", 2053, EndpointFamily.IPV6, "CA", "Example", RelayRisk.UNKNOWN, 50),
    )

    @Test fun filtersCountryDatacenterAndAddressFamily() {
        assertEquals(listOf("1.1.1.1:443"), RelayCatalog.filter(entries,
            RelayCatalogFilter("DE", "Hetzner", AddressFilter.IPV4)).map { it.endpoint.authority })
        assertEquals(listOf("[2606:4700::1]:2053"), RelayCatalog.filter(entries,
            RelayCatalogFilter(address = AddressFilter.IPV6)).map { it.endpoint.authority })
    }

    @Test fun no1101FilterDoesNotPretendUnknownIsClean() {
        val out = RelayCatalog.filter(entries, RelayCatalogFilter(exclude1101Risk = true))
        assertEquals(setOf(RelayRisk.CLEAN, RelayRisk.UNKNOWN), out.map { it.risk }.toSet())
    }

    @Test fun searchMatchesEndpointAndDatacenter() {
        assertEquals("2.2.2.2:8443", RelayCatalog.filter(entries, RelayCatalogFilter(query = "OVH")).single().endpoint.authority)
        assertEquals("1.1.1.1:443", RelayCatalog.filter(entries, RelayCatalogFilter(query = "1.1.1.1:443")).single().endpoint.authority)
    }

    @Test fun countsDriveCountryAndDatacenterSteps() {
        assertEquals(mapOf("DE" to 2, "CA" to 1), RelayCatalog.countryCounts(entries))
        assertEquals(mapOf("Hetzner" to 1, "OVH" to 1), RelayCatalog.datacenterCounts(entries, "de"))
    }
}

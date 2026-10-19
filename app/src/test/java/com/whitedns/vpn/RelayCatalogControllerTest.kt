package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayCatalogControllerTest {
    private fun row(host: String, port: Int, country: String, dc: String, family: EndpointFamily = EndpointFamily.IPV4) =
        RelayCatalogEntry(VerifiedEndpoint(host, port, EndpointRole.CLEAN_IP, family, EndpointVerdict.VERIFIED,
            1L, 20, source = "test"), country, country, dc, null, RelayRisk.UNKNOWN)
    private val controller = RelayCatalogController(listOf(
        row("1.1.1.1", 443, "DE", "OVH"), row("2.2.2.2", 8443, "DE", "Hetzner"),
        row("2606:4700::1", 2053, "CA", "OVH", EndpointFamily.IPV6)))

    @Test fun countryDatacenterServerFlowKeepsExactEndpoint() {
        assertEquals(RelayCatalogStep.COUNTRIES, controller.state.step)
        controller.selectCountry("de")
        assertEquals(mapOf("OVH" to 1, "Hetzner" to 1), controller.datacenters())
        controller.selectDatacenter("Hetzner")
        assertEquals(RelayCatalogStep.SERVERS, controller.state.step)
        assertEquals("2.2.2.2:8443", controller.servers().single().endpoint.authority)
    }

    @Test fun backFromServersKeepsCountryAndClearsDatacenter() {
        controller.selectCountry("DE")
        controller.selectDatacenter("OVH")
        controller.back()
        assertEquals(RelayCatalogStep.DATACENTERS, controller.state.step)
        assertEquals("DE", controller.state.countryCode)
        assertEquals(null, controller.state.datacenter)
    }

    @Test fun addressFilterDoesNotResetCountry() {
        controller.selectCountry("CA")
        controller.updateFilters(AddressFilter.IPV6)
        assertEquals("CA", controller.state.countryCode)
        assertEquals(listOf("[2606:4700::1]:2053"), controller.servers().map { it.endpoint.authority })
    }

    @Test fun topFiveUsesOnlyCurrentCountryAndDatacenter() {
        controller.selectCountry("DE")
        controller.selectDatacenter("OVH")
        assertEquals(listOf("1.1.1.1:443"), controller.topFiveProbePlan().map { "${it.host}:${it.port}" })
    }
}

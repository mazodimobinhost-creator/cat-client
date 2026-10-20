package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCatalogControllerTest {
    private fun row(
        host: String,
        port: Int,
        country: String,
        dc: String,
        family: EndpointFamily = EndpointFamily.IPV4,
        role: EndpointRole = EndpointRole.CLEAN_IP,
        verdict: EndpointVerdict = EndpointVerdict.VERIFIED,
    ) = RelayCatalogEntry(
        VerifiedEndpoint(host, port, role, family, verdict, 1L, 20, source = "test"),
        country, country, dc, null, RelayRisk.UNKNOWN,
    )

    private val controller = RelayCatalogController(
        listOf(
            row("1.1.1.1", 443, "DE", "OVH"),
            row("2.2.2.2", 8443, "DE", "Hetzner"),
            row("2606:4700::1", 2053, "CA", "OVH", EndpointFamily.IPV6),
        ),
    )

    @Test fun countryDatacenterServerFlowKeepsExactEndpoint() {
        assertEquals(RelayCatalogStep.COUNTRIES, controller.state.step)
        assertEquals(mapOf("DE" to 2, "CA" to 1), controller.countries())
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

    @Test fun recordRuntimeResultPromotesExactPortAndUpdates1101RiskWithoutAffectingSiblingPort() {
        val local = RelayCatalogController(
            listOf(
                row("103.237.102.191", 11111, "DE", "FRA", role = EndpointRole.PROXY_IP, verdict = EndpointVerdict.PARTIAL),
                row("103.237.102.191", 3118, "DE", "FRA", role = EndpointRole.PROXY_IP, verdict = EndpointVerdict.PARTIAL),
            ),
        )
        local.selectCountry("DE")
        local.selectDatacenter("FRA")
        val plan = local.topFiveProbePlan()
        assertEquals(2, plan.size)

        val updated = local.recordRuntimeResult(
            candidate = plan[0],
            evidence = EndpointProbeEvidence(
                tcpConnected = true,
                protocolHandshakeCompleted = true,
                runtimeCompleted = true,
                latencyMs = 48L,
            ),
            checkedAtMs = 500L,
            workerOutcome = WorkerRuntimeOutcome(attempted = true, httpStatus = 200, runtimeCompleted = true),
            networkId = "cell:43211",
        )
        assertTrue(updated)
        val rows = local.servers().associateBy { it.endpoint.authority }
        assertEquals(EndpointVerdict.VERIFIED, rows.getValue("103.237.102.191:11111").endpoint.verdict)
        assertEquals(RelayRisk.CLEAN, rows.getValue("103.237.102.191:11111").risk)
        assertEquals(EndpointVerdict.PARTIAL, rows.getValue("103.237.102.191:3118").endpoint.verdict)
        assertEquals(RelayRisk.UNKNOWN, rows.getValue("103.237.102.191:3118").risk)

        assertEquals(emptyList<VerifiedPortRecommendation>(), local.recommendPorts("cell:43211", nowMs = 600L))
        assertEquals(
            listOf(11111),
            local.recommendPorts("cell:43211", nowMs = 600L, role = EndpointRole.PROXY_IP).map { it.port },
        )

        val batch = local.buildVisiblePanelBatch()
        assertEquals(listOf("103.237.102.191:11111"), batch.payload.proxyIps)
        assertEquals(1, batch.accepted)
        assertEquals(1, batch.rejected)

        val sync = local.verifyVisiblePanelSync(
            before = emptyList(),
            after = listOf(rows.getValue("103.237.102.191:11111").endpoint),
            kvAvailable = true,
        )
        assertEquals(1, sync.saved)
        assertEquals(1, sync.rejected)
    }
}

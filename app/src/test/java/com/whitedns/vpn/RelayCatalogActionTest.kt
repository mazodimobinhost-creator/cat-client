package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayCatalogActionTest {
    private fun row(role: EndpointRole, port: Int, verdict: EndpointVerdict = EndpointVerdict.VERIFIED,
                    family: EndpointFamily = EndpointFamily.IPV4, host: String = "103.237.102.191") = RelayCatalogEntry(
        VerifiedEndpoint(host, port, role, family, verdict, 1L, source = "test"),
        "DE", "Germany", "FRA", null, RelayRisk.UNKNOWN)

    @Test fun cleanEntryButtonKeepsExactPort() {
        assertEquals("103.237.102.191:8443", RelayCatalogAction.useAsEntry(row(EndpointRole.CLEAN_IP, 8443))?.value)
        assertNull(RelayCatalogAction.useAsEntry(row(EndpointRole.PROXY_IP, 11111)))
    }

    @Test fun proxyIpButtonKeepsNonStandardPortAndRejectsSocks() {
        assertEquals("103.237.102.191:11111", RelayCatalogAction.useAsProxyIp(row(EndpointRole.PROXY_IP, 11111))?.value)
        assertNull(RelayCatalogAction.useAsProxyIp(row(EndpointRole.SOCKS5, 1080)))
    }

    @Test fun relayCopyKeepsProtocolAndIpv6Port() {
        val socks = row(EndpointRole.SOCKS5, 1080, family = EndpointFamily.IPV6, host = "2606:4700::5")
        assertEquals("socks5://[2606:4700::5]:1080", RelayCatalogAction.copyRelay(socks)?.value)
        assertNull(RelayCatalogAction.copyRelay(row(EndpointRole.PROXY_IP, 443)))
    }

    @Test fun failedRowsHaveNoAction() {
        val failed = row(EndpointRole.CLEAN_IP, 443, EndpointVerdict.FAILED)
        assertNull(RelayCatalogAction.useAsEntry(failed))
    }
}

package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class VerifiedEndpointTest {
    private val now = 1_000_000L
    private fun endpoint(host: String, port: Int, family: EndpointFamily, verdict: EndpointVerdict = EndpointVerdict.VERIFIED, role: EndpointRole = EndpointRole.CLEAN_IP) =
        VerifiedEndpoint(host, port, role, family, verdict, now - 1_000, 42, source = "device-scan")

    @Test fun preservesTheExactMeasuredPort() {
        val result = VerifiedEndpointSelection.subscriptionEntries(
            listOf(endpoint("104.16.1.1", 443, EndpointFamily.IPV4), endpoint("104.16.1.1", 8443, EndpointFamily.IPV4)), now, 60_000)
        assertEquals(listOf("104.16.1.1:443", "104.16.1.1:8443"), result.map { it.authority }.sorted())
    }

    @Test fun bracketsIpv6ExactlyOnce() {
        assertEquals("[2606:4700::1]:2053", endpoint("[2606:4700::1]", 2053, EndpointFamily.IPV6).authority)
    }

    @Test fun parserRequiresAndPreservesExplicitPort() {
        assertEquals("proxy.example:8443", VerifiedEndpointParser.parseAuthority(
            "proxy.example:8443", EndpointRole.PROXY_IP, EndpointVerdict.VERIFIED, now, "panel"
        )?.authority)
        assertEquals("[2606:4700::1]:2053", VerifiedEndpointParser.parseAuthority(
            "[2606:4700::1]:2053", EndpointRole.CLEAN_IP, EndpointVerdict.VERIFIED, now, "scanner"
        )?.authority)
        assertEquals(null, VerifiedEndpointParser.parseAuthority(
            "proxy.example", EndpointRole.PROXY_IP, EndpointVerdict.VERIFIED, now, "panel"
        ))
        assertEquals(null, VerifiedEndpointParser.parseAuthority(
            "1.1.1.1:0", EndpointRole.CLEAN_IP, EndpointVerdict.VERIFIED, now, "scanner"
        ))
    }

    @Test fun excludesFailedAndRelayEndpointsFromSubscriptions() {
        val result = VerifiedEndpointSelection.subscriptionEntries(listOf(
            endpoint("1.1.1.1", 443, EndpointFamily.IPV4, EndpointVerdict.FAILED),
            endpoint("proxy.example", 1080, EndpointFamily.DOMAIN, role = EndpointRole.SOCKS5),
        ), now, 60_000)
        assertEquals(emptyList<VerifiedEndpoint>(), result)
    }
}

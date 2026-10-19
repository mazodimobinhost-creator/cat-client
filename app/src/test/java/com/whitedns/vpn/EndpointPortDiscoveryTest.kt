package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPortDiscoveryTest {
    @Test fun cleanScanUsesSeparateTlsAndPlainPortSets() {
        val plan = EndpointPortDiscovery.plan("104.16.1.1", setOf(PortProbeKind.CLEAN_TLS, PortProbeKind.CLEAN_PLAIN))
        assertTrue(PortProbeCandidate("104.16.1.1", 443, PortProbeKind.CLEAN_TLS) in plan)
        assertTrue(PortProbeCandidate("104.16.1.1", 80, PortProbeKind.CLEAN_PLAIN) in plan)
        assertTrue(PortProbeCandidate("104.16.1.1", 80, PortProbeKind.CLEAN_TLS) !in plan)
    }

    @Test fun customProxyPortsAreNotReplacedByDefaults() {
        val de = EndpointPortDiscovery.plan("103.237.102.191", setOf(PortProbeKind.PROXY_IP), listOf(11111))
        val ca = EndpointPortDiscovery.plan("184.75.221.82", setOf(PortProbeKind.PROXY_IP), listOf(3118))
        assertEquals(listOf(11111), de.map { it.port })
        assertEquals(listOf(3118), ca.map { it.port })
        assertEquals("103.237.102.191:11111", EndpointPortDiscovery.verifiedEndpoint(de.single(), 1L, 50L, "device").authority)
    }

    @Test fun sameHostAndPortCanBeTestedAsDifferentProtocols() {
        val plan = EndpointPortDiscovery.plan("relay.example", setOf(PortProbeKind.SOCKS5, PortProbeKind.HTTP_CONNECT), listOf(8080))
        assertEquals(setOf(PortProbeKind.SOCKS5, PortProbeKind.HTTP_CONNECT), plan.map { it.kind }.toSet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCustomPortIsRejected() {
        EndpointPortDiscovery.plan("relay.example", setOf(PortProbeKind.SOCKS5), listOf(0))
    }
}

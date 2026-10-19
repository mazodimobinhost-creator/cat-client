package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedConfigFailoverTest {
    private val now = 100_000L
    private fun endpoint(host: String, port: Int, verdict: EndpointVerdict, role: EndpointRole = EndpointRole.CLEAN_IP) =
        VerifiedEndpoint(host, port, role, EndpointFamily.IPV4, verdict, now - 10, 30, source = "device")

    @Test fun routeChangePreservesEveryNonIngressField() {
        val proxyIp = endpoint("103.237.102.191", 11111, EndpointVerdict.VERIFIED, EndpointRole.PROXY_IP)
        val current = VerifiedConfigUri(TunnelProtocol.VLESS, "uuid", endpoint("1.1.1.1", 80, EndpointVerdict.FAILED),
            "worker.example", "/vl?ed=2560", tls = true, fingerprint = "chrome", proxyIp = proxyIp)
        val result = VerifiedConfigFailover.changeRoute(current,
            listOf(endpoint("2.2.2.2", 8443, EndpointVerdict.VERIFIED)), now, 60_000)

        assertEquals(FailoverStatus.SWITCHED, result.status)
        assertEquals("2.2.2.2:8443", result.selectedAuthority)
        assertEquals(TunnelProtocol.VLESS, result.config.protocol)
        assertEquals("uuid", result.config.credential)
        assertEquals("worker.example", result.config.workerDomain)
        assertEquals("/vl?ed=2560", result.config.path)
        assertEquals("chrome", result.config.fingerprint)
        assertEquals("103.237.102.191:11111", result.config.proxyIp?.authority)
        assertTrue(result.config.encode().contains("path=%2Fvl%3Fed%3D2560%26proxyip%3D103.237.102.191%3A11111"))
    }

    @Test fun noReplacementReturnsByteEquivalentUri() {
        val current = VerifiedConfigUri(TunnelProtocol.TROJAN, "secret", endpoint("1.1.1.1", 443, EndpointVerdict.FAILED),
            "worker.example", "/tr")
        val result = VerifiedConfigFailover.changeRoute(current,
            listOf(endpoint("2.2.2.2", 8443, EndpointVerdict.PARTIAL)), now, 60_000)
        assertEquals(FailoverStatus.NO_VERIFIED_REPLACEMENT, result.status)
        assertEquals(current.encode(), result.config.encode())
    }
}

package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCatalogConfigFactoryTest {
    private fun row(host: String, port: Int, role: EndpointRole, verdict: EndpointVerdict = EndpointVerdict.VERIFIED) =
        RelayCatalogEntry(VerifiedEndpoint(host, port, role, EndpointFamily.IPV4, verdict, 1L, source = "test"),
            "DE", "Germany", "FRA", null, RelayRisk.UNKNOWN)

    @Test fun workerConfigKeepsIngressAndProxyIpPortsIndependent() {
        val config = RelayCatalogConfigFactory.build(
            row("104.16.1.1", 8443, EndpointRole.CLEAN_IP), TunnelProtocol.VLESS, "uuid",
            "worker.example", "/vl", row("103.237.102.191", 11111, EndpointRole.PROXY_IP), "chrome")!!
        val uri = config.encode()
        assertTrue(uri.startsWith("vless://uuid@104.16.1.1:8443?"))
        assertTrue(uri.contains("path=%2Fvl%3Fproxyip%3D103.237.102.191%3A11111"))
        assertTrue(uri.contains("sni=worker.example"))
    }

    @Test fun plainVerifiedPortDisablesTlsWithoutChangingPort() {
        val config = RelayCatalogConfigFactory.build(row("104.16.1.1", 8080, EndpointRole.CLEAN_IP),
            TunnelProtocol.TROJAN, "secret", "worker.example", "/tr")!!
        assertEquals(8080, config.endpoint.port)
        assertEquals(false, config.tls)
        assertTrue(config.encode().contains("security=none"))
    }

    @Test fun wrongRoleOrFailedRowsCannotBuildConfig() {
        assertNull(RelayCatalogConfigFactory.build(row("1.1.1.1", 1080, EndpointRole.SOCKS5),
            TunnelProtocol.VLESS, "uuid", "worker.example", "/vl"))
        assertNull(RelayCatalogConfigFactory.build(row("1.1.1.1", 443, EndpointRole.CLEAN_IP),
            TunnelProtocol.VLESS, "uuid", "worker.example", "/vl",
            row("2.2.2.2", 11111, EndpointRole.PROXY_IP, EndpointVerdict.FAILED)))
    }
}

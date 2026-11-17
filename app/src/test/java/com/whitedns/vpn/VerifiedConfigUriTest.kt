package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedConfigUriTest {
    private fun endpoint(host: String, port: Int, family: EndpointFamily) = VerifiedEndpoint(
        host, port, EndpointRole.CLEAN_IP, family, EndpointVerdict.VERIFIED,
        checkedAtMs = 1L, source = "test"
    )

    @Test fun vlessKeepsVerifiedIpv4PortAndWorkerTlsIdentity() {
        val uri = VerifiedConfigUri(TunnelProtocol.VLESS, "uuid", endpoint("104.16.1.1", 8443, EndpointFamily.IPV4),
            "worker.example", "/vl?ed=2560", fingerprint = "chrome").encode()
        assertTrue(uri.startsWith("vless://uuid@104.16.1.1:8443?"))
        assertTrue(uri.contains("host=worker.example"))
        assertTrue(uri.contains("sni=worker.example"))
        assertTrue(uri.contains("path=%2Fvl%3Fed%3D2560"))
        assertTrue(uri.contains("fp=chrome"))
    }

    @Test fun trojanBracketsIpv6OnceAndKeepsItsPort() {
        val uri = VerifiedConfigUri(TunnelProtocol.TROJAN, "secret", endpoint("[2606:4700::1]", 2053, EndpointFamily.IPV6),
            "worker.example", "/tr").encode()
        assertTrue(uri.startsWith("trojan://secret@[2606:4700::1]:2053?"))
        assertEquals(1, Regex("\\[2606:4700::1]").findAll(uri).count())
    }

    @Test fun proxyIpKeepsItsIndependentNonStandardPortInWorkerPath() {
        val ingress = endpoint("104.16.1.1", 443, EndpointFamily.IPV4)
        val germany = VerifiedEndpoint("103.237.102.191", 11111, EndpointRole.PROXY_IP,
            EndpointFamily.IPV4, EndpointVerdict.VERIFIED, 1L, source = "manual")
        val canada = VerifiedEndpoint("184.75.221.82", 3118, EndpointRole.PROXY_IP,
            EndpointFamily.IPV4, EndpointVerdict.VERIFIED, 1L, source = "manual")

        val deUri = VerifiedConfigUri(TunnelProtocol.VLESS, "uuid", ingress,
            "worker.example", "/vl", proxyIp = germany).encode()
        val caUri = VerifiedConfigUri(TunnelProtocol.TROJAN, "secret", ingress,
            "worker.example", "/tr?ed=2560", proxyIp = canada).encode()

        assertTrue(deUri.startsWith("vless://uuid@104.16.1.1:443?"))
        assertTrue(deUri.contains("path=%2Fvl%3Fproxyip%3D103.237.102.191%3A11111"))
        assertTrue(caUri.contains("path=%2Ftr%3Fed%3D2560%26proxyip%3D184.75.221.82%3A3118"))
    }

    @Test fun parserRoundTripsIpv6CustomSniAndProxyIpPort() {
        val ingress = endpoint("2606:4700::1", 2053, EndpointFamily.IPV6)
        val proxy = VerifiedEndpoint("103.237.102.191", 11111, EndpointRole.PROXY_IP,
            EndpointFamily.IPV4, EndpointVerdict.VERIFIED, 1L, source = "manual")
        val original = VerifiedConfigUri(
            TunnelProtocol.TROJAN, "secret", ingress, "worker.example", "/tr?ed=2560",
            fingerprint = "chrome", proxyIp = proxy, serverName = "sni.worker.example",
        )
        val parsed = VerifiedConfigUriParser.parse(original.encode() + "#Germany", 1L, "test")!!
        assertEquals(original.encode(), parsed.encode())
        assertEquals("[2606:4700::1]:2053", parsed.endpoint.authority)
        assertEquals("103.237.102.191:11111", parsed.proxyIp?.authority)
        assertEquals("worker.example", parsed.workerDomain)
        assertEquals("sni.worker.example", parsed.serverName)
        assertEquals("/tr?ed=2560", parsed.path)
    }

    @Test fun parserRejectsMissingIngressPortOrBareProxyIp() {
        assertNull(VerifiedConfigUriParser.parse("vless://uuid@104.16.1.1?type=ws&host=worker.example&path=%2Fvl", 1L, "test"))
        assertNull(VerifiedConfigUriParser.parse("vless://uuid@104.16.1.1:443?type=ws&host=worker.example&path=%2Fvl%3Fproxyip%3D103.237.102.191", 1L, "test"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun socksCannotBePassedAsProxyIp() {
        val socks = VerifiedEndpoint("relay.example", 1080, EndpointRole.SOCKS5,
            EndpointFamily.DOMAIN, EndpointVerdict.VERIFIED, 1L, source = "scan")
        VerifiedConfigUri(TunnelProtocol.VLESS, "uuid", endpoint("1.1.1.1", 443, EndpointFamily.IPV4),
            "worker.example", "/vl", proxyIp = socks)
    }

    @Test(expected = IllegalArgumentException::class)
    fun failedEndpointCannotGenerateConfig() {
        VerifiedConfigUri(TunnelProtocol.VLESS, "uuid", VerifiedEndpoint("1.1.1.1", 443,
            EndpointRole.CLEAN_IP, EndpointFamily.IPV4, EndpointVerdict.FAILED, 1L, source = "test"),
            "worker.example", "/vl")
    }
}

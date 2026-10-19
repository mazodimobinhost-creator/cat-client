package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
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

    @Test(expected = IllegalArgumentException::class)
    fun failedEndpointCannotGenerateConfig() {
        VerifiedConfigUri(TunnelProtocol.VLESS, "uuid", VerifiedEndpoint("1.1.1.1", 443,
            EndpointRole.CLEAN_IP, EndpointFamily.IPV4, EndpointVerdict.FAILED, 1L, source = "test"),
            "worker.example", "/vl")
    }
}

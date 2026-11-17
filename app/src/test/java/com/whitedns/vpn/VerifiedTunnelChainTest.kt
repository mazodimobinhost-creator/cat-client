package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedTunnelChainTest {
    @Test fun parsesVlessChainWithoutChangingPortOrTlsFields() {
        val chain = VerifiedTunnelChainParser.parse(
            "vless://uuid@1.2.3.4:8443?host=worker.example&sni=tls.example&path=%2Fvl%3Fed%3D2560&security=tls&fp=chrome",
            1L, EndpointVerdict.VERIFIED, "manual")!!
        assertEquals(EndpointRole.VLESS_CHAIN, chain.endpoint.role)
        assertEquals("1.2.3.4:8443", chain.endpoint.authority)
        assertEquals("worker.example", chain.hostHeader)
        assertEquals("tls.example", chain.serverName)
        assertEquals("/vl?ed=2560", chain.path)
        assertEquals("chrome", chain.fingerprint)
    }

    @Test fun parsesTrojanIpv6ChainAndCredential() {
        val chain = VerifiedTunnelChainParser.parse(
            "trojan://p%40ss@[2606:4700::3]:2053?sni=worker.example&security=tls",
            1L, EndpointVerdict.VERIFIED, "manual")!!
        assertEquals(EndpointRole.TROJAN_CHAIN, chain.endpoint.role)
        assertEquals("[2606:4700::3]:2053", chain.endpoint.authority)
        assertEquals("p@ss", chain.credential)
    }

    @Test fun missingPortIsRejectedRatherThanDefaulted() {
        assertNull(VerifiedTunnelChainParser.parse("vless://uuid@host?sni=worker.example", 1L,
            EndpointVerdict.UNKNOWN, "manual"))
    }

    @Test fun socksIsNotAcceptedAsTunnelChain() {
        assertNull(VerifiedTunnelChainParser.parse("socks5://host:1080", 1L,
            EndpointVerdict.UNKNOWN, "manual"))
    }
}

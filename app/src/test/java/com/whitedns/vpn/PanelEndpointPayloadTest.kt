package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelEndpointPayloadTest {
    private fun endpoint(role: EndpointRole, port: Int, verdict: EndpointVerdict = EndpointVerdict.VERIFIED) =
        VerifiedEndpoint("relay.example", port, role, EndpointFamily.DOMAIN, verdict, 1L, source = "test")

    @Test fun cleanAndProxyIpGoToDifferentFields() {
        val clean = PanelEndpointPayloadFactory.from(endpoint(EndpointRole.CLEAN_IP, 8443))!!
        val proxy = PanelEndpointPayloadFactory.from(endpoint(EndpointRole.PROXY_IP, 11111))!!
        assertEquals(listOf("relay.example:8443"), clean.cleanIps)
        assertEquals(emptyList<String>(), clean.proxyIps)
        assertEquals(listOf("relay.example:11111"), proxy.proxyIps)
        assertEquals(emptyList<String>(), proxy.cleanIps)
    }

    @Test fun socksAndHttpRemainTypedUris() {
        assertEquals(listOf("socks5://relay.example:1080"),
            PanelEndpointPayloadFactory.from(endpoint(EndpointRole.SOCKS5, 1080))?.relayUris)
        assertEquals(listOf("http://relay.example:3128"),
            PanelEndpointPayloadFactory.from(endpoint(EndpointRole.HTTP_RELAY, 3128))?.relayUris)
    }

    @Test fun tunnelChainNeedsItsOriginalTypedUri() {
        val chain = PanelEndpointPayloadFactory.from(endpoint(EndpointRole.TROJAN_CHAIN, 2053),
            "trojan://secret@relay.example:2053")
        assertEquals(listOf("trojan://secret@relay.example:2053"), chain?.tunnelChains)
        assertNull(PanelEndpointPayloadFactory.from(endpoint(EndpointRole.TROJAN_CHAIN, 2053)))
    }

    @Test fun unverifiedEndpointCreatesNoPayload() {
        assertNull(PanelEndpointPayloadFactory.from(endpoint(EndpointRole.PROXY_IP, 11111, EndpointVerdict.PARTIAL)))
    }
}

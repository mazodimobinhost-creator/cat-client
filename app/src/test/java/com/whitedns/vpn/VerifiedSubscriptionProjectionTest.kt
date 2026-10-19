package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class VerifiedSubscriptionProjectionTest {
    private fun config(protocol: TunnelProtocol, host: String, port: Int, family: EndpointFamily) = VerifiedConfigUri(
        protocol, if (protocol == TunnelProtocol.VLESS) "uuid" else "secret",
        VerifiedEndpoint(host, port, EndpointRole.CLEAN_IP, family, EndpointVerdict.VERIFIED, 1L, source = "scan"),
        "worker.example", "/ws?ed=2560", fingerprint = "chrome"
    )

    @Test fun allFormatsKeepTheSameIpv4PortHostAndSni() {
        val config = config(TunnelProtocol.VLESS, "104.16.1.1", 8443, EndpointFamily.IPV4)
        val clash = VerifiedSubscriptionProjector.clash(config)
        val sing = VerifiedSubscriptionProjector.singBox(config)
        val xray = VerifiedSubscriptionProjector.xray(config)
        assertEquals(8443, clash["port"])
        assertEquals(8443, sing["server_port"])
        assertEquals(8443, xray["port"])
        assertEquals("worker.example", clash["servername"])
        assertEquals("104.16.1.1", clash["server"])
    }

    @Test fun allFormatsKeepRawIpv6ServerAndPinnedPort() {
        val config = config(TunnelProtocol.TROJAN, "2606:4700::1", 2053, EndpointFamily.IPV6)
        listOf(VerifiedSubscriptionProjector.clash(config), VerifiedSubscriptionProjector.singBox(config),
            VerifiedSubscriptionProjector.xray(config)).forEach {
            assertEquals("2606:4700::1", it[if (it.containsKey("address")) "address" else "server"])
            assertEquals(2053, it[if (it.containsKey("server_port")) "server_port" else "port"])
        }
    }

    @Test fun trojanUsesPasswordWhileVlessUsesUuidOrId() {
        val trojan = VerifiedSubscriptionProjector.clash(config(TunnelProtocol.TROJAN, "1.1.1.1", 443, EndpointFamily.IPV4))
        val vless = VerifiedSubscriptionProjector.xray(config(TunnelProtocol.VLESS, "1.1.1.1", 443, EndpointFamily.IPV4))
        assertEquals("secret", trojan["password"])
        assertEquals("uuid", vless["id"])
    }
}

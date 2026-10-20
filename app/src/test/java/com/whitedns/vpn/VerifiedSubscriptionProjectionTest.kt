package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedSubscriptionProjectionTest {
    private fun config(
        protocol: TunnelProtocol,
        host: String,
        port: Int,
        family: EndpointFamily,
        proxyIp: VerifiedEndpoint? = null,
        serverName: String = "worker.example",
        tls: Boolean = true,
    ) = VerifiedConfigUri(
        protocol,
        if (protocol == TunnelProtocol.VLESS) "uuid" else "secret",
        VerifiedEndpoint(host, port, EndpointRole.CLEAN_IP, family, EndpointVerdict.VERIFIED, 1L, source = "scan"),
        "worker.example",
        "/ws?ed=2560",
        tls = tls,
        fingerprint = "chrome",
        proxyIp = proxyIp,
        serverName = serverName,
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
        listOf(
            VerifiedSubscriptionProjector.clash(config),
            VerifiedSubscriptionProjector.singBox(config),
            VerifiedSubscriptionProjector.xray(config),
        ).forEach {
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

    @Test fun proxyIpAuthorityAndCustomSniPropagateAcrossClashSingBoxAndXray() {
        val proxy = VerifiedEndpoint(
            "103.237.102.191", 11111, EndpointRole.PROXY_IP, EndpointFamily.IPV4,
            EndpointVerdict.VERIFIED, 1L, source = "manual",
        )
        val cfg = config(
            TunnelProtocol.VLESS, "104.16.1.1", 443, EndpointFamily.IPV4,
            proxyIp = proxy, serverName = "sni.worker.example",
        )
        val expectedPath = "/ws?ed=2560&proxyip=103.237.102.191:11111"

        val clash = VerifiedSubscriptionProjector.clash(cfg)
        val clashWs = clash["ws-opts"] as Map<*, *>
        assertEquals(expectedPath, clashWs["path"])
        assertEquals(mapOf("Host" to "worker.example"), clashWs["headers"])
        assertEquals("sni.worker.example", clash["servername"])
        assertEquals("chrome", clash["client-fingerprint"])

        val sing = VerifiedSubscriptionProjector.singBox(cfg)
        val singTransport = sing["transport"] as Map<*, *>
        val singTls = sing["tls"] as Map<*, *>
        assertEquals(expectedPath, singTransport["path"])
        assertEquals(mapOf("Host" to "worker.example"), singTransport["headers"])
        assertEquals("sni.worker.example", singTls["server_name"])
        assertEquals(mapOf("enabled" to true, "fingerprint" to "chrome"), singTls["utls"])

        val xray = VerifiedSubscriptionProjector.xray(cfg)
        val stream = xray["streamSettings"] as Map<*, *>
        val xrayWs = stream["wsSettings"] as Map<*, *>
        assertEquals(expectedPath, xrayWs["path"])
        assertEquals(mapOf("Host" to "worker.example"), xrayWs["headers"])
        assertEquals("sni.worker.example", stream["serverName"])
        assertEquals("chrome", stream["fingerprint"])
    }

    @Test fun uriClashSingBoxAndXrayAllImportEndToEndIntoValidMihomoYaml() {
        val proxy11111 = VerifiedEndpoint(
            "103.237.102.191", 11111, EndpointRole.PROXY_IP, EndpointFamily.IPV4,
            EndpointVerdict.VERIFIED, 1L, source = "manual",
        )
        val proxy3118 = VerifiedEndpoint(
            "184.75.221.82", 3118, EndpointRole.PROXY_IP, EndpointFamily.IPV4,
            EndpointVerdict.VERIFIED, 1L, source = "manual",
        )
        val vlessIpv6Tls = config(
            TunnelProtocol.VLESS, "2606:4700::1", 2053, EndpointFamily.IPV6,
            proxyIp = proxy11111, serverName = "sni.worker.example", tls = true,
        )
        val trojanPlain = config(
            TunnelProtocol.TROJAN, "104.16.1.1", 8080, EndpointFamily.IPV4,
            proxyIp = proxy3118, serverName = "worker.example", tls = false,
        )
        val configs = listOf(vlessIpv6Tls, trojanPlain)

        val payloads = listOf(
            configs.joinToString("\n") { it.encode() },
            VerifiedSubscriptionProjector.toClashJson(configs),
            VerifiedSubscriptionProjector.toSingBoxJson(configs),
            VerifiedSubscriptionProjector.toXrayJson(configs),
        )

        for (payload in payloads) {
            val imported = UserSubscriptionImporter.import(payload, nowMs = 1_000L)
            assertEquals(2, imported.connectionCount)
            assertTrue(imported.yaml.contains("2606:4700::1"))
            assertTrue(imported.yaml.contains("2053"))
            assertTrue(imported.yaml.contains("103.237.102.191:11111"))
            assertTrue(imported.yaml.contains("sni.worker.example"))
            assertTrue(imported.yaml.contains("104.16.1.1"))
            assertTrue(imported.yaml.contains("8080"))
            assertTrue(imported.yaml.contains("184.75.221.82:3118"))
            assertTrue(imported.yaml.contains("tls: false"))
        }
    }
}

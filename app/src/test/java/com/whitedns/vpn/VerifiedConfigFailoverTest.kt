package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedConfigFailoverTest {
    private val now = 100_000L
    private fun endpoint(
        host: String,
        port: Int,
        verdict: EndpointVerdict,
        role: EndpointRole = EndpointRole.CLEAN_IP,
        checkedAtMs: Long = now - 10,
        family: EndpointFamily = if (host.contains(':')) EndpointFamily.IPV6 else EndpointFamily.IPV4,
    ) = VerifiedEndpoint(host, port, role, family, verdict, checkedAtMs, 30, source = "device")

    @Test fun routeChangePreservesEveryNonIngressField() {
        val proxyIp = endpoint("103.237.102.191", 11111, EndpointVerdict.VERIFIED, EndpointRole.PROXY_IP)
        val current = VerifiedConfigUri(
            TunnelProtocol.VLESS, "uuid",
            endpoint("1.1.1.1", 80, EndpointVerdict.VERIFIED, checkedAtMs = now - 60_001),
            "worker.example", "/vl?ed=2560", tls = true, fingerprint = "chrome",
            proxyIp = proxyIp, serverName = "sni.worker.example",
        )
        val result = VerifiedConfigFailover.changeRoute(
            current,
            listOf(endpoint("2.2.2.2", 8443, EndpointVerdict.VERIFIED)),
            now, 60_000,
        )

        assertEquals(FailoverStatus.SWITCHED, result.status)
        assertEquals("2.2.2.2:8443", result.selectedAuthority)
        assertEquals(TunnelProtocol.VLESS, result.config.protocol)
        assertEquals("uuid", result.config.credential)
        assertEquals("worker.example", result.config.workerDomain)
        assertEquals("sni.worker.example", result.config.serverName)
        assertEquals("/vl?ed=2560", result.config.path)
        assertEquals("chrome", result.config.fingerprint)
        assertEquals("103.237.102.191:11111", result.config.proxyIp?.authority)
        assertTrue(result.config.encode().contains("path=%2Fvl%3Fed%3D2560%26proxyip%3D103.237.102.191%3A11111"))
    }

    @Test fun proxyIpFailoverSwitchesRelayWithoutTouchingIngressEndpoint() {
        val staleProxy = endpoint("103.237.102.191", 11111, EndpointVerdict.VERIFIED, EndpointRole.PROXY_IP, checkedAtMs = now - 60_001)
        val freshProxy = endpoint("184.75.221.82", 3118, EndpointVerdict.VERIFIED, EndpointRole.PROXY_IP)
        val current = VerifiedConfigUri(
            TunnelProtocol.TROJAN, "secret",
            endpoint("104.16.1.1", 8443, EndpointVerdict.VERIFIED),
            "worker.example", "/tr", proxyIp = staleProxy,
        )
        val result = VerifiedConfigFailover.changeProxyIp(current, listOf(freshProxy), now, 60_000)

        assertEquals(FailoverStatus.SWITCHED, result.status)
        assertEquals("103.237.102.191:11111", result.previousAuthority)
        assertEquals("184.75.221.82:3118", result.selectedAuthority)
        assertEquals("104.16.1.1:8443", result.config.endpoint.authority)
        assertTrue(result.config.encode().contains("path=%2Ftr%3Fproxyip%3D184.75.221.82%3A3118"))
    }

    @Test fun changeRouteUriSwitchesBrokenShareLinkToVerifiedIpv6AndKeepsRemark() {
        val broken = "vless://uuid@104.16.1.1:443?type=ws&security=tls&host=worker.example&sni=sni.worker.example&path=%2Fvl%3Fproxyip%3D103.237.102.191%3A11111&fp=chrome#DE-Route"
        val switched = VerifiedConfigFailover.changeRouteUri(
            broken,
            listOf(endpoint("2606:4700::1", 2053, EndpointVerdict.VERIFIED)),
            now, 60_000,
        )!!
        assertTrue(switched.startsWith("vless://uuid@[2606:4700::1]:2053?"))
        assertTrue(switched.contains("host=worker.example"))
        assertTrue(switched.contains("sni=sni.worker.example"))
        assertTrue(switched.contains("path=%2Fvl%3Fproxyip%3D103.237.102.191%3A11111"))
        assertTrue(switched.endsWith("#DE-Route"))
    }

    @Test fun noReplacementReturnsByteEquivalentUri() {
        val current = VerifiedConfigUri(
            TunnelProtocol.TROJAN, "secret",
            endpoint("1.1.1.1", 443, EndpointVerdict.VERIFIED, checkedAtMs = now - 60_001),
            "worker.example", "/tr",
        )
        val result = VerifiedConfigFailover.changeRoute(
            current,
            listOf(endpoint("2.2.2.2", 8443, EndpointVerdict.PARTIAL)),
            now, 60_000,
        )
        assertEquals(FailoverStatus.NO_VERIFIED_REPLACEMENT, result.status)
        assertEquals(current.encode(), result.config.encode())
    }

    @Test fun changeProxyIpUriRotatesProxyIpAndPreservesRemark() {
        val raw = "vless://uuid@104.16.1.1:8443?type=ws&security=tls&host=worker.example&sni=sni.worker.example&path=%2Fvl%3Fed%3D2560%26proxyip%3D103.237.102.191%3A11111&fp=chrome#DE-Route"
        val switched = VerifiedConfigFailover.changeProxyIpUri(
            raw,
            listOf(endpoint("184.75.221.82", 3118, EndpointVerdict.VERIFIED, EndpointRole.PROXY_IP)),
            now,
            60_000,
        )!!
        assertTrue(switched.startsWith("vless://uuid@104.16.1.1:8443?"))
        assertTrue(switched.contains("path=%2Fvl%3Fed%3D2560%26proxyip%3D184.75.221.82%3A3118"))
        assertTrue(switched.endsWith("#DE-Route"))
    }

    @Test fun replaceProxyInYamlUpdatesBlockAndFlowProxiesInPlace() {
        val yaml = """
            mixed-port: 7890
            proxies:
              - {name: "DE-Flow", type: vless, server: 104.16.1.1, port: 443, uuid: 11111111-1111-1111-1111-111111111111, tls: true, servername: worker.example, network: ws, ws-opts: {path: "/vl?ed=2560", headers: {Host: worker.example}}}
              - name: "DE-Block"
                type: trojan
                server: 104.16.1.2
                port: 443
                password: "secret"
                tls: true
                servername: "worker.example"
                network: ws
                ws-opts:
                  path: "/tr?ed=2560"
                  headers:
                    Host: "worker.example"
            proxy-groups:
              - name: "Proxy"
                type: select
                proxies:
                  - "DE-Flow"
                  - "DE-Block"
        """.trimIndent()
        val parsedFlow = MihomoProxyYaml.parse(yaml)
        assertEquals(listOf("DE-Flow", "DE-Block"), parsedFlow.map { it.name })

        val updatedFlowUri = "vless://11111111-1111-1111-1111-111111111111@172.67.1.9:2053?type=ws&security=tls&host=worker.example&sni=worker.example&path=%2Fvl%3Fed%3D2560%26proxyip%3D103.237.102.191%3A11111&fp=chrome#DE-Flow"
        val updatedYaml = MihomoLinkConfigBuilder.replaceProxyInYaml(yaml, "DE-Flow", updatedFlowUri)!!
        val reparsed = MihomoProxyYaml.parse(updatedYaml)
        assertEquals(listOf("DE-Flow", "DE-Block"), reparsed.map { it.name })
        assertEquals("172.67.1.9", reparsed[0].fields["server"])
        assertEquals("2053", reparsed[0].fields["port"])
        assertEquals("/vl?ed=2560&proxyip=103.237.102.191:11111", reparsed[0].wsPath)
    }

    @Test fun communityIpLibraryIncludesExplicitPortProxyIpSeeds() {
        assertTrue(CommunityIpLibrary.proxyIpSeeds.contains("103.237.102.191:11111"))
        assertTrue(CommunityIpLibrary.proxyIpSeeds.contains("184.75.221.82:3118"))
    }
}

package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoShareLinkTest {
    private val now = 1_000L

    @Test
    fun roundTripYamlBlockRebuildsVlessAndTrojanShareLinksWithExactPortsAndProxyIp() {
        val links = listOf(
            "vless://uuid@104.16.1.1:8443?encryption=none&security=tls&type=ws&host=worker.example" +
                "&path=%2Fvl%3Fed%3D2560%26proxyip%3D103.237.102.191%3A11111&sni=sni.worker.example#DE-VL",
            "trojan://secret@[2606:4700::1]:2053?security=tls&type=ws&host=worker.example" +
                "&path=%2Ftr%3Fproxyip%3D184.75.221.82%3A3118&sni=sni.worker.example#CA-TR",
            "trojan://secret@104.16.1.2:8080?security=none&type=ws&host=worker.example&path=%2Ftr#PLAIN-TR",
        ).joinToString("\n")

        val imported = UserSubscriptionImporter.import(links, now)
        val parsed = MihomoConfigParser.parse(imported.yaml, now)
        assertEquals(3, parsed.summary.proxies.size)

        val vlessRebuilt = MihomoShareLink.fromYamlBlock(parsed.summary.proxies[0].rawYaml)!!
        assertTrue(vlessRebuilt.startsWith("vless://uuid@104.16.1.1:8443?"))
        assertTrue(vlessRebuilt.contains("sni=sni.worker.example"))
        assertTrue(vlessRebuilt.contains("host=worker.example"))
        assertTrue(vlessRebuilt.contains("proxyip%3D103.237.102.191%3A11111"))
        assertTrue(vlessRebuilt.endsWith("#DE-VL"))

        val trojanIpv6Rebuilt = MihomoShareLink.fromYamlBlock(parsed.summary.proxies[1].rawYaml)!!
        assertTrue(trojanIpv6Rebuilt.startsWith("trojan://secret@[2606:4700::1]:2053?"))
        assertTrue(!trojanIpv6Rebuilt.contains("[["))
        assertTrue(trojanIpv6Rebuilt.contains("proxyip%3D184.75.221.82%3A3118"))
        assertTrue(trojanIpv6Rebuilt.endsWith("#CA-TR"))

        val trojanPlainRebuilt = MihomoShareLink.fromYamlBlock(parsed.summary.proxies[2].rawYaml)!!
        assertTrue(trojanPlainRebuilt.startsWith("trojan://secret@104.16.1.2:8080?"))
        assertTrue(trojanPlainRebuilt.contains("security=none"))
        assertTrue(trojanPlainRebuilt.endsWith("#PLAIN-TR"))
    }

    @Test
    fun changeRouteAndChangeProxyIpFromYamlBlockPreserveRemarkSniAndHost() {
        val link =
            "vless://uuid@104.16.1.1:443?encryption=none&security=tls&type=ws&host=worker.example" +
                "&path=%2Fvl%3Fed%3D2560%26proxyip%3D103.237.102.191%3A11111&sni=sni.worker.example#MainRoute"
        val imported = UserSubscriptionImporter.import(link, now)
        val rawBlock = MihomoConfigParser.parse(imported.yaml, now).summary.proxies.single().rawYaml

        val nextIngress = VerifiedEndpoint(
            "2606:4700::9", 2053, EndpointRole.CLEAN_IP, EndpointFamily.IPV6,
            EndpointVerdict.VERIFIED, now, 18L, source = "scan",
        )
        val rotatedRoute = MihomoShareLink.changeRouteFromYamlBlock(rawBlock, listOf(nextIngress), now)!!
        assertTrue(rotatedRoute.startsWith("vless://uuid@[2606:4700::9]:2053?"))
        assertTrue(rotatedRoute.contains("host=worker.example"))
        assertTrue(rotatedRoute.contains("sni=sni.worker.example"))
        assertTrue(rotatedRoute.contains("proxyip%3D103.237.102.191%3A11111"))
        assertTrue(rotatedRoute.endsWith("#MainRoute"))

        val nextProxyIp = VerifiedEndpoint(
            "184.75.221.82", 3118, EndpointRole.PROXY_IP, EndpointFamily.IPV4,
            EndpointVerdict.VERIFIED, now, 22L, source = "scan",
        )
        val rotatedProxyIp = MihomoShareLink.changeProxyIpFromYamlBlock(rawBlock, listOf(nextProxyIp), now)!!
        assertTrue(rotatedProxyIp.startsWith("vless://uuid@104.16.1.1:443?"))
        assertTrue(rotatedProxyIp.contains("proxyip%3D184.75.221.82%3A3118"))
        assertTrue(rotatedProxyIp.endsWith("#MainRoute"))
    }

    @Test
    fun echProxiesSkipDpiFragmentAndCustomSniMaskWhilePreservingEchShareLink() {
        val links = listOf(
            "vless://uuid@104.16.1.1:443?encryption=none&security=tls&type=ws&host=worker.example" +
                "&path=%2Fvl%3Fed%3D2560&sni=worker.example&ech=cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1#ECH-Node",
            "vless://uuid@104.16.1.2:2053?encryption=none&security=tls&type=ws&host=worker.example" +
                "&path=%2Fvl%3Fed%3D2560&sni=worker.example#Standard-Node",
        ).joinToString("\n")

        val imported = UserSubscriptionImporter.import(links, now)
        val parsed = MihomoConfigParser.parse(imported.yaml, now)
        assertEquals(2, parsed.summary.proxies.size)

        val echRebuilt = MihomoShareLink.fromYamlBlock(parsed.summary.proxies[0].rawYaml)!!
        assertTrue(echRebuilt.contains("ech=cloudflare-ech.com"))
        assertTrue(echRebuilt.endsWith("#ECH-Node"))

        val fronted = MihomoFrontingPatcher.patchProxyServers(
            rawYaml = imported.yaml,
            serverOverrideIp = "172.67.10.20",
            serverOverridePort = 8443,
            sniOverride = "www.speedtest.net",
        )
        val frontedProxies = MihomoConfigParser.parse(fronted, now).summary.proxies
        assertTrue(frontedProxies[0].rawYaml.contains("server: 172.67.10.20"))
        assertTrue(frontedProxies[0].rawYaml.contains("port: 8443"))
        assertTrue(!frontedProxies[0].rawYaml.contains("www.speedtest.net"))
        assertTrue(frontedProxies[1].rawYaml.contains("www.speedtest.net"))

        val dpiPatched = MihomoDpiBypassPatcher.patch(
            rawYaml = imported.yaml,
            enabled = true,
            proxyPort = 18080,
        )
        val dpiProxies = MihomoConfigParser.parse(dpiPatched, now).summary.proxies
        val echProxy = dpiProxies.first { it.name == "ECH-Node" }
        val standardProxy = dpiProxies.first { it.name == "Standard-Node" }
        assertTrue(!echProxy.rawYaml.contains("dialer-proxy"))
        assertTrue(standardProxy.rawYaml.contains("dialer-proxy: 'CatClient ByeByeDPI'"))
    }
}

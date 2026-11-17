package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta247: what the free-network screens save really imports into runnable proxies (full converter path). */
class Beta247FreeNetworksImportTest {
    private val now = 1_700_000_000_000L

    @Test fun freeProxyTextIsInlineAndBecomesVerifiedHttpsProxies() {
        val loc = FreeProxyPlanner.LOCATIONS.first { it.region == "sg" }
        val links = FreeProxyPlanner.links(loc, listOf(
            FreeProxyServer("s1.example.com", 443, "user1", "p@ss/1"),
            FreeProxyServer("s2.example.com", 8443, "user1", "p@ss/1"),
        ), fa = true)
        val text = FreeProxyPlanner.subscriptionText(links)
        // Text starting with https:// would be downloaded as a URL instead of read as configs.
        assertEquals(UserSubscriptionSourceKind.Inline, UserSubscriptionSourceKind.fromWireName(null, text))
        val imported = UserSubscriptionImporter.import(text, now)
        assertEquals(2, imported.connectionCount)
        val proxies = MihomoConfigParser.parse(imported.yaml, now).summary.proxies
        assertEquals(2, proxies.size)
        proxies.forEach { p ->
            assertEquals("http", p.type)
            assertTrue(p.rawYaml, p.rawYaml.contains("tls: true"))
            assertTrue(p.rawYaml, p.rawYaml.contains("user1"))
            assertTrue(p.rawYaml, p.rawYaml.contains("p@ss/1"))
            assertFalse(p.rawYaml, p.rawYaml.contains("skip-cert-verify: true"))
        }
        assertTrue(proxies[0].rawYaml.contains("s1.example.com"))
        assertTrue(proxies[0].rawYaml, proxies[0].rawYaml.contains("sni: s1.example.com") || proxies[0].rawYaml.contains("sni: 's1.example.com'"))
        // the connect-time swap finds the written credentials in the real runtime YAML
        val written = FreeProxyPlanner.parseCreds(FreeProxyPlanner.credLines("sg", listOf(FreeProxyServer("s1.example.com", 443, "user1", "p@ss/1"))).joinToString("\n"))
        assertTrue(imported.yaml, FreeProxyPlanner.mentions(imported.yaml, written))
        val swapped = FreeProxyPlanner.swapAll(imported.yaml, written, listOf(FreeProxyCred("sg", "s1.example.com", "fresh2", "new#pw")))
        assertFalse(swapped.contains("user1"))
        assertEquals(2, MihomoConfigParser.parse(swapped, now).summary.proxies.count { it.rawYaml.contains("fresh2") })
    }

    @Test fun fanoosLinksImport() {
        val tr = LanternPlanner.translate("""{"options":{"outbounds":[
            {"type":"shadowsocks","server":"1.2.3.4","server_port":8388,"method":"chacha20-ietf-poly1305","password":"pw"},
            {"type":"vless","server":"v.example.com","server_port":443,"uuid":"11111111-2222-3333-4444-555555555555",
              "tls":{"enabled":true,"server_name":"v.example.com","utls":{"fingerprint":"chrome"}},"transport":{"type":"ws","path":"/x","headers":{"Host":"v.example.com"}}},
            {"type":"hysteria2","server":"h.example.com","server_port":443,"password":"hp","tls":{"enabled":true,"server_name":"h.example.com"}},
            {"type":"vmess","server":"m.example.com","server_port":443,"uuid":"11111111-2222-3333-4444-555555555555","tls":{"enabled":true,"server_name":"m.example.com"}}
        ]}}""")
        assertEquals(4, tr.links.size)
        val imported = UserSubscriptionImporter.import(tr.links.joinToString("\n"), now)
        assertEquals(4, imported.connectionCount)
        val types = MihomoConfigParser.parse(imported.yaml, now).summary.proxies.map { it.type }.toSet()
        assertEquals(setOf("ss", "vless", "hysteria2", "vmess"), types)
    }
}

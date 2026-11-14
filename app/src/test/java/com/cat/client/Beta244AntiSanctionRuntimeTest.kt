package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta244 «دور زدن تحریم» in the real engine YAML: one rules list, probes first, DNS policy inside dns:. */
class Beta244AntiSanctionRuntimeTest {
    private val subscription = """
        proxies:
          - name: node-a
            type: vless
            server: edge.example.com
            port: 443
            uuid: 11111111-1111-1111-1111-111111111111
            tls: true
            network: ws
        proxy-groups:
          - name: PROXY
            type: select
            proxies: [node-a]
        rules:
          - MATCH,PROXY
    """.trimIndent()
    private val on = AntiSanctionRouting.Settings(enabled = true, dns = "10.202.10.202", services = setOf("gemini"))

    private fun tunnelled(yaml: String, domain: String) =
        Regex("'DOMAIN-SUFFIX," + Regex.escape(domain) + ",(?!DIRECT')[^']+'").containsMatchIn(yaml)

    private fun runtime(s: AntiSanctionRouting.Settings, mode: RoutingMode = RoutingMode.Subscription) =
        MihomoRuntimeConfigBuilder.flClashRuntimeYaml(rawYaml = subscription, secret = "s", controlPort = 19090, routingMode = mode, antiSanction = s)

    @Test
    fun offChangesNothing() {
        val yaml = runtime(AntiSanctionRouting.Settings())
        assertFalse("no DNS policy", yaml.contains("nameserver-policy"))
        assertFalse("no DIRECT rule", yaml.contains("gemini.google.com,DIRECT"))
        assertTrue("Gemini keeps riding the tunnel", tunnelled(yaml, "gemini.google.com"))
    }

    @Test
    fun onAddsDirectRulesIntoTheOneRulesListAfterTheProbes() {
        for (mode in listOf(RoutingMode.Subscription, RoutingMode.GlobalProxy, RoutingMode.IranBypass)) {
            val yaml = runtime(on, mode)
            assertEquals(mode.name, 1, yaml.lines().count { it == "rules:" })
            val rules = yaml.substringAfter("rules:\n").lines().takeWhile { it.startsWith("  - ") }
            val dnsRule = rules.indexOf("  - 'IP-CIDR,10.202.10.202/32,DIRECT,no-resolve'")
            val svc = rules.indexOf("  - 'DOMAIN-SUFFIX,gemini.google.com,DIRECT'")
            val probe = rules.indexOfFirst { it.contains("DOMAIN,") && !it.contains("DIRECT") }
            val match = rules.indexOfFirst { it.contains("MATCH,") }
            assertTrue(mode.name + " " + rules, dnsRule >= 0 && svc > dnsRule && match > svc)
            if (probe >= 0) assertTrue("probes stay first", probe < dnsRule)
            assertFalse(mode.name + ": the ticked service leaves the forced-tunnel list (it would shadow DIRECT)", tunnelled(yaml, "gemini.google.com"))
            assertTrue(mode.name + ": an unticked AI service keeps riding the tunnel", tunnelled(yaml, "chatgpt.com"))
        }
    }

    @Test
    fun policyLivesInsideTheDnsBlock() {
        val yaml = runtime(on)
        val dns = yaml.substringAfter("\ndns:\n").substringBefore("\ntun:\n")
        assertTrue("policy inside dns: " + dns, dns.contains("  nameserver-policy:\n    '+.gemini.google.com': '10.202.10.202'\n"))
        assertEquals("one dns key", 1, yaml.lines().count { it == "dns:" })
    }
}

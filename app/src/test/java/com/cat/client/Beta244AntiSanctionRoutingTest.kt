package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta244 «دور زدن تحریم»: the pure parts (store format, YAML pieces, DNS packet, verdicts). */
class Beta244AntiSanctionRoutingTest {
    private val on = AntiSanctionRouting.Settings(enabled = true, dns = "178.22.122.100", services = setOf("openai", "docker"))

    @Test
    fun offByDefaultAndInactiveWithoutServices() {
        assertFalse(AntiSanctionRouting.decode(null).enabled)
        assertFalse(AntiSanctionRouting.Settings().active)
        assertFalse(AntiSanctionRouting.Settings(enabled = true).active)
        assertEquals("", AntiSanctionRouting.rulesYaml(AntiSanctionRouting.Settings(enabled = true)))
        assertEquals("", AntiSanctionRouting.nameserverPolicyYaml(on.copy(enabled = false)))
    }

    @Test
    fun storeFormatRoundTripsAndDropsUnknownValues() {
        assertEquals(on, AntiSanctionRouting.decode(AntiSanctionRouting.encode(on)))
        val tampered = AntiSanctionRouting.decode("1|9.9.9.9|openai,evil,docker")
        assertEquals("unknown DNS falls back to the first option", AntiSanctionRouting.DNS_OPTIONS[0].ip, tampered.dns)
        assertEquals(setOf("openai", "docker"), tampered.services)
        assertFalse(AntiSanctionRouting.decode("garbage").enabled)
    }

    @Test
    fun yamlPiecesSendTheServiceDirectAndItsDnsQueriesDirect() {
        val rules = AntiSanctionRouting.rulesYaml(on)
        assertTrue(rules.startsWith("  - 'IP-CIDR,178.22.122.100/32,DIRECT,no-resolve'\n"))
        assertTrue(rules.contains("  - 'DOMAIN-SUFFIX,chatgpt.com,DIRECT'\n"))
        assertTrue(rules.contains("  - 'DOMAIN-SUFFIX,docker.io,DIRECT'\n"))
        assertFalse("only ticked services", rules.contains("anthropic"))
        val policy = AntiSanctionRouting.nameserverPolicyYaml(on)
        assertTrue(policy.startsWith("  nameserver-policy:\n"))
        assertTrue(policy.contains("    '+.openai.com': '178.22.122.100'\n"))
        assertEquals(on.chosen().sumOf { it.domains.size }, policy.lines().count { it.startsWith("    '+.") })
    }

    @Test
    fun everyServiceHasDomainsAndAnHttpsTestTarget() {
        val ids = AntiSanctionRouting.SERVICES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        AntiSanctionRouting.SERVICES.forEach { s ->
            assertTrue(s.id, s.domains.isNotEmpty() && s.domains.all { it.contains('.') && !it.startsWith(".") })
            assertTrue(s.id, s.testPath.startsWith("/") && s.domains.any { s.testHost == it || s.testHost.endsWith(".$it") })
        }
    }

    @Test
    fun dnsQueryAndReplyParsing() {
        val q = AntiSanctionRouting.dnsQuery("api.openai.com", 0x1234)
        assertEquals(0x12, q[0].toInt() and 0xff)
        assertEquals(0x34, q[1].toInt() and 0xff)
        assertEquals(3, q[12].toInt())
        // reply = the query + a CNAME (compressed name) + an A record
        val ans = byteArrayOf(
            0xc0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xc0.toByte(), 16,
            0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 185.toByte(), 51, 200.toByte(), 2,
        )
        val reply = q.copyOf() + ans
        reply[2] = 0x81.toByte(); reply[3] = 0x80.toByte(); reply[7] = 2
        assertEquals("185.51.200.2", AntiSanctionRouting.firstA(reply, reply.size, 0x1234))
        assertNull("wrong id", AntiSanctionRouting.firstA(reply, reply.size, 0x9999))
        val nx = reply.copyOf(); nx[3] = 0x83.toByte()
        assertNull("NXDOMAIN", AntiSanctionRouting.firstA(nx, nx.size, 0x1234))
        assertNull("truncated", AntiSanctionRouting.firstA(reply, reply.size - 3, 0x1234))
    }

    @Test
    fun verdictsComeFromTheRealAnswer() {
        assertEquals(AntiSanctionRouting.Verdict.Works, AntiSanctionRouting.verdict(401, "{\"error\":{\"code\":\"invalid_api_key\"}}"))
        assertEquals(AntiSanctionRouting.Verdict.StillSanctioned, AntiSanctionRouting.verdict(403, "{\"error\":{\"code\":\"unsupported_country_region_territory\"}}"))
        assertEquals(AntiSanctionRouting.Verdict.StillSanctioned, AntiSanctionRouting.verdict(403, "Since Docker is a US company, we must comply with US export control regulations."))
        assertEquals(AntiSanctionRouting.Verdict.StillSanctioned, AntiSanctionRouting.verdict(400, "User location is not supported for the API use."))
        assertEquals(AntiSanctionRouting.Verdict.StillSanctioned, AntiSanctionRouting.verdict(451, ""))
        assertEquals(AntiSanctionRouting.Verdict.Works, AntiSanctionRouting.verdict(200, "<html>sanction news</html>"))
        assertEquals(AntiSanctionRouting.Verdict.ServerError, AntiSanctionRouting.verdict(503, ""))
        assertEquals(401, AntiSanctionRouting.statusOf("HTTP/1.1 401 Unauthorized\r\n"))
        assertEquals(0, AntiSanctionRouting.statusOf("garbage"))
        assertTrue(AntiSanctionRouting.isFilterAddress("10.10.34.35"))
        assertFalse(AntiSanctionRouting.isFilterAddress("185.51.200.2"))
    }
}

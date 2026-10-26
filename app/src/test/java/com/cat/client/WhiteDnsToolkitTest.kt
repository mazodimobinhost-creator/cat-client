package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteDnsToolkitTest {

    @Test fun expandCidrsSpreadsHostsAndSkipsNetworkAndBroadcast() {
        val ips = WhiteDnsToolkit.expandCidrs(listOf("10.1.2.0/24"), perPrefix = 4)
        assertEquals(4, ips.size)
        assertEquals("10.1.2.1", ips.first())
        assertTrue(ips.none { it == "10.1.2.0" || it == "10.1.2.255" })
        assertTrue(ips.all { it.startsWith("10.1.2.") })
    }

    @Test fun expandCidrsHandlesSingleHostsAndRejectsGarbage() {
        assertEquals(listOf("1.2.3.4"), WhiteDnsToolkit.expandCidrs(listOf("1.2.3.4/32")))
        assertTrue(WhiteDnsToolkit.expandCidrs(listOf("2001:db8::/32", "300.1.1.1/24", "nonsense")).isEmpty())
        assertEquals(5, WhiteDnsToolkit.expandCidrs(listOf("8.0.0.0/8"), perPrefix = 100, maxTotal = 5).size)
    }

    @Test fun parseTargetsKeepsExactPorts() {
        val targets = WhiteDnsToolkit.parseTargets("1.1.1.1:8443, 2.2.2.2\n[2606:4700::1]:2053 2606:4700::2")
        assertEquals(WhiteDnsToolkit.Target("1.1.1.1", 8443), targets[0])
        assertEquals(WhiteDnsToolkit.Target("2.2.2.2", null), targets[1])
        assertEquals(WhiteDnsToolkit.Target("2606:4700::1", 2053), targets[2])
        assertEquals(WhiteDnsToolkit.Target("2606:4700::2", null), targets[3])
    }

    @Test fun parseTargetsExpandsCidrsAndRespectsMax() {
        val targets = WhiteDnsToolkit.parseTargets("104.16.0.0/24", perCidr = 8)
        assertEquals(8, targets.size)
        assertTrue(targets.all { it.port == null && it.host.startsWith("104.16.0.") })
        assertEquals(3, WhiteDnsToolkit.parseTargets("1.1.1.1 2.2.2.2 3.3.3.3 4.4.4.4", max = 3).size)
    }

    @Test fun rewriteEndpointKeepsDomainAsSniAndHostAndUsesTheGivenPort() {
        val link = "vless://uuid@sub.example.com:443?type=ws&security=tls&path=%2Fws#My%20node"
        val out = WhiteDnsToolkit.rewriteEndpoint(link, "104.16.1.1", 8443)!!
        assertTrue(out, out.startsWith("vless://uuid@104.16.1.1:8443?"))
        assertTrue(out, "sni=sub.example.com" in out)
        assertTrue(out, "host=sub.example.com" in out)
        assertTrue(out, "path=%2Fws" in out)
    }

    @Test fun rewriteEndpointKeepsTheConfigPortForABareIpAndNeverGuesses() {
        val link = "trojan://pw@cdn.example.org:2083?security=tls#t"
        val out = WhiteDnsToolkit.rewriteEndpoint(link, "188.114.98.0", null)!!
        assertTrue(out, out.startsWith("trojan://pw@188.114.98.0:2083?"))
        assertTrue(out, "sni=cdn.example.org" in out)
    }

    @Test fun rewriteEndpointBracketsIpv6AndRejectsUnknownSchemes() {
        val out = WhiteDnsToolkit.rewriteEndpoint("vless://u@a.example:443?security=tls#x", "2a06:98c1:3101::6812:202f", null)!!
        assertTrue(out, out.startsWith("vless://u@[2a06:98c1:3101::6812:202f]:443?"))
        assertNull(WhiteDnsToolkit.rewriteEndpoint("slipnet://abc", "1.1.1.1", 53))
    }

    @Test fun makeConfigsMultipliesConfigsByEndpoints() {
        val made = WhiteDnsToolkit.makeConfigs(
            listOf("vless://u@a.example:443?security=tls#a", "not a link"),
            listOf(WhiteDnsToolkit.Target("1.1.1.1", null), WhiteDnsToolkit.Target("2.2.2.2", 2053)),
        )
        assertEquals(2, made.size)
        assertTrue(made[0].startsWith("vless://u@1.1.1.1:443?"))
        assertTrue(made[1].startsWith("vless://u@2.2.2.2:2053?"))
    }

    @Test fun extractEndpointsListsUniqueHostPorts() {
        val endpoints = WhiteDnsToolkit.extractEndpoints(listOf(
            "vless://u@1.2.3.4:443?security=tls#a",
            "trojan://p@1.2.3.4:443#dup",
            "hysteria2://p@[2001:db8::1]:8443/?sni=x#h",
        ))
        assertEquals(listOf("1.2.3.4:443", "[2001:db8::1]:8443"), endpoints)
    }

    @Test fun buildDnsQueryHasHeaderQuestionAndOptRecord() {
        val q = WhiteDnsToolkit.buildDnsQuery("www.google.com", 1, edns = true)
        assertEquals(0x01, q[2].toInt())                // RD
        assertEquals(1, q[5].toInt())                   // QDCOUNT
        assertEquals(1, q[11].toInt())                  // ARCOUNT (OPT)
        assertEquals(3, q[12].toInt())                  // "www"
        // header 12 + qname (1+3 +1+6 +1+3 +1) 16 + qtype/qclass 4 + OPT 11
        assertEquals(12 + 16 + 4 + 11, q.size)
        val plain = WhiteDnsToolkit.buildDnsQuery("a.b", 16, edns = false)
        assertEquals(0, plain[11].toInt())
        assertEquals(12 + 5 + 4, plain.size)
    }

    @Test fun ipLiteralDetection() {
        assertTrue(WhiteDnsToolkit.isIpLiteral("104.16.1.1"))
        assertTrue(WhiteDnsToolkit.isIpLiteral("2606:4700::1"))
        assertFalse(WhiteDnsToolkit.isIpLiteral("example.com"))
    }

    @Test fun asnPresetsCoverTheIranianOperatorsFromTheRequest() {
        val asns = WhiteDnsToolkit.ASN_PRESETS.map { it.asn }.toSet()
        listOf(58224, 43211, 42337, 202319, 24631, 13335, 209242, 14789).forEach { assertTrue("AS$it", it in asns) }
    }

    @Test fun appLockBacksOffAfterFiveWrongPins() {
        assertEquals(0L, AppLock.lockoutForFails(4))
        assertEquals(30_000L, AppLock.lockoutForFails(5))
        assertEquals(60_000L, AppLock.lockoutForFails(6))
        assertEquals(15 * 60_000L, AppLock.lockoutForFails(50))
    }

    @Test fun wireGuardConfBecomesAShareLinkWithoutInventedObfuscation() {
        val conf = """
            [Interface]
            PrivateKey = aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI=
            Address = 10.2.0.2/32
            DNS = 10.2.0.1

            [Peer]
            PublicKey = cGVlci1wZWVyLXBlZXItcGVlci1wZWVyLXBlZXItMTI=
            AllowedIPs = 0.0.0.0/0
            Endpoint = 185.159.157.1:51820
        """.trimIndent()
        val uris = WireGuardConfText.toUris(conf)
        assertEquals(1, uris.size)
        val uri = uris.single()
        assertTrue(uri, uri.startsWith("wireguard://"))
        assertTrue(uri, "@185.159.157.1:51820?" in uri)
        assertFalse(uri, "s1=" in uri || "jc=" in uri)
        assertTrue(WireGuardConfText.toUris("just text").isEmpty())
    }

    @Test fun amneziaConfKeepsItsOwnParameters() {
        val conf = "[Interface]\nPrivateKey = a\nAddress = 10.0.0.2/32\nJc = 4\nS1 = 15\n[Peer]\nPublicKey = b\nEndpoint = 1.2.3.4:443\n"
        val uri = WireGuardConfText.toUris(conf).single()
        assertTrue(uri, uri.startsWith("awg://"))
        assertTrue(uri, "jc=4" in uri && "s1=15" in uri)
    }
}

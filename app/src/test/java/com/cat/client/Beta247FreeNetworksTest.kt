package com.cat.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** beta247 🎁 free networks — the pure parts of FreeNetworkEngines.kt (no network). */
class Beta247FreeNetworksTest {
    private val nl = FreeProxyPlanner.LOCATIONS.first { it.region == "nl" }

    @Test fun onlyTheSevenRealFreeRegions() {
        assertEquals(
            setOf("nl", "gb-lnd", "fr-prs", "ru-spb", "sg", "us-va", "us-or"),
            FreeProxyPlanner.LOCATIONS.map { it.region }.toSet(),
        )
        assertEquals(7, FreeProxyPlanner.LOCATIONS.size)
    }

    @Test fun serverListBecomesOneServerPerAddress() {
        val json = """[{"username":"u1","password":"p/1","port":443,"addresses":["s1.example.com","s2.example.com"]},
            {"username":"u2","password":"p2","port":0,"addresses":["bad.example.com"]},
            {"username":"u3","password":"p3","port":8443,"addresses":["s1.example.com"," "]}]"""
        val servers = FreeProxyPlanner.servers(json)
        assertEquals(listOf("s1.example.com:443", "s2.example.com:443", "s1.example.com:8443"), servers.map { "${it.host}:${it.port}" })
        assertTrue(FreeProxyPlanner.servers("not json").isEmpty())
    }

    @Test fun linksVerifyTlsAgainstTheServerName() {
        val servers = listOf(FreeProxyServer("s1.example.com", 443, "u 1", "p@ss:/1"), FreeProxyServer("203.0.113.9", 443, "u", "p"))
        val links = FreeProxyPlanner.links(nl, servers, fa = false)
        assertEquals(2, links.size)
        assertTrue(links[0], links[0].startsWith("https://u%201:p%40ss%3A%2F1@s1.example.com:443?sni=s1.example.com#"))
        assertTrue(links[0].contains("Cat%20Free%20Proxy"))
        // bare IP: no SNI (the core verifies the certificate as-is); never an insecure flag
        assertTrue(links[1], links[1].startsWith("https://u:p@203.0.113.9:443#"))
        assertFalse(links.any { it.contains("insecure") || it.contains("allowInsecure") })
        assertEquals(1, FreeProxyPlanner.links(nl, servers, fa = true, max = 1).size)
    }

    @Test fun subscriptionTextNeverStartsWithHttps() {
        // A subscription whose text starts with https:// is downloaded as a remote URL — the proxy lines must not come first.
        val text = FreeProxyPlanner.subscriptionText(listOf("https://u:p@s1.example.com:443?sni=s1.example.com#a"))
        assertTrue(text.startsWith("#"))
        assertFalse(text.startsWith("http", ignoreCase = true))
        assertTrue(text.lines()[1].startsWith("https://u:p@"))
    }

    @Test fun reserveDomainsAcceptOnlyHttpsHostnames() {
        val json = """{"domains":{"free":["https://new1.example.com/","http://plain.example.com","https://1.2.3.4.example.com"]},
            "free":["https://new2.example.com","https://evil.example.com/path","javascript:alert(1)"]}"""
        assertEquals(
            listOf("https://new1.example.com", "https://1.2.3.4.example.com", "https://new2.example.com"),
            FreeProxyPlanner.reserveDomains(json),
        )
        assertTrue(FreeProxyPlanner.reserveDomains("{oops").isEmpty())
    }

    @Test fun requestBodiesCarryTheExpectedFields() {
        val launch = JSONObject(FreeProxyPlanner.launchBody("dev-1", "Asia/Tehran"))
        assertEquals("dev-1", launch.optString("udid"))
        assertEquals("5.0.2", launch.optString("appVersion"))
        assertEquals("chrome", launch.optString("platform"))
        val list = JSONObject(FreeProxyPlanner.serverListBody("sg"))
        assertEquals("https", list.optString("protocol"))
        assertEquals("sg", list.optString("region"))
        assertEquals(0, list.optInt("type", -1))
    }

    @Test fun credentialSwapTouchesOnlyUsernameAndPasswordValues() {
        val yaml = "proxies:\n  - {name: 'u1 test', type: http, server: s1.example.com, port: 443, username: u1, password: 'p1', tls: true}\n" +
            "  - name: other\n    username: u12\n    password: p1x\n# u1 p1 in a comment\n"
        val written = listOf(FreeProxyCred("nl", "s1.example.com", "u1", "p1"))
        assertTrue(FreeProxyPlanner.mentions(yaml, written))
        assertFalse(FreeProxyPlanner.mentions(yaml, listOf(FreeProxyCred("nl", "h", "u", "p"))))
        val out = FreeProxyPlanner.swapAll(yaml, written, listOf(FreeProxyCred("nl", "s1.example.com", "N\$1", "Q\\2")))
        assertTrue(out, out.contains("username: N\$1, password: 'Q\\2'"))
        assertTrue(out.contains("name: 'u1 test'"))          // the name is untouched
        assertTrue(out.contains("username: u12\n    password: p1x")) // a longer value that merely starts the same
        assertTrue(out.contains("# u1 p1 in a comment"))
        assertEquals(yaml, FreeProxyPlanner.swapAll(yaml, written, emptyList()))
    }

    @Test fun swapPrefersSameHostThenSameRegion() {
        val yaml = "a: {username: a1, password: a2}\nb: {username: b1, password: b2}\n"
        val written = listOf(FreeProxyCred("nl", "h1", "a1", "a2"), FreeProxyCred("sg", "h2", "b1", "b2"))
        val fresh = listOf(FreeProxyCred("sg", "h9", "SG", "sgp"), FreeProxyCred("nl", "h1", "NL", "nlp"))
        val out = FreeProxyPlanner.swapAll(yaml, written, fresh)
        assertEquals("a: {username: NL, password: nlp}\nb: {username: SG, password: sgp}\n", out)
    }

    @Test fun credLinesRoundTrip() {
        val lines = FreeProxyPlanner.credLines("us-va", listOf(FreeProxyServer("h", 443, "u", "p:x")))
        assertEquals(listOf(FreeProxyCred("us-va", "h", "u", "p:x")), FreeProxyPlanner.parseCreds(lines.joinToString("\n") + "\nbroken"))
    }

    @Test fun fanoosUserAndConfigBody() {
        assertEquals("123456789012" to "tok", LanternPlanner.user("""{"userId":123456789012,"token":"tok"}"""))
        assertNull(LanternPlanner.user("""{"userId":0,"token":"x"}"""))
        assertNull(LanternPlanner.user("<html>"))
        val body = JSONObject(LanternPlanner.configBody("dev", "42", "tok", LanternPlanner.Location("Netherlands", "Amsterdam", "NL")))
        assertEquals("dev", body.optString("device_id"))
        assertEquals("lantern", body.optString("app_name"))
        assertEquals("Netherlands", body.optJSONObject("preferred_location")?.optString("country"))
        assertEquals("NL", body.optJSONObject("preferred_location")?.optString("country_code"))
        assertEquals(LanternPlanner.PROTOCOLS.size, body.optJSONArray("protocols")?.length())
        val h = LanternPlanner.headers("dev", "42", "tok")
        assertEquals("42", h["X-Lantern-User-Id"])
        assertFalse(LanternPlanner.headers("dev", "", "").containsKey("X-Lantern-Pro-Token"))
    }

    @Test fun fanoosTranslatesRunnableOutboundsAndCountsTheRest() {
        val cfg = """{"options":{"outbounds":[
            {"type":"direct","tag":"d"},
            {"type":"shadowsocks","server":"1.2.3.4","server_port":8388,"method":"chacha20-ietf-poly1305","password":"pw"},
            {"type":"shadowsocks","server":"1.2.3.5","server_port":8388,"method":"aes-128-gcm","password":"pw","plugin":"obfs-local"},
            {"type":"vless","server":"v.example.com","server_port":443,"uuid":"11111111-2222-3333-4444-555555555555",
              "tls":{"enabled":true,"server_name":"v.example.com","utls":{"fingerprint":"chrome"}},"transport":{"type":"ws","path":"/x","headers":{"Host":"v.example.com"}}},
            {"type":"vless","server":"r.example.com","server_port":443,"uuid":"u","tls":{"enabled":true,"server_name":"www.microsoft.com",
              "reality":{"enabled":true,"public_key":"PBK","short_id":"ab"}}},
            {"type":"trojan","server":"t.example.com","server_port":443,"password":"tp","tls":{"enabled":true,"insecure":true}},
            {"type":"trojan","server":"t2.example.com","server_port":443,"password":"tp"},
            {"type":"hysteria2","server":"h.example.com","server_port":443,"password":"hp","tls":{"enabled":true,"server_name":"h.example.com"}},
            {"type":"samizdat","server":"s.example.com","server_port":443},
            {"type":"vless","server":"q.example.com","server_port":443,"uuid":"u","transport":{"type":"quic"}}
        ]}}"""
        val tr = LanternPlanner.translate(cfg)
        assertEquals(4, tr.links.size)
        assertEquals(mapOf("shadowsocks" to 1, "vless" to 2, "hysteria2" to 1), tr.byType)
        assertEquals(mapOf("shadowsocks" to 1, "trojan" to 2, "samizdat" to 1, "vless" to 1), tr.skipped)
        val ss = tr.links[0]
        assertTrue(ss, ss.startsWith("ss://"))
        assertEquals("chacha20-ietf-poly1305:pw", String(Base64.getUrlDecoder().decode(ss.removePrefix("ss://").substringBefore('@'))))
        assertTrue(tr.links[1], tr.links[1].startsWith("vless://11111111-2222-3333-4444-555555555555@v.example.com:443?encryption=none&security=tls&sni=v.example.com&fp=chrome&type=ws&path=%2Fx&host=v.example.com#"))
        assertTrue(tr.links[2], tr.links[2].contains("security=reality&pbk=PBK&sid=ab&sni=www.microsoft.com"))
        assertTrue(tr.links[3], tr.links[3].startsWith("hysteria2://hp@h.example.com:443?sni=h.example.com#"))
        assertTrue(tr.links.all { it.contains("Cat%20Fanoos") })
        assertTrue(LanternPlanner.translate("garbage").links.isEmpty())
    }

    @Test fun vmessLinkIsValidJson() {
        val ob = JSONObject("""{"type":"vmess","server":"m.example.com","server_port":443,"uuid":"id-1","tls":{"enabled":true,"server_name":"m.example.com"},"transport":{"type":"grpc","service_name":"svc"}}""")
        val link = SingBoxOutboundLinks.link(ob, "n")!!
        val j = JSONObject(String(Base64.getDecoder().decode(link.removePrefix("vmess://"))))
        assertEquals("m.example.com", j.optString("add"))
        assertEquals("grpc", j.optString("net"))
        assertEquals("svc", j.optString("path"))
        assertEquals("tls", j.optString("tls"))
    }

    @Test fun deepLinksGiveTheSubscriptionUrl() {
        val sub = "https://sub.example.org/api/v1/sub/AbCdEf123456"
        val enc = java.net.URLEncoder.encode(sub, "UTF-8")
        assertEquals(sub, FreeAccountLinks.fromDeepLink("hiddify://import/$sub#FreeSocks"))
        assertEquals(sub, FreeAccountLinks.fromDeepLink("v2rayng://install-sub?url=$enc"))
        assertEquals(sub, FreeAccountLinks.fromDeepLink("sing-box://import-remote-profile?url=$enc#x"))
        assertEquals(sub, FreeAccountLinks.fromDeepLink("clash://install-config?url=$enc"))
        assertEquals(sub, FreeAccountLinks.fromDeepLink("karing://install-config?url=$enc"))
        assertEquals(sub, FreeAccountLinks.fromDeepLink("sub://" + Base64.getEncoder().encodeToString("$sub#name".toByteArray())))
        val share = "vless://u@h.example.com:443?security=tls#a"
        assertEquals(share, FreeAccountLinks.fromDeepLink("anywhere://add-proxy?link=" + java.net.URLEncoder.encode(share, "UTF-8")))
        assertNull(FreeAccountLinks.fromDeepLink("intent://whatever"))
        assertNull(FreeAccountLinks.fromDeepLink("v2rayng://install-sub?url=javascript%3Aalert(1)"))
    }

    @Test fun pageTextScan() {
        val text = "Your account number 1234 5678 9012 3456 7890 1234 5678 9012 keep it. " +
            "Subscription: https://sub.example.org/api/v1/sub/Tok_en-12345 and vless://u@h:443?x=1#n, ss://abc@h:1."
        assertEquals("12345678901234567890123456789012", FreeAccountLinks.accountNumber(text))
        assertNull(FreeAccountLinks.accountNumber("123456789012345678901234567890123")) // 33 digits
        assertEquals("1234 5678 9012 3456 7890 1234 5678 9012", FreeAccountLinks.groupAccount("12345678901234567890123456789012"))
        assertEquals(listOf("https://sub.example.org/api/v1/sub/Tok_en-12345"), FreeAccountLinks.subscriptionUrls(text))
        assertEquals(listOf("vless://u@h:443?x=1#n", "ss://abc@h:1"), FreeAccountLinks.shareLinks(text))
    }
}

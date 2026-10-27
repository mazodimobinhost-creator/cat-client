package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VercelRelayTest {
    private val uuid = "3f6b0d2a-9c41-4e7b-8a52-1d2e3f405162"
    private val tlsLink = "vless://$uuid@203.0.113.7:2096?encryption=none&security=tls&sni=srv.example.org" +
        "&alpn=h2%2Chttp%2F1.1&fp=chrome&type=xhttp&host=srv.example.org&path=%2Fcat-xh&mode=auto#My%20XHTTP"

    @Test
    fun acceptsXhttpTlsAndBuildsBackend() {
        val check = VercelRelayPlanner.check(tlsLink)
        assertTrue(check is VercelSourceCheck.Ok)
        val ok = check as VercelSourceCheck.Ok
        assertEquals("https", ok.backend.proto)
        assertEquals("203.0.113.7", ok.backend.connect)
        assertEquals(2096, ok.backend.port)
        assertEquals("srv.example.org", ok.backend.servername)
        assertEquals("srv.example.org", ok.backend.host)
        assertEquals("/cat-xh", ok.backend.path)
        assertFalse(ok.backend.insecure)
        assertTrue(ok.edgeAllowed)
        assertEquals("My XHTTP", ok.source.remark)
        assertEquals(uuid, ok.source.credential)
    }

    @Test
    fun plainAndSelfSignedBackends() {
        val plain = VercelRelayPlanner.check("vless://$uuid@198.51.100.4:8080?security=none&type=xhttp&path=%2Fabc") as VercelSourceCheck.Ok
        assertEquals("http", plain.backend.proto)
        assertEquals("198.51.100.4", plain.backend.host)
        assertFalse("an IP-only server cannot use Edge", plain.edgeAllowed)
        val insecure = VercelRelayPlanner.check("trojan://pass@198.51.100.4:443?security=tls&sni=a.example.net&allowInsecure=1&type=splithttp&path=%2Fp") as VercelSourceCheck.Ok
        assertTrue(insecure.backend.insecure)
        assertFalse(insecure.edgeAllowed)
        assertEquals("trojan", insecure.source.scheme)
    }

    @Test
    fun rejectsWhatVercelCannotRelay() {
        assertTrue(VercelRelayPlanner.check("vless://$uuid@1.2.3.4:443?security=tls&type=ws&path=%2F") is VercelSourceCheck.Rejected)
        assertTrue(VercelRelayPlanner.check("vless://$uuid@1.2.3.4:443?security=reality&type=xhttp&pbk=abc") is VercelSourceCheck.Rejected)
        assertTrue(VercelRelayPlanner.check("vless://$uuid@1.2.3.4:443?security=tls") is VercelSourceCheck.Rejected)
        assertTrue(VercelRelayPlanner.check("vmess://abc") is VercelSourceCheck.Rejected)
        assertTrue(VercelRelayPlanner.check("not a link") is VercelSourceCheck.Rejected)
    }

    @Test
    fun clientLinksKeepCredentialPathAndPointAtVercel() {
        val ok = VercelRelayPlanner.check("$tlsLink&flow=xtls-rprx-vision&pbk=zzz") as VercelSourceCheck.Ok
        val links = VercelRelayPlanner.buildLinks(ok.source, "notes-ab12c.vercel.app", "notes-ab12c")
        assertEquals(2 + VercelRelayPlanner.VERCEL_IPS.size, links.size)
        val addresses = links.map { VercelRelayPlanner.parse(it.link)!!.address }
        assertEquals(listOf("vercel.com", "notes-ab12c.vercel.app") + VercelRelayPlanner.VERCEL_IPS, addresses)
        links.forEach { item ->
            val parsed = VercelRelayPlanner.parse(item.link)
            assertNotNull(parsed)
            parsed!!
            assertEquals(uuid, parsed.credential)
            assertEquals(443, parsed.port)
            assertEquals("tls", parsed.params["security"])
            assertEquals("xhttp", parsed.params["type"])
            assertEquals("packet-up", parsed.params["mode"])
            assertEquals("/cat-xh", parsed.params["path"])
            assertEquals("notes-ab12c.vercel.app", parsed.params["host"])
            assertEquals("none", parsed.params["encryption"])
            assertFalse(parsed.params.containsKey("flow"))
            assertFalse(parsed.params.containsKey("pbk"))
            assertTrue(parsed.remark.startsWith("🔺Vercel-"))
        }
        assertEquals("vercel.com", VercelRelayPlanner.parse(links[0].link)!!.params["sni"])
        assertEquals("notes-ab12c.vercel.app", VercelRelayPlanner.parse(links[2].link)!!.params["sni"])
    }

    @Test
    fun templatesAreFilledWithTheBackend() {
        val ok = VercelRelayPlanner.check(tlsLink) as VercelSourceCheck.Ok
        listOf(VercelRuntime.Node to "relay-node.js", VercelRuntime.Edge to "relay-edge.js").forEach { (runtime, file) ->
            val template = File("src/main/assets/vercel/$file").readText()
            val files = VercelRelayPlanner.files(template, ok.backend, runtime, "fra1", "notes-ab12c", 300)
            assertEquals(listOf("package.json", "vercel.json", "api/index.js"), files.keys.toList())
            val js = files.getValue("api/index.js")
            assertFalse(js.contains("example.com"))
            assertTrue(js.contains("\"servername\":\"srv.example.org\""))
            assertTrue(js.contains("\"path\":\"/cat-xh\""))
            val expectedConnect = if (runtime == VercelRuntime.Edge) "srv.example.org" else "203.0.113.7"
            assertTrue(js.contains("\"connect\":\"$expectedConnect\""))
            assertTrue(files.getValue("vercel.json").contains("/api/index"))
        }
        assertTrue(VercelRelayPlanner.vercelJson(VercelRuntime.Node, "fra1", 60).contains("\"maxDuration\":60"))
        assertTrue(VercelRelayPlanner.vercelJson(VercelRuntime.Node, "fra1", 300).contains("\"regions\":[\"fra1\"]"))
    }

    @Test
    fun namesUuidsAndEscaping() {
        repeat(50) {
            val name = VercelRelayPlanner.randomProjectName()
            assertTrue(name, VercelRelayPlanner.isValidProjectName(name))
            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(VercelRelayPlanner.randomUuid()))
            assertTrue(VercelRelayPlanner.randomPath().matches(Regex("/[a-z0-9]{10}")))
        }
        assertFalse(VercelRelayPlanner.isValidProjectName("Bad Name"))
        assertEquals("\"a\\\"b\\\\c\\u003c/script\\u003e\"", VercelRelayPlanner.jsonString("a\"b\\c</script>"))
        assertTrue(VercelRelayPlanner.isIpLiteral("76.76.21.21"))
        assertTrue(VercelRelayPlanner.isIpLiteral("2a06:98c1::1"))
        assertFalse(VercelRelayPlanner.isIpLiteral("vercel.com"))
    }

    @Test
    fun vpsHelperLinkIsRelayable() {
        val link = VercelRelayPlanner.serverLink(uuid, "198.51.100.9", 8080, "abcdef")
        val ok = VercelRelayPlanner.check(link) as VercelSourceCheck.Ok
        assertEquals("http", ok.backend.proto)
        assertEquals("/abcdef", ok.backend.path)
        assertTrue(VercelRelayPlanner.serverInboundJson(uuid, 8080, "/abcdef").contains("\"network\": \"xhttp\""))
        assertEquals(listOf(link), VercelRelayPlanner.relayableLinks("junk $link\nvless://$uuid@1.1.1.1:443?type=ws"))
    }

    @Test
    fun picksProductionAlias() {
        assertEquals("notes-ab12c.vercel.app", VercelRelayPlanner.pickProductionHost(listOf("notes-ab12c-git-main-me.vercel.app", "notes-ab12c.vercel.app"), "notes-ab12c"))
        assertEquals("notes-ab12c-me.vercel.app", VercelRelayPlanner.pickProductionHost(listOf("notes-ab12c-git-main-me.vercel.app", "notes-ab12c-me.vercel.app"), "notes-ab12c"))
    }
}

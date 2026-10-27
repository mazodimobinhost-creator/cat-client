package com.cat.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class VercelPanelTest {
    private val uuid = "719f3599-09b0-4c6f-9f8d-bd78f0d36c57"
    private val host = "cat-panel-v7k2m.vercel.app"

    private fun asset(path: String): File = listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path")).first { it.isFile }

    @Test
    fun patchesTheRealPanelBundleExactlyOnce() {
        val source = asset(VercelPanelPlanner.PANEL_ASSET).readText()
        val patched = VercelPanelPlanner.patchPanel(source)
        assertFalse(patched.contains("cloudflare:sockets\")"))
        assertTrue(patched.contains("Promise.resolve(globalThis.__catSockets)"))
        assertTrue(patched.contains("export"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesABundleWithoutTheSocketImport() {
        VercelPanelPlanner.patchPanel("export default { fetch() {} }")
    }

    @Test
    fun realAdapterLoadsThePanelFromLib() {
        val adapter = asset(VercelPanelPlanner.ADAPTER_ASSET).readText()
        assertEquals(adapter, VercelPanelPlanner.checkAdapter(adapter))
        assertTrue(adapter.contains("env.CAT_PLATFORM = 'vercel'"))
    }

    @Test
    fun vercelJsonKeepsLibAndRewritesEverything() {
        val json = VercelPanelPlanner.vercelJson("fra1", 300)
        assertTrue(json.contains("\"framework\":null"))
        assertTrue(json.contains("\"regions\":[\"fra1\"]"))
        assertTrue(json.contains("\"maxDuration\":300"))
        assertTrue(json.contains("\"includeFiles\":\"lib/**\""))
        assertTrue(json.contains("\"destination\":\"/api/index\""))
        assertFalse(VercelPanelPlanner.vercelJson(null, 60).contains("regions"))
        val pkg = VercelPanelPlanner.packageJson("notes-ab12c")
        assertTrue(pkg.contains("\"type\":\"module\"") && pkg.contains("\"ws\"") && pkg.contains("24.x"))
        assertTrue(pkg.contains("\"@vercel/blob\""))
    }

    @Test
    fun envVarsEnableTheDataPlaneAndKeepAdminDefault() {
        val env = VercelPanelPlanner.envVars(uuid, "pass123", "")
        assertEquals(uuid, env["UUID"])
        assertEquals("pass123", env["TROJAN_PASS"])
        assertEquals("true", env["DATA_PLANE_ENABLED"])
        assertFalse(env.containsKey("PANEL_PASSWORD"))
        assertEquals("s3cret", VercelPanelPlanner.envVars(uuid, "p", "s3cret")["PANEL_PASSWORD"])
        assertEquals(20, VercelPanelPlanner.randomSecret().length)
    }

    @Test
    fun readsTheBase64SubscriptionAndChecksItFitsVercel() {
        val lines = listOf(
            "vless://$uuid@76.76.21.21:443?encryption=none&security=tls&type=ws&host=$host&path=%2Fvl%2Fabc%3Fed%3D2560&sni=$host&fp=chrome&alpn=http%2F1.1#a",
            "trojan://pw@vercel.com:443?security=tls&type=ws&host=$host&path=%2Ftr%2Fabc%3Fed%3D2560&sni=$host&fp=chrome&alpn=http%2F1.1#b",
        )
        val body = Base64.getEncoder().encodeToString(lines.joinToString("\n").toByteArray())
        val links = VercelPanelPlanner.extractSubLinks(body)
        assertEquals(lines, links)
        assertTrue(VercelPanelPlanner.linksFitVercel(links, host))
        // A Cloudflare-style line (alt port / other SNI) must not be accepted as a Vercel sub.
        assertFalse(VercelPanelPlanner.linksFitVercel(links + "vless://$uuid@104.17.25.14:2053?security=tls&sni=$host&host=$host#c", host))
        assertFalse(VercelPanelPlanner.linksFitVercel(emptyList(), host))
    }

    @Test
    fun fallbackLinksUseOnly443WithProjectSni() {
        val links = VercelPanelPlanner.fallbackLinks(uuid, "pw+1", host, "notes-ab12c").map { it.link }
        assertEquals((1 + VercelPanelPlanner.EDGE_ADDRESSES.size) * 2, links.size)
        assertTrue(VercelPanelPlanner.linksFitVercel(links, host))
        assertTrue(links.any { it.startsWith("vless://$uuid@76.76.21.21:443?") && it.contains("path=%2Fws") })
        assertTrue(links.any { it.startsWith("trojan://pw%2B1@vercel.com:443?") && it.contains("path=%2Ftrojan") })
        assertTrue(links.any { it.contains("@$host:443?") })
    }

    @Test
    fun vlessRequestMatchesTheWireFormat() {
        val req = VercelPanelProbe.vlessRequest(uuid, "example.com", 80, "X".toByteArray())
        assertEquals(0, req[0].toInt())
        assertEquals(0x71, req[1].toInt() and 0xff)
        assertEquals(0x57, req[16].toInt() and 0xff)
        assertEquals(0, req[17].toInt()) // addons
        assertEquals(1, req[18].toInt()) // tcp
        assertEquals(80, ((req[19].toInt() and 0xff) shl 8) or (req[20].toInt() and 0xff))
        assertEquals(2, req[21].toInt())
        assertEquals(11, req[22].toInt())
        assertEquals("example.com", String(req, 23, 11))
        assertEquals('X'.code, req.last().toInt())
    }

    @Test
    fun webSocketFramesRoundTrip() {
        val payload = ByteArray(300) { (it * 7).toByte() }
        val mask = byteArrayOf(1, 2, 3, 4)
        val frame = VercelPanelProbe.maskedBinaryFrame(payload, mask)
        assertEquals(0x82, frame[0].toInt() and 0xff)
        assertEquals(0x80 or 126, frame[1].toInt() and 0xff)
        assertArrayEquals(payload, VercelPanelProbe.unmaskedPayloads(frame).single())
        val small = byteArrayOf(0x82.toByte(), 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())
        assertEquals("abc", String(VercelPanelProbe.unmaskedPayloads(small + small).joinToString("") { String(it) }.toByteArray()).take(3))
        assertEquals(2, VercelPanelProbe.unmaskedPayloads(small + small).size)
        assertTrue(VercelPanelProbe.unmaskedPayloads(small.copyOf(4)).isEmpty())
    }

    @Test
    fun scannerAcceptsWsTlsConfigsOnADomainOnly() {
        val ws = "vless://$uuid@76.76.21.21:443?encryption=none&security=tls&type=ws&host=$host&path=%2Fvl%2Fabc%3Fed%3D2560&sni=$host&fp=chrome&alpn=http%2F1.1#a"
        val target = VercelIpScanner.target(ws)!!
        assertEquals(host, target.sni)
        assertEquals(host, target.host)
        assertEquals("/vl/abc", target.path)
        assertEquals(443, target.port)
        assertEquals(null, VercelIpScanner.target(ws.replace("type=ws", "type=xhttp")))
        assertEquals(null, VercelIpScanner.target(ws.replace("security=tls", "security=none")))
        assertEquals(null, VercelIpScanner.target("vless://$uuid@1.2.3.4:443?security=tls&type=ws&sni=1.2.3.4#ip-only"))
        val trojan = "trojan://pw@vercel.com:443?security=tls&type=ws&host=$host&path=%2Ftr%2Fx&sni=$host#t"
        assertEquals("trojan", VercelIpScanner.target(trojan)!!.scheme)
        val sub = Base64.getEncoder().encodeToString("ss://junk\n$trojan\n$ws".toByteArray())
        assertEquals("trojan", VercelIpScanner.firstTarget(sub)!!.scheme)
    }

    @Test
    fun stripsOnlyTheEarlyDataParameter() {
        assertEquals("/vl/abc", VercelIpScanner.stripEarlyData("/vl/abc?ed=2560"))
        assertEquals("/ws?proxyip=x", VercelIpScanner.stripEarlyData("/ws?ed=2048&proxyip=x"))
        assertEquals("/trojan", VercelIpScanner.stripEarlyData("/trojan"))
    }

    @Test
    fun scannedLinkSwapsOnlyAddressAndNameAndKeepsTheTestedPort() {
        val ws = "vless://$uuid@vercel.com:443?encryption=none&security=tls&type=ws&host=$host&path=%2Fws&sni=$host&fp=chrome#old"
        val target = VercelIpScanner.target(ws)!!
        val link = VercelIpScanner.linkFor(target, VercelScanHit("216.150.16.7", 443, 40, 95), 1)
        assertTrue(link.startsWith("vless://$uuid@216.150.16.7:443?encryption=none&security=tls&type=ws&host=$host&path=%2Fws&sni=$host&fp=chrome#"))
        assertFalse(link.contains("vercel.com:443"))
        assertTrue(link.contains("216.150.16.7") && link.contains("95%20ms"))
    }

    @Test
    fun trojanRequestMatchesTheWireFormat() {
        val req = VercelPanelProbe.trojanRequest("pw", "example.com", 80, "X".toByteArray())
        val hex = String(req, 0, 56, Charsets.US_ASCII)
        assertTrue(hex.matches(Regex("[0-9a-f]{56}")))
        assertEquals(13, req[56].toInt()); assertEquals(10, req[57].toInt())
        assertEquals(1, req[58].toInt()); assertEquals(3, req[59].toInt()); assertEquals(11, req[60].toInt())
        assertEquals("example.com", String(req, 61, 11))
        assertEquals(80, ((req[72].toInt() and 0xff) shl 8) or (req[73].toInt() and 0xff))
        assertEquals('X'.code, req.last().toInt())
    }

    @Test
    fun candidatesAreTheMappedLiveEdgeAddresses() {
        val quick = VercelIpScanner.candidates(false)
        val deep = VercelIpScanner.candidates(true)
        assertEquals(60, quick.size)
        assertEquals(167, deep.size)
        assertTrue(deep.containsAll(quick))
        VercelIpScanner.NETS_24.forEach { net -> assertTrue(net, quick.containsAll(listOf("$net.1", "$net.65", "$net.129", "$net.193"))) }
        assertTrue(quick.containsAll(listOf("76.76.21.21", "76.76.21.93", "76.76.21.164", "66.33.60.65", "216.198.79.65", "64.29.17.65")))
        assertFalse(deep.any { it.startsWith("143.13.") || it.startsWith("155.121.") || it.endsWith(".229") })
        assertTrue(deep.all { ip -> ip.split('.').size == 4 && ip.split('.').all { it.toInt() in 0..255 } })
    }

    @Test
    fun panelHealthReadsTheLiveVersionAnswer() {
        val bundled = PanelUpdate.parseVersion(asset(VercelPanelPlanner.PANEL_ASSET).readText())!!
        // exactly what cat-panel-v7k2m answered on 2026-10-27 (6.69.38, no storage → «0 configs»)
        val live = """{"ok":true,"panel":"cat-panel","version":"6.69.38","needsUser":true,"kv":false,"open":false}"""
        val noStorage = VercelPanelPlanner.health(200, live, bundled)
        assertTrue(noStorage is VercelPanelHealth.NoStorage)
        assertEquals("6.69.38", (noStorage as VercelPanelHealth.NoStorage).version)
        assertTrue(noStorage.needsUpdate)

        val ok = VercelPanelPlanner.health(200, """{"ok":true,"panel":"cat-panel","version":"$bundled","kv":true}""", bundled)
        assertEquals(VercelPanelHealth.Ok(bundled), ok)
        assertFalse(ok.needsUpdate)

        val old = VercelPanelPlanner.health(200, """{"panel":"cat-panel","version":"6.60.1","kv":true}""", bundled)
        assertEquals(VercelPanelHealth.Outdated("6.60.1", bundled), old)

        assertTrue(VercelPanelPlanner.health(404, "The deployment could not be found", bundled, "DEPLOYMENT_NOT_FOUND") is VercelPanelHealth.Unreachable)
        assertTrue(VercelPanelPlanner.health(200, "<html>login</html>", bundled) is VercelPanelHealth.Unreachable)
        assertTrue(VercelPanelPlanner.health(0, "", bundled) is VercelPanelHealth.Unreachable)
    }
}

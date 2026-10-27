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
}

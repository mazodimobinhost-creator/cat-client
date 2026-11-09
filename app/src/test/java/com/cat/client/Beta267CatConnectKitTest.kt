package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta267CatConnectKitTest {

    @Test
    fun versionIsBeta267() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 359"))
        assertTrue(gradle.contains("1.10.0-beta292"))
        val worker = listOf(
            File("app/src/main/assets/panels/catclient.worker.js"),
            File("src/main/assets/panels/catclient.worker.js"),
        ).first { it.isFile }.readText()
        assertTrue(worker.contains("CAT_PANEL_VERSION = '6.89.0'"))
        assertFalse(worker.contains("CAT_PANEL_VERSION = '6.88.0'"))
    }

    @Test
    fun naiveIsRejectedNotRewrittenToHttp() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            SubConvConverter.convert("naive+https://user:pass@naive.example:443")
        }
        assertTrue(ex.message!!.contains("Chromium NaiveProxy"))
        val quic = assertThrows(IllegalArgumentException::class.java) {
            SubConvConverter.convert("naive+quic://user:pass@naive.example:443")
        }
        assertTrue(quic.message!!.contains("کرومیوم") || quic.message!!.contains("Chromium"))
        assertTrue(SubConvConverter.isNaiveShare("naive+https://u:p@h:443"))
        assertFalse(SubConvConverter.isNaiveShare("https://u:p@h:443"))
        val mixed = SubConvConverter.convert(
            "vless://11111111-2222-4333-8444-555555555555@example.com:443?encryption=none&security=tls&type=ws#Keep\n" +
                "naive+https://user:pass@naive.example:443",
        )
        assertEquals(1, mixed.size)
        assertEquals("vless", mixed[0].getString("type"))
        assertFalse(mixed.any { it.optString("type") == "http" || it.optString("type") == "https" })
        val inspect = SubConvConverter.inspectUniversalConfigInput("naive+https://user:pass@naive.example:443")
        assertTrue(inspect.formatCounts.containsKey("NaiveProxy (unsupported)"))
        assertTrue(inspect.normalizedSubscriptionText.isBlank())
        val http = src("SubConvConverter.kt")
        assertFalse(http.contains("naive+https://\", RegexOption.IGNORE_CASE), \"https://\""))
        assertFalse(http.contains("\"http\", \"https\", \"naive+https\", \"naive+quic\""))
    }

    @Test
    fun dedicatedTuicWireGuardAetherUrisRoundTrip() {
        val tuic = ProtocolConnect.Draft(
            kind = ProtocolConnect.Kind.Tuic,
            name = "Cat-TUIC",
            server = "tuic.example",
            port = 443,
            uuid = "11111111-2222-4333-8444-555555555555",
            password = "secret",
            sni = "tuic.example",
        )
        val tuicUri = ProtocolConnect.toShareUri(tuic)
        assertTrue(tuicUri.startsWith("tuic://"))
        val parsedTuic = ProtocolConnect.parse(tuicUri)!!
        assertEquals("tuic.example", parsedTuic.server)
        assertEquals("11111111-2222-4333-8444-555555555555", parsedTuic.uuid)
        val converted = SubConvConverter.convert(tuicUri)
        assertEquals("tuic", converted.single().getString("type"))
        assertEquals(443, converted.single().getInt("port"))

        val wg = ProtocolConnect.empty(ProtocolConnect.Kind.WireGuard)
        val wgUri = ProtocolConnect.toShareUri(wg)
        assertTrue(wgUri.startsWith("wireguard://auto@162.159.193.1:443?"))
        assertTrue(wgUri.contains("publickey="))
        val parsedWg = ProtocolConnect.parse(wgUri)!!
        assertEquals(ProtocolConnect.Kind.WireGuard, parsedWg.kind)
        assertEquals(443, parsedWg.port)
        val wgConverted = SubConvConverter.convert(wgUri)
        assertEquals("wireguard", wgConverted.single().getString("type"))
        assertEquals(443, wgConverted.single().getInt("port"))

        val aether = ProtocolConnect.empty(ProtocolConnect.Kind.Aether)
        val aetherUri = ProtocolConnect.toShareUri(aether)
        assertTrue(aetherUri.startsWith("aether://162.159.198.1:443?"))
        assertTrue(aetherUri.contains("protocol=masque"))
        val sel = ProtocolConnect.toAetherSelection(aether)
        assertEquals(AetherMode.MasqueHttp2, sel.mode)
        assertEquals("162.159.198.1:443", sel.endpoint)
        assertNull(ProtocolConnect.parse("aether://162.159.198.1:443?protocol=masque#x"))
    }

    @Test
    fun pairingCodeShapeAndHttpsOrigin() {
        assertTrue(CatPanelPair.looksLikeCode("AB23CD"))
        assertTrue(CatPanelPair.looksLikeCode("ab23cd"))
        assertFalse(CatPanelPair.looksLikeCode("IIIIII"))
        assertFalse(CatPanelPair.looksLikeCode("ABC"))
        assertEquals("https://panel.example.workers.dev", CatPanelPair.httpsOrigin("panel.example.workers.dev"))
        assertNull(CatPanelPair.httpsOrigin("http://panel.example.workers.dev"))
        // CI copies the minified worker over this path before JVM tests, so only
        // string literals that survive minify.mjs are compared here.
        val worker = listOf(
            File("app/src/main/assets/panels/catclient.worker.js"),
            File("src/main/assets/panels/catclient.worker.js"),
        ).first { it.isFile }.readText()
        assertTrue(worker.contains("/api/pair/claim"))
        assertTrue(worker.contains("/api/pair/start"))
        assertTrue(worker.contains("/api/pair/status"))
        assertTrue(worker.contains("162.159.193.1:443"))
        assertTrue(worker.contains("162.159.198.1:443"))
        assertFalse(worker.contains("Omni"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).firstOrNull { it.isFile }?.readText().orEmpty()
}

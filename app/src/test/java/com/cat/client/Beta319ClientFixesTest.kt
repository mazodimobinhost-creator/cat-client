package com.cat.client

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta319 — Edge picker pool, finder obfs abort, share, v2rayNG-style editor, Edge paste source. */
class Beta319ClientFixesTest {

    @Test
    fun versionIsBeta319() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 388"))
        assertTrue(gradle.contains("1.10.0-beta321"))
    }

    @Test
    fun edgePickerTakesTheSignedListPoolNotFourRowsAndDoesNotInventCountries() {
        val nodes = (1..40).map { i ->
            CatEdgePlanner.Node("g$i", "g$i.example.com", 443, "global", "public")
        } + (1..40).map { i ->
            CatEdgePlanner.Node("i$i", "i$i.example.com", 443, "ir", "public", country = "IR")
        }
        val picked = CatEdgePlanner.pickAllCountries(nodes)
        assertTrue("reservoir is the signed list, not 4 per country", picked.size > 8)
        assertEquals(32, picked.count { CatEdgePlanner.locationCode(it) == "GLOBAL" })
        assertEquals(32, picked.count { CatEdgePlanner.locationCode(it) == "IR" })
        assertFalse("no invented country", picked.any { CatEdgePlanner.locationCode(it) == "DE" })
        assertEquals(CatEdgePlanner.PICK_PER_COUNTRY, 32)
        assertEquals(CatEdgePlanner.PICK_MAX_TOTAL, 80)
        val ui = src("MainActivityFreeNetworks.kt")
        assertTrue(ui.contains("کشور ساختگی"))
        assertTrue(ui.contains("پل یا دامنه را Cat از جایی دانلود نمی‌کند") || ui.contains("Nothing is fetched from the internet here"))
        assertTrue(ui.contains("listHeight"))
        assertFalse(ui.contains("setSingleChoiceItems(labels"))
        assertTrue(ui.contains("PICK_MAX_TOTAL"))
    }

    @Test
    fun hysteria2WithoutObfsPasswordDoesNotAbortTheCoreYaml() {
        val missing = "hysteria2://pw@203.0.113.9:443?sni=example.org&obfs=salamander#x"
        val parsed = MethodFinderPlan.parseFreeServer(missing)!!
        assertTrue(parsed.incompleteObfs)
        assertFalse(parsed.obfs)
        val good = MethodFinderPlan.parseFreeServer(
            "hysteria2://41d4f8b3-2a45-4531-a33b-938e2eebb939@oh.sbbz.tech:443?sni=oh.sbbz.tech&obfs=salamander&obfs-password=x19890604#US",
        )!!
        assertTrue(good.obfs)
        assertFalse(good.incompleteObfs)
        val picked = MethodFinderPlan.pickFreeServers(listOf(parsed, good))
        assertEquals(listOf("oh.sbbz.tech"), picked.map { it.host })
        val converted = SubConvConverter.convert(missing).single()
        assertFalse(converted.has("obfs"))
        assertEquals("", converted.optString("obfs-password"))
        val kept = SubConvConverter.convert(good.link).single()
        assertEquals("salamander", kept.optString("obfs"))
        assertEquals("x19890604", kept.optString("obfs-password"))
        val yaml = MihomoLinkConfigBuilder.build(
            listOf(
                JSONObject()
                    .put("name", "hy2-bad")
                    .put("type", "hysteria2")
                    .put("server", "203.0.113.9")
                    .put("port", 443)
                    .put("password", "pw")
                    .put("obfs", "salamander"),
            ),
        )
        assertFalse(yaml.contains("obfs"))
        val msg = MethodFinderPlan.plainCoreError("proxy 43: missing obfs password", fa = true)
        assertTrue(msg.contains("Hysteria2") || msg.contains("obfuscation"))
        assertFalse(msg.contains("proxy 43"))
    }

    @Test
    fun localSubscriptionsGetShareAndEditorShowsProtocolSections() {
        val sub = src("MainActivitySubscriptions.kt")
        assertFalse("share is not gated on https:// only", sub.contains("= if (isRemote) listOf("))
        assertTrue(sub.contains("R.string.subscription_action_share to { shareSubscriptionLink(item) }"))
        assertTrue(sub.contains("R.string.subscription_action_copy_link to { copySubscriptionLink(item) }"))
        assertTrue(src("MainActivityShareLinkEditor.kt").contains("TLS / Reality"))
        assertTrue(src("MainActivityShareLinkEditor.kt").contains("showShareLinkEditor"))
        assertTrue(src("MainActivityCatWarp.kt").contains("ShareLinkEditor.lines"))
        val vless = "vless://11111111-2222-3333-4444-555555555555@edge.example.com:443" +
            "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com" +
            "&fp=chrome&pbk=publicKEY&sid=abcd&type=tcp&headerType=none#MyVless"
        val draft = ShareLinkEditor.parse(vless)
        assertNotNull(draft)
        assertEquals("vless", draft!!.type)
        assertEquals("edge.example.com", draft.server)
        assertEquals(443, draft.port)
        assertEquals("11111111-2222-3333-4444-555555555555", draft.uuid)
        assertEquals("reality", draft.security)
        assertEquals("www.microsoft.com", draft.sni)
        assertEquals("chrome", draft.fingerprint)
        assertEquals("publicKEY", draft.publicKey)
        assertEquals("abcd", draft.shortId)
        assertEquals("xtls-rprx-vision", draft.flow)
        val rebuilt = draft.copy(sni = "www.cloudflare.com", name = "Edited").toLink()
        assertNotNull(rebuilt)
        assertTrue(rebuilt!!.startsWith("vless://"))
        assertTrue(rebuilt.contains("www.cloudflare.com"))
        assertTrue(rebuilt.contains("pbk="))
        val round = ShareLinkEditor.parse(rebuilt)!!
        assertEquals("www.cloudflare.com", round.sni)
        assertEquals("publicKEY", round.publicKey)
        assertEquals("Edited", round.name)
        assertEquals(0, ShareLinkEditor.lines("https://panel.example/u/token").size)
        assertEquals(0, ShareLinkEditor.lines("aether://warp?protocol=masque#x").size)
        assertEquals(1, ShareLinkEditor.lines(vless).size)
    }

    companion object {
        private fun src(name: String): String = listOf(
            File("src/main/java/com/cat/client/$name"),
            File("app/src/main/java/com/cat/client/$name"),
        ).first { it.isFile }.readText()
    }
}

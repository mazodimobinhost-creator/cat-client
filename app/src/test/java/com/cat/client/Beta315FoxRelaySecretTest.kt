package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * beta315 — a bare workers.dev paste is missing `?secret=`, not a mysterious
 * "invalid". Test stays in the dialog so the user can type the secret.
 */
class Beta315FoxRelaySecretTest {

    @Test
    fun versionIsBeta315() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 382"))
        assertTrue(gradle.contains("1.10.0-beta315"))
    }

    @Test
    fun bareWorkersDevUrlIsNeedSecretNotInvalid() {
        val raw = "https://my-relay.example.workers.dev/"
        assertNull(FoxMozillaRelay.normalize(raw))
        assertEquals("need-secret", FoxMozillaRelay.formIssue(raw)!!.detail)
        assertEquals(
            "https://my-relay.example.workers.dev/?secret=",
            FoxMozillaRelay.withEmptySecretQuery(raw),
        )
        val fa = FoxMozillaRelay.probeLabel("need-secret", true)
        assertTrue(fa.contains("رمز نیست"))
        assertTrue(fa.contains("?secret="))
        assertFalse(fa.contains("invalid"))
        assertNull(FoxMozillaRelay.formIssue("https://my-relay.example.workers.dev/?secret=abc12345"))
        assertEquals(
            "https://my-relay.example.workers.dev/?secret=abc12345",
            FoxMozillaRelay.normalize("https://my-relay.example.workers.dev/?secret=abc12345"),
        )
        assertNull(FoxMozillaRelay.withEmptySecretQuery("https://my-relay.example.workers.dev/?secret=abc12345"))
        assertEquals("empty", FoxMozillaRelay.formIssue("")!!.detail)
        assertEquals("need-https", FoxMozillaRelay.formIssue("http://my-relay.example.workers.dev/?secret=abc")!!.detail)
    }

    @Test
    fun healthHttpCodesMapToSecretMessages() {
        assertEquals("wrong-secret", FoxMozillaRelay.parseHealth(403, "wrong secret").detail)
        assertEquals("wrong-secret", FoxMozillaRelay.parseHealth(401, "wrong secret").detail)
        assertEquals("secret-unset", FoxMozillaRelay.parseHealth(503, "no secret is set in the relay").detail)
        assertTrue(FoxMozillaRelay.parseHealth(200, """{"relay":"cat-fox","version":1,"ready":true}""").ok)
        assertEquals("not json", FoxMozillaRelay.parseHealth(200, "Hello World").detail)
        assertTrue(FoxMozillaRelay.probeLabel("wrong-secret", true).contains("رمز اشتباه"))
        assertTrue(FoxMozillaRelay.probeLabel("secret-unset", true).contains("SECRET خالی"))
        assertTrue(FoxMozillaRelay.probeLabel("not json", true).contains("رله نیست"))
        assertFalse(FoxMozillaRelay.probeLabel("wrong-secret", true).contains("invalid"))
    }

    @Test
    fun dialogKeepsTestOpenAndShowsWrongVsRightUrl() {
        val fox = src("MainActivityFox.kt")
        assertTrue(fox.contains("withEmptySecretQuery"))
        assertTrue(fox.contains("probeLabel"))
        assertTrue(fox.contains("formIssue"))
        assertTrue(fox.contains("🔍 تست رله"))
        assertFalse(fox.contains("setNeutralButton(t(\"تست\""))
        assertTrue(fox.contains("BUTTON_POSITIVE"))
        assertTrue(fox.contains("این غلط است"))
        assertTrue(fox.contains("https://NAME.workers.dev/?secret="))
        assertFalse(fox.contains("رله جواب نداد: \${probe.detail}"))
        val engine = src("FirefoxVpnEngine.kt")
        assertTrue(engine.contains("FoxMozillaRelay.formIssue(relayUrl)"))
        assertFalse(fox.contains("temomail"))
        assertFalse(fox.contains("Spider"))
        assertFalse(src("FoxMozillaRelay.kt").contains("Spider"))
        assertNotNull(FoxMozillaRelay.healthUrl("https://my-relay.example.workers.dev/?secret=abc12345"))
    }

    companion object {
        private fun src(name: String): String = listOf(
            File("src/main/java/com/cat/client/$name"),
            File("app/src/main/java/com/cat/client/$name"),
        ).first { it.isFile }.readText()
    }
}

package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta273 — Cat Wind after login adds every allowed config; WebView session is not a no-op. */
class Beta273CatWindAutoAddTest {

    @Test
    fun versionIsBeta273() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 363"))
        assertTrue(gradle.contains("1.10.0-beta296"))
    }

    @Test
    fun sessionHashFromCookiesAndHtmlAndUrl() {
        assertEquals(
            "abcDEF1234567890",
            CatWindPlanner.sessionHashFromCookies("csrf=x; session_auth_hash=abcDEF1234567890; other=1"),
        )
        assertEquals(
            "hashFromWs",
            CatWindPlanner.sessionHashFromCookies("ws_session_auth_hash=hashFromWs; a=b"),
        )
        assertEquals(
            "fromHtmlHashValue12",
            CatWindPlanner.sessionHashFromHtml("""<script>var session_auth_hash="fromHtmlHashValue12";</script>"""),
        )
        assertEquals(
            "queryHashValue1234",
            CatWindPlanner.sessionHashFromUrl("https://api.windscribe.com/Session?session_auth_hash=queryHashValue1234&x=1"),
        )
        assertEquals(
            "plainHashValue1234",
            CatWindPlanner.sessionHashFromText("plainHashValue1234"),
        )
        assertNull(CatWindPlanner.sessionHashFromCookies("csrf=only"))
        assertFalse(CatWindPlanner.looksLoggedIn("https://windscribe.com/login"))
        assertTrue(CatWindPlanner.looksLoggedIn("https://windscribe.com/myaccount"))
    }

    @Test
    fun hubAutoAddsAfterWebLoginNoProtocolQuiz() {
        val hub = src("MainActivityFreeNetworks.kt")
        assertTrue(hub.contains("showCatWindLogin"))
        assertTrue(hub.contains("pullFromSession"))
        assertTrue(hub.contains("SESSION_SCAN_JS"))
        assertTrue(hub.contains("adoptAuth"))
        assertTrue(hub.contains("adoptFromWeb"))
        assertTrue(hub.contains("connectCatWind()"))
        assertTrue(hub.contains("client.credentials(session)"))
        assertTrue(hub.contains("client.wgLink"))
        assertFalse(hub.contains("pickWindProtocol"))
        assertFalse("username field is not the only path", hub.contains("نام کاربری Windscribe") || hub.contains("Windscribe username"))
        assertFalse("Plus is not faked", hub.contains("fakePlus") || hub.contains("is_premium\\\":1"))
        val engine = src("CatWindEngine.kt")
        assertTrue(engine.contains("sessionHashFromCookies"))
        assertTrue(engine.contains("adoptFromWeb"))
        assertTrue(engine.contains("?session_auth_hash="))
        assertFalse(engine.contains("Bearer"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

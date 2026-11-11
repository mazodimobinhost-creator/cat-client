package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta277 — SPA stays on /vpn/login while showing Credentials; Cat still fetches. */
class Beta277ProtonCredentialsPageTest {

    @Test
    fun versionIsBeta277() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 369"))
        assertTrue(gradle.contains("1.10.0-beta302"))
    }

    @Test
    fun loginUrlWithCredentialsTextIsSignedIn() {
        assertFalse(ProtonFreePlanner.pageLooksSignedIn("Sign in to Proton"))
        assertTrue(ProtonFreePlanner.pageLooksSignedIn("OpenVPN\nCredentials\nYou can use the following credentials"))
        assertTrue(
            ProtonFreePlanner.looksSignedIn(
                null,
                ProtonFreePlanner.LOGIN,
                "OpenVPN Credentials You can use the following credentials to connect to a Proton VPN server",
            ),
        )
        assertTrue(ProtonFreePlanner.inpagePullJs("uid123").contains("x-pm-uid"))
        assertTrue(ProtonFreePlanner.inpagePullJs("uid123").contains("uid123"))
    }

    @Test
    fun hubScansPageTextAndHasFetchButton() {
        val hub = src("MainActivityFreeNetworks.kt")
        assertTrue(hub.contains("lastPageText"))
        assertTrue(hub.contains("SCAN_JS"))
        assertTrue(hub.contains("forcePull"))
        assertTrue(hub.contains("گرفتن کانفیگ‌ها"))
        assertTrue(hub.contains("Fetch configs"))
        assertTrue(hub.contains("pageLooksSignedIn") || hub.contains("looksSignedIn(cookies, lastUrl, lastPageText)"))
        assertFalse(hub.contains("fakePlus"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

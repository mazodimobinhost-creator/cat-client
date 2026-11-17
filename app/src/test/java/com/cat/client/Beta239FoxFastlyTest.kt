package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta239 — Fox challenge (406) rework, modelled on the MIT-licensed reference client: the challenge is solved on the
 * host that raised it, with the same identity (User-Agent) as the request, and its cookies are kept per host.
 */
class Beta239FoxFastlyTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory || File(it, "src/main").isDirectory }
        .let { if (File(it, "app/src/main").isDirectory) File(it, "app/src/main") else File(it, "src/main") }

    private fun src(name: String) = File(root(), "java/com/cat/client/$name").readText()

    @Test fun parsesTheLastInitCallWithBracketsInsideStrings() {
        val script = "var noise = {init:function(){}};something.init([1,2]);" +
            ";init([{\"ty\":\"pat\",\"data\":{\"list\":[1,\"]\"]}}], \"token-abc\", \"/_fs-ch-1T1wmsGaOgGaSxcX\", true);"
        val parsed = FoxFastlyChallenge.parseInit(script)!!
        assertEquals("token-abc", parsed.second)
        assertTrue(parsed.first.startsWith("[{\"ty\":\"pat\""))
        assertTrue(parsed.first.endsWith("}]"))
    }

    @Test fun decodesUnicodeEscapesInTheToken() {
        val parsed = FoxFastlyChallenge.parseInit(";init([{\"ty\":\"pow\",\"data\":{\"base\":\"b\",\"hash\":\"00\"}}], \"t\\u0041k\");")
        assertEquals("tAk", parsed?.second)
        assertNull(FoxFastlyChallenge.parseInit("init([1,2]);"))
    }

    @Test fun recognisesChallengePagesAndPrefix() {
        val page = "<html><title>Client Challenge</title><script src=\"/_fs-ch-AbC9/script.js\"></script>"
        assertTrue(FoxFastlyChallenge.isChallengePage(page))
        assertFalse(FoxFastlyChallenge.isChallengePage("{\"errno\":1}"))
        assertEquals("/_fs-ch-AbC9", FoxFastlyChallenge.prefix(page))
    }

    @Test fun onlyTrustedHttpsOriginsOn443AreSolved() {
        assertEquals("https://vpn.mozilla.org", FoxFastlyChallenge.trustedOrigin("https://vpn.mozilla.org/api/v1/fpn/token"))
        assertEquals("https://api.accounts.firefox.com", FoxFastlyChallenge.trustedOrigin("https://api.accounts.firefox.com/v1/account/status"))
        assertNull(FoxFastlyChallenge.trustedOrigin("https://evil.example/"))
        assertNull(FoxFastlyChallenge.trustedOrigin("http://vpn.mozilla.org/"))
        assertNull(FoxFastlyChallenge.trustedOrigin("https://vpn.mozilla.org:8443/"))
        assertTrue(FoxFastlyChallenge.MAX_ROUNDS in 1..8)
    }

    @Test fun cookieJarIsPerHostAndHonoursDomainAndDeletion() {
        val jar = FoxCookieJar()
        jar.store("vpn.mozilla.org", listOf("_fs_ch_cp_a=V1; Path=/; Secure", "x=1"))
        jar.store("api.accounts.firefox.com", listOf("_fs_ch_cp_a=A1; Path=/", "shared=s; Domain=.firefox.com"))
        assertEquals(setOf("_fs_ch_cp_a=V1", "x=1"), jar.header("vpn.mozilla.org").split("; ").toSet())
        val api = jar.header("api.accounts.firefox.com").split("; ").toSet()
        assertEquals(setOf("_fs_ch_cp_a=A1", "shared=s"), api)
        assertEquals("shared=s", jar.header("accounts.firefox.com"))
        jar.store("vpn.mozilla.org", listOf("x=; Max-Age=0"))
        assertEquals("_fs_ch_cp_a=V1", jar.header("vpn.mozilla.org"))
        jar.store("vpn.mozilla.org", listOf("bad=1; Domain=firefox.com"))
        assertFalse(jar.header("accounts.firefox.com").contains("bad"))
    }

    @Test fun solvedCookiesAreCopiedToEveryTrustedHost() {
        val solved = FoxCookieJar().apply { store("vpn.mozilla.org", listOf("_fs_ch_cp_z=Z")) }
        val target = FoxCookieJar()
        solved.copyInto(target, FoxFastlyChallenge.TRUSTED_HOSTS)
        assertTrue(FoxFastlyChallenge.TRUSTED_HOSTS.all { target.header(it) == "_fs_ch_cp_z=Z" })
    }

    @Test fun engineUsesOneIdentityAndSolvesOnTheChallengedHost() {
        val fox = src("FirefoxVpnEngine.kt")
        assertTrue(fox.contains("Firefox/159.0"))
        assertTrue(fox.contains("fun mozillaVpnUserAgent()"))
        val solver = fox.substringAfter("fun solveFastlyChallenge(").substringBefore("): Boolean")
        assertTrue(solver.contains("challengedBody: String?") && solver.contains("userAgent: String"))
        // the retried request's own User-Agent is the identity the challenge is solved with
        assertTrue(fox.contains("solveFastlyChallenge(resp.body, originOf(url), userAgentOf(headers))"))
        assertTrue(fox.contains("controlCookies"))
        // the old global cookie map + fixed-host solver (cause of the persistent 406) must not come back
        assertFalse(fox.contains("fastlyCookieStore"))
        assertFalse(fox.contains("solveFastlyChallengeOnHost"))
        val guardian = fox.substringAfter("private fun guardianRequest(").substringBefore("\n    }\n")
        assertTrue(guardian.contains("mozillaVpnUserAgent()"))
        assertTrue(src("FoxWebChannel.kt").contains("Firefox/159.0"))
    }
}

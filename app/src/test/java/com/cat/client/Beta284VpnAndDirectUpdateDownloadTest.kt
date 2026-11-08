package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta284 — race github.com (VPN) with gh-proxy (no VPN); progress file not "preparing" forever. */
class Beta284VpnAndDirectUpdateDownloadTest {

    @Test
    fun versionIsBeta284() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 355"))
        assertTrue(gradle.contains("1.10.0-beta288"))
    }

    @Test
    fun racesGithubWithMirrorsAndShowsProgressFile() {
        val apk = "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta288/CatClient-V1.10.0-beta288-arm64-v8a.apk"
        val candidates = GitHubReleaseClient.assetUrlCandidates(apk)
        assertEquals(apk, candidates.first())
        assertTrue(candidates.contains("https://gh-proxy.com/$apk"))
        assertTrue(candidates.contains("https://kkgithub.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta288/CatClient-V1.10.0-beta288-arm64-v8a.apk"))
        val src = src("AppUpdate.kt")
        assertTrue(src.contains("firstSuccessful"))
        assertTrue(src.contains("Range\", \"bytes=0-255"))
        assertFalseContainsGetTmp(src)
        val ui = src("AppUpdateUi.kt")
        assertTrue(ui.contains("downloadJob"))
        assertTrue(ui.contains("transferring"))
        val manager = src("AppUpdateManager.kt")
        assertTrue(manager.contains("savePending(PendingDownload(SELF_DOWNLOAD_ID, release, \"\")"))
    }

    private fun assertFalseContainsGetTmp(src: String) {
        org.junit.Assert.assertFalse(src.contains("dest.path + \".get\""))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

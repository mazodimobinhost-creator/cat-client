package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta282 — in-app GET walks gh-proxy / kkgithub; failed GET opens the mirrored APK in the browser. */
class Beta282BrowserUpdateDownloadTest {

    @Test
    fun versionIsBeta282() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 373"))
        assertTrue(gradle.contains("1.10.0-beta306"))
    }

    @Test
    fun mirrorsIncludeHostRewritesAndBrowserButton() {
        val apk = "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta306/CatClient-V1.10.0-beta306-arm64-v8a.apk"
        val candidates = GitHubReleaseClient.assetUrlCandidates(apk)
        assertEquals(apk, candidates.first())
        assertEquals("https://gh-proxy.com/$apk", GitHubReleaseClient.browserAssetUrl(apk))
        assertTrue(candidates.contains("https://kkgithub.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta306/CatClient-V1.10.0-beta306-arm64-v8a.apk"))
        assertTrue(candidates.contains("https://github.akams.cn/$apk"))
        assertTrue(candidates.contains(apk))
        assertTrue(
            GitHubReleaseClient.isAllowedReleasesFetch(
                "https://github.akams.cn/https://api.github.com/repos/mazodimobinhost-creator/cat-client/releases?per_page=20",
            ),
        )
        val src = src("AppUpdate.kt")
        assertFalse(src.contains("Accept-Encoding\", \"identity"))
        assertTrue(src.contains("ASSET_UA"))
        val ui = src("AppUpdateUi.kt")
        assertTrue(ui.contains("browserAssetUrl"))
        assertTrue(ui.contains("update_download_browser"))
        assertTrue(src("AppUpdateManager.kt").contains("getOrNull().orEmpty()"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

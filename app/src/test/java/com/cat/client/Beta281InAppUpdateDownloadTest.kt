package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta281 — in-app APK download uses ghproxy first, not Android DownloadManager → github.com. */
class Beta281InAppUpdateDownloadTest {

    @Test
    fun versionIsBeta281() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 351"))
        assertTrue(gradle.contains("1.10.0-beta284"))
    }

    @Test
    fun downloadWalksMirrorsAndDoesNotEnqueueDownloadManager() {
        val src = src("AppUpdate.kt")
        assertTrue(src.contains("fun downloadAsset"))
        assertTrue(src.contains("followAssetRedirect"))
        assertTrue(src.contains("ghfast.top"))
        val apk = "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta284/CatClient-V1.10.0-beta284-arm64-v8a.apk"
        val candidates = GitHubReleaseClient.assetUrlCandidates(apk)
        assertEquals(apk, candidates.first())
        assertTrue(candidates.contains("https://gh-proxy.com/$apk"))
        assertTrue(
            GitHubReleaseClient.isAllowedReleasesFetch(
                "https://ghfast.top/https://api.github.com/repos/mazodimobinhost-creator/cat-client/releases?per_page=20",
            ),
        )
        val manager = src("AppUpdateManager.kt")
        assertTrue(manager.contains("downloadAsset"))
        assertFalse(manager.contains("downloads.enqueue"))
        assertFalse(manager.contains("setDestinationInExternalFilesDir"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

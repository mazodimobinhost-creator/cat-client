package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta317 — in-app update when GitHub is blocked: kkgithub browser, pointer file, finder. */
class Beta317InAppUpdateIranTest {

    @Test
    fun versionIsBeta317() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 384"))
        assertTrue(gradle.contains("1.10.0-beta317"))
    }

    @Test
    fun browserMirrorAndPointerAndFinder() {
        val apk = "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta317/CatClient-V1.10.0-beta317-arm64-v8a.apk"
        assertEquals(
            "https://kkgithub.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta317/CatClient-V1.10.0-beta317-arm64-v8a.apk",
            GitHubReleaseClient.browserAssetUrl(apk),
        )
        assertEquals(
            "https://kkgithub.com/mazodimobinhost-creator/cat-client/releases/latest",
            GitHubReleaseClient.browserLatestReleaseUrl(),
        )
        val pointer = File("docs/latest-release.json").readText()
        val parsed = GitHubReleaseClient.parseLatestPointer(pointer, ApkVariant.Arm64V8a)
        assertEquals("v1.10.0-beta317", parsed.version)
        assertTrue(parsed.downloadable)
        assertEquals(
            "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta317/CatClient-V1.10.0-beta317-arm64-v8a.apk",
            parsed.apk!!.url,
        )
        val src = src("AppUpdate.kt")
        assertTrue(src.contains("optBoolean(\"draft\", false)"))
        assertTrue(src.contains("parseLatestPointer"))
        assertTrue(src.contains("LATEST_POINTER_PATH"))
        val ui = src("AppUpdateUi.kt")
        assertTrue(ui.contains("openLatestReleasePage"))
        assertTrue(ui.contains("releaseButton.visibility = View.VISIBLE"))
        val guides = src("MainActivityGuides.kt")
        assertTrue(guides.contains("openAppUpdatePanel"))
        assertTrue(guides.contains("آپدیت برنامه"))
        val diag = src("MainActivityDiagnostics.kt")
        assertTrue(diag.contains("fun MainActivity.openAppUpdatePanel"))
        assertFalse(src.contains("Spider"))
    }

    companion object {
        private fun src(name: String): String = listOf(
            File("src/main/java/com/cat/client/$name"),
            File("app/src/main/java/com/cat/client/$name"),
        ).first { it.isFile }.readText()
    }
}

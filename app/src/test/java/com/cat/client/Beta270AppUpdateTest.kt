package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta270AppUpdateTest {

    @Test
    fun versionIsBeta270() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 340"))
        assertTrue(gradle.contains("1.10.0-beta273"))
        assertFalse(gradle.contains("versionCode = 336"))
        assertFalse(gradle.contains("1.10.0-beta269"))
    }

    @Test
    fun beta270IsNewerThanInstalledBetas() {
        assertTrue(AppUpdatePolicy.isNewer("1.10.0-beta270", "1.10.0-beta269"))
        assertTrue(AppUpdatePolicy.isNewer("v1.10.0-beta270", "1.10.0-beta266"))
        assertTrue(AppUpdatePolicy.shouldPrompt("v1.10.0-beta270", "1.10.0-beta269", "1.10.0-beta268"))
    }

    @Test
    fun parseReleaseListSkipsDraftThenTakesDownloadableApk() {
        val json = """
            [
              {
                "draft": true,
                "prerelease": true,
                "tag_name": "untagged-cb8cedf5c844e7f3a10a",
                "html_url": "https://github.com/mazodimobinhost-creator/cat-client/releases/tag/untagged-cb8cedf5c844e7f3a10a",
                "assets": [
                  {
                    "id": 9,
                    "name": "CatClient-V1.10.0-beta27-arm64-v8a.apk",
                    "state": "uploaded",
                    "size": 10,
                    "browser_download_url": "https://github.com/mazodimobinhost-creator/cat-client/releases/download/untagged-cb8cedf5c844e7f3a10a/CatClient-V1.10.0-beta27-arm64-v8a.apk"
                  }
                ]
              },
              {
                "draft": false,
                "prerelease": false,
                "tag_name": "v1.10.0-beta270",
                "html_url": "https://github.com/mazodimobinhost-creator/cat-client/releases/tag/v1.10.0-beta270",
                "assets": [
                  {
                    "id": 1,
                    "name": "CatClient-V1.10.0-beta270-arm64-v8a.apk",
                    "state": "uploaded",
                    "size": 42,
                    "browser_download_url": "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta270/CatClient-V1.10.0-beta270-arm64-v8a.apk"
                  },
                  {
                    "id": 2,
                    "name": "SHA256SUMS",
                    "state": "uploaded",
                    "size": 12,
                    "browser_download_url": "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta270/SHA256SUMS"
                  }
                ]
              }
            ]
        """.trimIndent()
        val release = GitHubReleaseClient.parseReleaseList(json, ApkVariant.Arm64V8a)
        assertEquals("v1.10.0-beta270", release.version)
        assertTrue(release.downloadable)
        assertEquals("CatClient-V1.10.0-beta270-arm64-v8a.apk", release.apk!!.name)
    }

    @Test
    fun parseReleaseListSkipsReleaseWithoutChecksums() {
        val json = """
            [
              {
                "draft": false,
                "tag_name": "v1.10.0-beta269",
                "html_url": "https://github.com/mazodimobinhost-creator/cat-client/releases/tag/v1.10.0-beta269",
                "assets": [
                  {
                    "id": 3,
                    "name": "CatClient-V1.10.0-beta269-arm64-v8a.apk",
                    "state": "uploaded",
                    "size": 10,
                    "browser_download_url": "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta269/CatClient-V1.10.0-beta269-arm64-v8a.apk"
                  }
                ]
              },
              {
                "draft": false,
                "tag_name": "v1.10.0-beta268",
                "html_url": "https://github.com/mazodimobinhost-creator/cat-client/releases/tag/v1.10.0-beta268",
                "assets": [
                  {
                    "id": 4,
                    "name": "CatClient-V1.10.0-beta268-arm64-v8a.apk",
                    "state": "uploaded",
                    "size": 11,
                    "browser_download_url": "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta268/CatClient-V1.10.0-beta268-arm64-v8a.apk"
                  },
                  {
                    "id": 5,
                    "name": "SHA256SUMS",
                    "state": "uploaded",
                    "size": 12,
                    "browser_download_url": "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta268/SHA256SUMS"
                  }
                ]
              }
            ]
        """.trimIndent()
        val release = GitHubReleaseClient.parseReleaseList(json, ApkVariant.Arm64V8a)
        assertEquals("v1.10.0-beta268", release.version)
        assertTrue(release.downloadable)
    }

    @Test
    fun apiAndApkUrlsWalkGhproxyMirrors() {
        val src = listOf(
            File("app/src/main/java/com/cat/client/AppUpdate.kt"),
            File("src/main/java/com/cat/client/AppUpdate.kt"),
        ).first { it.isFile }.readText()
        assertTrue(src.contains("ghproxy.net/https://api.github.com/repos/"))
        assertTrue(src.contains("gh-proxy.com/https://api.github.com/repos/"))
        assertTrue(src.contains("isAllowedReleasesFetch"))
        assertTrue(src.contains("reachableAssetUrl"))
        val apk = "https://github.com/mazodimobinhost-creator/cat-client/releases/download/v1.10.0-beta270/CatClient-V1.10.0-beta270-arm64-v8a.apk"
        val candidates = GitHubReleaseClient.assetUrlCandidates(apk)
        assertEquals(apk, candidates.first())
        assertTrue(candidates.any { it.startsWith("https://ghproxy.net/") && it.endsWith(apk) })
        assertTrue(
            GitHubReleaseClient.isAllowedReleasesFetch(
                "https://api.github.com/repos/mazodimobinhost-creator/cat-client/releases?per_page=20",
            ),
        )
        assertTrue(
            GitHubReleaseClient.isAllowedReleasesFetch(
                "https://ghproxy.net/https://api.github.com/repos/mazodimobinhost-creator/cat-client/releases?per_page=20",
            ),
        )
        assertFalse(
            GitHubReleaseClient.isAllowedReleasesFetch(
                "https://evil.example/https://api.github.com/repos/mazodimobinhost-creator/cat-client/releases",
            ),
        )
    }

    @Test
    fun ciMarksNewReleasesLatestNotPrerelease() {
        val workflow = listOf(
            File(".github/workflows/android.yml"),
            File("../.github/workflows/android.yml"),
        ).first { it.isFile }.readText()
        assertTrue(workflow.contains("make_latest=true"))
        assertTrue(workflow.contains("release_flags=(--latest)"))
        assertFalse(workflow.contains("release_flags+=(--prerelease)"))
        assertFalse(workflow.contains("-F prerelease=true"))
        val manager = listOf(
            File("app/src/main/java/com/cat/client/AppUpdateManager.kt"),
            File("src/main/java/com/cat/client/AppUpdateManager.kt"),
        ).first { it.isFile }.readText()
        assertTrue(manager.contains("reachableAssetUrl(apk.url)"))
    }
}

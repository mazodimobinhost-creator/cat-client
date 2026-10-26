package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerArtifactManifestTest {
    private val canonicalBytes = """
        const CAT_PANEL_VERSION = '6.68.1';
        export default { async fetch(request, env, ctx) { return new Response('ok'); } };
    """.trimIndent().toByteArray(Charsets.UTF_8)

    @Test fun verifiesAllCanonicalChannelsAndSha256Equality() {
        val hash = WorkerDeploymentPlanner.sha256Hex(canonicalBytes)
        val apk = (WorkerArtifactManifest.verify(WorkerArtifactChannel.APK_BUNDLE, canonicalBytes, hash) as WorkerManifestDecision.Verified).record
        val release = (WorkerArtifactManifest.verify(WorkerArtifactChannel.RELEASE_ASSET, canonicalBytes, hash) as WorkerManifestDecision.Verified).record
        val update = (WorkerArtifactManifest.verify(WorkerArtifactChannel.UPDATE, canonicalBytes, hash) as WorkerManifestDecision.Verified).record
        val repair = (WorkerArtifactManifest.verify(WorkerArtifactChannel.REPAIR, canonicalBytes, hash) as WorkerManifestDecision.Verified).record

        assertTrue(WorkerArtifactManifest.verifyCanonicalConsistency(apk, release))
        assertTrue(WorkerArtifactManifest.verifyCanonicalConsistency(apk, update))
        assertTrue(WorkerArtifactManifest.verifyCanonicalConsistency(apk, repair))
    }

    @Test fun rejectsSha256MismatchAndMissingCanonicalVersion() {
        val mismatch = WorkerArtifactManifest.verify(WorkerArtifactChannel.RELEASE_ASSET, canonicalBytes, "0".repeat(64))
        assertEquals(WorkerManifestDecision.Rejected("sha256-mismatch"), mismatch)

        val noVersion = """export default { async fetch(r) { return new Response('ok'); } };""".toByteArray()
        assertEquals(
            WorkerManifestDecision.Rejected("version-marker-required"),
            WorkerArtifactManifest.verify(WorkerArtifactChannel.APK_BUNDLE, noVersion),
        )
        assertTrue(WorkerArtifactManifest.verify(WorkerArtifactChannel.RAW_URL, noVersion) is WorkerManifestDecision.Verified)
    }

    @Test fun blocksKnown1101VersionAcrossEveryChannel() {
        val bad1101 = """
            const CAT_PANEL_VERSION = '6.69.12';
            export default { async fetch(r) { return new Response('bad'); } };
        """.trimIndent().toByteArray()
        assertEquals(
            WorkerManifestDecision.Rejected("blocked-1101-version"),
            WorkerArtifactManifest.verify(WorkerArtifactChannel.UPDATE, bad1101),
        )
        assertFalse(
            WorkerArtifactManifest.verifyCanonicalConsistency(
                WorkerArtifactRecord(WorkerArtifactChannel.APK_BUNDLE, "6.68.1", "a".repeat(64), 10, true),
                WorkerArtifactRecord(WorkerArtifactChannel.REPAIR, "6.68.1", "b".repeat(64), 10, true),
            ),
        )
    }

    @Test fun realBundledAndPasteArtifactsPassManifestVerificationAndMatchSha256() {
        val pasteFile = listOf(
            java.io.File("src/main/assets/panels/catpanel.min.js"),
            java.io.File("app/src/main/assets/panels/catpanel.min.js"),
        ).first { it.isFile }
        val distFile = listOf(
            java.io.File("../dist-panel/catpanel.min.js"),
            java.io.File("dist-panel/catpanel.min.js"),
        ).first { it.isFile }
        val pasteBytes = pasteFile.readBytes()
        val distBytes = distFile.readBytes()

        val apkDecision = WorkerArtifactManifest.verify(WorkerArtifactChannel.APK_BUNDLE, pasteBytes)
        assertTrue("Expected APK bundle artifact to verify, got $apkDecision", apkDecision is WorkerManifestDecision.Verified)
        val apkRecord = (apkDecision as WorkerManifestDecision.Verified).record
        assertEquals("6.69.37", apkRecord.version)

        val releaseDecision = WorkerArtifactManifest.verify(
            WorkerArtifactChannel.RELEASE_ASSET,
            distBytes,
            expectedSha256 = apkRecord.sha256,
        )
        assertTrue("Expected release artifact to verify, got $releaseDecision", releaseDecision is WorkerManifestDecision.Verified)
        val releaseRecord = (releaseDecision as WorkerManifestDecision.Verified).record
        assertTrue(WorkerArtifactManifest.verifyCanonicalConsistency(apkRecord, releaseRecord))

        val sumsText = """
            ${"a".repeat(64)}  CatClient-V1.10.0-beta129-arm64-v8a.apk
            ${releaseRecord.sha256}  catclient.worker.js
        """.trimIndent()
        assertEquals(releaseRecord.sha256, GitHubReleaseClient.parseChecksum(sumsText, "catclient.worker.js"))
    }
}

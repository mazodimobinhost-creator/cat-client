package com.whitedns.vpn

import com.cat.client.AppUpdatePolicy
import com.cat.client.PanelUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelUpdatePolicyTest {
    @Test
    fun parsesThePanelVersionMarker() {
        assertEquals("5.7.0", PanelUpdate.parseVersion("const CAT_PANEL_VERSION = '5.7.0';"))
        assertEquals("1.2.3", PanelUpdate.parseVersion("x\nCAT_PANEL_VERSION  =  '1.2.3'\ny"))
        assertNull(PanelUpdate.parseVersion("const CAT_WIZARD_VERSION = '1.0.0';"))
        assertNull(PanelUpdate.parseVersion("no version marker here"))
        assertNull(PanelUpdate.parseVersion("const CAT_PANEL_VERSION = 'not-a-version';"))
        assertNull(PanelUpdate.parseVersion("const CAT_PANEL_VERSION = 5.7.0;"))
    }

    @Test
    fun releaseSourceMustBeStrictlyNewerThanTheBundle() {
        assertTrue(PanelUpdate.preferRelease("5.6.0", "5.7.0"))
        assertTrue(PanelUpdate.preferRelease("5.6.0", "v5.10.0"))
        assertFalse("ties keep the offline bundle", PanelUpdate.preferRelease("5.7.0", "5.7.0"))
        assertFalse(PanelUpdate.preferRelease("5.7.0", "5.6.9"))
        assertFalse(PanelUpdate.preferRelease("5.7.0", null))
        assertFalse(PanelUpdate.preferRelease("5.7.0", ""))
        assertFalse(PanelUpdate.preferRelease("5.7.0", "garbage"))
    }

    @Test
    fun betaPrereleaseVersionsCompareNumerically() {
        // The shipped versionName is a beta (1.10.0-betaN). The old comparator
        // only accepted bare numbers, so isNewer was ALWAYS false and in-app
        // updates never appeared (beta10 even sorted before beta9 lexically).
        assertTrue(AppUpdatePolicy.isNewer("1.10.0-beta10", "1.10.0-beta9"))
        assertTrue(AppUpdatePolicy.isNewer("v1.10.0-beta10", "1.10.0-beta5"))
        assertFalse(AppUpdatePolicy.isNewer("1.10.0-beta9", "1.10.0-beta10"))
        assertFalse(AppUpdatePolicy.isNewer("1.10.0-beta10", "1.10.0"))
        assertTrue(AppUpdatePolicy.isNewer("1.10.0", "1.10.0-beta10"))
        assertTrue(AppUpdatePolicy.isNewer("1.11.0-beta1", "1.10.0-beta99"))
        assertTrue(AppUpdatePolicy.isNewer("1.10.0-beta10", "1.9.9-beta77"))
        assertEquals("1.10.0-beta10", AppUpdatePolicy.normalizedVersion("v1.10.0-beta10"))
        assertEquals("1.10.0", AppUpdatePolicy.normalizedVersion("1.10.0"))
        assertTrue(AppUpdatePolicy.isNewer("1.4-rc1", "1.3.0"))
        assertFalse(AppUpdatePolicy.isNewer("1.4-rc1", "1.4.0"))
        assertTrue(AppUpdatePolicy.isNewer("1.4.0", "1.4-rc1"))
        assertFalse(AppUpdatePolicy.shouldPrompt("1.10.0-beta121", "1.10.0-beta120", "1.10.0-beta121"))
        assertTrue(AppUpdatePolicy.shouldPrompt("1.10.0-beta125", "1.10.0-beta124", "1.10.0-beta121"))
        assertTrue(AppUpdatePolicy.shouldPrompt("v1.10.0-beta125", "1.10.0-beta124", "1.10.0-beta121"))
        for (invalid in listOf("1.-1", "1..4", "vV1.4", "1.4+build", "9999999999999999999999999")) {
            assertFalse(invalid, AppUpdatePolicy.isNewer(invalid, "1.3.0"))
            assertFalse(invalid, AppUpdatePolicy.isNewer("1.4.0", invalid))
        }
    }

    @Test
    fun updatePromptSemanticsMatchAppUpdates() {
        // Same comparison the deployment rows use: installed vs newest known source.
        assertTrue(AppUpdatePolicy.isNewer("5.7.0", "5.6.0"))
        assertFalse(AppUpdatePolicy.isNewer("5.6.0", "5.6.0"))
        assertFalse(AppUpdatePolicy.isNewer("5.6.0", "5.7.0"))
    }

    @Test
    fun obfuscatedArtifactCarriesAReadableVersionLine() {
        // scripts/panels/obfuscate.mjs writes this as the FIRST line of every shipped artifact;
        // the string-array noise after it must not be needed to learn the version.
        val shipped = "/* CAT_PANEL_VERSION = '6.53.0' */\n" +
            "const _0x375ce0=_0x81c1;(function(_0x58d982){})();\nexport default globalThis.__CAT_DEFAULT;\n"
        assertEquals("6.53.0", PanelUpdate.parseVersion(shipped))
        // An artifact WITHOUT the line is exactly the bug: the APK bundle read as 0.0.0.
        assertNull(PanelUpdate.parseVersion("const _0x375ce0=_0x81c1;export default globalThis.__CAT_DEFAULT;"))
    }

    @Test
    fun staleRepositoryCopyNeverBeatsTheBundle() {
        // «The panel went back to the old version»: main sat at 5.23.13 while the obfuscated
        // bundle had no readable marker (0.0.0), so the stale copy looked like an upgrade.
        assertFalse(PanelUpdate.acceptRepositoryFallback(PanelUpdate.UNKNOWN_VERSION, "5.23.13"))
        assertFalse(PanelUpdate.acceptRepositoryFallback("6.53.0", "5.23.13"))
        assertFalse("ties keep the bundle", PanelUpdate.acceptRepositoryFallback("6.53.0", "6.53.0"))
        assertTrue(PanelUpdate.acceptRepositoryFallback("6.53.0", "6.54.0"))
        assertTrue(PanelUpdate.acceptRepositoryFallback("6.53.0", "7.0.0"))
        assertFalse(PanelUpdate.acceptRepositoryFallback("6.53.0", null))
        assertFalse(PanelUpdate.acceptRepositoryFallback("6.53.0", ""))
        assertFalse(PanelUpdate.acceptRepositoryFallback("6.53.0", "garbage"))
    }

    @Test
    fun aLivePanelIsNeverMovedBackwards() {
        assertTrue(PanelUpdate.isDowngrade("6.53.0", "5.23.13"))
        assertTrue(PanelUpdate.isDowngrade("6.53.1", "6.53.0"))
        assertFalse("same version = a re-install / repair, allowed", PanelUpdate.isDowngrade("6.53.0", "6.53.0"))
        assertFalse(PanelUpdate.isDowngrade("5.23.13", "6.53.0"))
        assertFalse("an unknown deployed version cannot be judged", PanelUpdate.isDowngrade(null, "5.23.13"))
        assertFalse(PanelUpdate.isDowngrade("", "5.23.13"))
        assertFalse(PanelUpdate.isDowngrade("garbage", "5.23.13"))
    }

    @Test
    fun validateCandidateBlocks1101VersionAndDowngradesAndChecksSha256() {
        val valid = PanelUpdate.PanelScript(
            version = "6.70.0",
            text = "/* CAT_PANEL_VERSION = '6.70.0' */\nexport default { async fetch(req, env) { return new Response('ok'); } }",
            fromRelease = true,
        )
        assertTrue(
            PanelUpdate.validateCandidate(
                valid,
                expectedSha256 = valid.sha256,
                deployedVersion = "6.69.11",
            ) is com.cat.client.WorkerManifestDecision.Verified,
        )
        assertEquals(
            "downgrade-blocked",
            (PanelUpdate.validateCandidate(valid, deployedVersion = "6.71.0") as com.cat.client.WorkerManifestDecision.Rejected).reason,
        )
        assertEquals(
            "sha256-mismatch",
            (PanelUpdate.validateCandidate(valid, expectedSha256 = "0".repeat(64)) as com.cat.client.WorkerManifestDecision.Rejected).reason,
        )

        val bad1101 = PanelUpdate.PanelScript(
            version = "6.69.12",
            text = "/* CAT_PANEL_VERSION = '6.69.12' */\nexport default { async fetch(req, env) { return new Response('ok'); } }",
            fromRelease = true,
        )
        assertEquals(
            "blocked-1101-version",
            (PanelUpdate.validateCandidate(bad1101) as com.cat.client.WorkerManifestDecision.Rejected).reason,
        )
    }

    @Test
    fun downloadedWorkerSourceIntegratesWithManifestStaticAnalysisAndPlanner() {
        val src = "/* CAT_PANEL_VERSION = '6.70.0' */\nexport default { async fetch(req, env) { return env.CAT_KV.get(env.UUID); } }"
        val bytes = src.toByteArray(Charsets.UTF_8)
        val downloaded = com.cat.client.DownloadedWorkerSource(
            finalUrl = "https://raw.githubusercontent.com/example/repo/main/worker.js",
            bytes = bytes,
            sha256 = com.cat.client.WorkerDeploymentPlanner.sha256Hex(bytes),
            contentType = "application/javascript",
        )
        val decision = downloaded.verifyManifest() as com.cat.client.WorkerManifestDecision.Verified
        assertEquals("6.70.0", decision.record.version)
        val analysis = downloaded.staticAnalysis()
        assertTrue(analysis.inspection.valid)
        assertTrue(com.cat.client.WorkerCapability.KV_STORAGE in analysis.capabilities)
        val plan = downloaded.planDeployment(
            target = com.cat.client.CloudflareTargetSelection(
                accountId = "account-12345678",
                workerName = "cat-worker",
            ),
            approval = com.cat.client.CloudflareOperationApproval(
                previewReviewed = true,
                confirmed = true,
            ),
        )
        assertTrue(plan is com.cat.client.WorkerDeploymentPlanResult.Ready)
    }
}

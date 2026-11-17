package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeploymentProfileTest {
    private fun profile(url: String = "https://Example.Pages.dev/") = DeploymentProfile("pages-main", "Pages", DeploymentTarget.PAGES, url)

    @Test fun normalizesHttpsOriginOnly() {
        assertEquals("https://example.pages.dev", profile().normalized()?.baseUrl)
        assertNull(profile("http://example.pages.dev").normalized())
        assertNull(profile("https://user@example.pages.dev").normalized())
        assertNull(profile("https://example.pages.dev/path").normalized())
    }

    @Test fun successRequiresBothReal2xxResponses() {
        val p = profile()
        assertEquals(DeploymentProbeVerdict.SUCCESS, DeploymentProbePolicy.classify(p, "wifi-1", "wifi-1", false, 200, 204))
        assertEquals(DeploymentProbeVerdict.FAILED, DeploymentProbePolicy.classify(p, "wifi-1", "wifi-1", false, 200, 500))
    }

    @Test fun networkChangeAndCaptiveAreInconclusive() {
        val p = profile()
        assertEquals(DeploymentProbeVerdict.INCONCLUSIVE, DeploymentProbePolicy.classify(p, "wifi", "cell", false, 200, 200))
        assertEquals(DeploymentProbeVerdict.INCONCLUSIVE, DeploymentProbePolicy.classify(p, "wifi", "wifi", true, 200, 200))
    }

    @Test fun disabledOrInvalidIsNotConfigured() {
        assertEquals(DeploymentProbeVerdict.NOT_CONFIGURED, DeploymentProbePolicy.classify(profile().copy(enabled = false), "n", "n", false, 200, 200))
        assertEquals(DeploymentProbeVerdict.NOT_CONFIGURED, DeploymentProbePolicy.classify(profile("javascript:alert(1)"), "n", "n", false, 200, 200))
    }

    @Test fun infersOnlyKnownDeploymentHosts() {
        assertEquals(DeploymentTarget.WORKER, DeploymentTargetPolicy.infer("https://cat.workers.dev"))
        assertEquals(DeploymentTarget.PAGES, DeploymentTargetPolicy.infer("https://cat.pages.dev"))
        assertEquals(DeploymentTarget.RAILWAY, DeploymentTargetPolicy.infer("https://cat.up.railway.app"))
        assertNull(DeploymentTargetPolicy.infer("https://example.com"))
    }

    @Test fun automaticSelectionUsesOnlyFreshSuccessfulMeasurement() {
        val now = 1_000_000L
        val slow = profile("https://slow.pages.dev").copy(id = "slow")
        val fast = profile("https://fast.pages.dev").copy(id = "fast")
        val failed = profile("https://failed.pages.dev").copy(id = "failed")
        val stale = profile("https://stale.pages.dev").copy(id = "stale")
        val chosen = DeploymentTargetPolicy.selectBest(listOf(
            DeploymentProbeMeasurement(slow, DeploymentProbeVerdict.SUCCESS, 90, now - 1_000),
            DeploymentProbeMeasurement(fast, DeploymentProbeVerdict.SUCCESS, 20, now - 2_000),
            DeploymentProbeMeasurement(failed, DeploymentProbeVerdict.FAILED, 1, now),
            DeploymentProbeMeasurement(stale, DeploymentProbeVerdict.SUCCESS, 2, now - 600_000),
        ), now)
        assertEquals("fast", chosen?.id)
    }
}

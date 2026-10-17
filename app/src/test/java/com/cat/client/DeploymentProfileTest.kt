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
}

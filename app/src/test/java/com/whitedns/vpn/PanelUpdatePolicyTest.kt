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
}

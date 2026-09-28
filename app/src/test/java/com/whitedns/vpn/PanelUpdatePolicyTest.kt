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
        assertFalse(PanelUpdate.preferRelease("5.7.0", "5.7.0"), "ties keep the offline bundle")
        assertFalse(PanelUpdate.preferRelease("5.7.0", "5.6.9"))
        assertFalse(PanelUpdate.preferRelease("5.7.0", null))
        assertFalse(PanelUpdate.preferRelease("5.7.0", ""))
        assertFalse(PanelUpdate.preferRelease("5.7.0", "garbage"))
    }

    @Test
    fun updatePromptSemanticsMatchAppUpdates() {
        // Same comparison the deployment rows use: installed vs newest known source.
        assertTrue(AppUpdatePolicy.isNewer("5.7.0", "5.6.0"))
        assertFalse(AppUpdatePolicy.isNewer("5.6.0", "5.6.0"))
        assertFalse(AppUpdatePolicy.isNewer("5.6.0", "5.7.0"))
    }
}

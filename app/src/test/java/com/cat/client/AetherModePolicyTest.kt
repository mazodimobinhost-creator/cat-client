package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AetherModePolicyTest {
    @Test fun allModesFailClosedWithoutNativeCore() {
        AetherMode.entries.forEach {
            assertEquals(
                AetherUnavailableReason.NativeCoreMissing,
                AetherModePolicy.unavailableReason(it, AetherCapabilities(false, false, false, false)),
            )
        }
    }

    @Test fun eachModeRequiresItsActualStack() {
        val masqueOnly = AetherCapabilities(true, true, false, false)
        assertNull(AetherModePolicy.unavailableReason(AetherMode.MasqueHttp2, masqueOnly))
        assertEquals(AetherUnavailableReason.WireGuardOverMasqueMissing,
            AetherModePolicy.unavailableReason(AetherMode.WireGuardOverMasque, masqueOnly))
        assertEquals(AetherUnavailableReason.PsiphonMissing,
            AetherModePolicy.unavailableReason(AetherMode.MasquePsiphon, masqueOnly))

        val complete = AetherCapabilities(true, true, true, true)
        AetherMode.entries.forEach { assertNull(AetherModePolicy.unavailableReason(it, complete)) }
    }
}

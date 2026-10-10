package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TechNamoozWorkerSourceTest {
    @Test
    fun sha256UsesStandardHexEncoding() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TechNamoozWorkerSource.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun workerNameValidationRejectsUnsafeOrInvalidNames() {
        assertTrue(TechNamoozWorkerSource.isValidWorkerName("technamooz-abc123"))
        assertFalse(TechNamoozWorkerSource.isValidWorkerName(""))
        assertFalse(TechNamoozWorkerSource.isValidWorkerName("-starts-with-dash"))
        assertFalse(TechNamoozWorkerSource.isValidWorkerName("ends-with-dash-"))
        assertFalse(TechNamoozWorkerSource.isValidWorkerName("UpperCase"))
        assertFalse(TechNamoozWorkerSource.isValidWorkerName("bad_name"))
        assertFalse(TechNamoozWorkerSource.isValidWorkerName("x".repeat(64)))
        assertEquals("worker-one", TechNamoozWorkerSource.normalizeWorkerName(" Worker-One "))
    }

    @Test
    fun panelPasswordMustBeLongAndWhitespaceFree() {
        assertTrue(TechNamoozWorkerSource.isValidPanelPassword("GoodLongPassword123"))
        assertFalse(TechNamoozWorkerSource.isValidPanelPassword("short"))
        assertFalse(TechNamoozWorkerSource.isValidPanelPassword("password with spaces 123"))
        assertFalse(TechNamoozWorkerSource.isValidPanelPassword("x".repeat(129)))
    }

    @Test
    fun subscriptionUrlTargetsTheNormalXrayEndpoint() {
        assertEquals(
            "https://worker.example.workers.dev/sub/normal/abc123?app=xray",
            TechNamoozWorkerSource.normalXraySubscriptionUrl(
                "https://worker.example.workers.dev/",
                "abc123",
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun unpinnedBundleIsRejectedBeforeUpload() {
        TechNamoozWorkerSource.verifyAndDecode("not the pinned Worker".toByteArray())
    }
}

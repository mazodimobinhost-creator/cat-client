package com.whitedns.vpn

import com.cat.client.WorkerSourceInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerSourceInspectorTest {
    @Test fun recognizesModuleAndVersion() {
        val result = WorkerSourceInspector.inspect("const CAT_PANEL_VERSION = '7.0.0'; export default { async fetch(request) { return new Response('ok') } }".toByteArray())
        assertTrue(result.valid)
        assertTrue(result.module)
        assertEquals("7.0.0", result.version)
    }

    @Test fun rejectsNulAndMissingFetchHandler() {
        assertEquals("nul-byte-forbidden", WorkerSourceInspector.inspect(byteArrayOf(1, 0, 2)).reason)
        val missing = WorkerSourceInspector.inspect("export default { scheduled() {} }".toByteArray())
        assertFalse(missing.valid)
        assertEquals("fetch-handler-not-found", missing.reason)
    }
}

package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta269CatGpCoreHealthTest {

    @Test
    fun versionIsBeta269() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 345"))
        assertTrue(gradle.contains("1.10.0-beta278"))
    }

    @Test
    fun catGpHttpLoopbackIsTheRelayNotACopiedInbound() {
        assertTrue(
            CatGpScripts.isLocalRelay(
                "http",
                "127.0.0.1",
                10871,
                "http://catgp:secret@127.0.0.1:10871#Cat%20GP",
            ),
        )
        assertTrue(CatGpScripts.isLocalRelay("HTTP", "localhost", 10871, null))
        assertFalse(CatGpScripts.isLocalRelay("socks5", "127.0.0.1", 10819, "socks5://127.0.0.1:10819"))
        assertFalse(CatGpScripts.isLocalRelay("http", "example.com", 443, "http://example.com:443"))
        assertFalse(CatGpScripts.isLocalRelay("http", "127.0.0.1", 2080, null))
    }

    @Test
    fun coreHealthErrorForCatGpIsExplainedNotAsGeneric2080() {
        val fault = ConnectionFaultExplainer.explain(CatGpEngine.CORE_UNSUPPORTED)
        assertEquals(ConnectionFaultExplainer.Cause.CatGp, fault.cause)
        assertTrue(fault.hint(true).contains("UDP"))
        assertTrue(fault.hint(true).contains("کروم") || fault.hint(true).contains("مرورگر"))
    }
}

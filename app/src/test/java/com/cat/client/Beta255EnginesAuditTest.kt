package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta255: audit of Cat Warp / Psiphon / Xray / panel pins.
 * Xray-core v26.10.31 is a security update (MPTCP off — IPv4 MPTCP kernel bug).
 * Aether stays 2.4.0 (WhiteAestherMobile v1.15.0): v1.15.1 is their Worker/proxy UI, which Cat already has.
 * Psiphon Android library is already the latest 2.0.42. Mihomo stays v1.19.30 (custom FlClash patches).
 */
class Beta255EnginesAuditTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "scripts/build-xray-core.sh").isFile }
    private fun src(): File =
        listOf(File("src/main/java/com/cat/client"), File("app/src/main/java/com/cat/client")).first { it.isDirectory }

    @Test
    fun xrayIsTheMptcpOffSecurityReleaseAndListensOnlyOnLoopback() {
        val script = File(root(), "scripts/build-xray-core.sh").readText()
        assertTrue(script.contains("XRAY_TAG=\"\${XRAY_TAG:-v26.10.31}\""))
        assertTrue(script.contains("a4a3dff5b20c2faae567b742c172c7b95e75c083"))
        val routing = File(src(), "XrayRouting.kt").readText()
        assertTrue(routing.contains("\"listen\" to \"127.0.0.1\""))
        assertTrue(routing.contains("\"tcpMptcp\" to false"))
        val adapter = File(root(), "app/src/main/assets/vercel/panel-adapter.mjs").takeIf { it.isFile }
            ?: File(root(), "src/main/assets/vercel/panel-adapter.mjs")
        val ad = adapter.readText()
        assertTrue(ad.contains("export const XRAY_VERSION = 'v26.10.31'"))
        assertTrue(ad.contains("tcpMptcp: false"))
        val notice = File(root(), "app/src/main/assets/licenses/XRAY-NOTICE.md").readText()
        assertTrue(notice.contains("v26.10.31"))
    }

    @Test
    fun aetherAndPsiphonPinsAreTheCurrentStableReleases() {
        val lock = File(root(), "aether/upstream.lock").readText()
        assertTrue(lock.contains("commit=2409297365e23e3fd320a87c0da73d0656bee67c"))
        assertTrue(lock.contains("Aether 2.4.0"))
        val gradle = File(root(), "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("ca.psiphon:psiphontunnel:2.0.42"))
        val config = File(src(), "PsiphonCarrierConfig.kt").readText()
        val chainedBlock = config.substringAfter("CHAINED_TCP_TUNNEL_PROTOCOLS").substringBefore("LIBRARY_TUNNEL_PROTOCOLS")
        assertTrue(chainedBlock.contains("\"SSH\"") && chainedBlock.contains("\"FRONTED-MEEK-OSSH\""))
        assertFalse("QUIC needs UDP; the WARP carrier is TCP-only", chainedBlock.contains("QUIC"))
        assertFalse("unknown 2.0.42 names must not return", chainedBlock.contains("FRONTED-MEEK-CDN"))
        val service = File(src(), "PsiphonCarrierService.kt").readText()
        assertTrue("a second start stops the previous tunnel instead of ignoring the request", service.contains("if (tunnel != null) stopCarrier()"))
        val client = File(src(), "PsiphonCarrierClient.kt").readText()
        assertTrue(client.contains("if (bound) close()"))
    }
}

package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta205: scanned IP:port pairs are injected only where they can work. */
class CleanIpInjectTest {
    private val tlsCfg = "vless://a1b2c3d4-e5f6-4789-abcd-ef0123456789@my.panel.workers.dev:2053?encryption=none&security=tls&type=ws&path=%2Fws#W"
    private val plainCfg = "vless://a1b2c3d4-e5f6-4789-abcd-ef0123456789@my.panel.workers.dev:8080?encryption=none&security=none&type=ws&path=%2Fws#P"

    @Test
    fun plainPortNeverLandsOnTlsLinkAndViceVersa() {
        val out = LocalSniSpoofProxy.injectVerifiedCleanIpsIntoConfigs(
            "$tlsCfg\n$plainCfg",
            listOf("104.16.1.1:80", "104.16.1.2:8443"),
        )
        assertEquals(2, out.size)
        assertTrue(out.any { it.contains("@104.16.1.2:8443?") && it.contains("security=tls") })
        assertTrue(out.any { it.contains("@104.16.1.1:80?") && it.contains("security=none") })
    }

    @Test
    fun endpointWithoutPortKeepsTheConfigsOwnPort() {
        val out = LocalSniSpoofProxy.injectVerifiedCleanIpsIntoConfigs(tlsCfg, listOf("104.16.1.3"))
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].contains("@104.16.1.3:2053?"))
    }

    @Test
    fun trojanWithoutSecurityParamCountsAsTls() {
        val tr = "trojan://pw@my.panel.workers.dev:443?type=ws&path=%2F#T"
        val out = LocalSniSpoofProxy.injectVerifiedCleanIpsIntoConfigs(tr, listOf("104.16.1.4:2095", "104.16.1.5:2096"))
        assertEquals(1, out.size)
        assertTrue(out[0].contains("@104.16.1.5:2096?"))
    }

    @Test
    fun scannerInjectFallbackHasNoPlaceholderUuid() {
        val src = listOf(java.io.File("src/main/java/com/cat/client/MainActivity.kt"), java.io.File("app/src/main/java/com/cat/client/MainActivity.kt")).first { it.isFile }.readText()
        val start = src.indexOf("private fun injectScannedIpsIntoActiveSubscription")
        val body = src.substring(start, src.indexOf("private fun showWhitelistSuperScannerDialog", start))
        assertTrue(!body.contains("11111111-2222-4333-8444-555555555555"))
        assertTrue(!body.contains("alpn=h2%2Chttp%2F1.1"))
        assertTrue(body.contains("results.filter { !it.verifyTls }"))
    }
}

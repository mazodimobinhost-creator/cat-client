package com.cat.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta220: Irancell «F&F» (`finalMask=tlshello-0-len`) is an Xray option the Cat core (mihomo v1.19.30) does not have —
 * its decoder silently drops unknown keys. Proxies carrying it are now dialled through the local fragmenting proxy.
 */
class Beta220FinalMaskTest {
    private val now = 1_800_000_000_000L
    private val ff = "vless://11111111-2222-4333-8444-555555555555@188.114.98.0:443?type=ws&security=tls" +
        "&sni=a.example.workers.dev&host=a.example.workers.dev&path=%2F&fp=chrome&alpn=http%2F1.1" +
        "&fm=tlshello-0-len&cs=semi-python#FF-Node"
    private val plain = "vless://11111111-2222-4333-8444-555555555555@104.16.1.1:443?type=ws&security=tls" +
        "&sni=b.example.workers.dev&host=b.example.workers.dev&path=%2F&fp=chrome#Plain-Node"

    @Test fun finalMaskSurvivesImportAndIsDetected() {
        val imported = UserSubscriptionImporter.import("$ff\n$plain", now)
        assertTrue(imported.yaml, MihomoDpiBypassPatcher.hasTlsHelloFinalMask(imported.yaml))
        assertFalse(MihomoDpiBypassPatcher.hasTlsHelloFinalMask(UserSubscriptionImporter.import(plain, now).yaml))
    }

    @Test fun onlyTheFinalMaskProxyIsFragmented() {
        val imported = UserSubscriptionImporter.import("$ff\n$plain", now)
        val patched = MihomoDpiBypassPatcher.patch(imported.yaml, enabled = true, proxyPort = 18080, finalMaskOnly = true)
        val proxies = MihomoConfigParser.parse(patched, now).summary.proxies
        assertTrue(proxies.first { it.rawYaml.contains("188.114.98.0") }.rawYaml.contains("dialer-proxy: 'CatClient ByeByeDPI'"))
        assertFalse(proxies.first { it.rawYaml.contains("104.16.1.1") }.rawYaml.contains("dialer-proxy"))
        assertTrue(patched.contains("name: 'CatClient ByeByeDPI'"))
        // the global switch still fragments everything (unchanged behaviour)
        val all = MihomoConfigParser.parse(
            MihomoDpiBypassPatcher.patch(imported.yaml, enabled = true, proxyPort = 18080),
            now,
        ).summary.proxies
        assertTrue(all.first { it.rawYaml.contains("104.16.1.1") }.rawYaml.contains("dialer-proxy"))
    }

    @Test fun inlineAndBlockYamlAreBothRecognised() {
        assertTrue(MihomoDpiBypassPatcher.hasTlsHelloFinalMask("  - {name: a, final-mask: tlshello-0-len}"))
        assertTrue(MihomoDpiBypassPatcher.hasTlsHelloFinalMask("    final-mask: 'tlshello-6,98,1'"))
        assertFalse(MihomoDpiBypassPatcher.hasTlsHelloFinalMask("    final-mask: ''"))
        assertFalse(MihomoDpiBypassPatcher.hasTlsHelloFinalMask("    name: tlshello"))
    }

    @Test fun serviceTurnsTheLocalProxyOnForThoseProxiesOnly() {
        val service = listOf(File("src/main/java/com/cat/client/CatClientVpnService.kt"), File("app/src/main/java/com/cat/client/CatClientVpnService.kt"))
            .first { it.isFile }.readText()
        assertTrue(service.contains("MihomoDpiBypassPatcher.hasTlsHelloFinalMask(snapshot.rawConfig)"))
        assertTrue(service.contains("dpiBypassFinalMaskOnly = dpiEnabled && finalMaskOnly"))
    }
}

package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * beta309 — enable IPv6 in the core (TUN already captured it), surface Cat Scout as the real
 * method finder, keep FakeDNS. OpenVPN / Mami / Argo / L2TP are not engines Cat can run.
 */
class Beta309Ipv6ScoutTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    @Test
    fun versionIsBeta309() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 378"))
        assertTrue(gradle.contains("1.10.0-beta311"))
    }

    @Test
    fun coreYamlIPv6AndFakeDnsAreRealToggles() {
        val on = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = "proxies: []\n", secret = "s", controlPort = 19090, ipv6 = true, fakeDns = true,
        )
        assertTrue(on.contains("ipv6: true\n"))
        assertTrue(on.contains("  ipv6: true\n"))
        assertTrue(on.contains("  enhanced-mode: fake-ip\n"))
        assertTrue(on.contains("  fake-ip-range6: fd11:208:a::/48\n"))
        val off = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = "proxies: []\n", secret = "s", controlPort = 19090, ipv6 = false, fakeDns = false,
        )
        assertTrue(off.contains("ipv6: false\n"))
        assertTrue(off.contains("  enhanced-mode: redir-host\n"))
        assertFalse(off.contains("fake-ip-range6"))
        val runtime = src("MihomoRuntime.kt")
        assertTrue(runtime.contains("ipv6 = RoutingModePreferenceStore(context).isIpv6Enabled()"))
        assertTrue(runtime.contains("fakeDns = RoutingModePreferenceStore(context).isFakeDnsEnabled()"))
        val ui = src("CoreOptionsSettings.kt")
        assertTrue(ui.contains("saveCoreOption(\"ipv6\""))
        assertTrue(ui.contains("saveCoreOption(\"fakeDns\""))
    }

    @Test
    fun catScoutOpensTheRealMethodFinderNotTcpOnlyScout() {
        val guides = src("MainActivityGuides.kt")
        assertTrue(guides.contains("🧭 Cat Scout"))
        assertTrue(guides.contains("showMethodFinderDialog(FinderFamily.entries.toSet(), freeOnly = false)"))
        assertFalse(guides.contains("runMlmScoutRecommendations("))
        val hub = src("MainActivityAntiFilterTools.kt")
        assertTrue(hub.contains("launchMlmHubEntry(catalog.getOrNull(which)?.id)"))
        assertTrue(hub.contains("\"mlm_scout_flux\" -> showMethodFinderDialog(FinderFamily.entries.toSet(), freeOnly = false)"))
        assertFalse(hub.contains("8 -> showRecommendedOneClickConnectWizardDialog()"))
        val catalog = src("MlmAndNextGenVpnHub.kt")
        assertTrue(catalog.contains("id = \"mlm_scout_flux\""))
        assertTrue(catalog.contains("Cat Scout"))
    }

    @Test
    fun openVpnMamiArgoL2tpAreNotInAppEngines() {
        val tunnels = src("CatVpsTunnels.kt")
        assertTrue("OpenVPN exists as a VPS install script, not a phone engine", tunnels.contains("openvpn --config"))
        val vpn = src("CatClientVpnService.kt")
        assertFalse(vpn.contains("OpenVpnService"))
        assertFalse(vpn.contains("l2tp://"))
        assertTrue(src("AmneziaVpnKey.kt").contains("Not runnable here: OpenVPN"))
    }
}

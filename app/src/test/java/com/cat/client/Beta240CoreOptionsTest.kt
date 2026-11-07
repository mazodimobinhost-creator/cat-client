package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** beta240 — settings other clients carry: sniffing, FakeDNS, system HTTP proxy. */
class Beta240CoreOptionsTest {
    private val subscription = """
        proxies:
          - name: node-a
            type: vless
            server: edge.example.com
            port: 443
            uuid: 11111111-1111-1111-1111-111111111111
            tls: true
            network: ws
        proxy-groups:
          - name: PROXY
            type: select
            proxies: [node-a]
        rules:
          - MATCH,PROXY
    """.trimIndent()

    private fun runtime(raw: String = subscription, sniffing: Boolean = false, fakeDns: Boolean = true) =
        MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = raw, secret = "s", controlPort = 19090, sniffing = sniffing, fakeDns = fakeDns,
        )

    private fun topLevelCount(yaml: String, key: String) = yaml.lines().count { it == "$key:" }

    @Test
    fun defaultsKeepTheBehaviourCatAlwaysHad() {
        val yaml = runtime()
        assertTrue(yaml.contains("  enhanced-mode: fake-ip\n"))
        assertTrue(yaml.contains("  fake-ip-range: 198.18.0.1/16\n"))
        assertEquals(0, topLevelCount(yaml, "sniffer"))
    }

    @Test
    fun sniffingIsRouteOnlyAndCoversTlsHttpQuic() {
        val yaml = runtime(sniffing = true)
        assertEquals(1, topLevelCount(yaml, "sniffer"))
        val block = yaml.substringAfter("sniffer:\n")
        assertTrue(block.contains("  enable: true\n"))
        assertTrue("never rewrites the dialled address", block.contains("  override-destination: false\n"))
        assertTrue(block.contains("    TLS:\n") && block.contains("    HTTP:\n") && block.contains("    QUIC:\n"))
        assertTrue(block.contains("      ports: [443, 8443]\n"))
    }

    @Test
    fun aSubscriptionSnifferIsReplacedNotDuplicated() {
        val withOwn = subscription + "\nsniffer:\n  enable: false\n  override-destination: true\n"
        assertEquals("left alone while the option is off", 1, topLevelCount(runtime(withOwn), "sniffer"))
        val yaml = runtime(withOwn, sniffing = true)
        assertEquals("one sniffer key, otherwise the YAML is invalid", 1, topLevelCount(yaml, "sniffer"))
        assertFalse(yaml.contains("override-destination: true"))
    }

    @Test
    fun fakeDnsOffReturnsRealAddresses() {
        val yaml = runtime(fakeDns = false)
        assertTrue(yaml.contains("  enhanced-mode: redir-host\n"))
        assertFalse(yaml.contains("fake-ip-range"))
        assertFalse(yaml.contains("enhanced-mode: fake-ip"))
    }

    @Test
    fun pingAddressIsAChoiceOfHttpsEndpointsOnly() {
        assertEquals(MihomoRuntimeDefaults.DELAY_TEST_URL, ConnectionTestSettings().delayTestUrl)
        val cf = "https://cp.cloudflare.com/generate_204"
        assertEquals(cf, ConnectionTestSettings(delayTestUrl = cf).normalized().delayTestUrl)
        assertEquals(
            "unknown or plain-HTTP addresses fall back to the default",
            MihomoRuntimeDefaults.DELAY_TEST_URL,
            ConnectionTestSettings(delayTestUrl = "http://example.com/").normalized().delayTestUrl,
        )
        assertTrue(ConnectionTestSettings.DELAY_TEST_URLS.all { it.startsWith("https://") })
        val tests = File("src/main/java/com/cat/client/CatClientVpnServiceConnectionTests.kt").readText()
        assertTrue(tests.contains("url = connectionTestSettingsPreferenceStore.read().delayTestUrl"))
        val screen = File("src/main/java/com/cat/client/MainActivityAdvanced.kt").readText()
        assertTrue("updates the same local settings the other fields save", screen.contains("connectionTestSettings.copy(delayTestUrl = url)"))
    }

    @Test
    fun preferencesAndUiAreWired() {
        val main = File("src/main/java/com/cat/client")
        val store = File(main, "MihomoRuntime.kt").readText()
        listOf("isSniffingEnabled", "isFakeDnsEnabled", "isSystemHttpProxyEnabled").forEach {
            assertTrue(it, store.contains("fun $it()"))
        }
        assertTrue("FakeDNS stays on by default", store.contains("getBoolean(KEY_FAKE_DNS, true)"))
        val vpn = File(main, "CatClientVpnService.kt").readText()
            .substringAfter("fun establishTun(").substringBefore("applySplitTunnel(builder")
        assertTrue(vpn.contains("isSystemHttpProxyEnabled()"))
        assertTrue(vpn.contains("buildDirectProxy(\"127.0.0.1\", MihomoRuntimeDefaults.MIXED_PORT)"))
        val ui = File(main, "CoreOptionsSettings.kt").readText()
        assertTrue(ui.contains("Build.VERSION_CODES.Q"))
        assertTrue(ui.contains("reconnectForConnectionOptionChange()"))
        assertTrue(File(main, "MainActivityAdvanced.kt").readText().contains("addCoreOptionRows(routingPanel)"))
        listOf("src/main/res/values/strings.xml", "src/main/res/values-fa/strings.xml").forEach { path ->
            val xml = File(path).readText()
            listOf("core_sniffing_title", "core_fake_dns_description", "core_http_proxy_title").forEach {
                assertTrue("$path $it", xml.contains("name=\"$it\""))
            }
        }
    }
}

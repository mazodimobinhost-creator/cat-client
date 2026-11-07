package com.cat.client

import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Orbot / Tor engine, exercised as real code on the JVM.
 *
 * This is the layman's third path: Orbot runs Tor on the phone and opens a SOCKS5 port on
 * loopback, so the app can keep working even when nothing of ours is reachable. What is pinned
 * here, and why each check matters:
 *
 *  - **loopback only**: a "127.0.0.1" that is not loopback would hand a stranger the exit, so
 *    the normalizer refuses every other address instead of quietly accepting it;
 *  - **the injected proxy block** is what the core actually dials (`socks5`, `udp: false`
 *    because Tor carries no UDP), it lands inside the existing `proxies:` list, and injecting
 *    twice does not grow a second copy — the runtime YAML is rebuilt on every reconnect;
 *  - **the routing shape**: with the engine on, the effective routing mode becomes the
 *    global-proxy shape, subscription rules are dropped (a subscription rule that routes DIRECT
 *    would quietly walk around Tor) and the DNS resolvers are pinned to `#cat-orbot`;
 *  - **the probe**: a real TCP connect against a real local listener, and a truthful
 *    «unreachable» once that listener is gone.
 */
class OrbotEngineTest {

    private val subscription = """
        proxies:
          - name: node-a
            type: vless
            server: edge.example.com
            port: 443
            uuid: 11111111-1111-1111-1111-111111111111
            network: ws
            tls: true
        proxy-groups:
          - name: PROXY
            type: select
            proxies:
              - node-a
        rules:
          - 'DOMAIN,example.org,DIRECT'
          - 'MATCH,PROXY'
    """.trimIndent()

    // ---------- the address is loopback or nothing ----------

    @Test
    fun onlyLoopbackIsAcceptedAsTheOrbotAddress() {
        assertEquals("127.0.0.1", OrbotEnginePolicy.normalizeHost("127.0.0.1"))
        assertEquals("127.0.0.5", OrbotEnginePolicy.normalizeHost(" 127.0.0.5 "))
        assertEquals("::1", OrbotEnginePolicy.normalizeHost("[::1]"))
        assertEquals("127.0.0.1", OrbotEnginePolicy.normalizeHost("localhost"))
        listOf("10.0.0.1", "8.8.8.8", "example.com", "127.1", "127.0.0.256", "", "::2").forEach { bad ->
            val threw = runCatching { OrbotEnginePolicy.normalizeHost(bad) }.isFailure
            assertTrue("accepted a non-loopback Orbot address: '$bad'", threw)
        }
    }

    @Test
    fun thePortIsARealPort() {
        assertEquals(9050, OrbotEnginePolicy.normalizePort(9050))
        assertEquals(1, OrbotEnginePolicy.normalizePort(1))
        assertEquals(65535, OrbotEnginePolicy.normalizePort(65535))
        listOf(0, -1, 65536).forEach { bad ->
            assertTrue("accepted port $bad", runCatching { OrbotEnginePolicy.normalizePort(bad) }.isFailure)
        }
    }

    // ---------- the injected proxy ----------

    @Test
    fun theInjectedBlockIsWhatTheCoreDials() {
        val block = OrbotEnginePolicy.proxyBlockYaml("127.0.0.1", 9050)
        assertTrue(block.contains("- name: cat-orbot"))
        assertTrue(block.contains("type: socks5"))
        assertTrue(block.contains("server: 127.0.0.1"))
        assertTrue(block.contains("port: 9050"))
        // Tor's SOCKS5 has no UDP relay: claiming UDP would make QUIC look tunneled while it
        // is dropped, which is exactly the kind of lie this project avoids.
        assertTrue(block.contains("udp: false"))
        block.lines().filter { it.isNotBlank() }.forEach { line ->
            assertTrue("every line must sit under a `proxies:` list item: '$line'", line.startsWith("  "))
        }
    }

    @Test
    fun injectionLandsInsideTheExistingListAndKeepsEveryOtherProxy() {
        val injected = OrbotEnginePolicy.injectProxy(subscription, "127.0.0.1", 9050)
        assertTrue(injected.contains("- name: cat-orbot"))
        assertTrue("the subscription's own proxy must survive", injected.contains("- name: node-a"))
        assertTrue(injected.contains("server: edge.example.com"))
        // It has to be inside the proxies list, i.e. before the next top-level key.
        val proxiesAtIndex = injected.indexOf("proxies:")
        val groupsAtIndex = injected.indexOf("proxy-groups:")
        val orbotAtIndex = injected.indexOf("- name: cat-orbot")
        assertTrue(orbotAtIndex in (proxiesAtIndex + 1) until groupsAtIndex)
        assertTrue(OrbotEnginePolicy.containsProxy(injected))
    }

    @Test
    fun injectingTwiceDoesNotGrowASecondCopy() {
        val once = OrbotEnginePolicy.injectProxy(subscription, "127.0.0.1", 9050)
        val twice = OrbotEnginePolicy.injectProxy(once, "127.0.0.1", 9050)
        assertEquals(1, Regex("- name: cat-orbot").findAll(twice).count())
    }

    @Test
    fun anExistingEntryWithTheSameNameIsReplacedNotDuplicated() {
        val stale = subscription.replace(
            "  - name: node-a\n",
            "  - name: cat-orbot\n    type: socks5\n    server: 127.0.0.1\n    port: 1111\n  - name: node-a\n",
        )
        val fixed = OrbotEnginePolicy.injectProxy(stale, "127.0.0.1", 9050)
        assertEquals(1, Regex("- name: cat-orbot").findAll(fixed).count())
        assertTrue(fixed.contains("port: 9050"))
        assertFalse("the stale port must be gone", fixed.contains("port: 1111"))
        assertTrue("and the neighbour proxy is still there", fixed.contains("- name: node-a"))
    }

    @Test
    fun aConfigWithoutAProxiesSectionGetsOne() {
        val minimal = "mixed-port: 7890\nrules:\n  - 'MATCH,DIRECT'\n"
        val injected = OrbotEnginePolicy.injectProxy(minimal, "127.0.0.1", 9050)
        assertTrue(injected.contains("proxies:"))
        assertTrue(injected.contains("- name: cat-orbot"))
        // The existing content is untouched, and the new block is a top-level section.
        assertTrue(injected.contains("mixed-port: 7890"))
        assertTrue(injected.contains("rules:"))
        assertFalse(OrbotEnginePolicy.containsProxy(minimal))
    }

    // ---------- the routing shape ----------

    @Test
    fun withTheEngineOnEverythingGoesThroughOrbotAndNoRuleCanWalkAroundIt() {
        val runtime = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = subscription,
            secret = "test-secret",
            controlPort = 19090,
            orbot = OrbotProxySettings("127.0.0.1", 9050),
        )
        assertTrue(runtime.contains("- name: cat-orbot"))
        assertTrue(runtime.contains("type: socks5"))
        assertTrue("the last rule must point at Orbot", runtime.contains("MATCH,cat-orbot"))
        // The subscription's own DIRECT rule would be a hole in the tunnel; ROUTING_OVERRIDE_KEYS
        // drops the whole rules list in the non-subscription shape.
        assertFalse("a subscription rule must not survive", runtime.contains("DOMAIN,example.org,DIRECT"))
        // DNS is resolved through the same proxy, otherwise the lookup leaks.
        val nameserverLines = runtime.lines().filter { it.contains("dns-query") || it.contains("tls://") }
        assertTrue("encrypted resolvers must ride the Orbot proxy", nameserverLines.isNotEmpty())
        nameserverLines.forEach { line ->
            assertTrue("resolver without the proxy suffix: '$line'", line.contains("#cat-orbot'"))
        }
    }

    @Test
    fun withoutTheEngineNothingChanges() {
        val plain = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = subscription,
            secret = "test-secret",
            controlPort = 19090,
        )
        assertFalse(plain.contains("cat-orbot"))
        assertTrue("the subscription's own rules stay in charge", plain.contains("DOMAIN,example.org,DIRECT"))
    }

    // ---------- the probe ----------

    @Test
    fun theProbeAnswersForARealListenerAndStopsAnsweringWhenItCloses() {
        val server = ServerSocket(0)
        val port = server.localPort
        try {
            val found = OrbotProbe.probe("127.0.0.1", port, timeoutMs = 500)
            assertTrue("a listener on loopback must be reported as reachable", found.reachable)
            assertTrue("a reachable probe reports a latency", (found.latencyMs ?: -1L) >= 0L)
        } finally {
            server.close()
        }
        val gone = OrbotProbe.probe("127.0.0.1", port, timeoutMs = 300)
        assertFalse("a closed port must be reported as unreachable", gone.reachable)
        assertTrue("and the reason is carried for the log", gone.detail.isNotBlank())
    }

    @Test
    fun aProbeThatNoOneListensOnIsNotSlow() {
        // 127.0.0.1 refuses instantly; the timeout is the ceiling, not the expected cost. This
        // pins that the probe cannot hang the caller for longer than its timeout.
        val started = System.currentTimeMillis()
        OrbotProbe.probe("127.0.0.1", 1, timeoutMs = 300)
        assertTrue("the probe must respect its timeout", System.currentTimeMillis() - started < 1_500)
    }
}

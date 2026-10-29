package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * beta219: «connected, but nothing loads / Instagram freezes» on Cat Warp and Cat Fox VPN.
 *  - Cat Warp advertised MTU 1280 also for the two-hop transports whose inner hop carries 1200 (wiw/wom) or 1162 (mim).
 *  - Cat Fox VPN refused every new connection once 64 (idle) tunnels were open, and sent QUIC DIRECT.
 */
class Beta219TunnelTrafficTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    // ---------- Cat Warp MTU ----------

    @Test
    fun interfaceMtuMatchesWhatTheTransportCarries() {
        assertEquals(1200, AetherTunPolicy.mtuForTransport("wiw"))
        assertEquals(1200, AetherTunPolicy.mtuForTransport("wom"))
        assertEquals(1162, AetherTunPolicy.mtuForTransport("mim"))
        assertEquals(1280, AetherTunPolicy.mtuForTransport("h2"))
        assertEquals(1280, AetherTunPolicy.mtuForTransport("h3"))
        assertEquals(1280, AetherTunPolicy.mtuForTransport("wg"))
        assertTrue(AetherTunPolicy.carriesIpv6(1280))
        assertFalse(AetherTunPolicy.carriesIpv6(1200))
        assertFalse(AetherTunPolicy.carriesIpv6(1162))
    }

    @Test
    fun foreignExitProfilesGetTheSmallInnerMtu() {
        fun mtuOf(cfg: AetherRuntimeConfig) = AetherTunPolicy.mtuForTransport(AetherTunPolicy.transportOfJson(cfg.toNativeJson()))
        val id = File("/tmp/aether.toml")
        assertEquals(1200, mtuOf(AetherRuntimeConfig(AetherMode.MasqueHttp2, id, exitLocation = "foreign")))
        assertEquals(1200, mtuOf(AetherRuntimeConfig(AetherMode.WireGuard, id, exitLocation = "foreign")))
        assertEquals(1200, mtuOf(AetherRuntimeConfig(AetherMode.WarpInWarp, id)))
        assertEquals(1162, mtuOf(AetherRuntimeConfig(AetherMode.MasqueInMasque, id)))
        assertEquals(1280, mtuOf(AetherRuntimeConfig(AetherMode.MasqueHttp2, id, exitLocation = "iran")))
        assertEquals(1280, mtuOf(AetherRuntimeConfig(AetherMode.WireGuard, id, exitLocation = "iran")))
        assertEquals("h3", AetherTunPolicy.transportOfJson("{}"))
    }

    @Test
    fun theServiceAndSessionUseTheTransportMtu() {
        val session = src("AetherSession.kt")
        assertTrue(session.contains("AetherTunPolicy.mtuForTransport(AetherTunPolicy.transportOfJson(effectiveJson))"))
        val service = src("CatClientVpnService.kt")
        val fn = service.substring(service.indexOf("private fun establishAetherTun("), service.indexOf("private suspend fun establishTun("))
        assertTrue(fn.contains(".setMtu(safeMtu)"))
        assertTrue("IPv6 only when the MTU allows it", fn.contains("if (AetherTunPolicy.carriesIpv6(safeMtu))"))
        assertFalse("no unconditional IPv6 address", fn.contains(".addAddress(ipv6, 128)\n            .addRoute(\"0.0.0.0\", 0)"))
    }

    // ---------- Cat Fox VPN tunnel slots ----------

    private class Flag : Closeable {
        val closed = AtomicBoolean(false)
        override fun close() { closed.set(true) }
    }

    @Test
    fun aFullBridgeEvictsTheLongestIdleTunnelInsteadOfRefusing() {
        var now = 1_000_000L
        val slots = FenixTunnelSlots(capacity = 2, idleEvictMs = 15_000L, clock = { now })
        val a = slots.acquire(waitMs = 10)!!
        val aSock = Flag().also { a.attach(it) }
        now += 1_000
        val b = slots.acquire(waitMs = 10)!!
        val bSock = Flag().also { b.attach(it) }
        // Both busy (recent traffic): a third connection cannot get a slot.
        assertNull(slots.acquire(waitMs = 10))
        // 20 s later only b is still moving data → a is the idle one and is closed for the newcomer.
        now += 20_000
        b.touch()
        val c = slots.acquire(waitMs = 10)
        assertNotNull(c)
        assertTrue(aSock.closed.get())
        assertTrue(a.evicted)
        assertFalse(bSock.closed.get())
        assertEquals(2, slots.inUse)
        // Releasing twice (handler finally after eviction) never inflates the permits.
        a.close(); a.close()
        c!!.close(); b.close()
        assertEquals(0, slots.inUse)
        val x = slots.acquire(waitMs = 10)!!
        val y = slots.acquire(waitMs = 10)!!
        assertNull(slots.acquire(waitMs = 10))
        x.close(); y.close()
    }

    @Test
    fun foxBridgeUsesSlotsAndSpreadsOverConnections() {
        val s = src("FirefoxVpnEngine.kt")
        assertTrue(s.contains("private val tunnelSlots = FenixTunnelSlots(MAX_FENIX_TUNNELS)"))
        assertFalse(s.contains("activeTunnelSemaphore"))
        assertTrue(s.contains("slot.attach(upstreamStream)"))
        assertTrue(s.contains("pipeBytesWithAccounting(inp, upOut, slot)"))
        assertTrue(s.contains("pipeBytesWithAccounting(upIn, out, slot)"))
        assertTrue(FirefoxVpnEngine.FENIX_MAX_CONNECTIONS_PER_NODE in 2..8)
        assertTrue(FirefoxVpnEngine.FENIX_SOFT_STREAMS_PER_CONNECTION in 8..64)
    }

    // ---------- Cat Fox VPN runtime: QUIC and DNS ----------

    private val foxSubscription = """
        proxies:
          - name: fox-de
            type: socks5
            server: 127.0.0.1
            port: 10829
            udp: false
            fenix-uri: 'fenix://127.0.0.1:10829/?country=DE#Fox'
        proxy-groups:
          - name: PROXY
            type: select
            proxies:
              - fox-de
        rules:
          - 'MATCH,PROXY'
    """.trimIndent()

    private val mixedSubscription = """
        proxies:
          - name: fox-de
            type: socks5
            server: 127.0.0.1
            port: 10829
            udp: false
            fenix-uri: 'fenix://127.0.0.1:10829/?country=DE#Fox'
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
              - fox-de
              - node-a
        rules:
          - 'MATCH,PROXY'
    """.trimIndent()

    @Test
    fun foxRuntimeRefusesQuicAndUsesForeignDohOnly() {
        assertTrue(MihomoRuntimeConfigBuilder.isTcpOnlyEngineConfig(foxSubscription))
        val runtime = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(rawYaml = foxSubscription, secret = "s", controlPort = 19090)
        assertTrue(runtime.contains(MihomoRuntimeConfigBuilder.TCP_ONLY_QUIC_RULE))
        val rules = runtime.substring(runtime.indexOf("rules:\n"))
        assertTrue("QUIC refusal comes before MATCH", rules.indexOf("NETWORK,UDP") in 0 until rules.indexOf("MATCH,PROXY"))
        val dnsSection = runtime.substring(runtime.indexOf("  nameserver:\n"), runtime.indexOf("  proxy-server-nameserver:"))
        assertFalse("no DoT through a web-ports-only proxy", dnsSection.contains("tls://"))
        assertFalse("no Iranian resolver through a foreign exit", dnsSection.contains("178.22.122.100"))
        assertTrue(dnsSection.contains("https://1.1.1.1/dns-query"))
    }

    @Test
    fun otherSubscriptionsAreUnchanged() {
        assertFalse(MihomoRuntimeConfigBuilder.isTcpOnlyEngineConfig(mixedSubscription))
        val runtime = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(rawYaml = mixedSubscription, secret = "s", controlPort = 19090)
        assertFalse(runtime.contains("NETWORK,UDP"))
        assertTrue(runtime.contains("tls://1.1.1.1:853"))
    }
}

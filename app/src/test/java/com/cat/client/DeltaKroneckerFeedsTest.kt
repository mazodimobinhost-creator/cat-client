package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaKroneckerFeedsTest {
    @Test
    fun feedsAreHttpsUniqueAndNamed() {
        val feeds = DeltaKroneckerFeeds.FEEDS
        assertEquals(feeds.size, feeds.map { it.id }.toSet().size)
        assertEquals(feeds.size, feeds.map { it.url }.toSet().size)
        for (f in feeds) {
            assertTrue(f.url, f.url.startsWith("https://"))
            assertTrue(f.url, f.url.contains("Delta-Kronecker"))
            assertTrue(f.titleFa.isNotBlank() && f.titleEn.isNotBlank() && f.whatFa.isNotBlank() && f.whatEn.isNotBlank())
            val sub = f.kind in setOf(DeltaKroneckerFeeds.Kind.SUBSCRIPTION, DeltaKroneckerFeeds.Kind.AETHER, DeltaKroneckerFeeds.Kind.DNS_TUNNEL)
            assertEquals(f.id, sub, f.subName.isNotBlank())
        }
        for (k in DeltaKroneckerFeeds.Kind.values()) assertTrue(k.name, DeltaKroneckerFeeds.byKind(k).isNotEmpty())
    }

    @Test
    fun protonWireGuardFeedIsASubscription() {
        val f = DeltaKroneckerFeeds.FEEDS.first { it.id == "proton-wg" }
        assertEquals(DeltaKroneckerFeeds.Kind.SUBSCRIPTION, f.kind)
        assertTrue(f.url.endsWith("ProtonVPN-WireGuard-configuration/refs/heads/main/proton_wireguard_links.txt"))
        assertTrue(f.whatFa.contains("WireGuard"))
    }

    @Test
    fun bridgeParsing() {
        val fp = "FBA329BA4F5FE648456C3A28BBAA3C7EBD118E08"
        val text = """
            # tested
            obfs4 100.8.170.63:50164 $fp cert=abc iat-mode=0
            Bridge webtunnel [2001:db8::1]:443 $fp url=https://x.example/p ver=0.0.2
            obfs4 100.8.170.63:50164 $fp cert=abc iat-mode=0
            1.2.3.4:9001 $fp
            <html>not a bridge</html>
            vless://nope
        """.trimIndent()
        val lines = DeltaKroneckerFeeds.parseBridgeLines(text)
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("obfs4 100.8.170.63:50164"))
        assertTrue(lines[1].startsWith("webtunnel [2001:db8::1]:443"))
        assertEquals("1.2.3.4:9001 $fp", lines[2])
        assertEquals(1, DeltaKroneckerFeeds.parseBridgeLines(text, max = 1).size)
        assertTrue(DeltaKroneckerFeeds.parseBridgeLines("404: Not Found").isEmpty())
    }
}

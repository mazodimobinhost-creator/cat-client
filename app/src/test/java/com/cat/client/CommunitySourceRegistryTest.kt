package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunitySourceRegistryTest {
    @Test fun catalogIsOptInLicensedAndPinnedToRawGithub() {
        assertTrue(CommunitySourceRegistry.sources.isNotEmpty())
        CommunitySourceRegistry.sources.forEach {
            assertEquals(false, it.enabledByDefault)
            assertEquals("GPL-3.0", it.license)
            assertTrue(CommunitySourceRegistry.validate(it) != null)
        }
    }

    @Test fun rejectsUntrustedOrDefaultEnabledSources() {
        assertNull(CommunitySourceRegistry.validate(CommunitySource("bad", "Bad", "https://example.com/sub", "unknown")))
        assertNull(CommunitySourceRegistry.validate(CommunitySource("bad", "Bad", "https://raw.githubusercontent.com/a/b/main/x", "unknown", enabledByDefault = true)))
    }

    @Test fun extractsBoundedDeduplicatedPublicLinks() {
        val good = "vless://123e4567-e89b-42d3-a456-426614174000@example.com:443?security=tls"
        val trojan = "trojan://secret@1.1.1.1:443?security=tls"
        val text = listOf(good, good, trojan, "vless://id@127.0.0.1:443", "javascript:alert(1)").joinToString("\n")
        assertEquals(listOf(good, trojan), CommunitySourceRegistry.extractLinks(text))
    }

    @Test fun refusesOversizedPayloadAndPrivateDestinations() {
        assertTrue(CommunitySourceRegistry.extractLinks("x".repeat(2 * 1024 * 1024 + 1)).isEmpty())
        assertTrue(CommunitySourceRegistry.extractLinks("trojan://x@192.168.1.2:443").isEmpty())
        assertTrue(CommunitySourceRegistry.extractLinks("trojan://x@[fd00::1]:443").isEmpty())
    }
}

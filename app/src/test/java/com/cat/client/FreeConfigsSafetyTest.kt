package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeConfigsSafetyTest {
    @Test fun rejectsLocalPrivateAndCarrierNatDestinations() {
        val unsafe = listOf(
            "vless://id@127.0.0.1:443",
            "trojan://x@10.0.0.1:443",
            "vless://id@100.64.0.1:443",
            "trojan://x@172.16.1.1:443",
            "vless://id@192.168.1.1:443",
            "trojan://x@[fd00::1]:443",
            "vless://id@[fe80::1]:443",
        )
        unsafe.forEach { assertFalse(it, FreeConfigs.isSafePublicLink(it)) }
        assertTrue(FreeConfigs.isSafePublicLink("vless://id@1.1.1.1:443"))
        assertTrue(FreeConfigs.isSafePublicLink("trojan://x@example.com:443"))
    }

    @Test fun extractionDropsUnsafeEntriesBeforePreviewCacheOrImport() {
        val safe = "vless://id@example.com:443"
        val links = FreeConfigs.extractLinks("$safe\nvless://id@127.0.0.1:443\n$safe")
        assertEquals(listOf(safe, safe), links)
    }
}

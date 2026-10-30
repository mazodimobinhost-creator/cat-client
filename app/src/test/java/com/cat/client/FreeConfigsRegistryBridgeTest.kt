package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeConfigsRegistryBridgeTest {
    @Test fun reviewedV2goSourcesReachExistingFreeConfigUiPipeline() {
        val sources = FreeConfigs.sources()
        val vless = sources.single { it.id == "v2go-vless" }
        val trojan = sources.single { it.id == "v2go-trojan" }
        assertTrue(vless.urls.any { it == CommunitySourceRegistry.sources.single { source -> source.id == vless.id }.url })
        assertTrue(trojan.urls.any { it == CommunitySourceRegistry.sources.single { source -> source.id == trojan.id }.url })
        assertEquals(250, vless.limit)
        assertEquals(250, trojan.limit)
        assertFalse(vless.urls.any { it.startsWith("http://") })
    }
}

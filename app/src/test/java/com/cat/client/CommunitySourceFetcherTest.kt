package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunitySourceFetcherTest {
    @Test fun invalidSourceFailsBeforeAnyNetworkRequest() {
        val source = CommunitySource("bad", "Bad", "http://127.0.0.1/private", "unknown")
        val result = CommunitySourceFetcher(connectTimeoutMs = 1, readTimeoutMs = 1).fetch(source)
        assertTrue(result is CommunityFetchResult.Failure)
        assertEquals(CommunityFetchResult.Reason.INVALID_SOURCE, (result as CommunityFetchResult.Failure).reason)
    }

    @Test fun registryNeverEnablesBackgroundFetchByDefault() {
        assertTrue(CommunitySourceRegistry.sources.all { !it.enabledByDefault })
        assertTrue(CommunitySourceRegistry.sources.all { it.refreshHours >= 1 })
    }
}

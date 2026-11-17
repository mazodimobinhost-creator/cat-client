package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta220: persisted forms (org.json) of the real internet result and the route memory. */
class Beta220RealInternetJsonTest {
    @Test fun resultRoundTrips() {
        val r = RealInternetResult(
            RealInternetStatus.Slow, latencyMs = 420, kbps = 90, detail = "download 90kbps", fingerprint = "fp1",
            routeLabel = "🐱 Cat · DE", atMs = 123L, hops = 1, canTryNext = true, autoHopping = false,
        )
        assertEquals(r, RealInternetResult.fromJson(r.toJson()))
        assertNull(RealInternetResult.fromJson("{\"status\":\"bogus\"}"))
        assertNull(RealInternetResult.fromJson("not json"))
        assertNull(RealInternetResult.fromJson(null))
    }

    @Test fun routeMemoryRoundTripsAndToleratesJunk() {
        val e = listOf(RouteMemoryEntry("A", 10, 20, 1, 2), RouteMemoryEntry("B", 30, 0, 3, 0))
        assertEquals(e, RouteMemoryPolicy.fromJson(RouteMemoryPolicy.toJson(e)))
        assertTrue(RouteMemoryPolicy.fromJson("[{\"f\":\"\"},5,{\"x\":1}]").isEmpty())
        assertTrue(RouteMemoryPolicy.fromJson("{oops").isEmpty())
    }
}

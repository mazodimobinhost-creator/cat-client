package com.cat.client

import com.whitedns.whiteaesther.core.EndpointScanResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherOperationsTest {
    @Test fun acceptsOnlyPublicLiteralScanEndpoints() {
        assertTrue(AetherOperations.safeEndpoint(EndpointScanResult("1.1.1.1:443", 20)))
        assertTrue(AetherOperations.safeEndpoint(EndpointScanResult("[2606:4700:4700::1111]:443", 30)))
        listOf(
            "127.0.0.1:443", "10.0.0.1:443", "192.168.1.1:443",
            "100.64.0.1:443", "[fd00::1]:443", "[fe80::1]:443",
            "example.com:443", "1.1.1.1:0",
        ).forEach { assertFalse(it, AetherOperations.safeEndpoint(EndpointScanResult(it, 1))) }
        assertFalse(AetherOperations.safeEndpoint(EndpointScanResult("1.1.1.1:443", -1)))
    }
}

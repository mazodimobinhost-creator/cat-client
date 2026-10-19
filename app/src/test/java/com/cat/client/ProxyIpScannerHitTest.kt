package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyIpScannerHitTest {
    @Test fun port443IsStillVisibleAndPreserved() {
        val hit = ProxyIpScannerPage.Hit("103.237.102.191", 443, "DE", "FRA", 50)
        assertEquals("103.237.102.191:443", hit.authority)
    }

    @Test fun nonStandardProxyIpPortIsPreserved() {
        val hit = ProxyIpScannerPage.Hit("103.237.102.191", 11111, "DE", "FRA", 50)
        assertEquals("103.237.102.191:11111", hit.authority)
    }

    @Test fun ipv6IsBracketedExactlyOnce() {
        val raw = ProxyIpScannerPage.Hit("2606:4700::1", 2053, "DE", "FRA", 50)
        val bracketed = ProxyIpScannerPage.Hit("[2606:4700::1]", 2053, "DE", "FRA", 50)
        assertEquals("[2606:4700::1]:2053", raw.authority)
        assertEquals(raw.authority, bracketed.authority)
    }
}

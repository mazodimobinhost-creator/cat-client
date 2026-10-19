package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Test

class IpScannerPanelLineTest {
    @Test
    fun ipv4ScanResultKeepsVerifiedPortAndCountry() {
        val result = IpScanner.ScanResult(
            ip = "104.16.1.1",
            pingMs = 42,
            sni = "example.com",
            port = 2053,
            countryCode = "DE",
        )

        assertEquals("104.16.1.1:2053", result.authority)
        assertEquals("104.16.1.1:2053#DE", result.panelLine)
    }

    @Test
    fun ipv6ScanResultBracketsAddressBeforeAddingPort() {
        val result = IpScanner.ScanResult(
            ip = "2606:4700::1",
            pingMs = 42,
            sni = "example.com",
            port = 2053,
            countryCode = "DE",
        )

        assertEquals("[2606:4700::1]:2053", result.authority)
        assertEquals("[2606:4700::1]:2053#DE", result.panelLine)
    }

    @Test
    fun alreadyBracketedIpv6IsNotDoubleBracketed() {
        val result = IpScanner.ScanResult(
            ip = "[2606:4700::1]",
            pingMs = 42,
            sni = "example.com",
            port = 443,
        )

        assertEquals("[2606:4700::1]:443", result.panelLine)
    }
}

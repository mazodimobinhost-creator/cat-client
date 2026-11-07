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
    fun verifiedScanFeedsNativeCatalogWithItsExactPort() {
        val result = IpScanner.ScanResult(
            ip = "104.16.1.1", pingMs = 42, sni = "worker.example", port = 8443,
            countryCode = "DE", countryName = "Germany", colo = "FRA", verifyTls = true, tlsOk = true,
        )
        val row = result.toCatalogEntry(100L)
        assertEquals("104.16.1.1:8443", row.endpoint.authority)
        assertEquals(EndpointRole.CLEAN_IP, row.endpoint.role)
        assertEquals(EndpointVerdict.PARTIAL, row.endpoint.verdict)
        assertEquals("Germany", row.countryName)
        assertEquals("FRA", row.datacenter)
        assertEquals(RelayRisk.UNKNOWN, row.risk)
    }

    @Test
    fun failedTlsScanIsNotPromotedByCatalogConversion() {
        val result = IpScanner.ScanResult(
            ip = "104.16.1.1", pingMs = 42, sni = "worker.example", port = 443,
            verifyTls = true, tlsOk = false,
        )
        assertEquals(EndpointVerdict.FAILED, result.toCatalogEntry(100L).endpoint.verdict)
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

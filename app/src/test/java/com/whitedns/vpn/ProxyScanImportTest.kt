package com.whitedns.vpn

import com.cat.client.EndpointRole
import com.cat.client.EndpointVerdict
import com.cat.client.ProxyScanImport
import com.cat.client.ProxyScannerPage
import com.cat.client.RelayRisk
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyScanImportTest {
    @Test fun scannerImportKeepsSocksPort() {
        assertEquals("socks5://relay.example:11111", ProxyScanImport.uri(
            ProxyScannerPage.Hit("relay.example", 11111, ProxyScannerPage.Proto.SOCKS5, 50), 1L))
    }

    @Test fun scannerCopyKeepsHttpTypeAndPort() {
        assertEquals("http://184.75.221.82:3118", ProxyScanImport.uri(
            ProxyScannerPage.Hit("184.75.221.82", 3118, ProxyScannerPage.Proto.HTTP, 60), 1L))
    }

    @Test fun scannerImportBracketsIpv6Once() {
        assertEquals("socks4://[2606:4700::4]:1080", ProxyScanImport.uri(
            ProxyScannerPage.Hit("[2606:4700::4]", 1080, ProxyScannerPage.Proto.SOCKS4, 70), 1L))
    }

    @Test fun scannerHitFeedsCatalogAsPartialWithExactRoleAndPort() {
        val row = ProxyScannerPage.Hit("184.75.221.82", 3118, ProxyScannerPage.Proto.HTTP, 60, "DE")
            .toCatalogEntry(100L, "FRA")
        assertEquals("184.75.221.82:3118", row.endpoint.authority)
        assertEquals(EndpointRole.HTTP_RELAY, row.endpoint.role)
        assertEquals(EndpointVerdict.PARTIAL, row.endpoint.verdict)
        assertEquals("DE", row.countryCode)
        assertEquals("FRA", row.datacenter)
        assertEquals(RelayRisk.UNKNOWN, row.risk)
    }
}

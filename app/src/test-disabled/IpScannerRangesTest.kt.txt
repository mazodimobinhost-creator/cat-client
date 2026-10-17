package com.whitedns.vpn

import com.cat.client.IpScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpScannerRangesTest {
    @Test
    fun rangeListAcceptsIpv4Ipv6AndHostnames() {
        val parsed = IpScanner.parseRangeList("104.16.0.0/13, 2606:4700::/32 www.visa.com [2606:4700::1111] 1.1.1.1 bogus/99 nope")
        assertEquals(listOf("104.16.0.0/13", "2606:4700::/32", "www.visa.com", "2606:4700::1111", "1.1.1.1"), parsed)
    }

    @Test
    fun ipv6SubnetYieldsDistinctHostsInsidePrefix() {
        val hosts = IpScanner.expandSubnet("2606:4700::/32", 16, random = true)
        assertEquals(16, hosts.size)
        assertTrue(hosts.all { it.startsWith("2606:4700:") })
        assertEquals(hosts.size, hosts.toSet().size)
    }

    @Test
    fun hostnameValidationRejectsIpsAndJunk() {
        assertTrue(IpScanner.isValidHostname("cdnjs.cloudflare.com"))
        assertFalse(IpScanner.isValidHostname("104.16.1.1"))
        assertFalse(IpScanner.isValidHostname("no-dot"))
        assertFalse(IpScanner.isValidHostname("bad_.com"))
        assertTrue(IpScanner.isValidIpv6("2606:4700::1111"))
    }

    @Test
    fun panelLineCarriesCountryTag() {
        val tagged = IpScanner.ScanResult(ip = "1.2.3.4", pingMs = 10, sni = "x", countryCode = "DE")
        assertEquals("1.2.3.4#DE", tagged.panelLine)
        assertEquals("1.2.3.4", tagged.copy(countryCode = null).panelLine)
    }
}

package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IPv6 discovery + the manual address box.
 *
 * Why these exist: blind random sampling of Cloudflare's `/32` never produced a
 * single v6 row (the block is 2^96 wide and only a few /48s inside it answer), and
 * the manual box rejected every `ip:port` input because it stripped brackets and
 * nothing else — the colon stayed in the host and the TCP connect failed.
 */
class IpScannerIpv6Test {

    @Test
    fun ipv4MapsToBothCloudflareV6Blocks() {
        // 104.16 = 0x6810, 132.229 = 0x84e5 — the mapping the v6 library already used.
        assertEquals(
            listOf("2606:4700::6810:84e5", "2a06:98c0::6810:84e5"),
            IpScanner.deriveIpv6Candidates("104.16.132.229"),
        )
    }

    @Test
    fun leadingZeroBytesArePaddedToTwoHexDigits() {
        assertEquals(
            listOf("2606:4700::0a01:0101", "2a06:98c0::0a01:0101"),
            IpScanner.deriveIpv6Candidates("10.1.1.1"),
        )
    }

    @Test
    fun derivedAddressesAreParseableIpv6Literals() {
        // isValidIpv6 runs them through InetAddress — a malformed hextet fails here.
        IpScanner.deriveIpv6Candidates("172.64.7.9").forEach { address ->
            assertTrue("$address is not a parseable v6 literal", IpScanner.isValidIpv6(address))
        }
        IpScanner.V6_LIBRARY.forEach { address ->
            assertTrue("$address is not a parseable v6 literal", IpScanner.isValidIpv6(address))
        }
    }

    @Test
    fun nonIpv4InputProducesNoCandidate() {
        assertTrue(IpScanner.deriveIpv6Candidates("2606:4700::1").isEmpty())
        assertTrue(IpScanner.deriveIpv6Candidates("cdn.example.com").isEmpty())
        assertTrue(IpScanner.deriveIpv6Candidates("").isEmpty())
        assertTrue(IpScanner.deriveIpv6Candidates("104.16.132.999").isEmpty())
    }

    @Test
    fun everyKnownSubnetIsA48OrTighterBlock() {
        IpScanner.IPV6_SUBNETS.forEach { cidr ->
            val prefix = cidr.substringAfter('/').toInt()
            assertTrue("$cidr is wider than the blocks that actually answer", prefix >= 48)
        }
    }

    @Test
    fun manualBoxAcceptsBareIpv4() {
        assertEquals("104.16.1.1" to null, IpScanner.parseManualTarget("104.16.1.1"))
    }

    @Test
    fun manualBoxAcceptsIpv4WithPort() {
        assertEquals("104.16.1.1" to 2053, IpScanner.parseManualTarget("104.16.1.1:2053"))
        assertEquals("104.16.1.1" to 2053, IpScanner.parseManualTarget(" 104.16.1.1:2053 "))
    }

    @Test
    fun manualBoxAcceptsBracketedIpv6WithPort() {
        assertEquals("2606:4700:4700::1111" to 443, IpScanner.parseManualTarget("[2606:4700:4700::1111]:443"))
    }

    @Test
    fun manualBoxAcceptsBareIpv6WithoutPort() {
        // A bare v6 literal has 2+ colons, so no port can be split off.
        assertEquals("2606:4700:4700::1111" to null, IpScanner.parseManualTarget("2606:4700:4700::1111"))
    }

    @Test
    fun manualBoxAcceptsBracketedIpv6WithoutPort() {
        assertEquals("2606:4700:4700::1111" to null, IpScanner.parseManualTarget("[2606:4700:4700::1111]"))
    }

    @Test
    fun manualBoxAcceptsHostnameWithPort() {
        assertEquals("cdn.example.com" to 8443, IpScanner.parseManualTarget("cdn.example.com:8443"))
    }

    @Test
    fun manualBoxRejectsGarbage() {
        assertEquals("" to null, IpScanner.parseManualTarget("   "))
        // Not a valid port → the whole tail stays part of the host, and the caller's
        // validity check rejects it instead of silently probing a broken name.
        assertEquals("104.16.1.1:abc" to null, IpScanner.parseManualTarget("104.16.1.1:abc"))
    }
}

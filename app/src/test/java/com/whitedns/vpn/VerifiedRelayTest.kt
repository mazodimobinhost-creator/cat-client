package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedRelayTest {
    @Test fun preservesSocksCredentialsAndPort() {
        val relay = VerifiedRelayParser.parse("socks5://user:pass@relay.example:1080", 1L,
            EndpointVerdict.VERIFIED, "manual")!!
        assertEquals(EndpointRole.SOCKS5, relay.endpoint.role)
        assertEquals(1080, relay.endpoint.port)
        assertEquals("socks5://user:pass@relay.example:1080", relay.encode())
    }

    @Test fun preservesHttpsAndBracketedIpv6Port() {
        val relay = VerifiedRelayParser.parse("https://name:secret@[2606:4700::2]:8443", 1L,
            EndpointVerdict.VERIFIED, "scanner")!!
        assertEquals(RelayProtocol.HTTPS, relay.protocol)
        assertEquals("https://name:secret@[2606:4700::2]:8443", relay.encode())
    }

    @Test fun doesNotInventMissingPorts() {
        assertNull(VerifiedRelayParser.parse("socks5://relay.example", 1L,
            EndpointVerdict.UNKNOWN, "manual"))
        assertNull(VerifiedRelayParser.parse("https://relay.example", 1L,
            EndpointVerdict.UNKNOWN, "manual"))
    }

    @Test fun vlessAndTrojanAreNotRelays() {
        assertNull(VerifiedRelayParser.parse("vless://uuid@host:443", 1L,
            EndpointVerdict.UNKNOWN, "manual"))
        assertNull(VerifiedRelayParser.parse("trojan://secret@host:443", 1L,
            EndpointVerdict.UNKNOWN, "manual"))
    }
}

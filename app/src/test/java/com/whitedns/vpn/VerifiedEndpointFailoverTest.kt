package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class VerifiedEndpointFailoverTest {
    private val now = 100_000L
    private fun endpoint(host: String, port: Int, verdict: EndpointVerdict, latency: Long = 50,
                         role: EndpointRole = EndpointRole.CLEAN_IP, checked: Long = now - 100) =
        VerifiedEndpoint(host, port, role, EndpointFamily.IPV4, verdict, checked, latency, source = "device")

    @Test fun brokenConfigSwitchesToFastestVerifiedExactEndpoint() {
        val current = endpoint("1.1.1.1", 80, EndpointVerdict.FAILED)
        val result = VerifiedEndpointFailover.choose(current, listOf(
            endpoint("2.2.2.2", 443, EndpointVerdict.VERIFIED, 80),
            endpoint("3.3.3.3", 8443, EndpointVerdict.VERIFIED, 40),
        ), now, 60_000)
        assertEquals(FailoverStatus.SWITCHED, result.status)
        assertEquals("3.3.3.3:8443", result.selected.authority)
    }

    @Test fun partialOrWrongRoleNeverReplacesCurrent() {
        val current = endpoint("1.1.1.1", 443, EndpointVerdict.FAILED)
        val result = VerifiedEndpointFailover.choose(current, listOf(
            endpoint("2.2.2.2", 8443, EndpointVerdict.PARTIAL),
            endpoint("3.3.3.3", 1080, EndpointVerdict.VERIFIED, role = EndpointRole.SOCKS5),
        ), now, 60_000)
        assertEquals(FailoverStatus.NO_VERIFIED_REPLACEMENT, result.status)
        assertEquals("1.1.1.1:443", result.selected.authority)
    }

    @Test fun healthyCurrentIsNotChangedSilently() {
        val current = endpoint("1.1.1.1", 443, EndpointVerdict.VERIFIED, 100)
        val result = VerifiedEndpointFailover.choose(current,
            listOf(endpoint("2.2.2.2", 8443, EndpointVerdict.VERIFIED, 10)), now, 60_000)
        assertEquals(FailoverStatus.KEPT_CURRENT, result.status)
        assertEquals(current, result.selected)
    }

    @Test fun staleReplacementIsRejected() {
        val current = endpoint("1.1.1.1", 443, EndpointVerdict.FAILED)
        val stale = endpoint("2.2.2.2", 8443, EndpointVerdict.VERIFIED, checked = now - 60_001)
        assertEquals(FailoverStatus.NO_VERIFIED_REPLACEMENT,
            VerifiedEndpointFailover.choose(current, listOf(stale), now, 60_000).status)
    }
}

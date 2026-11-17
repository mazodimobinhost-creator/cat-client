package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class VerifiedEndpointRegistryTest {
    private fun endpoint(port: Int, role: EndpointRole = EndpointRole.PROXY_IP, verdict: EndpointVerdict = EndpointVerdict.VERIFIED, time: Long = 1L) =
        VerifiedEndpoint("relay.example", port, role, EndpointFamily.DOMAIN, verdict, time, source = "test")

    @Test fun sameHostOnDifferentPortsIsNotDuplicate() {
        val first = VerifiedEndpointRegistry.save(emptyList(), endpoint(11111))
        val second = VerifiedEndpointRegistry.save(first.first, endpoint(3118))
        assertEquals(EndpointSaveStatus.SAVED, second.second.status)
        assertEquals(setOf(11111, 3118), second.first.map { it.port }.toSet())
    }

    @Test fun sameHostPortWithDifferentRoleIsNotDuplicate() {
        val first = VerifiedEndpointRegistry.save(emptyList(), endpoint(1080, EndpointRole.SOCKS5))
        val second = VerifiedEndpointRegistry.save(first.first, endpoint(1080, EndpointRole.HTTP_RELAY))
        assertEquals(2, second.first.size)
    }

    @Test fun exactDuplicateIsReported() {
        val item = endpoint(11111)
        val second = VerifiedEndpointRegistry.save(listOf(item), item)
        assertEquals(EndpointSaveStatus.DUPLICATE, second.second.status)
    }

    @Test fun freshEvidenceUpdatesSameTypedEndpoint() {
        val second = VerifiedEndpointRegistry.save(listOf(endpoint(11111)), endpoint(11111, time = 2L))
        assertEquals(EndpointSaveStatus.UPDATED, second.second.status)
        assertEquals(2L, second.first.single().checkedAtMs)
    }

    @Test fun unverifiedEndpointIsNotPersisted() {
        val result = VerifiedEndpointRegistry.save(emptyList(), endpoint(11111, verdict = EndpointVerdict.PARTIAL))
        assertEquals(EndpointSaveStatus.NOT_VERIFIED, result.second.status)
        assertEquals(0, result.first.size)
    }
}

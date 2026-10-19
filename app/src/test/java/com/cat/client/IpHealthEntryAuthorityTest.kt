package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Test

class IpHealthEntryAuthorityTest {
    @Test fun preservesPinnedPortAndBracketsIpv6ExactlyOnce() {
        val v4 = IpHealthEntry(ip = "104.16.1.1", port = 8443)
        val rawV6 = IpHealthEntry(ip = "2606:4700::1", port = 2053)
        val bracketedV6 = IpHealthEntry(ip = "[2606:4700::1]", port = 2053)

        assertEquals("104.16.1.1:8443", v4.authority)
        assertEquals("[2606:4700::1]:2053", rawV6.authority)
        assertEquals("[2606:4700::1]:2053", bracketedV6.authority)

        val normalized = FrontingIpPolicy.normalizeIps(
            listOf(v4, rawV6).map { it.authority }.take(5).joinToString(","),
        )
        assertEquals(listOf("104.16.1.1:8443", "[2606:4700::1]:2053"), normalized)
    }
}

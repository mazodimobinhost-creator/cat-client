package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Test

/** beta205: adding a scanned IP when five are already saved must not throw (it crashed «use this IP»). */
class FrontingMergeTest {
    @Test
    fun sixthIpPushesTheOldestOutInsteadOfThrowing() {
        val existing = listOf("1.1.1.1:443", "1.1.1.2:2053", "1.1.1.3:8443", "1.1.1.4:2083", "1.1.1.5:2087")
        val merged = FrontingIpPolicy.mergeNewestFirst(listOf("9.9.9.9:2096", "[2606:4700::1]:443"), existing)
        assertEquals(listOf("9.9.9.9:2096", "[2606:4700::1]:443", "1.1.1.1:443", "1.1.1.2:2053", "1.1.1.3:8443"), merged)
    }

    @Test
    fun invalidAndDuplicateTokensAreSkipped() {
        val merged = FrontingIpPolicy.mergeNewestFirst(listOf("not an ip", "1.1.1.1:443", " "), listOf("1.1.1.1:443"))
        assertEquals(listOf("1.1.1.1:443"), merged)
    }
}

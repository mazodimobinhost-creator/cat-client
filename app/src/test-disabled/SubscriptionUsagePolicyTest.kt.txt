package com.whitedns.vpn

import com.cat.client.SubscriptionUsagePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals as assertEq
import org.junit.Test

class SubscriptionUsagePolicyTest {
    @Test
    fun parsesCatPanelHeader() {
        val usage = SubscriptionUsagePolicy.parse("upload=1024; download=2048; total=1099511627776")!!
        assertEquals(1024L, usage.uploadBytes)
        assertEquals(2048L, usage.downloadBytes)
        assertEquals(1099511627776L, usage.totalBytes)
        assertEquals(3072L, usage.usedBytes)
        assertEq(0, usage.usedPercent)
    }

    @Test
    fun parsesExpiryAndPartialHeader() {
        val usage = SubscriptionUsagePolicy.parse("download=5368709120; total=21474836480; expire=1893456000")!!
        assertEq(25, usage.usedPercent)
        assertEquals(1893456000L, usage.expireEpochSeconds)
        assertEquals(16106127360L, usage.remainingBytes)
    }

    @Test
    fun handlesSpacingAndUppercase() {
        val usage = SubscriptionUsagePolicy.parse("Upload=10 ; DOWNLOAD=20; Total=100")!!
        assertEquals(30L, usage.usedBytes)
        assertEq(30, usage.usedPercent)
        assertNull(usage.expireEpochSeconds)
    }

    @Test
    fun rejectsEmptyOrUnusableHeaders() {
        assertNull(SubscriptionUsagePolicy.parse(null))
        assertNull(SubscriptionUsagePolicy.parse(""))
        assertNull(SubscriptionUsagePolicy.parse("   "))
        assertNull(SubscriptionUsagePolicy.parse("expire=0"))
        assertNull(SubscriptionUsagePolicy.parse("not-a-header"))
    }

    @Test
    fun clampsOveruseToHundredPercent() {
        val usage = SubscriptionUsagePolicy.parse("download=500; total=100")!!
        assertEq(100, usage.usedPercent)
        assertEquals(0L, usage.remainingBytes)
    }

    @Test
    fun formatsByteSizes() {
        assertEquals("512 B", SubscriptionUsagePolicy.formatBytes(512L))
        assertEquals("2 KB", SubscriptionUsagePolicy.formatBytes(2048L))
        assertEquals("1.5 MB", SubscriptionUsagePolicy.formatBytes(1024L * 1536L))
        assertEquals("2.00 GB", SubscriptionUsagePolicy.formatBytes(2L * 1024L * 1024L * 1024L))
        assertEquals("0 B", SubscriptionUsagePolicy.formatBytes(-5L))
    }

    @Test
    fun sourceKeyIsStableForTrimmedInput() {
        assertEquals(
            SubscriptionUsagePolicy.sourceKey("https://panel.example/sub"),
            SubscriptionUsagePolicy.sourceKey("  https://panel.example/sub  "),
        )
    }
}

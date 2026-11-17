package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Beta217PerformanceTest {
    @Test
    fun frontingIpsAreTriedAfterTheOriginalOnlyWhereTheyRecentlyFailed() {
        FrontingFailureMemory.clearForTest()
        val ips = listOf("188.114.98.0", " 104.16.1.1")
        assertFalse(FrontingFailureMemory.shouldTryOriginalFirst("wifi-a", ips, nowMs = 1_000L))

        FrontingFailureMemory.frontingFailedOriginalWorked("wifi-a", ips, nowMs = 1_000L)
        assertTrue(FrontingFailureMemory.shouldTryOriginalFirst("wifi-a", ips, nowMs = 2_000L))
        // Same IPs in another order / with spaces is the same list.
        assertTrue(FrontingFailureMemory.shouldTryOriginalFirst("wifi-a", listOf("104.16.1.1", "188.114.98.0"), nowMs = 2_000L))
        // Another network or another IP list starts fresh.
        assertFalse(FrontingFailureMemory.shouldTryOriginalFirst("mobile-b", ips, nowMs = 2_000L))
        assertFalse(FrontingFailureMemory.shouldTryOriginalFirst("wifi-a", listOf("188.114.98.0"), nowMs = 2_000L))
        // Expires.
        assertFalse(
            FrontingFailureMemory.shouldTryOriginalFirst("wifi-a", ips, nowMs = 1_000L + FrontingFailureMemory.TTL_MS),
        )
    }

    @Test
    fun aFrontingSuccessForgetsTheFailure() {
        FrontingFailureMemory.clearForTest()
        val ips = listOf("188.114.98.0")
        FrontingFailureMemory.frontingFailedOriginalWorked("net", ips, nowMs = 0L)
        FrontingFailureMemory.frontingWorked("net", ips)
        assertFalse(FrontingFailureMemory.shouldTryOriginalFirst("net", ips, nowMs = 1L))
        assertFalse(FrontingFailureMemory.shouldTryOriginalFirst("net", emptyList(), nowMs = 1L))
    }

    @Test
    fun subscriptionTextIsReusedUntilTheFileIsRewritten() {
        val dir = Files.createTempDirectory("b217").toFile()
        try {
            val file = File(dir, "sub.yaml")
            writeSubscriptionTextAtomically(file, "proxies:\n  - name: a\n")
            val first = SnapshotTextMemo.read(file)
            val second = SnapshotTextMemo.read(file)
            assertEquals("proxies:\n  - name: a\n", first)
            assertSame(first, second)

            writeSubscriptionTextAtomically(file, "proxies:\n  - name: b\n")
            assertEquals("proxies:\n  - name: b\n", SnapshotTextMemo.read(file))

            file.delete()
            assertEquals(null, SnapshotTextMemo.read(file))
        } finally {
            dir.deleteRecursively()
        }
    }
}

package com.whitedns.vpn

import com.cat.client.WorkerSourceDecision
import com.cat.client.WorkerSourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerSourcePolicyTest {
    @Test fun acceptsNormalHttpsRawSources() {
        assertTrue(WorkerSourcePolicy.validateUrl(
            "https://raw.githubusercontent.com/owner/repo/main/worker.js",
        ) is WorkerSourceDecision.Allowed)
    }

    @Test fun rejectsPlaintextCredentialsAndMetadata() {
        fun reason(url: String) = (WorkerSourcePolicy.validateUrl(url) as WorkerSourceDecision.Blocked).reason
        assertEquals("https-required", reason("http://example.com/worker.js"))
        assertEquals("url-credentials-forbidden", reason("https://user:pass@example.com/worker.js"))
        assertEquals("local-host-forbidden", reason("https://169.254.169.254/latest/meta-data"))
    }

    @Test fun rejectsPrivateAndLoopbackAddresses() {
        val urls = listOf("https://127.0.0.1/a", "https://10.0.0.1/a", "https://172.16.1.2/a", "https://192.168.1.1/a")
        urls.forEach {
            assertEquals("private-address-forbidden", (WorkerSourcePolicy.validateUrl(it) as WorkerSourceDecision.Blocked).reason)
        }
    }

    @Test fun enforcesBoundedDownloadsEvenWithoutContentLength() {
        assertTrue(WorkerSourcePolicy.validateDownloadedSize(null, 1024) is WorkerSourceDecision.Allowed)
        assertEquals("source-empty", (WorkerSourcePolicy.validateDownloadedSize(null, 0) as WorkerSourceDecision.Blocked).reason)
        assertEquals("source-too-large", (WorkerSourcePolicy.validateDownloadedSize(null, WorkerSourcePolicy.MAX_SOURCE_BYTES + 1) as WorkerSourceDecision.Blocked).reason)
        assertEquals("source-too-large", (WorkerSourcePolicy.validateDownloadedSize(WorkerSourcePolicy.MAX_SOURCE_BYTES + 1, 1) as WorkerSourceDecision.Blocked).reason)
    }
}

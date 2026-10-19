package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointEvidenceCacheTest {
    private val now = 100_000L
    private fun key(network: String, port: Int = 443, sni: String = "worker.example") = EndpointEvidenceKey(
        network, EndpointRole.CLEAN_IP, "104.16.1.1", port, "vless", sni, true
    )
    private val evidence = EndpointProbeEvidence(true, tlsCompleted = true, websocketCompleted = true, runtimeCompleted = true)

    @Test fun resultFromAnotherSimOrWifiIsNotReused() {
        val cached = listOf(CachedEndpointEvidence(key("wifi:home"), evidence, now - 10))
        assertNull(EndpointEvidenceCache.fresh(cached, key("cell:irancell"), now, 60_000))
    }

    @Test fun portAndSniArePartOfTheEvidenceIdentity() {
        val cached = listOf(CachedEndpointEvidence(key("cell:mci", 443), evidence, now - 10))
        assertNull(EndpointEvidenceCache.fresh(cached, key("cell:mci", 8443), now, 60_000))
        assertNull(EndpointEvidenceCache.fresh(cached, key("cell:mci", 443, "other.example"), now, 60_000))
    }

    @Test fun staleEvidenceIsNotHealthyOnTheSameNetwork() {
        val cached = listOf(CachedEndpointEvidence(key("cell:mci"), evidence, now - 60_001))
        assertNull(EndpointEvidenceCache.fresh(cached, key("cell:mci"), now, 60_000))
    }

    @Test fun newerEvidenceReplacesOnlyTheExactKey() {
        val old = CachedEndpointEvidence(key("cell:mci"), evidence.copy(runtimeCompleted = false), now - 100)
        val fresh = CachedEndpointEvidence(key("cell:mci"), evidence, now - 10)
        val otherPort = CachedEndpointEvidence(key("cell:mci", 8443), evidence, now - 20)
        val updated = EndpointEvidenceCache.put(listOf(old, otherPort), fresh)
        assertEquals(2, updated.size)
        assertEquals(fresh, EndpointEvidenceCache.fresh(updated, fresh.key, now, 60_000))
        assertEquals(otherPort, EndpointEvidenceCache.fresh(updated, otherPort.key, now, 60_000))
    }
}

package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelEndpointSyncTest {
    private fun endpoint(port: Int, role: EndpointRole = EndpointRole.PROXY_IP,
                         verdict: EndpointVerdict = EndpointVerdict.VERIFIED) =
        VerifiedEndpoint("103.237.102.191", port, role, EndpointFamily.IPV4, verdict, 1L, source = "app")

    @Test fun savedRequiresExactReadBackIncludingPort() {
        val requested = endpoint(11111)
        assertEquals(PanelEndpointSyncStatus.SAVED,
            PanelEndpointSync.verify(requested, emptyList(), listOf(requested), true).status)
        assertEquals(PanelEndpointSyncStatus.READ_BACK_MISMATCH,
            PanelEndpointSync.verify(requested, emptyList(), listOf(endpoint(443)), true).status)
    }

    @Test fun sameHostDifferentPortIsNotDuplicate() {
        val requested = endpoint(11111)
        val before = listOf(endpoint(3118))
        assertEquals(PanelEndpointSyncStatus.SAVED,
            PanelEndpointSync.verify(requested, before, before + requested, true).status)
    }

    @Test fun sameHostPortDifferentRoleIsNotDuplicate() {
        val requested = endpoint(1080, EndpointRole.SOCKS5)
        val before = listOf(endpoint(1080, EndpointRole.PROXY_IP))
        assertEquals(PanelEndpointSyncStatus.SAVED,
            PanelEndpointSync.verify(requested, before, before + requested, true).status)
    }

    @Test fun noKvAndUnverifiedHaveHonestStatuses() {
        assertEquals(PanelEndpointSyncStatus.NO_KV,
            PanelEndpointSync.verify(endpoint(11111), emptyList(), null, false).status)
        assertEquals(PanelEndpointSyncStatus.REJECTED,
            PanelEndpointSync.verify(endpoint(11111, verdict = EndpointVerdict.PARTIAL), emptyList(), emptyList(), true).status)
    }

    @Test fun exactExistingEndpointIsDuplicate() {
        val requested = endpoint(11111)
        assertEquals(PanelEndpointSyncStatus.DUPLICATE,
            PanelEndpointSync.verify(requested, listOf(requested), listOf(requested), true).status)
    }
}

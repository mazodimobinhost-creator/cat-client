package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProbeDisplayTest {
    @Test fun untestedAndTcpOnlyAreNeverGreen() {
        val untested = RelayProbeDisplayPolicy.from(PortProbeKind.SOCKS5, null)
        val tcp = RelayProbeDisplayPolicy.from(PortProbeKind.SOCKS5, EndpointProbeEvidence(tcpConnected = true, latencyMs = 20))
        assertEquals(RelayProbeStage.UNTESTED, untested.stage)
        assertFalse(untested.healthy)
        assertEquals(RelayProbeStage.TCP, tcp.stage)
        assertFalse(tcp.healthy)
    }

    @Test fun handshakeOnlyShowsProtocolButIsNotHealthy() {
        val display = RelayProbeDisplayPolicy.from(PortProbeKind.HTTP_CONNECT,
            EndpointProbeEvidence(true, protocolHandshakeCompleted = true, latencyMs = 30))
        assertEquals(RelayProbeStage.PROTOCOL, display.stage)
        assertFalse(display.healthy)
    }

    @Test fun onlyRuntimeVerifiedResultIsHealthy() {
        val display = RelayProbeDisplayPolicy.from(PortProbeKind.SOCKS5,
            EndpointProbeEvidence(true, protocolHandshakeCompleted = true, runtimeCompleted = true, latencyMs = 40))
        assertEquals(RelayProbeStage.RUNTIME, display.stage)
        assertTrue(display.healthy)
    }

    @Test fun explicitFailureShowsError() {
        val display = RelayProbeDisplayPolicy.from(PortProbeKind.CLEAN_TLS,
            EndpointProbeEvidence(true, tlsCompleted = false, latencyMs = 50, error = "tls failed"))
        assertEquals(RelayProbeStage.FAILED, display.stage)
        assertFalse(display.healthy)
        assertEquals("tls failed", display.detail)
    }
}

package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ConnectionFaultExplainerTest {
    private fun cause(raw: String) = ConnectionFaultExplainer.classify(raw)

    @Test
    fun classifiesCommonFailures() {
        assertEquals(ConnectionFaultExplainer.Cause.PortBusy, cause("listen tcp 127.0.0.1:7890: bind: address already in use"))
        assertEquals(ConnectionFaultExplainer.Cause.Certificate, cause("x509: certificate signed by unknown authority"))
        assertEquals(ConnectionFaultExplainer.Cause.Reality, cause("REALITY: processed invalid connection"))
        assertEquals(ConnectionFaultExplainer.Cause.Config, cause("yaml: unmarshal errors: line 3"))
        assertEquals(ConnectionFaultExplainer.Cause.NeedsRoot, cause("Paqet requires root for raw sockets"))
        assertEquals(ConnectionFaultExplainer.Cause.Dns, cause("dial tcp: lookup example.com: no such host"))
        assertEquals(ConnectionFaultExplainer.Cause.Refused, cause("dial tcp 1.2.3.4:443: connect: connection refused"))
        assertEquals(ConnectionFaultExplainer.Cause.Timeout, cause("context deadline exceeded"))
        assertEquals(
            ConnectionFaultExplainer.Cause.HealthCheck,
            cause("No configured connection chain passed the runtime health check"),
        )
        assertEquals(ConnectionFaultExplainer.Cause.Unknown, cause(""))
        assertEquals(ConnectionFaultExplainer.Cause.Unknown, cause("something odd"))
    }

    @Test
    fun explanationIsBilingualAndKeepsRaw() {
        val fault = ConnectionFaultExplainer.explain("bind: address already in use")
        assertTrue(fault.title(true).isNotBlank())
        assertTrue(fault.hint(false).isNotBlank())
        assertEquals("bind: address already in use", fault.raw)
        ConnectionFaultExplainer.Cause.values().forEach { c ->
            // every cause must produce texts (no crash on any branch)
            assertTrue(ConnectionFaultExplainer.explain(if (c == ConnectionFaultExplainer.Cause.Unknown) "zz" else c.name).titleFa.isNotBlank())
        }
    }

    @Test
    fun describeChainKeepsRootCause() {
        val error = IOException(
            "No configured connection chain passed the runtime health check",
            IllegalStateException("dial tcp: i/o timeout"),
        )
        val text = ConnectionFaultExplainer.describeChain(error)
        assertTrue(text.contains("health check"))
        assertTrue(text.contains("i/o timeout"))
        assertEquals("", ConnectionFaultExplainer.describeChain(null))
    }

    @Test
    fun amneziaHeadersKeepUint32AndRanges() {
        assertEquals(1L, AmneziaHeaders.value("1"))
        assertEquals(3_900_000_000L, AmneziaHeaders.value(" 3900000000 "))
        assertEquals(4_294_967_295L, AmneziaHeaders.value("4294967295"))
        assertEquals("100-200", AmneziaHeaders.value("100 - 200"))
        assertNull(AmneziaHeaders.value("4294967296"))
        assertNull(AmneziaHeaders.value("200-100"))
        assertNull(AmneziaHeaders.value("abc"))
        assertNull(AmneziaHeaders.value(""))
        assertNull(AmneziaHeaders.value(null))
    }
}

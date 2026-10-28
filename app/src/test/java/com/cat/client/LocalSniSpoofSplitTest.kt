package com.cat.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta203: the two DeltaSpoof-style splitters must never change the handshake bytes — only framing / segmentation. */
class LocalSniSpoofSplitTest {
    private val hello = LocalSniSpoofProxy.buildFakeClientHelloRecord("very-long-name.example-domain.workers.dev")

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    @Test
    fun tinyRecordsKeepTheHandshakeBytesAndAreValidRecords() {
        val parts = LocalSniSpoofProxy.splitClientHelloTinyRecords(hello, rnd = java.util.Random(7))
        assertTrue("expected many records, got ${parts.size}", parts.size >= 3)
        val payload = java.io.ByteArrayOutputStream()
        for (r in parts) {
            assertEquals(0x16, r[0].toInt() and 0xFF)
            assertEquals(hello[1], r[1])
            assertEquals(hello[2], r[2])
            val len = ((r[3].toInt() and 0xFF) shl 8) or (r[4].toInt() and 0xFF)
            assertEquals(r.size - 5, len)
            assertTrue(len in 1..48)
            payload.write(r, 5, len)
        }
        assertArrayEquals(hello.copyOfRange(5, hello.size), payload.toByteArray())
    }

    @Test
    fun tinyRecordsPassTrailingBytesRawAndLeavePartialRecordsAlone() {
        val tail = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x01, 0x42)
        val parts = LocalSniSpoofProxy.splitClientHelloTinyRecords(hello + tail, rnd = java.util.Random(1))
        assertArrayEquals(tail, parts.last())
        val partial = hello.copyOfRange(0, hello.size - 3)
        val same = LocalSniSpoofProxy.splitClientHelloTinyRecords(partial)
        assertEquals(1, same.size)
        assertArrayEquals(partial, same[0])
    }

    @Test
    fun sniTcpSplitIsByteIdenticalAndCutsOnTheHostname() {
        val parts = LocalSniSpoofProxy.splitAtSniBoundariesTcp(hello)
        assertArrayEquals(hello, concat(parts))
        val host = LocalSniSpoofProxy.findSniHostnameOffset(hello)
        assertTrue(host > 0)
        assertEquals(host, parts[0].size)
        val hostLen = "very-long-name.example-domain.workers.dev".length
        assertTrue(parts.size >= hostLen / 2)
        assertTrue(parts.drop(1).take(hostLen / 2).all { it.size <= 2 })
    }

    @Test
    fun nonTlsInputIsPassedThrough() {
        val plain = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        assertEquals(1, LocalSniSpoofProxy.splitClientHelloTinyRecords(plain).size)
        assertEquals(1, LocalSniSpoofProxy.splitAtSniBoundariesTcp(plain).size)
    }

    @Test
    fun newStrategiesAreListed() {
        val ids = LocalSniSpoofProxy.SpoofStrategy.entries.map { it.id }
        assertTrue(ids.contains("tls-record-tiny"))
        assertTrue(ids.contains("sni-tcp-boundary"))
    }
}

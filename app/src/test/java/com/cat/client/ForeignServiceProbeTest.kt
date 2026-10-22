package com.cat.client

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignServiceProbeTest {
    @Test
    fun catalogCoversEverySelectedForeignServiceAndUsesHttps() {
        assertEquals(
            setOf("google", "youtube", "play_store", "telegram", "whatsapp", "instagram"),
            ForeignServiceCatalog.targets.map { it.id }.toSet(),
        )
        assertTrue(ForeignServiceCatalog.targets.all { it.url.startsWith("https://") })
        assertEquals(ForeignServiceCatalog.targets.size, ForeignServiceCatalog.targets.map { it.id }.distinct().size)
    }

    @Test
    fun onlySuccessfulHttpResponsesAreReportedAsOpen() {
        assertEquals(ForeignServiceStatus.OPEN, classifyForeignServiceStatus(200))
        assertEquals(ForeignServiceStatus.OPEN, classifyForeignServiceStatus(204))
        assertEquals(ForeignServiceStatus.OPEN, classifyForeignServiceStatus(206))
        assertEquals(ForeignServiceStatus.HTTP_RESPONSE, classifyForeignServiceStatus(302))
        assertEquals(ForeignServiceStatus.HTTP_RESPONSE, classifyForeignServiceStatus(403))
        assertEquals(ForeignServiceStatus.HTTP_RESPONSE, classifyForeignServiceStatus(404))
    }

    @Test
    fun rateLimitServerAndTransportFailuresRemainDistinct() {
        assertEquals(ForeignServiceStatus.RATE_LIMITED, classifyForeignServiceStatus(429))
        assertEquals(ForeignServiceStatus.SERVER_ERROR, classifyForeignServiceStatus(503))
        assertEquals(ForeignServiceStatus.UNREACHABLE, classifyForeignServiceStatus(0))
        assertEquals(ForeignServiceFailure.TIMEOUT, classifyForeignServiceFailure(SocketTimeoutException("private detail")))
        assertEquals(ForeignServiceFailure.DNS, classifyForeignServiceFailure(UnknownHostException("private detail")))
        assertEquals(ForeignServiceFailure.TLS, classifyForeignServiceFailure(SSLHandshakeException("private detail")))
    }
}

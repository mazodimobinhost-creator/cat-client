package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

class Beta206EngineTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    @Test fun loopbackEngineRowsNameTheEngineInsteadOfSocks5() {
        assertEquals("🐱 PattNG-MIM-H2-DE", ConnectionLabelPolicy.displayName("PattNG-MIM-H2-DE", "socks5", "127.0.0.1", 1819))
        assertEquals("🐱 Germany · Aether", ConnectionLabelPolicy.displayName("Germany", "socks5", "127.0.0.1", 1819))
        assertEquals("🐱 🦊 Firefox VPN (DE)", ConnectionLabelPolicy.displayName("🦊 Firefox VPN (DE)", "socks5", "127.0.0.1", 10829))
        assertEquals("🐱 Firefox VPN", ConnectionLabelPolicy.displayName("", "socks5", "127.0.0.1", 10829))
        val remote = ConnectionLabelPolicy.displayName("x", "socks5", "203.0.113.9", 1080)
        assertTrue(remote, remote.contains("Socks5"))
    }

    @Test fun fakeIpAnswersAreNeverUsedByProtectedSockets() {
        assertTrue(isMihomoFakeIp(InetAddress.getByName("198.18.0.7")))
        assertTrue(isMihomoFakeIp(InetAddress.getByName("198.19.255.1")))
        assertFalse(isMihomoFakeIp(InetAddress.getByName("198.20.0.1")))
        assertFalse(isMihomoFakeIp(InetAddress.getByName("151.101.1.1")))
        assertEquals("1.2.3.4", resolveForProtectedSocket("1.2.3.4").hostAddress)
        assertTrue(src("FirefoxVpnEngine.kt").contains("InetSocketAddress(resolveForProtectedSocket(connectHost), connectPort)"))
        assertTrue(src("PaqetEngine.kt").contains("resolveForProtectedSocket(serverClean)"))
    }

    @Test fun aetherAndFenixDelayTestsAreRealNotSocketPings() {
        val svc = src("CatClientVpnService.kt")
        assertTrue(svc.contains("realAetherDelayMs(profile, rawShare, timeoutMs)"))
        assertTrue(svc.contains("realFenixDelayMs(profile, rawShare, timeoutMs)"))
        assertFalse(svc.contains("AetherOperations.probeAetherUriLatencyMs(rawShare"))
        val ops = src("AetherOperations.kt")
        assertTrue(ops.contains("fun realAetherHandshake("))
        assertTrue(ops.contains("if (port != 53) return null"))
        assertFalse("UDP send time must never be reported", ops.contains("val pkt = ByteArray(32)"))
    }

    @Test fun chainedCarriersKeepTheLinkTlsOptions() {
        assertTrue(src("AetherProxyCarrier.kt").contains("fingerprint = o.fingerprint.ifBlank { \"chrome\" }"))
        assertEquals(2, Regex("linkOptions = selection,").findAll(src("CatClientVpnService.kt")).count())
    }

    @Test fun inlineMapRegexEscapesBracesForAndroidIcu() {
        val m = src("MihomoRuntime.kt")
        val fn = m.substringAfter("fun upsertInlineMap", "").take(1500)
        assertTrue("upsertInlineMap missing", fn.isNotEmpty())
        assertTrue("bare { in a Regex breaks Android ICU", fn.contains("""\s*)\{([^\}]*)\}"""))
    }
}

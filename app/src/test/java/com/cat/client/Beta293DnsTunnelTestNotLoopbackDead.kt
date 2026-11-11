package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta293DnsTunnelTestNotLoopbackDead {
    private fun src(name: String): String =
        listOf("src/main/java/com/cat/client/$name", "app/src/main/java/com/cat/client/$name")
            .map(::File).first { it.isFile }.readText()

    @Test
    fun versionIsBeta293() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 370"))
        assertTrue(gradle.contains("1.10.0-beta303"))
    }

    @Test
    fun dnsAndSpoofLoopbackRowsAreNotLabeledLocal() {
        val dns = ConnectionLabelPolicy.displayName(
            "Cat-DNS-ruhnama-178.22.122.100",
            "socks5",
            "127.0.0.1",
            10865,
        )
        assertTrue(dns, dns.contains("DNS"))
        assertFalse(dns, dns.contains("Local"))
        val spoof = ConnectionLabelPolicy.displayName("CF-Spoof-1", "socks5", "127.0.0.1", 19042)
        assertTrue(spoof, spoof.contains("Spoof"))
        assertFalse(spoof, spoof.contains("Local"))
    }

    @Test
    fun catDnsDoesNotDumpEveryPublicTunnelUnchecked() {
        val links = SlipNetFreePlanner.linksForResolvers(listOf("178.22.122.100", "10.202.10.202", "8.8.8.8"))
        assertTrue(links.size >= 6)
        assertTrue(links.any { it.contains("1.1.1.1") })
        val free = src("MainActivityFreeNetworks.kt")
        assertTrue(free.contains("probeWorking"))
        assertFalse(free.contains("listOf(\"178.22.122.100\")"))
        val delay = src("CatClientVpnServiceConnectionTests.kt")
        assertTrue(delay.contains("realDnsTunnelDelayMs"))
        assertTrue(delay.contains("httpDelayThroughSocks"))
        assertTrue(delay.contains("ensureSpoofForwarderForDelay"))
    }
}

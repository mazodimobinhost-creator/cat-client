package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta291SpoofAndSlipNetTest {
    private fun src(name: String): String =
        listOf("src/main/java/com/cat/client/$name", "app/src/main/java/com/cat/client/$name")
            .map(::File).first { it.isFile }.readText()

    @Test
    fun versionIsBeta291() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 370"))
        assertTrue(gradle.contains("1.10.0-beta303"))
    }

    @Test
    fun spoofForwarderUsesDeterministicPortAndTinyFrag() {
        val spoof = src("LocalSniSpoofProxy.kt")
        assertTrue(spoof.contains("fun preferredForwarderPort"))
        assertTrue(spoof.contains("19_000 + (h % 900)"))
        assertTrue(spoof.contains("TLS_RECORD_TINY_FRAG"))
        assertTrue(spoof.contains("ServerSocket(preferred, 64, loopback)"))
        assertFalse(spoof.contains("sampleEndpoint = \"188.114.98.0:443\""))
        val vpn = src("CatClientVpnService.kt")
        assertTrue(vpn.contains("(?!\\s*port\\s*:)"))
        assertTrue(vpn.contains("mr.groupValues[1]"))
        val engines = src("MainActivityEngines.kt")
        assertTrue(engines.contains("&sni_fwd_target="))
        assertFalse(engines.contains("&sni=\$realSni&"))
    }

    @Test
    fun yamlRewriteFindsPortEvenWhenServerLineIsFarFromSniFwd() {
        val target = "188.114.98.224:443"
        val escaped = Regex.escape(target)
        val pattern = Regex(
            """(port\s*:\s*)\d+((?:\s*\r?\n(?!\s*port\s*:).*){0,25}?\s*\r?\n\s*sni-fwd-target\s*:\s*['"]?$escaped['"]?)""",
        )
        val yaml = """
            |  - name: other
            |    port: 1080
            |  - name: spoof
            |    type: socks
            |    server: 127.0.0.1
            |    username: x
            |    password: y
            |    port: 48111
            |    udp: true
            |    sni-fwd-target: '188.114.98.224:443'
        """.trimMargin()
        assertTrue(pattern.containsMatchIn(yaml))
        val rewritten = yaml.replace(pattern) { "${it.groupValues[1]}19042${it.groupValues[2]}" }
        assertTrue(rewritten.contains("port: 19042"))
        assertTrue(rewritten.contains("port: 1080"))
        assertFalse(rewritten.contains("port: 48111"))
    }

    @Test
    fun freeCatDnsUrisAreRealDnsttAndParse() {
        val links = SlipNetFreePlanner.linksForResolvers(listOf("178.22.122.100", "10.202.10.202"))
        assertTrue(links.size >= 6)
        assertTrue(links.any { it.contains("1.1.1.1") })
        links.forEach { uri ->
            assertTrue(uri.startsWith("dnstt://"))
            val parsed = WhiteDnsSmartScanner.parseTunnelUri(uri)
            assertNotNull(uri, parsed)
            assertEquals("dnstt", parsed!!.scheme)
            assertTrue(parsed.publicKeyHex.length >= 32)
            assertTrue(parsed.tunnelDomain.contains("."))
            assertTrue(
                parsed.resolverIp == "178.22.122.100" ||
                    parsed.resolverIp == "10.202.10.202" ||
                    parsed.resolverIp == "1.1.1.1",
            )
        }
        assertEquals(links.size, SlipNetFreePlanner.runnableUris(links + "cottendns://x").size)
        val free = src("MainActivityFreeNetworks.kt")
        assertTrue(free.contains("addFreeSlipNet()"))
        assertTrue(free.contains("🧵 Cat DNS"))
        val finder = src("MainActivityGuides.kt")
        assertTrue(finder.contains("اسلیپ نت"))
    }
}

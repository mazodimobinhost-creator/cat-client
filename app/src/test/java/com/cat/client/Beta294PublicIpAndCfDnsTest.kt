package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta294PublicIpAndCfDnsTest {
    private fun src(name: String): String =
        listOf("src/main/java/com/cat/client/$name", "app/src/main/java/com/cat/client/$name")
            .map(::File).first { it.isFile }.readText()

    private fun profile(tag: String, type: String, server: String, port: Int, link: String) =
        ConnectionProfile(
            tag = tag,
            type = type,
            server = server,
            port = port,
            transport = "tcp",
            validationHost = "www.gstatic.com",
            shareLink = link,
        )

    @Test
    fun versionIsBeta294() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 373"))
        assertTrue(gradle.contains("1.10.0-beta306"))
    }

    @Test
    fun dnsAndSpoofRowsShowPublicHostNotLoopbackSocks() {
        val key = "241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f"
        val dns = profile(
            "Cat-DNS-ruhnama-1.1.1.1",
            "socks5",
            "127.0.0.1",
            10865,
            "dnstt://1.1.1.1:53/t.ruhnama.net?pubkey=$key#Cat-DNS-ruhnama-1.1.1.1",
        )
        val dnsLine = ConnectionPublicEndpoint.detailLine(dns)
        assertTrue(dnsLine, dnsLine.contains("1.1.1.1:53"))
        assertTrue(dnsLine, dnsLine.contains("DNSTT"))
        assertFalse(dnsLine, dnsLine.contains("127.0.0.1"))
        assertFalse(dnsLine, dnsLine.contains("10865"))

        val spoof = profile(
            "CF-Spoof-1",
            "socks5",
            "127.0.0.1",
            19042,
            "vless://uuid@127.0.0.1:19042?security=tls&sni=ebanksepah.ir&sni_fwd_target=188.114.98.224%3A443#CF-Spoof-1",
        )
        val spoofLine = ConnectionPublicEndpoint.detailLine(spoof)
        assertTrue(spoofLine, spoofLine.contains("188.114.98.224:443"))
        assertFalse(spoofLine, spoofLine.contains("127.0.0.1"))
        assertFalse(spoofLine, spoofLine.contains("19042"))

        val vless = profile(
            "DE-1",
            "vless",
            "203.0.113.9",
            8443,
            "vless://uuid@203.0.113.9:8443?security=tls&sni=www.cloudflare.com&type=ws&host=www.cloudflare.com#DE",
        )
        val vlessLine = ConnectionPublicEndpoint.detailLine(vless)
        assertTrue(vlessLine, vlessLine.contains("203.0.113.9:8443"))
        assertTrue(vlessLine, vlessLine.contains("www.cloudflare.com"))
    }

    @Test
    fun editorRewritesPublicEndpointAndRejectsLoopback() {
        val key = "241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f"
        val dns = ConnectionPublicEndpoint.applyEdits(
            "dnstt://1.1.1.1:53/t.ruhnama.net?pubkey=$key#Cat-DNS",
            "1.0.0.1",
            53,
            "",
            "",
        )
        assertEquals("dnstt://1.0.0.1:53/t.ruhnama.net?pubkey=$key#Cat-DNS", dns)
        assertNull(ConnectionPublicEndpoint.applyEdits("vless://u@1.2.3.4:443#x", "127.0.0.1", 443, "", ""))
        val next = ConnectionPublicEndpoint.applyEdits(
            "vless://u@1.2.3.4:443?security=tls&sni=a.example&type=ws&host=a.example#x",
            "9.9.9.9",
            8443,
            "b.example",
            "b.example",
        )
        assertNotNull(next)
        assertTrue(next!!, next.contains("9.9.9.9:8443"))
        assertTrue(next, next.contains("sni=b.example") || next.contains("sni=b.example".replace(".", "%2E")))
        assertFalse(next, next.contains("127.0.0.1"))
    }

    @Test
    fun cloudflareIsRecursiveResolverNotWorkerNameserver() {
        val cf = SlipNetFreePlanner.cloudflareLinks()
        assertTrue(cf.any { it.contains("dnstt://1.1.1.1:53/") })
        assertTrue(cf.any { it.contains("doh=") && it.contains("1.1.1.1") })
        assertFalse(cf.any { it.contains("workers.dev") })
        val cli = src("SlipNetCliEngine.kt")
        assertTrue(cli.contains("args += listOf(\"--dns\", doh)"))
        assertTrue(cli.contains("startsWith(\"https://\")"))
        val testing = src("MainActivityConnectionTesting.kt")
        assertTrue(testing.contains("ConnectionPublicEndpoint.detailLine"))
        assertTrue(testing.contains("showProfileValuesEditorDialog"))
        assertTrue(testing.contains("text = \"✏️\""))
        val sub = src("MainActivitySubscriptions.kt")
        assertTrue(sub.contains("showProfileValuesEditorDialog"))
        assertTrue(sub.contains("ویرایش IP / پورت / SNI"))
        val free = src("MainActivityFreeNetworks.kt")
        assertTrue(free.contains("cloudflareLinks()"))
        assertFalse(free.contains("listOf(\"178.22.122.100\")"))
    }
}

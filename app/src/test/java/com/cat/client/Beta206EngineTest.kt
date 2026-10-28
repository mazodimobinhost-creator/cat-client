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

    @Test fun loopbackEngineRowsUseOnlyCatNames() {
        val mim = ConnectionLabelPolicy.displayName("PattNG-MIM-H2-DE", "socks5", "127.0.0.1", 1819)
        assertTrue(mim, mim.startsWith("🐱 Cat · ") && mim.contains("Warp MIM-H2") && !mim.contains("PattNG") && !mim.contains("1819"))
        assertEquals("🐱 Cat · Germany · Warp · 🇩🇪", ConnectionLabelPolicy.displayName("Germany", "socks5", "127.0.0.1", 1819))
        val fox = ConnectionLabelPolicy.displayName("🦊 Firefox VPN (DE)", "socks5", "127.0.0.1", 10829)
        assertTrue(fox, fox.startsWith("🐱 Cat · ") && fox.contains("Fox VPN") && !fox.contains("Firefox"))
        assertEquals("🐱 Cat · Auto · Fox VPN · 🌐", ConnectionLabelPolicy.displayName("", "socks5", "127.0.0.1", 10829))
        val remote = ConnectionLabelPolicy.displayName("MLM-free-node", "socks5", "203.0.113.9", 1080)
        assertTrue(remote, remote.contains("Socks5") && !remote.contains("MLM"))
    }

    @Test fun noThirdPartyAppNamesOnScreen() {
        val dir = listOf(File("src/main/java/com/cat/client"), File("app/src/main/java/com/cat/client")).first { it.isDirectory }
        val banned = Regex("""(?<![A-Za-z0-9_./-])(MLM|PattNG|Patterniha|FENIX|foxyvpn|MahsaNG|Firefox VPN|فایرفاکس VPN)(?![A-Za-z0-9_/])""")
        val hits = dir.listFiles { f -> f.extension == "kt" && f.name != "CatBranding.kt" }!!.flatMap { f ->
            f.readLines().withIndex().filter { (_, line) ->
                val t = line.trimStart()
                !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) && banned.containsMatchIn(line)
            }.map { (i, line) -> "${f.name}:${i + 1}: ${line.trim().take(120)}" }
        }
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
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

    @Test fun savedSubscriptionNamesAreRebranded() {
        assertEquals("⚡ Cat Warp Configs", CatBranding.rebrandName("⚡ Aether / PattNG Configs"))
        assertEquals("🦊 Cat Fox VPN (🇩🇪 DE)", CatBranding.rebrandName("🦊 فایرفاکس VPN (🇩🇪 DE)"))
        assertEquals("⚡ Cat FLUX (V4)", CatBranding.rebrandName("⚡ MLM FLUX (V4)"))
        assertEquals("Cat-MIM-H2-DE", CatBranding.rebrandName("PattNG-MIM-H2-DE"))
        assertEquals("My VLESS", CatBranding.rebrandName("My VLESS"))
    }
}

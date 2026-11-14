package com.cat.client

import java.io.File
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta307 — dump methods inside Cat: Fox Mozilla relay, Amnezia 1.5 + clean IPs, MASQUE H2/H3 extras. */
class Beta307DumpMethodsTest {

    @Test
    fun versionIsBeta307() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 374"))
        assertTrue(gradle.contains("1.10.0-beta307"))
    }

    @Test
    fun foxRelayWrapsMozillaOnlyAndNeedsSecret() {
        assertNull(FoxMozillaRelay.normalize("http://relay.example.com/?secret=abc"))
        assertNull(FoxMozillaRelay.normalize("https://relay.example.com/"))
        assertNull(FoxMozillaRelay.normalize("https://127.0.0.1/?secret=abc"))
        val relay = FoxMozillaRelay.normalize("https://relay.example.com/?secret=s3cret1&health=1&url=https://evil.test")
        assertEquals("https://relay.example.com/?secret=s3cret1", relay)

        val wrapped = FoxMozillaRelay.wrap("https://vpn.mozilla.org/api/v1/fpn/token", relay)
        assertNotNull(wrapped)
        assertTrue(wrapped!!.startsWith("https://relay.example.com/?"))
        assertTrue(wrapped.contains("secret=s3cret1"))
        assertTrue(wrapped.contains("url="))
        val urlParam = wrapped.substringAfter("url=")
        assertEquals("https://vpn.mozilla.org/api/v1/fpn/token", URLDecoder.decode(urlParam, "UTF-8"))

        assertTrue(FoxMozillaRelay.allowedHost("accounts.firefox.com"))
        assertTrue(FoxMozillaRelay.allowedHost("oauth.accounts.firefox.com"))
        assertFalse(FoxMozillaRelay.allowedHost("github.com"))
        assertNull(FoxMozillaRelay.wrap("https://github.com/foo", relay))
        assertNull(FoxMozillaRelay.wrap("https://relay.example.com/api", relay))

        val health = FoxMozillaRelay.healthUrl(relay)
        assertEquals("https://relay.example.com/?secret=s3cret1&health=1", health)
        assertTrue(FoxMozillaRelay.parseHealth(200, """{"relay":"cat-fox","version":1,"ready":true}""").ok)
        assertTrue(FoxMozillaRelay.parseHealth(200, """{"ready":true}""").ok)
        assertFalse(FoxMozillaRelay.parseHealth(403, """{"ready":true}""").ok)
        assertFalse(FoxMozillaRelay.parseHealth(200, """{"ready":false}""").ok)
        assertTrue(FoxMozillaRelay.redact("https://relay.example.com/?secret=s3cret1").contains("secret=…"))
        assertFalse(FoxMozillaRelay.redact("https://relay.example.com/?secret=s3cret1").contains("s3cret1"))
    }

    @Test
    fun foxRelayWorkerIsCatBrandedAndHubHasTheRow() {
        val secret = "abc12345def"
        val js = FoxMozillaRelay.workerScript(secret)
        assertTrue(js.contains("const SECRET = \"$secret\";"))
        assertTrue(js.contains("cat-fox"))
        assertTrue(js.contains("mozilla.org"))
        assertTrue(js.contains("firefox.com"))
        assertTrue(js.contains("health"))
        assertFalse(js.contains("Arman"))
        assertFalse(js.contains("Spider"))
        assertFalse(js.contains("arman-vpn"))
        val fox = src("MainActivityFox.kt")
        assertTrue(fox.contains("showFoxMozillaRelayDialog"))
        assertTrue(fox.contains("اگر ورود موزیلا بسته است"))
        assertTrue(fox.contains("If Mozilla sign-in is blocked"))
        assertTrue(fox.contains("workers.dev is not required") || fox.contains("workers.dev لازم نیست"))
        assertFalse(fox.contains("Spider"))
        assertFalse(fox.contains("Arman"))
        val engine = src("FirefoxVpnEngine.kt")
        assertTrue(engine.contains("FoxMozillaRelay.wrap"))
        assertTrue(engine.contains("useRelay"))
        assertTrue(engine.contains("bindMozillaRelay"))
        assertFalse(engine.contains("Spider"))
    }

    @Test
    fun amneziaCleanIpsKeepExactPortAndAddInitPackets() {
        val parsed = AmneziaCleanEndpoints.parse(
            """
            8.6.112.208:2408
            8.6.112.10:1701
            1.2.3.4:80
            1.2.3.4:1080
            1.2.3.4:443
            127.0.0.1:2408
            not-an-ip:500
            8.6.112.121:500
            """.trimIndent(),
        )
        assertEquals(listOf("8.6.112.208:2408", "8.6.112.10:1701", "8.6.112.121:500"), parsed)
        val uri = AmneziaCleanEndpoints.awgUri("8.6.112.208:2408", withInitPackets = true)
        assertNotNull(uri)
        assertTrue(uri!!.startsWith("awg://auto@8.6.112.208:2408?"))
        assertTrue(uri.contains("jc=5"))
        assertTrue(uri.contains("i1="))
        assertTrue(uri.contains("i5="))
        assertTrue(uri.contains("itime=120"))
        assertTrue(URLDecoder.decode(uri, "UTF-8").contains("<r 8+8>"))
        assertNull(AmneziaCleanEndpoints.awgUri("8.6.112.208:443"))
        assertNull(AmneziaCleanEndpoints.awgUri("8.6.112.208:80"))
        val plain = AmneziaCleanEndpoints.awgUri("8.6.112.10:1701", withInitPackets = false)
        assertNotNull(plain)
        assertFalse(plain!!.contains("i1="))
        val hub = src("MlmAndNextGenVpnHub.kt")
        assertTrue(hub.contains("AmneziaCleanEndpoints.liveOrFallback"))
        assertTrue(hub.contains("withInitPackets = true"))
        val conf = src("MlmAndNextGenVpnHub.kt")
        val convert = conf.indexOf("fun convertWgConfToAwgUri")
        assertTrue(convert >= 0)
        val convertBody = conf.substring(convert, conf.indexOf("fun fetchAndScanAmneziaAndProtonConfigs", convert))
        assertTrue(convertBody.contains("\"i1\""))
        assertTrue(convertBody.contains("\"i5\""))
    }

    @Test
    fun masqueExtraH2AndH3StayOn443AndScanTriesH3() {
        assertTrue(CatWarpFreePlanner.EXTRA_H2_PEERS.size >= 4)
        assertTrue(CatWarpFreePlanner.EXTRA_H2_PEERS.all { it.endsWith(":443") })
        assertTrue(CatWarpFreePlanner.EXTRA_H3_PEERS.all { it.endsWith(":443") })
        assertTrue(CatWarpFreePlanner.EXTRA_H2_PEERS.contains("162.159.198.1:443"))
        assertTrue(CatWarpFreePlanner.EXTRA_H2_PEERS.contains("162.159.192.1:443"))
        assertTrue(CatWarpFreePlanner.EXTRA_H2_PEERS.contains("188.114.96.1:443"))
        assertFalse(CatWarpFreePlanner.EXTRA_H2_PEERS.any { it.endsWith(":80") || it.endsWith(":1080") })
        assertEquals(4, CatWarpFreePlanner.PROTOS.size)
        val scan = src("MainActivityFreeNetworks.kt")
        val start = scan.indexOf("internal fun MainActivity.scanCatWarpOnThisLine")
        assertTrue(start >= 0)
        val body = scan.substring(start, minOf(scan.length, start + 9000))
        assertTrue(body.contains("\"h3\""))
        assertTrue(body.contains("EXTRA_H2_PEERS"))
        assertTrue(body.contains("masqueTransport = when"))
        assertTrue(body.contains("bestH3"))
        assertTrue(body.contains("AmneziaCleanEndpoints.liveOrFallback"))
        assertFalse(body.contains("WarpScout"))
        assertFalse(body.contains("Masque-Generator"))
    }

    @Test
    fun dumpBrandsAreNotRequiredNames() {
        val files = listOf("FoxMozillaRelay.kt", "AmneziaCleanEndpoints.kt", "CatWarpFree.kt", "MainActivityFox.kt")
        files.forEach { name ->
            val body = src(name)
            listOf("RX VPN", "Firefox-Spider", "MahsaNG", "Oblivion", "Defyx", "ProxyCloud", "WarpScout", "Arman VPN").forEach { brand ->
                assertFalse("$name must not require $brand", body.contains(brand))
            }
        }
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

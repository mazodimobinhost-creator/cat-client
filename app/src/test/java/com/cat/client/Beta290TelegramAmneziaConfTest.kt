package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta290 — Telegram AmneziaWG .conf opens in Cat; WARP rows use real hosts, not .0 */
class Beta290TelegramAmneziaConfTest {

    @Test
    fun versionIsBeta290() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 362"))
        assertTrue(gradle.contains("1.10.0-beta295"))
    }

    @Test
    fun telegramConfOpensAsAmneziaAndKeepsEndpoint() {
        val conf = """
            [Interface]
            PrivateKey = ERERERERERERERERERERERERERERERERERERERERERE=
            Address = 172.16.0.2/32
            Jc = 7
            Jmin = 121
            Jmax = 784
            MTU = 1420
            [Peer]
            PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
            Endpoint = 188.114.98.193:500
        """.trimIndent()
        assertEquals("🔐 Amnezia", IncomingConfigIntents.guessName(conf))
        val uri = WireGuardConfText.toUris(conf).single()
        assertTrue(uri.startsWith("awg://"))
        assertTrue(uri.contains("@188.114.98.193:500?"))
        assertTrue(uri.contains("jc=7"))
        val manifest = srcFile("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("application/octet-stream"))
        assertTrue(manifest.contains("application/x-wireguard-profile"))
        val activity = src("MainActivity.kt")
        assertTrue(activity.contains("previewAndImportAmneziaText(body)"))
        assertFalse(activity.contains("Varvara"))
    }

    @Test
    fun warpAmneziaUsesHostAddressesNotNetworkDotZero() {
        val pool = src("MlmAndNextGenVpnHub.kt")
        val start = pool.indexOf("val BUILTIN_AWG_AND_HY2_URIS")
        assertTrue(start >= 0)
        val list = pool.substring(start, pool.indexOf("fun convertWgConfToAwgUri", start))
        assertTrue(list.contains("awg://auto@188.114.98.193:500"))
        assertFalse(list.contains("awg://auto@188.114.98.0:"))
        assertTrue(list.contains("awg://auto@162.159.192.1:2408"))
        assertTrue(list.contains("awg://auto@162.159.192.1:4500"))
    }

    private fun src(name: String): String = srcFile(
        "src/main/java/com/cat/client/$name",
        "app/src/main/java/com/cat/client/$name",
    )

    private fun srcFile(vararg paths: String): String =
        paths.map(::File).first { it.isFile }.readText()
}

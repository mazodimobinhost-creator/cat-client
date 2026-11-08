package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta289 — tapping Amnezia must put visible configs in the list even without a WARP key. */
class Beta289AmneziaLandsTest {

    @Test
    fun versionIsBeta289() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 356"))
        assertTrue(gradle.contains("1.10.0-beta289"))
    }

    @Test
    fun warpAmneziaSavesBuiltinRowsWithoutWaitingForCloudflare() {
        val hub = src("MainActivityFreeNetworks.kt")
        val start = hub.indexOf("internal fun MainActivity.addWarpAmneziaAuto")
        assertTrue(start >= 0)
        val body = hub.substring(start, hub.indexOf("internal fun MainActivity.showFreePsiphonDialog", start))
        assertTrue(body.contains("BUILTIN_AWG_AND_HY2_URIS"))
        assertTrue(body.contains("saveFreeSubscription"))
        assertTrue(body.contains("amneziaSubscriptionPrefix"))
        assertFalse(body.contains("ensureAccountDetailed"))
        assertFalse(body.contains("if (outcome?.account == null)"))
        assertFalse(body.contains("rankLinksByLivePing"))
        assertTrue(body.contains("freeResult"))
        assertTrue(MlmAndNextGenVpnHub.BUILTIN_AWG_AND_HY2_URIS.isNotEmpty())
        assertTrue(MlmAndNextGenVpnHub.BUILTIN_AWG_AND_HY2_URIS.all { it.startsWith("awg://auto@") })
        assertFalse(hub.contains("Varvara"))
    }

    @Test
    fun myAmneziaListShowsWarpRowsAndFinderHasTheMisspelling() {
        assertTrue(isAmneziaListed("🔐 Amnezia · Cat Warp", "awg://auto@162.159.192.1:2408"))
        assertTrue(isAmneziaListed("🛡️ Cat Warp Amnezia", "# comment\nawg://auto@host:500"))
        assertTrue(isAmneziaListed("امیزا", "vless://nope"))
        assertFalse(isAmneziaListed("🟢 Cat Proton", "wireguard://abc@1.1.1.1:51820"))
        val section = src("MainActivityAmneziaSstp.kt")
        assertTrue(section.contains("بساز و بیاور داخل اپ"))
        assertTrue(section.indexOf("بساز و بیاور داخل اپ") < section.indexOf("کلید vpn://"))
        assertTrue(section.contains("addWarpAmneziaAuto"))
        val finder = src("MainActivityGuides.kt")
        assertTrue(finder.contains("امیزا"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

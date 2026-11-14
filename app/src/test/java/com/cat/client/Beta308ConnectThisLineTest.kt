package com.cat.client

import java.io.File
import java.net.URLDecoder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * beta308 — Cat methods must actually connect: Amnezia on this line, Fanoos configs Cat can run,
 * one-tap that tries the next method itself (no ranking dialog).
 */
class Beta308ConnectThisLineTest {

    @Test
    fun versionIsBeta308() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 375"))
        assertTrue(gradle.contains("1.10.0-beta308"))
    }

    @Test
    fun fanoosKeepsObfsShadowsocksAndXhttpAndSkipsUnknownPlugins() {
        val ss = JSONObject()
            .put("type", "shadowsocks")
            .put("server", "1.2.3.4")
            .put("server_port", 8388)
            .put("method", "chacha20-ietf-poly1305")
            .put("password", "pw")
            .put("plugin", "obfs-local")
            .put("plugin_opts", "obfs=http;obfs-host=www.bing.com")
        val ssLink = SingBoxOutboundLinks.link(ss, "ss-obfs")
        assertNotNull(ssLink)
        assertTrue(ssLink!!.startsWith("ss://"))
        assertTrue(ssLink.contains("plugin="))
        assertTrue(URLDecoder.decode(ssLink.substringAfter("plugin=").substringBefore('#'), "UTF-8").contains("obfs-local"))
        assertTrue(SubConvConverter.convert(ssLink).isNotEmpty())

        val unknown = JSONObject(ss.toString()).put("plugin", "shadow-tls")
        assertNull("unknown plugin is not faked", SingBoxOutboundLinks.link(unknown, "ss-bad"))
        assertTrue(SingBoxOutboundLinks.shadowsocksPluginRunnable("obfs-local"))
        assertTrue(SingBoxOutboundLinks.shadowsocksPluginRunnable("v2ray-plugin"))
        assertFalse(SingBoxOutboundLinks.shadowsocksPluginRunnable("shadow-tls"))

        val vless = JSONObject()
            .put("type", "vless")
            .put("uuid", "11111111-1111-1111-1111-111111111111")
            .put("server", "9.9.9.9")
            .put("server_port", 443)
            .put("tls", JSONObject().put("enabled", true).put("server_name", "x.example"))
            .put("transport", JSONObject().put("type", "splithttp").put("path", "/api").put("host", "x.example"))
        val vlessLink = SingBoxOutboundLinks.link(vless, "xhttp")
        assertNotNull(vlessLink)
        assertTrue(vlessLink!!.startsWith("vless://"))
        assertTrue(vlessLink.contains("type=xhttp"))
        assertTrue(SubConvConverter.convert(vlessLink).isNotEmpty())
    }

    @Test
    fun fanoosWireGuardOutboundBecomesAShareLinkCatConverts() {
        val ob = JSONObject()
            .put("type", "wireguard")
            .put("server", "103.1.213.212")
            .put("server_port", 51820)
            .put("private_key", "cIq+3tcHVT7gRMl4kIv0WE68ERiTmDtA7rhyHhLJ/mg=")
            .put("peer_public_key", "jCi1I5HBTtwCblBbMzOFmOOFNG1GilYOZgst0kyq9gY=")
            .put("local_address", org.json.JSONArray().put("100.80.234.177/32"))
            .put("mtu", 1280)
        val link = SingBoxOutboundLinks.link(ob, "wg")
        assertNotNull(link)
        assertTrue(link!!.startsWith("wireguard://"))
        assertTrue(link.contains("103.1.213.212:51820"))
        assertTrue(SubConvConverter.convert(link).isNotEmpty())
    }

    @Test
    fun amneziaOneTapUsesLiveIpsAndConnects() {
        val src = src("MainActivityFreeNetworks.kt")
        val start = src.indexOf("internal fun MainActivity.addWarpAmneziaAuto()")
        assertTrue(start >= 0)
        val body = src.substring(start, src.indexOf("internal fun MainActivity.showFreePsiphonDialog()", start))
        assertTrue(body.contains("AmneziaCleanEndpoints.liveOrFallback"))
        assertTrue(body.contains("rankLinksByLivePing"))
        assertTrue(body.contains("connectFreeSubscription(subId, autoNext = true)"))
        assertTrue(body.contains("withInitPackets = true"))
        assertTrue(src.contains("beginConnectFlow(Actions.CONNECT, autoNext = autoNext)"))
        assertTrue(src.contains("connectFreeSubscription(subId, autoNext = true)"))
    }

    @Test
    fun oneTapConnectsWithoutFinderDialogAndAutoNextIsWired() {
        val guides = src("MainActivityGuides.kt")
        assertTrue(guides.contains("oneTapConnectThisLine()"))
        val wizard = guides.substringAfter("internal fun MainActivity.showRecommendedOneClickConnectWizardDialog()")
            .substringBefore("internal fun MainActivity.finderEntries()")
        assertTrue(wizard.contains("oneTapConnectThisLine()"))
        assertFalse(wizard.contains("showMethodFinderDialog("))

        val finder = src("MainActivityMethodFinder.kt")
        assertTrue(finder.contains("fun MainActivity.oneTapConnectThisLine()"))
        assertTrue(finder.contains("handleConnectionSelected(null, emptySet())"))
        assertTrue(finder.contains("beginConnectFlow(Actions.CONNECT, autoNext = true)"))
        assertTrue(finder.contains("AmneziaCleanEndpoints.liveOrFallback"))

        val actions = src("Actions.kt")
        assertTrue(actions.contains("EXTRA_AUTO_NEXT"))

        val activity = src("MainActivity.kt")
        assertTrue(activity.contains("autoNext: Boolean = false"))
        assertTrue(activity.contains("Actions.EXTRA_AUTO_NEXT"))

        val service = src("CatClientVpnService.kt")
        assertTrue(service.contains("tryAutoNextAfterStartupFailure"))
        assertTrue(service.contains("autoNextOnFail"))
        assertTrue(service.contains("\$eventPrefix.autoNext") || service.contains(".autoNext"))

        val real = src("CatClientVpnServiceRealInternet.kt")
        assertTrue(real.contains("autoNextOnFail || !connectionSelectionPreferenceStore.hasSelectedProfile"))
        assertTrue(real.contains("nextStartupProfile"))
        assertTrue(real.contains("autoNextHopLimit()"))
    }

    @Test
    fun noFakeArgoMamiGephOpenVpnEngine() {
        val finder = src("MainActivityMethodFinder.kt")
        assertFalse(finder.contains("Argo Authenticator"))
        assertFalse(finder.contains("liblantern.so"))
        assertFalse(finder.contains("GephEngine"))
        val guides = src("MainActivityGuides.kt")
        assertFalse(guides.contains("showGephDialog"))
        assertTrue(src("CatVpsTunnels.kt").contains("Cat has no OpenVPN engine") || src("CatVpsTunnels.kt").contains("no OpenVPN engine"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

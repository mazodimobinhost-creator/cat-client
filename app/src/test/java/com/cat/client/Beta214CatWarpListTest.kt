package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket

class Beta214CatWarpListTest {
    private val us = "aether://masque?endpoint=162.159.198.1%3A443#Cat%20%C2%B7%20United%20States%20%C2%B7%20Warp"
    private val de = "aether://masque?endpoint=162.159.198.2%3A443#Cat%20%C2%B7%20Germany%20%C2%B7%20Warp"

    @Test
    fun builderLeavesExactlyOneConfig() {
        val existing = listOf(us, "aether://warp?x=1#Old-1", "aether://warp?x=2#Old-2").joinToString("\n")
        assertEquals(de, CatWarpListPolicy.mergedInput(existing, de, replaceAll = true))
        assertEquals(de, CatWarpListPolicy.mergedInput(null, de, replaceAll = false))
    }

    @Test
    fun pastedLinkIsMergedFirstAndSameNameReplaced() {
        val existing = listOf(us, "aether://warp?x=2#Old").joinToString("\n")
        val usNew = us.replace("198.1", "198.9")
        val merged = CatWarpListPolicy.mergedInput(existing, usNew, replaceAll = false).lines()
        assertEquals(listOf(usNew, "aether://warp?x=2#Old"), merged)
        val many = (1..40).joinToString("\n") { "aether://warp?x=$it#N$it" }
        assertEquals(CatWarpListPolicy.MAX_MERGED_LINES, CatWarpListPolicy.mergedInput(many, de, false).lines().size)
    }

    @Test
    fun findsTheLineBehindARowAndMovesItFirst() {
        val input = listOf("vless://a@1.2.3.4:443#v", us, de).joinToString("\n")
        assertEquals(2, CatWarpListPolicy.lineIndexFor(input, de))
        // regenerated link with the same #name still finds the line
        assertEquals(1, CatWarpListPolicy.lineIndexFor(input, "aether://masque?endpoint=9.9.9.9%3A443#Cat%20%C2%B7%20United%20States%20%C2%B7%20Warp"))
        assertEquals(-1, CatWarpListPolicy.lineIndexFor(input, "vless://a@1.2.3.4:443#v"))
        assertEquals(listOf(de, "vless://a@1.2.3.4:443#v", us), CatWarpListPolicy.moveLineFirst(input, 2).lines())
        assertEquals(input, CatWarpListPolicy.moveLineFirst(input, 0))
        assertEquals(input, CatWarpListPolicy.moveLineFirst(input, 9))
    }

    @Test
    fun plainLocalRowsAreRecognisedButEngineRowsAreNot() {
        assertTrue(LoopbackProfileGuard.isPlainLoopback("socks5", "127.0.0.1", "socks://127.0.0.1:10819#Germany"))
        assertTrue(LoopbackProfileGuard.isPlainLoopback("http", "localhost", null))
        assertFalse(LoopbackProfileGuard.isPlainLoopback("socks5", "127.0.0.1", us))
        assertFalse(LoopbackProfileGuard.isPlainLoopback("socks5", "127.0.0.1", "sstp://vpn:vpn@h:443"))
        assertFalse(LoopbackProfileGuard.isPlainLoopback("socks5", "185.1.2.3", "socks://185.1.2.3:1080"))
        assertFalse(LoopbackProfileGuard.isPlainLoopback("vless", "127.0.0.1", "vless://a@127.0.0.1:1#x"))
        assertTrue(LoopbackProfileGuard.isLoopbackHost("127.0.0.53"))
        assertFalse(LoopbackProfileGuard.isLoopbackHost("1.1.1.1"))
    }

    @Test
    fun listeningProbeTellsLiveFromDeadPorts() {
        ServerSocket(0).use { server ->
            assertTrue(LoopbackProfileGuard.isListening(server.localPort))
        }
        val freed = ServerSocket(0).use { it.localPort }
        assertFalse(LoopbackProfileGuard.isListening(freed, 300))
        assertFalse(LoopbackProfileGuard.isListening(0))
    }

    @Test
    fun deadLocalErrorGetsItsOwnPlainExplanation() {
        val msg = LoopbackProfileGuard.deadLocalMessage(10819, "Cat · Germany · Local")
        assertTrue(msg.contains("127.0.0.1:10819"))
        assertEquals(ConnectionFaultExplainer.Cause.LocalProxyMissing, ConnectionFaultExplainer.classify(msg))
        assertFalse(msg.contains("health check"))
    }
}

class Beta214CatWarpWiringTest {
    private fun src(name: String): String =
        if (name == "MainActivity.kt") MainActivitySources.combined()
        else listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    @Test
    fun buildersReplaceTheListAndConnectOnlyAfterSaving() {
        val main = src("MainActivity.kt")
        val save = main.substringAfter("private fun saveAetherConfigIntoMainSubscriptionList(").substringBefore("private fun connectCatWarpAfterSave")
        assertTrue(save.contains("CatWarpListPolicy.mergedInput(existing.input, uri, replaceAll)"))
        assertTrue(save.contains("onSaved?.invoke()"))
        assertFalse(save.contains("take(24)"))
        // the quick country chips no longer pile up lines (default replaceAll = true)
        assertTrue(main.contains("saveAetherConfigIntoMainSubscriptionList(switched, activateAetherEngine = false)"))
        assertTrue(save.contains("replaceAll: Boolean = true"))
        // the wizard writes its result into the list
        assertTrue(main.contains("saveAetherConfigIntoMainSubscriptionList(builtSelection, activateAetherEngine = true, replaceAll = true)"))
        // «Save & connect» in the editor really connects
        assertTrue(main.substringAfter("private fun saveEditedAetherLine(").substringBefore("/** «کلید WARP ما").contains("if (connect) connectCatWarpAfterSave()"))
    }

    @Test
    fun rowMenuOffersEndpointScanForCatWarpRows() {
        val menu = src("MainActivity.kt").substringAfter("private fun showConnectionShareMenu(").substringBefore("/* ---- share a remote")
        assertTrue(menu.contains("openCatWarpEditorForLink(subscriptionId, link, autoScan = true)"))
        assertTrue(menu.contains("!catWarpRow && !plainLocalRow"))
        assertTrue(src("MainActivity.kt").contains("if (autoScan) scanBtn.post"))
    }

    @Test
    fun serviceExplainsDeadLocalRowsInsteadOfTheCoreHealthCheck() {
        val svc = VpnServiceSources.combined()
        assertTrue(svc.contains("LoopbackProfileGuard.deadLocalMessage("))
        assertTrue(svc.contains("CatWarpListPolicy.isCatWarpLink(p.shareLink) || p.tag in deadLocalTags"))
        val conv = src("SubConvConverter.kt")
        assertTrue(conv.contains("LoopbackProfileGuard.isPlainLoopback(proxy.optString(\"type\"), proxy.optString(\"server\"), null)"))
    }
}

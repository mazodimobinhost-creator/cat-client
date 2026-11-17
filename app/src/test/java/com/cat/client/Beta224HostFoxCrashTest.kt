package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta224:
 *  - Cat Fox VPN: only Mozilla's web ports are asked for; a 400/502/504 for one destination no longer rotates the
 *    node (only several different failing destinations in 30 s do);
 *  - the Cat panel on SmarterASP.NET (IIS + httpPlatformHandler): web.config + App_Data + vendored `ws`, IIS mode;
 *  - CrashWatch reads Android's exit reasons (ANR / native crash) instead of "closed unexpectedly".
 */
class Beta224HostFoxCrashTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory || File(it, "src/main").isDirectory }
        .let { if (File(it, "app/src/main").isDirectory) File(it, "app/src/main") else File(it, "src/main") }

    private fun src(name: String) = File(root(), "java/com/cat/client/$name").readText()
    private fun asset(path: String) = File(root(), "assets/$path")

    // ---------------- Fox ----------------

    @Test
    fun foxCarriesOnlyWebPortsAndRewritesGithubSsh() {
        for (p in listOf(80, 443, 8080, 8443)) {
            assertEquals(FoxPortPolicy.Route.Tunnel("example.com", p), FoxPortPolicy.route("example.com", p))
        }
        for (p in listOf(21, 22, 25, 53, 853, 993, 2053, 5222, 5228, 8880)) {
            assertTrue("$p", FoxPortPolicy.route("example.com", p) is FoxPortPolicy.Route.Refuse)
        }
        assertEquals(FoxPortPolicy.Route.Tunnel("ssh.github.com", 443), FoxPortPolicy.route("GitHub.com.", 22))
        assertTrue(FoxPortPolicy.route("gitlab.com", 22) is FoxPortPolicy.Route.Refuse)
        assertEquals("80, 443, 8080, 8443", FoxPortPolicy.WEB_PORTS.sorted().joinToString(", "))
        assertEquals(FoxPortPolicy.WEB_PORTS_TEXT, FoxPortPolicy.WEB_PORTS.sorted().joinToString(", "))
    }

    @Test
    fun aDeadSiteDoesNotRotateTheNode() {
        assertTrue(FoxPortPolicy.isTargetStatus(502) && FoxPortPolicy.isTargetStatus(504) && FoxPortPolicy.isTargetStatus(400))
        assertFalse(FoxPortPolicy.isTargetStatus(401) || FoxPortPolicy.isTargetStatus(403) || FoxPortPolicy.isTargetStatus(429))
        val f = FoxPortPolicy.TargetFailures(windowMs = 30_000L, distinct = 3)
        // the same dead site many times: never the node's fault
        repeat(10) { assertFalse(f.failed("dead.example:443", 1_000L + it)) }
        assertFalse(f.failed("b.example:443", 2_000L))
        assertTrue("3 different destinations within 30 s", f.failed("c.example:443", 3_000L))
        f.success()
        assertFalse(f.failed("d.example:443", 4_000L))
        // old failures leave the window
        val g = FoxPortPolicy.TargetFailures(windowMs = 30_000L, distinct = 3)
        g.failed("a:443", 0L); g.failed("b:443", 1_000L)
        assertFalse(g.failed("c:443", 40_000L))
    }

    @Test
    fun foxBridgeUsesThePolicy() {
        val fox = src("FirefoxVpnEngine.kt")
        assertTrue(fox.contains("FoxPortPolicy.route("))
        assertTrue(fox.contains("FoxPortPolicy.Route.Refuse"))
        assertTrue(fox.contains("fenix.port.refused"))
        assertTrue(fox.contains("targetFailures.failed("))
        assertTrue(fox.contains("targetFailures.success()"))
        assertTrue("web ports come from the policy", fox.contains("FoxPortPolicy.WEB_PORTS"))
    }

    // ---------------- SmarterASP ----------------

    @Test
    fun smarterAspIsAFreeUploadOnlyHost() {
        val host = NodeHostPanel.HOSTS.single { it.id == NodeHostPanel.SMARTERASP }
        assertTrue(host.free)
        assertTrue(NodeHostPanel.isUploadOnlyHost(NodeHostPanel.SMARTERASP))
        assertFalse(NodeHostPanel.isUploadOnlyHost(NodeHostPanel.KATABUMP))
        assertFalse(NodeHostPanel.isApiHost(NodeHostPanel.SMARTERASP))
        assertEquals(60L * 24 * 3600 * 1000, NodeHostPanel.SMARTERASP_TRIAL_MS)
        assertTrue(NodeHostPanel.SMARTERASP_SIGNUP.startsWith("https://www.smarterasp.net/"))
    }

    @Test
    fun smarterAspHttpFallbackOnlyForABareHttpsSite() {
        assertEquals("http://cat-001-site1.example.com", NodeHostPanel.smarterAspHttpFallback(NodeHostPanel.SMARTERASP, "https://cat-001-site1.example.com"))
        assertNull(NodeHostPanel.smarterAspHttpFallback(NodeHostPanel.SMARTERASP, "http://cat-001-site1.example.com"))
        assertNull(NodeHostPanel.smarterAspHttpFallback(NodeHostPanel.SMARTERASP, "https://cat.example.com:8443"))
        assertNull(NodeHostPanel.smarterAspHttpFallback(NodeHostPanel.SMARTERASP, "https://cat.example.com/x"))
        assertNull(NodeHostPanel.smarterAspHttpFallback(NodeHostPanel.KATABUMP, "https://cat.example.com"))
        assertEquals("node_modules/ws/lib/websocket.js", NodeHostPanel.smarterAspWsEntry("/lib/websocket.js"))
    }

    @Test
    fun smarterAspFilesAreShipped() {
        val webConfig = asset("node-panel/smarterasp/web.config").readText()
        assertTrue(webConfig.contains("modules=\"httpPlatformHandler\""))
        assertTrue(webConfig.contains("arguments=\"index.js\""))
        assertTrue(webConfig.contains("value=\"%HTTP_PLATFORM_PORT%\""))
        assertTrue(webConfig.contains("name=\"CAT_BEHIND_IIS\" value=\"1\""))
        assertTrue(webConfig.contains("/__cat_edge/{HTTPS}/{REMOTE_ADDR}{UNENCODED_URL}"))
        assertTrue("users stay in the writable App_Data", webConfig.contains("App_Data\\cat-data"))
        assertTrue(asset("node-panel/smarterasp/App_Data-README.txt").isFile)
        for (f in listOf("package.json", "index.js", "wrapper.mjs", "LICENSE", "lib/websocket.js", "lib/websocket-server.js")) {
            assertTrue(f, asset("node-panel/smarterasp/ws/$f").isFile)
        }
        assertTrue(asset("node-panel/smarterasp/ws/package.json").readText().contains("\"version\": \"8."))
        val launcher = asset("node-panel/index.js").readText()
        assertTrue(launcher.contains("CAT_BEHIND_IIS") && launcher.contains("attachIisEdge") && launcher.contains("codeDirOf"))
    }

    @Test
    fun smarterAspCardHasNoAutoOrRenewButton() {
        val ui = src("MainActivityUiKit.kt")
        assertTrue(ui.contains("NodeHostPanel.isUploadOnlyHost(kind)"))
        assertTrue(ui.contains("if (uploadOnly) null else Pair(if (git) R.string.cloud_node_auto_git"))
        assertTrue(src("MainActivityCloud.kt").contains("NodeHostPanel.SMARTERASP, R.string.cloud_smarterasp_title"))
        val controller = src("NodeHostController.kt")
        assertTrue(controller.contains("smarterAspSteps()") && controller.contains("smarterAspGuide()"))
        assertTrue("re-upload keeps the panel's UUID", controller.contains("saveFiles(item.kind, update = true)"))
        assertTrue(controller.contains("if (!update) {"))
    }

    // ---------------- CrashWatch ----------------

    @Test
    fun crashWatchReadsAndroidExitReasons() {
        val cw = src("CrashWatch.kt")
        assertTrue(cw.contains("getHistoricalProcessExitReasons(context.packageName, 0, 5)"))
        assertTrue(cw.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.R"))
        assertTrue(cw.contains("traceInputStream"))
        assertTrue("first run records only", cw.contains("if (seen == null) return"))
        val install = cw.substringAfter("fun install(app: Application)").substringBefore("fun markHealthy")
        assertTrue(install.indexOf("readExitReasons(app)") in 0 until install.indexOf("announceCrash(app, force = false)"))
        assertEquals("ANR", CrashWatch.exitReasonName(6))
        assertEquals("CRASH_NATIVE", CrashWatch.exitReasonName(5))
        assertEquals("LOW_MEMORY", CrashWatch.exitReasonName(3))
        assertEquals("UNKNOWN(99)", CrashWatch.exitReasonName(99))
    }
}

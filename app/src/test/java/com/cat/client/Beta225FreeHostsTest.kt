package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta225 «free hosts» audit: each host's panel knows which host it is on (CAT_HOST), KataBump's direct port no
 * longer believes client-sent forwarded headers, a 🟧 Cloudflare front can sit in front of any Node host with its
 * own HTTPS name, and the renewal / trial reminders open the right host's page.
 */
class Beta225FreeHostsTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "node-panel/index.js").exists() }
    private fun read(path: String) = File(root, path).readText()

    @Test
    fun configJsonNamesTheHostOnlyForKnownKinds() {
        val kb = NodeHostPanel.configJson("u", "p", noDisk = false, host = NodeHostPanel.KATABUMP)
        assertTrue(kb.contains("\"CAT_HOST\": \"katabump\""))
        assertFalse(NodeHostPanel.configJson("u", "p", noDisk = false, host = "evil").contains("CAT_HOST"))
        assertFalse(NodeHostPanel.configJson("u", "p", noDisk = false).contains("CAT_HOST"))
        assertEquals(NodeHostPanel.HOSTS.map { it.id }.toSet(), NodeHostPanel.HOST_KINDS.toSet())
    }

    @Test
    fun cloudflareFrontOnlyForHttpsNamesOn443() {
        assertTrue(NodeHostPanel.cfFrontCapable(NodeHostPanel.RAILWAY, "https://cat-panel-production.up.railway.app"))
        assertTrue(NodeHostPanel.cfFrontCapable(NodeHostPanel.BOTHOSTING, "https://cat.apps.bot-hosting.cloud"))
        assertTrue(NodeHostPanel.cfFrontCapable(NodeHostPanel.SMARTERASP, "https://site1.example-tempurl.com"))
        // KataBump IP:port is plain http on a game port; a Worker cannot front it (Link panels does that job)
        assertFalse(NodeHostPanel.cfFrontCapable(NodeHostPanel.KATABUMP, "https://cat.kdns.fr"))
        assertFalse(NodeHostPanel.cfFrontCapable(NodeHostPanel.SMARTERASP, "http://site1.example-tempurl.com"))
        assertFalse(NodeHostPanel.cfFrontCapable(NodeHostPanel.LUCITY, "https://1.2.3.4"))
        assertFalse(NodeHostPanel.cfFrontCapable(NodeHostPanel.SHIPRR, "https://cat.shiprr.dev:8443"))
    }

    @Test
    fun renewalOpensTheRightHostAndTheTrialReminderIsNeverInThePast() {
        assertEquals(NodeHostPanel.KATABUMP_DASHBOARD, NodeHostPanel.renewPage(NodeHostPanel.KATABUMP))
        assertEquals(ApiHostPlan.BOTHOSTING_DASHBOARD, NodeHostPanel.renewPage(NodeHostPanel.BOTHOSTING))
        assertEquals(NodeHostPanel.SMARTERASP_CONTROL, NodeHostPanel.renewPage(NodeHostPanel.SMARTERASP))
        assertNull(NodeHostPanel.renewPage(NodeHostPanel.RAILWAY))
        val day = 24L * 60 * 60 * 1000
        assertEquals(55 * day, NodeHostPanel.smarterAspReminderDelay(createdAt = 0L, now = 0L))
        assertEquals(60_000L, NodeHostPanel.smarterAspReminderDelay(createdAt = 0L, now = 70 * day))
    }

    @Test
    fun controllerWiresTheFixes() {
        val c = read("app/src/main/java/com/cat/client/NodeHostController.kt")
        // the reminder carries the host (it used to open KataBump's page for Bot-Hosting too)
        assertTrue(c.contains("NodeHostRenewReminder.schedule(activity, item.id, item.name, wait, item.kind)"))
        // «✍️ type the address» opens the form of the host that failed (it was always KataBump)
        assertTrue(c.contains("registerManual(o.kind.ifBlank { NodeHostPanel.KATABUMP })"))
        assertTrue(c.contains("NodeHostPanel.smarterAspReminderDelay(saved.createdAt"))
        // 🟧 front: token check → installer confirmation → the shared cf-front.js Worker → panel setting
        val ask = c.indexOf("private fun askCfFront(")
        val confirm = c.indexOf("private fun confirmCfFront(")
        val build = c.indexOf("private fun buildCfFront(")
        assertTrue(ask in 1 until confirm && confirm < build)
        assertTrue(c.substring(confirm, build).contains(".setPositiveButton(t(\"بساز\", \"Build\"))"))
        val b = c.substring(build, c.indexOf("private fun askPanelPassword("))
        assertTrue(b.contains("CloudflareWorker.deployVercelFront(activity, token, accountId, origin, workerName)"))
        assertTrue(b.contains("vercelCfFront"))
        // only Lucity / Shiprr log in with GitHub
        assertTrue(c.contains("NodeHostPanel.BOTHOSTING -> t(\n                    \"ورود به Bot-Hosting"))
        val h = read("app/src/main/java/com/cat/client/NodeHost.kt")
        assertTrue(h.contains("Uri.parse(NodeHostPanel.renewPage(kind) ?: NodeHostPanel.KATABUMP_DASHBOARD)"))
    }

    @Test
    fun launcherAndAdapterAgree() {
        val launcher = read("node-panel/index.js")
        assertEquals(launcher, read("app/src/main/assets/node-panel/index.js"))
        assertTrue(launcher.contains("'CAT_HOST', 'CAT_CF_FRONT', 'CAT_KEEPALIVE'"))
        assertTrue(launcher.contains("if (directHost(env)) env.CAT_HOST_DIRECT = '1';"))
        val adapter = read("app/src/main/assets/vercel/panel-adapter.mjs")
        assertTrue(adapter.contains("if (process.env.CAT_HOST_DIRECT === '1') return req.headers['cf-ray'] && isCloudflareIp(req.socket.remoteAddress) ? 'cf' : 'plain';"))
        assertTrue(adapter.contains("if (req.headers['x-cat-front'] && proto === 'https') return 'tls';"))
        val panel = read("app/src/main/assets/panels/catclient.worker.js")
        // CI swaps the worker for its minified build before the JVM tests, so only string literals are checked here
        // (the cfFront code path itself is covered by scripts/panels/node-host.test.mjs on both builds).
        assertTrue(panel.contains("جلوی Cloudflare فعال است"))
        assertTrue(panel.contains("data-plat=\"node\""))
    }
}

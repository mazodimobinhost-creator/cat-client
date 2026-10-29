package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta231 — SmarterASP Linux showed «Unhealthy», 6 restarts, exit code 0: its runtime loads the Startup file from
 * its own runner, so the launcher's `argv[1] === index.js` check was false and it quietly exited. Now a deployed
 * folder (cat-config.json next to index.js) starts anyway, and the zip carries the panel for a first start without
 * GitHub. Real-process proof: scripts/panels/smarterasp-linux.test.mjs (CommonJS runner, unreachable GitHub).
 */
class Beta231SmarterAspRunnerTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "node-panel/index.js").exists() }
    private fun read(path: String) = File(root, path).readText()

    @Test
    fun launcherStartsWhenAHostRunnerLoadsIt() {
        val l = read("node-panel/index.js")
        assertTrue(l.contains("export const autostarted = isMain || (process.env.CAT_AUTOSTART !== '0' && fs.existsSync(path.join(HERE, 'cat-config.json')));"))
        assertTrue(l.contains("} else if (!have && bundledReady(env)) {"))
        assertEquals(l, read("app/src/main/assets/node-panel/index.js"))
    }

    @Test
    fun smarterAspZipCarriesThePrebuiltPanel() {
        // beta232: the Linux runtime's site files can be read-only → cat-code/ comes prebuilt in the zip
        val c = read("app/src/main/java/com/cat/client/NodeHostController.kt")
        for (f in listOf("cat-code/api/index.mjs", "cat-code/lib/catpanel.mjs", "cat-code/version.txt")) {
            assertTrue(f, c.contains("out[\"$f\"]"))
        }
        assertTrue(File(root, "app/src/main/assets/" + NodeHostPanel.BUNDLED_WORKER_ASSET).exists())
        assertTrue(File(root, "app/src/main/assets/" + NodeHostPanel.BUNDLED_ADAPTER_ASSET).exists())
        val l = read("node-panel/index.js")
        assertTrue(l.contains("if (!hasCode(codeDir) && !writable(codeDir)) {"))
        assertTrue(read("app/src/main/assets/node-panel/smarterasp/server.js").contains("import * as cat from './index.js';"))
    }

    @Test
    fun patchPanelMatchesTheLauncher() {
        val src = "a;const s=await import(\"cloudflare:sockets\");b"
        assertEquals("a;const s=await Promise.resolve(globalThis.__catSockets);b", NodeHostPanel.patchPanel(src))
        assertEquals("x=Promise.resolve(globalThis.__catSockets)", NodeHostPanel.patchPanel("x=import('cloudflare:sockets')"))
        val two = runCatching { NodeHostPanel.patchPanel("import('cloudflare:sockets');import('cloudflare:sockets')") }
        assertTrue(two.isFailure)
        val bundle = read("app/src/main/assets/" + NodeHostPanel.BUNDLED_WORKER_ASSET)
        assertTrue(NodeHostPanel.patchPanel(bundle).contains("Promise.resolve(globalThis.__catSockets)"))
    }
}

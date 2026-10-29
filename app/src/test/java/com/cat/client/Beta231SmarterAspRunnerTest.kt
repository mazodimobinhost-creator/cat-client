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
    fun smarterAspZipCarriesTheBundledPanel() {
        val c = read("app/src/main/java/com/cat/client/NodeHostController.kt")
        for (f in listOf("catclient.worker.js", "cat-panel-adapter.mjs", "cat-node-panel.sha256", "version.txt")) {
            assertTrue(f, c.contains("out[\"cat-bundled/$f\"]"))
        }
        assertTrue(File(root, "app/src/main/assets/" + NodeHostPanel.BUNDLED_WORKER_ASSET).exists())
        assertTrue(File(root, "app/src/main/assets/" + NodeHostPanel.BUNDLED_ADAPTER_ASSET).exists())
    }

    @Test
    fun bundledSumsMatchSha256sumFormat() {
        val s = NodeHostPanel.bundledSums("abc".toByteArray(), ByteArray(0))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  catclient.worker.js\n" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  cat-panel-adapter.mjs\n",
            s,
        )
    }
}

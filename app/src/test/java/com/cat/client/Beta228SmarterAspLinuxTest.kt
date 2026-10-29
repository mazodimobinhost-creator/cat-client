package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta228 — SmarterASP's control panel offers two 60-day trials: «W60-US» (Windows/IIS) and «W60LX-US» (Linux).
 * The same zip runs on both (real-process proof: scripts/panels/smarterasp-linux.test.mjs); the codes end with the
 * location, so the country default is USA; after the panel answers, the app checks cat-config.json is not served.
 */
class Beta228SmarterAspLinuxTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "node-panel/index.js").exists() }
    private fun read(path: String) = File(root, path).readText()

    @Test
    fun usaIsTheTrialDefault() {
        assertEquals("usa", HostRegions.defaultRegion(NodeHostPanel.SMARTERASP))
        assertEquals("🇺🇸", HostRegions.flag(NodeHostPanel.SMARTERASP, null))
    }

    @Test
    fun guideNamesBothTrialsAndTheLeakCheckRuns() {
        val c = read("app/src/main/java/com/cat/client/NodeHostController.kt")
        assertTrue(c.contains("60-Day Free Linux Trial") && c.contains("W60LX-US") && c.contains("W60-US"))
        assertTrue(c.contains("Startup file = server.js") && c.contains("Health path = /health"))
        assertTrue(c.contains("out[\"server.js\"] = asset(NodeHostPanel.SMARTERASP_ASSETS + \"/server.js\")"))
        assertTrue(read("app/src/main/assets/node-panel/smarterasp/server.js").contains("import { main, autostarted } from './index.js';"))
        val verify = c.substringAfter("private suspend fun verify(").substringBefore("\n    }\n")
        assertTrue(verify.indexOf("NodeHostPanel.CONFIG_FILE") in 0 until verify.indexOf("\"/sub/\" + uuid"))
        assertTrue(verify.contains("+ exposedNote, true)"))
        val pkg = read("package.json")
        assertTrue(pkg.contains("scripts/panels/smarterasp-linux.test.mjs"))
    }
}

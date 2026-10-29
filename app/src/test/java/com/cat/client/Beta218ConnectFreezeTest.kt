package com.cat.client

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * beta218: «Connect» froze the whole screen. The VPN service runs its startup on the main thread (shared with
 * the activity), and the Cat Warp carrier of the WARP → Psiphon / WARP → Tor chains did its blocking native
 * prepare (endpoint scan + provisioning, seconds to minutes) right there. These contracts keep the blocking
 * work on IO threads.
 */
class Beta218ConnectFreezeTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    @Test
    fun carrierPrepareRunsOnIo() {
        val s = src("AetherProxyCarrier.kt")
        assertTrue(s.contains("withContext(Dispatchers.IO) { prepareBlocking(config, requestedMode, gen) }"))
        assertTrue(s.contains("withContext(Dispatchers.IO) { NativeAetherBridge.isLoaded }"))
        val start = s.indexOf("suspend fun start(")
        val helper = s.indexOf("private fun prepareBlocking(")
        assertTrue(start in 0 until helper)
        val startBody = s.substring(start, helper)
        // no blocking native call is left in start() itself
        assertTrue("prepare still in start()", !startBody.contains("NativeAetherBridge.prepare("))
        assertTrue("validate still in start()", !startBody.contains("NativeAetherBridge.validate("))
        val helperBody = s.substring(helper, s.indexOf("override fun close()"))
        assertTrue(helperBody.contains("AetherEngineSlot.prepareWhenFree { NativeAetherBridge.prepare(primaryJson) }"))
        assertTrue(helperBody.contains("NativeAetherBridge.validate(primaryJson)"))
        // a failed prepare must free the carrier for the next attempt
        assertTrue(Regex("""catch \(error: Throwable\) \{\s*active\.set\(false\)\s*throw error""").containsMatchIn(s))
    }

    @Test
    fun serviceStartupMovesFileAndNativeWorkOffMain() {
        val s = src("CatClientVpnService.kt")
        assertTrue(s.contains("withContext(Dispatchers.IO) { AetherRuntimeCapabilities.detect().nativeCoreLoaded }"))
        assertTrue(s.contains("withContext(Dispatchers.IO) { aetherPreferenceStore.applyWarmWarpKeyToFileIfNeeded(identityFile) }"))
        assertTrue(s.contains("withContext(Dispatchers.IO) { runCatching { SlipNetCliEngine.stopAll() } }"))
        val carriers = Regex("""withContext\(Dispatchers\.IO\) \{\s*MihomoRuntimeConfigBuilder\(this@CatClientVpnService\)\.writeCarrier\(""")
            .findAll(s).count()
        val allCarriers = Regex("""\.writeCarrier\(""").findAll(s).count()
        assertTrue("every carrier YAML write is on IO ($carriers/$allCarriers)", carriers == allCarriers && carriers >= 2)
    }
}

package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta285 — Cat GP home line is Google Script ± Worker, not «Cloudflare edge». */
class Beta285CatGpExitLineTest {

    @Test
    fun versionIsBeta285() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 370"))
        assertTrue(gradle.contains("1.10.0-beta303"))
    }

    @Test
    fun routeLineSaysWhetherCloudflareIsInThePath() {
        val hop = "https://raspy-sky-8be5.giciza3742.workers.dev/"
        val faHop = CatGpScripts.routeLine(hop, hopOn = true, fa = true)
        val enHop = CatGpScripts.routeLine(hop, hopOn = true, fa = false)
        assertTrue(faHop.contains("گوگل+کلادفلر"))
        assertTrue(enHop.contains("Google+Cloudflare"))
        assertTrue(CatGpScripts.routeLine("", hopOn = true, fa = true).contains("فقط گوگل اسکریپت"))
        assertTrue(CatGpScripts.routeLine(hop, hopOn = false, fa = false).contains("Google Script only"))
        assertTrue(CatGpEngine.isActiveTag("🐱 Cat · Auto · Cat GP · 🌐"))
        assertFalse(CatGpEngine.isActiveTag("🐱 Cat · Germany · Warp"))
        val ui = src("MainActivity.kt")
        assertTrue(ui.contains("CatGpEngine.routeLine"))
        assertFalse(ui.contains("route_edge_fallback) && catGp"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

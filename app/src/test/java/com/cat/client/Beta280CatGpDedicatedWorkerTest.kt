package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta280 — Cat GP Cloudflare Worker is only for Google Script, not the Cat panel. */
class Beta280CatGpDedicatedWorkerTest {

    @Test
    fun versionIsBeta280() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 366"))
        assertTrue(gradle.contains("1.10.0-beta299"))
    }

    @Test
    fun dedicatedWorkerIsNotTheCatPanel() {
        val worker = listOf(File("app/src/main/assets/catgp/worker.js"), File("src/main/assets/catgp/worker.js"))
            .first { it.isFile }.readText()
        assertTrue(worker.contains("var CAT_KEY = __CAT_KEY__"))
        assertTrue(worker.contains("گوگل اسکریپت و کلادفلر به هم متصل شدند"))
        assertTrue(worker.contains("Google Script and Cloudflare are connected"))
        assertTrue(worker.contains("not the Cat panel"))
        assertTrue(worker.contains("relay: 'cat-gp-cf'"))
        assertFalse(worker.contains("/panel/"))
        assertFalse(worker.contains("/*"))
        assertEquals(1, Regex("__CAT_KEY__").findAll(worker).count())
        val ui = src("MainActivityCatGp.kt")
        assertTrue(ui.contains("workerJs"))
        assertTrue(ui.contains("کپی کردن کد کلادفلر"))
        assertTrue(ui.contains("گوگل اسکریپت و کلادفلر به هم متصل شدند"))
        assertTrue(ui.contains("نه پنل کَت"))
        assertFalse(ui.contains("PanelDeploymentStore"))
        assertTrue(src("CatGpEngine.kt").contains("fun workerJs"))
        assertTrue(CatGpScripts.pairLinked(
            "https://script.google.com/macros/s/AKfycbxxxxxxxxxxxxxxxxxxxx/exec",
            "https://cat-gp.example.workers.dev",
        ))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

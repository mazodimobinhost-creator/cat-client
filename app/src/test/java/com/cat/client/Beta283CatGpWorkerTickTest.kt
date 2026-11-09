package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta283 — ticks read the workers.dev box, including account-subdomain hosts. */
class Beta283CatGpWorkerTickTest {

    @Test
    fun versionIsBeta283() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 364"))
        assertTrue(gradle.contains("1.10.0-beta297"))
    }

    @Test
    fun accountSubdomainWorkersDevCountsAsPasted() {
        val url = "https://raspy-sky-8be5.giciza3742.workers.dev/"
        assertEquals("https://raspy-sky-8be5.giciza3742.workers.dev", CatGpScripts.dedicatedWorkerUrl(url))
        assertEquals(
            "https://raspy-sky-8be5.giciza3742.workers.dev",
            CatGpScripts.normalizeHop(url, "jQfRqEzezmul3uxVrMF2Boef"),
        )
        assertTrue(
            CatGpScripts.pairLinked(
                "https://script.google.com/macros/s/AKfycbxxxxxxxxxxxxxxxxxxxx/exec",
                url,
            ),
        )
        assertNotNull(CatGpScripts.dedicatedWorkerUrl("raspy-sky-8be5.giciza3742.workers.dev"))
        val ui = src("MainActivityCatGp.kt")
        assertTrue(ui.contains("fun typedHop()"))
        assertTrue(ui.contains("dedicatedWorkerUrl(worker)"))
        assertTrue(ui.contains("hopField.addTextChangedListener"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

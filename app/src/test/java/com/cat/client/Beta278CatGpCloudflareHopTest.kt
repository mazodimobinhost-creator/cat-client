package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta278 — Cat GP Google script talks to the user's Cloudflare panel; the phone does not have to reach CF. */
class Beta278CatGpCloudflareHopTest {

    @Test
    fun versionIsBeta278() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 356"))
        assertTrue(gradle.contains("1.10.0-beta289"))
    }

    @Test
    fun hubBakesHopWithoutPhoningCloudflare() {
        val ui = src("MainActivityCatGp.kt")
        assertFalse(ui.contains("PanelDeploymentStore"))
        assertTrue(ui.contains("normalizeHop"))
        assertTrue(ui.contains("خروج کلادفلر"))
        assertTrue(ui.contains("hopWanted"))
        assertTrue(ui.contains("workers.dev"))
        assertFalse(ui.contains("FreeNetHttp"))
        assertFalse(ui.contains("cat-gp-hop"))
        assertFalse(ui.contains("View.GONE"))
        assertFalse(ui.contains("fakePlus"))
        val engine = src("CatGpEngine.kt")
        assertTrue(engine.contains("hopOn"))
        assertTrue(src("CatGpRelay.kt").contains("fun normalizeHop"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

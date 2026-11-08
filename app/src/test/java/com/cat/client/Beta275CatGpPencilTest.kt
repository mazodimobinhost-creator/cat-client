package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta275 — pencil or long-press on Cat GP edits Google edge IP + SNI. */
class Beta275CatGpPencilTest {

    @Test
    fun versionIsBeta275() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 345"))
        assertTrue(gradle.contains("1.10.0-beta278"))
    }

    @Test
    fun pencilAndLongPressOpenEdgeEditor() {
        val hub = src("MainActivityFreeNetworks.kt")
        assertTrue(hub.contains("showCatGpEdgeDialog()"))
        assertTrue(hub.contains("pencil = { showCatGpEdgeDialog() }"))
        assertTrue(hub.contains("setOnLongClickListener"))
        assertTrue(hub.contains("✏️"))
        val cards = src("MainActivitySubscriptions.kt")
        assertTrue(cards.contains("showCatGpEdgeDialog()"))
        assertTrue(cards.contains("CatGpEngine.SUB_NAME"))
        assertTrue(cards.contains("isLongClickable = true"))
        val ui = src("MainActivityCatGp.kt")
        assertTrue(ui.contains("internal fun MainActivity.showCatGpEdgeDialog()"))
        assertTrue(ui.contains("setOnLongClickListener { applyHit"))
        assertFalse("no other-app name", ui.contains("GUTS") || hub.contains("GUTS"))
        val edit = src("MainActivityCatWarp.kt")
        assertTrue(edit.contains("item.name == CatGpEngine.SUB_NAME"))
        assertTrue(edit.contains("showCatGpDialog()"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

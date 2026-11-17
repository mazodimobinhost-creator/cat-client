package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navy / sky-blue tokens from the Cat design file, on app + panel. */
class CatBlueDesignTokensTest {

    @Test
    fun versionIsBeta324() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 391"))
        assertTrue(gradle.contains("1.10.0-beta324"))
    }

    @Test
    fun resourceColorsAreSkyNavy() {
        val light = File("app/src/main/res/values/colors.xml").readText()
        val night = File("app/src/main/res/values-night/colors.xml").readText()
        assertTrue(light.contains("<color name=\"catclient_primary\">#0284C7</color>"))
        assertTrue(light.contains("<color name=\"catclient_background\">#F0F7FF</color>"))
        assertTrue(night.contains("<color name=\"catclient_primary\">#38BDF8</color>"))
        assertTrue(night.contains("<color name=\"catclient_background\">#080F24</color>"))
    }

    @Test
    fun defaultAccentAndGlobeUseSky() {
        val accent = src("AppAccent.kt")
        assertTrue(accent.contains("0xFF38BDF8.toInt()"))
        assertTrue(accent.contains("0xFF0284C7.toInt()"))
        val globe = src("GlobeDrawable.kt")
        assertTrue(globe.contains("0xFF38BDF8.toInt()"))
        assertTrue(globe.contains("0xFF0284C7.toInt()"))
        val tokens = src("DashboardViews.kt")
        assertTrue(tokens.contains("background = 0xFF080F24.toInt()"))
        assertTrue(tokens.contains("background = 0xFFF0F7FF.toInt()"))
        assertTrue(tokens.contains("teal = 0xFF38BDF8.toInt()"))
        assertTrue(tokens.contains("teal = 0xFF0284C7.toInt()"))
        val blob = src("ZedBlobView.kt")
        assertTrue(blob.contains("const val ZED_VIOLET = 0xFF38BDF8.toInt()"))
        assertTrue(blob.contains("const val ZED_DEEP_VIOLET = 0xFF075985.toInt()"))
    }

    @Test
    fun panelCssFollowsTheSamePalette() {
        val panel = File("cat-panel.html").readText()
        val worker = File("app/src/main/assets/panels/catclient.worker.js").readText()
        for (html in listOf(panel, worker)) {
            assertTrue(html.contains("--bg:#080F24"))
            assertTrue(html.contains("--violet:#38BDF8"))
            assertTrue(html.contains("[data-theme=clean]{"))
            assertTrue(html.contains("--bg:#F0F7FF"))
            assertTrue(html.contains("--violet:#0284C7"))
            assertTrue(html.contains("stop-color=\"#38BDF8\""))
        }
        assertEquals(AppAccent.Lavender, AppAccent.fromWireName(null))
        assertEquals(0xFF38BDF8.toInt(), AppAccent.Lavender.dark)
        assertEquals(0xFF0284C7.toInt(), AppAccent.Lavender.light)
    }

    private fun src(name: String): String =
        File("app/src/main/java/com/cat/client/$name").readText()
}

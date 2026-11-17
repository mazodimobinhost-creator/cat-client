package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navy / sky-blue tokens from the Cat design file. JVM tests run after CI may rewrite the worker. */
class CatBlueDesignTokensTest {

    @Test
    fun versionIsBeta324() {
        val gradle = file("app/build.gradle.kts", "build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 391"))
        assertTrue(gradle.contains("1.10.0-beta324"))
    }

    @Test
    fun resourceColorsAreSkyNavy() {
        val light = file("app/src/main/res/values/colors.xml", "src/main/res/values/colors.xml").readText()
        val night = file("app/src/main/res/values-night/colors.xml", "src/main/res/values-night/colors.xml").readText()
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
        assertEquals(AppAccent.Lavender, AppAccent.fromWireName(null))
        assertEquals(0xFF38BDF8.toInt(), AppAccent.Lavender.dark)
        assertEquals(0xFF0284C7.toInt(), AppAccent.Lavender.light)
    }

    private fun src(name: String): String =
        file("app/src/main/java/com/cat/client/$name", "src/main/java/com/cat/client/$name").readText()

    private fun file(vararg paths: String): File =
        paths.map(::File).first { it.isFile }
}

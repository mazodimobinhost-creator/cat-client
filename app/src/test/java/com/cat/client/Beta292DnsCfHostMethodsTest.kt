package com.cat.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Beta292DnsCfHostMethodsTest {
    private fun src(name: String): String =
        listOf("src/main/java/com/cat/client/$name", "app/src/main/java/com/cat/client/$name")
            .map(::File).first { it.isFile }.readText()

    @Test
    fun versionIsBeta292() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 373"))
        assertTrue(gradle.contains("1.10.0-beta306"))
    }

    @Test
    fun finderMapsDnsCloudflareVpsHostAndFreeVpns() {
        val guides = src("MainActivityGuides.kt")
        assertTrue(guides.contains("fun MainActivity.showDnsCfHostFreeMethodsDialog"))
        assertTrue(guides.contains("addFreeSlipNet()"))
        assertTrue(guides.contains("showPanelWizard()"))
        assertTrue(guides.contains("showCatWarpFreeDialog()"))
        assertTrue(guides.contains("FinderFamily.FREE_SERVERS"))
        assertTrue(guides.contains("وی پی اس"))
        assertFalse(guides.contains("188.114.98.0"))
        assertTrue(guides.contains("188.114.98.224"))
        assertTrue(guides.contains("showDnsCfHostFreeMethodsDialog()"))
    }
}

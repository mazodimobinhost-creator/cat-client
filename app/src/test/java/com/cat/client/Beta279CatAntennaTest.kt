package com.cat.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta279 — Cat ping antenna (own bars, real delay only). */
class Beta279CatAntennaTest {

    @Test
    fun versionIsBeta279() {
        val gradle = listOf(File("app/build.gradle.kts"), File("build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(gradle.contains("versionCode = 370"))
        assertTrue(gradle.contains("1.10.0-beta303"))
    }

    @Test
    fun barsFollowRealDelayNeverInvented() {
        assertEquals(0, CatAntenna.bars(null))
        assertEquals(0, CatAntenna.bars(0))
        assertEquals(0, CatAntenna.bars(40, failed = true))
        assertEquals(4, CatAntenna.bars(50))
        assertEquals(3, CatAntenna.bars(200))
        assertEquals(2, CatAntenna.bars(350))
        assertEquals(1, CatAntenna.bars(900))
        assertTrue(CatAntenna.spoken(50, fa = true).contains("آنتن"))
        assertFalse(CatAntenna.spoken(50, fa = false).contains("Omni"))
    }

    @Test
    fun antennaIsOnServerRowsAndHomePing() {
        val rows = src("MainActivityConnectionTesting.kt")
        assertTrue(rows.contains("CatAntennaView"))
        assertTrue(rows.contains("holder.antenna.bind"))
        val home = src("MainActivityDashboard.kt")
        assertTrue(home.contains("homeAntenna"))
        assertTrue(home.contains("CatAntennaView"))
        assertFalse(home.contains("GUTS"))
        assertFalse(rows.contains("GUTS"))
        assertFalse(src("CatAntenna.kt").contains("Omni"))
    }

    private fun src(name: String): String = listOf(
        File("src/main/java/com/cat/client/$name"),
        File("app/src/main/java/com/cat/client/$name"),
    ).first { it.isFile }.readText()
}

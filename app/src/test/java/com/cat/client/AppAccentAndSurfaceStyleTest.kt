package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAccentAndSurfaceStyleTest {

    @Test
    fun `AppAccent has 16 presets and resolves from wireName`() {
        assertEquals(16, AppAccent.entries.size)
        val expectedWireNames = listOf(
            "lavender",
            "aurora",
            "ember",
            "midnight",
            "lime",
            "pink",
            "amber",
            "mono",
            "turquoise",
            "cyberpunk",
            "royal_gold",
            "amoled_neon",
            "arctic_nord",
            "crimson_ruby",
            "emerald_matrix",
            "dracula_pro",
        )
        assertEquals(expectedWireNames, AppAccent.entries.map { it.wireName })
        AppAccent.entries.forEach { preset ->
            assertEquals(preset, AppAccent.fromWireName(preset.wireName))
            assertTrue(preset.tintStrength in 0.05f..0.40f)
            assertTrue(preset.overlayStyleRes != 0)
        }
        assertEquals(AppAccent.Lavender, AppAccent.fromWireName("unknown_preset"))
        assertEquals(AppAccent.Lavender, AppAccent.fromWireName(null))
    }

    @Test
    fun `AppSurfaceStyle resolves glass amoled and neon`() {
        assertEquals(3, AppSurfaceStyle.entries.size)
        assertEquals(AppSurfaceStyle.Glass, AppSurfaceStyle.fromWireName("glass"))
        assertEquals(AppSurfaceStyle.Amoled, AppSurfaceStyle.fromWireName("amoled"))
        assertEquals(AppSurfaceStyle.Neon, AppSurfaceStyle.fromWireName("neon"))
        assertEquals(AppSurfaceStyle.Glass, AppSurfaceStyle.fromWireName("unknown"))
        assertEquals(AppSurfaceStyle.Glass, AppSurfaceStyle.fromWireName(null))
    }

    @Test
    fun `HeroVisualMode resolves blob globe and shield`() {
        assertEquals(3, HeroVisualMode.entries.size)
        assertEquals(HeroVisualMode.Blob, HeroVisualMode.fromWireName("blob"))
        assertEquals(HeroVisualMode.Globe, HeroVisualMode.fromWireName("globe"))
        assertEquals(HeroVisualMode.Shield, HeroVisualMode.fromWireName("shield"))
        assertEquals(HeroVisualMode.Blob, HeroVisualMode.fromWireName("other"))
        assertEquals(HeroVisualMode.Blob, HeroVisualMode.fromWireName(null))
    }
}

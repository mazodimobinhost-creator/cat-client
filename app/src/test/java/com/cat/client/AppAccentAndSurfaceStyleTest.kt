package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAccentAndSurfaceStyleTest {

    @Test
    fun `AppAccent has 16 presets and resolves from rawValue`() {
        assertEquals(16, AppAccent.entries.size)
        val expectedRawValues = listOf(
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
        assertEquals(expectedRawValues, AppAccent.entries.map { it.rawValue })
        AppAccent.entries.forEach { preset ->
            assertEquals(preset, AppAccent.fromRawValue(preset.rawValue))
        }
        assertEquals(AppAccent.Lavender, AppAccent.fromRawValue("unknown_preset"))
        assertEquals(AppAccent.Lavender, AppAccent.fromRawValue(null))
    }

    @Test
    fun `AppSurfaceStyle resolves glass amoled and neon`() {
        assertEquals(3, AppSurfaceStyle.entries.size)
        assertEquals(AppSurfaceStyle.Glass, AppSurfaceStyle.fromRawValue("glass"))
        assertEquals(AppSurfaceStyle.Amoled, AppSurfaceStyle.fromRawValue("amoled"))
        assertEquals(AppSurfaceStyle.Neon, AppSurfaceStyle.fromRawValue("neon"))
        assertEquals(AppSurfaceStyle.Glass, AppSurfaceStyle.fromRawValue("unknown"))
        assertEquals(AppSurfaceStyle.Glass, AppSurfaceStyle.fromRawValue(null))
    }

    @Test
    fun `HeroVisualMode resolves blob globe and shield`() {
        assertEquals(3, HeroVisualMode.entries.size)
        assertEquals(HeroVisualMode.Blob, HeroVisualMode.fromRawValue("blob"))
        assertEquals(HeroVisualMode.Globe, HeroVisualMode.fromRawValue("globe"))
        assertEquals(HeroVisualMode.Shield, HeroVisualMode.fromRawValue("shield"))
        assertEquals(HeroVisualMode.Blob, HeroVisualMode.fromRawValue("other"))
        assertEquals(HeroVisualMode.Blob, HeroVisualMode.fromRawValue(null))
    }

    @Test
    fun `CatClientDesignTokens applies surface style and 16 accents`() {
        val baseDark = CatClientDesignTokens.palette(darkTheme = true)
        val baseLight = CatClientDesignTokens.palette(darkTheme = false)

        AppAccent.entries.forEach { accent ->
            val darkGlass = CatClientDesignTokens.themed(baseDark, accent, true, AppSurfaceStyle.Glass)
            val lightGlass = CatClientDesignTokens.themed(baseLight, accent, false, AppSurfaceStyle.Glass)
            assertEquals(accent.dark, darkGlass.teal)
            assertEquals(accent.light, lightGlass.teal)
            assertTrue(darkGlass.isDark)
            assertTrue(!lightGlass.isDark)
        }

        val darkAmoled = CatClientDesignTokens.themed(baseDark, AppAccent.Turquoise, true, AppSurfaceStyle.Amoled)
        assertEquals(0xFF000000.toInt(), darkAmoled.backgroundTop)
        assertEquals(0xFF000000.toInt(), darkAmoled.backgroundBottom)
    }
}

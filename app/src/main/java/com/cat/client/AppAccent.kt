package com.cat.client

import android.content.Context
import androidx.annotation.StringRes

/**
 * Accent/surface preset — mirrors ZedSecure's `AppTheme` (Color.kt + Theme.kt) plus 8 extended skins:
 * each preset carries both dark and light primaries, on-primary text, secondary accent, and surface tint
 * strength so the entire background/card stack shifts hue when the user picks a theme in Settings.
 */
enum class AppAccent(
    val wireName: String,
    @StringRes val labelRes: Int,
    val dark: Int,
    val onDark: Int,
    val light: Int,
    val onLight: Int,
    val secondaryDark: Int,
    val secondaryLight: Int,
    val surfaceTintDark: Int,
    val surfaceTintLight: Int,
    val tintStrength: Float,
) {
    Lavender("lavender", R.string.accent_lavender, 0xFFC7BFFF.toInt(), 0xFF2A0A93.toInt(), 0xFF5646D6.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF9BC4.toInt(), 0xFFC4326B.toInt(), 0xFF2A1361.toInt(), 0xFFE5DEFF.toInt(), 0.10f),
    Aurora("aurora", R.string.accent_aurora, 0xFF14B8A6.toInt(), 0xFF003731.toInt(), 0xFF00897E.toInt(), 0xFFFFFFFF.toInt(), 0xFFC7BFFF.toInt(), 0xFF5646D6.toInt(), 0xFF07202B.toInt(), 0xFFD6F5F0.toInt(), 0.22f),
    Ember("ember", R.string.accent_ember, 0xFFE8761F.toInt(), 0xFF2E1500.toInt(), 0xFF9A4A00.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFB4AB.toInt(), 0xFFB3261E.toInt(), 0xFF2A1407.toInt(), 0xFFFFE8D6.toInt(), 0.22f),
    Midnight("midnight", R.string.accent_midnight, 0xFF2F6BFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF1D4ED8.toInt(), 0xFFFFFFFF.toInt(), 0xFF9CCBFF.toInt(), 0xFF0A84FF.toInt(), 0xFF0A1226.toInt(), 0xFFDCE6FF.toInt(), 0.24f),
    Lime("lime", R.string.accent_lime, 0xFFC7F24E.toInt(), 0xFF1A2200.toInt(), 0xFF5B7A00.toInt(), 0xFFFFFFFF.toInt(), 0xFF37E0D8.toInt(), 0xFF00897E.toInt(), 0xFF151A0A.toInt(), 0xFFEFF8D6.toInt(), 0.12f),
    Pink("pink", R.string.accent_theme_pink, 0xFFFFB0C8.toInt(), 0xFF5E1136.toInt(), 0xFFC4326B.toInt(), 0xFFFFFFFF.toInt(), 0xFFC7BFFF.toInt(), 0xFF5646D6.toInt(), 0xFF2B0E1C.toInt(), 0xFFFFE0EA.toInt(), 0.14f),
    Amber("amber", R.string.accent_amber, 0xFFFFC46B.toInt(), 0xFF422C00.toInt(), 0xFF9A5B00.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF9BC4.toInt(), 0xFFC4326B.toInt(), 0xFF241A08.toInt(), 0xFFFFF0D6.toInt(), 0.16f),
    Mono("mono", R.string.accent_mono, 0xFFE5E1E9.toInt(), 0xFF1C1B20.toInt(), 0xFF1C1B20.toInt(), 0xFFFFFFFF.toInt(), 0xFFB0ADB8.toInt(), 0xFF5C5A66.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.30f),
    Turquoise("turquoise", R.string.accent_turquoise, 0xFF2DD4BF.toInt(), 0xFF003831.toInt(), 0xFF0D9488.toInt(), 0xFFFFFFFF.toInt(), 0xFF38BDF8.toInt(), 0xFF0284C7.toInt(), 0xFF06282D.toInt(), 0xFFCCFBF1.toInt(), 0.22f),
    Cyberpunk("cyberpunk", R.string.accent_cyberpunk, 0xFFF43F5E.toInt(), 0xFFFFFFFF.toInt(), 0xFFE11D48.toInt(), 0xFFFFFFFF.toInt(), 0xFF00F5D4.toInt(), 0xFF0D9488.toInt(), 0xFF1F0933.toInt(), 0xFFFFE4E6.toInt(), 0.24f),
    RoyalGold("royal_gold", R.string.accent_royal_gold, 0xFFFBBF24.toInt(), 0xFF2D1B00.toInt(), 0xFFB45309.toInt(), 0xFFFFFFFF.toInt(), 0xFFFDE047.toInt(), 0xFFD97706.toInt(), 0xFF231B0A.toInt(), 0xFFFEF3C7.toInt(), 0.20f),
    AmoledNeon("amoled_neon", R.string.accent_amoled_neon, 0xFF00FF9D.toInt(), 0xFF002917.toInt(), 0xFF059669.toInt(), 0xFFFFFFFF.toInt(), 0xFF38BDF8.toInt(), 0xFF0284C7.toInt(), 0xFF000000.toInt(), 0xFFD1FAE5.toInt(), 0.22f),
    ArcticNord("arctic_nord", R.string.accent_arctic_nord, 0xFF7DD3FC.toInt(), 0xFF082F49.toInt(), 0xFF0369A1.toInt(), 0xFFFFFFFF.toInt(), 0xFF818CF8.toInt(), 0xFF4F46E5.toInt(), 0xFF0C192C.toInt(), 0xFFE0F2FE.toInt(), 0.22f),
    CrimsonRuby("crimson_ruby", R.string.accent_crimson_ruby, 0xFFFB7185.toInt(), 0xFF4C0519.toInt(), 0xFFBE123C.toInt(), 0xFFFFFFFF.toInt(), 0xFFFDA4AF.toInt(), 0xFFE11D48.toInt(), 0xFF2A0912.toInt(), 0xFFFFE4E6.toInt(), 0.22f),
    EmeraldMatrix("emerald_matrix", R.string.accent_emerald_matrix, 0xFF34D399.toInt(), 0xFF022C22.toInt(), 0xFF047857.toInt(), 0xFFFFFFFF.toInt(), 0xFFA7F3D0.toInt(), 0xFF10B981.toInt(), 0xFF052418.toInt(), 0xFFD1FAE5.toInt(), 0.22f),
    DraculaPro("dracula_pro", R.string.accent_dracula_pro, 0xFFBD93F9.toInt(), 0xFF230F42.toInt(), 0xFF6D28D9.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF79C6.toInt(), 0xFFDB2777.toInt(), 0xFF1D172B.toInt(), 0xFFEDE9FE.toInt(), 0.22f),
    ;

    companion object {
        fun fromWireName(value: String?): AppAccent =
            entries.firstOrNull { it.wireName == value } ?: Lavender
    }

    /** XML theme overlay so framework/Material widgets (dialogs, switches, inputs, ripples) follow the preset too. */
    val overlayStyleRes: Int
        get() = when (this) {
            Lavender -> R.style.ThemeOverlay_CatClient_Lavender
            Aurora -> R.style.ThemeOverlay_CatClient_Aurora
            Ember -> R.style.ThemeOverlay_CatClient_Ember
            Midnight -> R.style.ThemeOverlay_CatClient_Midnight
            Lime -> R.style.ThemeOverlay_CatClient_Lime
            Pink -> R.style.ThemeOverlay_CatClient_Pink
            Amber -> R.style.ThemeOverlay_CatClient_Amber
            Mono -> R.style.ThemeOverlay_CatClient_Mono
            Turquoise -> R.style.ThemeOverlay_CatClient_Turquoise
            Cyberpunk -> R.style.ThemeOverlay_CatClient_Cyberpunk
            RoyalGold -> R.style.ThemeOverlay_CatClient_RoyalGold
            AmoledNeon -> R.style.ThemeOverlay_CatClient_AmoledNeon
            ArcticNord -> R.style.ThemeOverlay_CatClient_ArcticNord
            CrimsonRuby -> R.style.ThemeOverlay_CatClient_CrimsonRuby
            EmeraldMatrix -> R.style.ThemeOverlay_CatClient_EmeraldMatrix
            DraculaPro -> R.style.ThemeOverlay_CatClient_DraculaPro
        }
}

class AppAccentPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_theme", Context.MODE_PRIVATE)

    fun read(): AppAccent = AppAccent.fromWireName(prefs.getString("accent", null))

    fun save(accent: AppAccent) {
        prefs.edit().putString("accent", accent.wireName).apply()
    }
}

/**
 * Surface skin style (ZedSecure Frosted Glass vs. True AMOLED Black vs. Cyber Neon Glow).
 */
enum class AppSurfaceStyle(val wireName: String, @StringRes val labelRes: Int) {
    Glass("glass", R.string.surface_style_glass),
    Amoled("amoled", R.string.surface_style_amoled),
    Neon("neon", R.string.surface_style_neon),
    ;

    companion object {
        fun fromWireName(value: String?): AppSurfaceStyle =
            entries.firstOrNull { it.wireName == value } ?: Glass
    }
}

class AppSurfaceStylePreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_theme", Context.MODE_PRIVATE)

    fun read(): AppSurfaceStyle = AppSurfaceStyle.fromWireName(prefs.getString("surface_style", null))

    fun save(style: AppSurfaceStyle) {
        prefs.edit().putString("surface_style", style.wireName).apply()
    }
}

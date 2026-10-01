package com.cat.client

import android.content.Context
import androidx.annotation.StringRes

/**
 * Theme presets (ZedSecure "Theme presets"): a ready-made colour set that re-tints the whole app —
 * primary accent, secondary accent, and the hue the dark/light tonal surfaces lean toward.
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
    ;

    companion object {
        fun fromWireName(value: String?): AppAccent = entries.firstOrNull { it.wireName == value } ?: Lavender
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
        }
}

class AppAccentPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_theme", Context.MODE_PRIVATE)

    fun read(): AppAccent = AppAccent.fromWireName(prefs.getString("accent", null))

    fun save(accent: AppAccent) {
        prefs.edit().putString("accent", accent.wireName).apply()
    }
}

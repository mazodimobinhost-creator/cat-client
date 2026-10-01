package com.cat.client

import android.content.Context
import androidx.annotation.StringRes

/**
 * Accent colour themes (ZedSecure "Appearance → colour"). Each entry carries the dark-mode and
 * light-mode accent plus the matching "on accent" text colour; the rest of the palette is shared.
 */
enum class AppAccent(
    val wireName: String,
    @StringRes val labelRes: Int,
    val dark: Int,
    val onDark: Int,
    val light: Int,
    val onLight: Int,
) {
    Lavender("lavender", R.string.accent_lavender, 0xFFC7BFFF.toInt(), 0xFF2A0A93.toInt(), 0xFF5646D6.toInt(), 0xFFFFFFFF.toInt()),
    Lime("lime", R.string.accent_lime, 0xFFC7F24E.toInt(), 0xFF1A2200.toInt(), 0xFF5B7A00.toInt(), 0xFFFFFFFF.toInt()),
    Cyan("cyan", R.string.accent_cyan, 0xFF52DDCF.toInt(), 0xFF00382F.toInt(), 0xFF00897E.toInt(), 0xFFFFFFFF.toInt()),
    Pink("pink", R.string.accent_pink, 0xFFFFB0C8.toInt(), 0xFF5E1136.toInt(), 0xFFC4326B.toInt(), 0xFFFFFFFF.toInt()),
    Amber("amber", R.string.accent_amber, 0xFFFFC46B.toInt(), 0xFF422C00.toInt(), 0xFF9A5B00.toInt(), 0xFFFFFFFF.toInt()),
    Sky("sky", R.string.accent_sky, 0xFF9CCBFF.toInt(), 0xFF003355.toInt(), 0xFF1D6FD1.toInt(), 0xFFFFFFFF.toInt()),
    ;

    companion object {
        fun fromWireName(value: String?): AppAccent = entries.firstOrNull { it.wireName == value } ?: Lavender
    }
}

class AppAccentPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_theme", Context.MODE_PRIVATE)

    fun read(): AppAccent = AppAccent.fromWireName(prefs.getString("accent", null))

    fun save(accent: AppAccent) {
        prefs.edit().putString("accent", accent.wireName).apply()
    }
}

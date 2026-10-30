package com.cat.client

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils

/**
 * App design (ZedSecure 3.1.6 "App design"): the look of the whole app — home, shapes and motion.
 * Colours stay separate (theme mode, accent, colour style, contrast).
 */
enum class AppDesign(val wireName: String, @StringRes val labelRes: Int) {
    Pixel("pixel", R.string.design_pixel),
    Portal("portal", R.string.design_portal),
    Old("old", R.string.design_old),
    ;

    companion object {
        fun fromWireName(value: String?): AppDesign = entries.firstOrNull { it.wireName == value } ?: Portal
    }
}

/** How a full palette is generated from the accent (ZedSecure "Color style"). */
enum class ColorStyle(val wireName: String, @StringRes val labelRes: Int) {
    Cat("cat", R.string.color_style_cat),
    TonalSpot("tonal", R.string.color_style_tonal),
    Vibrant("vibrant", R.string.color_style_vibrant),
    Expressive("expressive", R.string.color_style_expressive),
    Rainbow("rainbow", R.string.color_style_rainbow),
    FruitSalad("fruit_salad", R.string.color_style_fruit_salad),
    Fidelity("fidelity", R.string.color_style_fidelity),
    Neutral("neutral", R.string.color_style_neutral),
    Monochrome("monochrome", R.string.color_style_monochrome),
    ;

    companion object {
        fun fromWireName(value: String?): ColorStyle = entries.firstOrNull { it.wireName == value } ?: Cat
    }
}

enum class ColorContrast(val wireName: String, @StringRes val labelRes: Int) {
    Standard("standard", R.string.contrast_standard),
    Medium("medium", R.string.contrast_medium),
    High("high", R.string.contrast_high),
    ;

    companion object {
        fun fromWireName(value: String?): ColorContrast = entries.firstOrNull { it.wireName == value } ?: Standard
    }
}

class AppDesignPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_theme", Context.MODE_PRIVATE)

    fun design(): AppDesign = AppDesign.fromWireName(prefs.getString(KEY_DESIGN, null))
    fun saveDesign(design: AppDesign) = prefs.edit().putString(KEY_DESIGN, design.wireName).apply()

    fun colorStyle(): ColorStyle = ColorStyle.fromWireName(prefs.getString(KEY_STYLE, null))
    fun saveColorStyle(style: ColorStyle) = prefs.edit().putString(KEY_STYLE, style.wireName).apply()

    fun contrast(): ColorContrast = ColorContrast.fromWireName(prefs.getString(KEY_CONTRAST, null))
    fun saveContrast(contrast: ColorContrast) = prefs.edit().putString(KEY_CONTRAST, contrast.wireName).apply()

    /** Wallpaper colours (Android 12+). Off by default. */
    fun dynamicColor(): Boolean = prefs.getBoolean(KEY_DYNAMIC, false) && dynamicColorAvailable()
    fun saveDynamicColor(enabled: Boolean) = prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()

    private companion object {
        const val KEY_DESIGN = "app_design"
        const val KEY_STYLE = "color_style"
        const val KEY_CONTRAST = "color_contrast"
        const val KEY_DYNAMIC = "dynamic_color"
    }
}

internal fun dynamicColorAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Accent roles before they are spread over the surfaces. */
internal data class AccentRoles(
    val tone: Int,
    val onTone: Int,
    val secondary: Int,
    val tint: Int,
    val tintStrength: Float,
)

/**
 * Colour-style generator. Not Google's HCT engine (that API is restricted in Material Components),
 * but the same idea in HSL: each style decides how saturated the primary is, where the tertiary
 * hue sits and how strongly the surfaces are tinted.
 */
internal object CatColorStyles {
    private fun hsl(color: Int): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
    private fun color(h: Float, s: Float, l: Float): Int =
        ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))

    fun onToneFor(tone: Int, night: Boolean): Int {
        val (h, s, l) = hsl(tone).let { Triple(it[0], it[1], it[2]) }
        return if (night) {
            if (l < 0.55f) 0xFFFFFFFF.toInt() else color(h, s.coerceAtMost(0.85f), 0.17f)
        } else {
            if (l > 0.62f) color(h, s.coerceAtMost(0.85f), 0.14f) else 0xFFFFFFFF.toInt()
        }
    }

    fun transform(base: AccentRoles, style: ColorStyle, night: Boolean): AccentRoles {
        if (style == ColorStyle.Cat) return base
        val src = hsl(base.tone)
        val h = src[0]
        val s = src[1]
        val lp = if (night) 0.83f else 0.43f
        val ls = if (night) 0.80f else 0.46f
        val lt = if (night) 0.13f else 0.93f
        val roles = when (style) {
            ColorStyle.TonalSpot -> AccentRoles(
                color(h, s.coerceIn(0.28f, 0.45f), lp), 0, color(h + 60f, 0.32f, ls), color(h, 0.32f, lt), 0.14f,
            )
            ColorStyle.Vibrant -> AccentRoles(
                color(h, s.coerceAtLeast(0.88f), lp - 0.03f), 0, color(h + 40f, 0.86f, ls), color(h, 0.55f, lt), 0.20f,
            )
            ColorStyle.Expressive -> AccentRoles(
                color(h + 240f, s.coerceIn(0.5f, 0.8f), lp), 0, color(h, 0.72f, ls), color(h + 15f, 0.36f, lt), 0.18f,
            )
            ColorStyle.Rainbow -> AccentRoles(
                color(h, 0.72f, lp), 0, color(h + 60f, 0.62f, ls), color(h, 0f, lt), 0f,
            )
            ColorStyle.FruitSalad -> AccentRoles(
                color(h - 50f, 0.72f, lp), 0, color(h, 0.70f, ls), color(h - 50f, 0.34f, lt), 0.16f,
            )
            ColorStyle.Fidelity -> AccentRoles(
                base.tone, 0, color(h + 60f, (s * 0.7f).coerceAtLeast(0.3f), ls), color(h, (s * 0.5f).coerceAtMost(0.5f), lt), 0.18f,
            )
            ColorStyle.Neutral -> AccentRoles(
                color(h, 0.14f, lp), 0, color(h + 60f, 0.12f, ls), color(h, 0.06f, lt), 0.08f,
            )
            ColorStyle.Monochrome -> AccentRoles(
                color(h, 0f, if (night) 0.90f else 0.18f), 0, color(h, 0f, if (night) 0.68f else 0.42f), color(h, 0f, lt), 0f,
            )
            ColorStyle.Cat -> base
        }
        return roles.copy(onTone = onToneFor(roles.tone, night))
    }

    /** Wallpaper colours from Android 12+ system palettes. */
    fun dynamicRoles(context: Context, night: Boolean): AccentRoles? {
        if (!dynamicColorAvailable()) return null
        return runCatching {
            val tone = context.getColor(if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            val secondary = context.getColor(if (night) android.R.color.system_accent3_200 else android.R.color.system_accent3_600)
            val tint = context.getColor(if (night) android.R.color.system_neutral2_900 else android.R.color.system_neutral2_50)
            AccentRoles(tone, onToneFor(tone, night), secondary, tint, 0.55f)
        }.getOrNull()
    }

    fun applyContrast(p: CatClientPalette, contrast: ColorContrast): CatClientPalette {
        fun mix(a: Int, b: Int, t: Float) = ColorUtils.blendARGB(a, b, t)
        return when (contrast) {
            ColorContrast.Standard -> p
            ColorContrast.Medium -> p.copy(
                textSecondary = mix(p.textSecondary, p.textPrimary, 0.35f),
                textTertiary = mix(p.textTertiary, p.textPrimary, 0.30f),
                neutral = mix(p.neutral, p.textPrimary, 0.25f),
                outline = mix(p.outline, p.textSecondary, 0.25f),
            )
            ColorContrast.High -> {
                val edge = if (p.isDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                p.copy(
                    background = mix(p.background, edge, 0.45f),
                    textSecondary = mix(p.textSecondary, p.textPrimary, 0.70f),
                    textTertiary = mix(p.textTertiary, p.textPrimary, 0.60f),
                    neutral = mix(p.neutral, p.textPrimary, 0.50f),
                    outline = mix(p.outline, p.textPrimary, 0.45f),
                    textPrimary = if (p.isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(),
                )
            }
        }
    }
}

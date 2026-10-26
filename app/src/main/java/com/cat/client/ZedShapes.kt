package com.cat.client

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Material 3 Expressive-style shapes (cookie, burst, clover, sunny...) for plain Android Views.
 *
 * ZedSecure 3.1.6 (CluvexStudio, AGPL-3.0) uses Compose `MaterialShapes` + `Morph`; Views have no
 * such API, so every shape here is a polar radius function r(θ) ∈ (0, 1]. Two shapes morph by
 * interpolating their radius functions, which keeps the outline smooth during the transition.
 */
internal enum class ZedShape(
    private val kind: Int,
    private val lobes: Int = 0,
    private val depth: Float = 0f,
    private val sharp: Float = 1f,
    private val squash: Float = 1f,
) {
    Circle(KIND_CIRCLE),
    Oval(KIND_CIRCLE, squash = 0.80f),
    Cookie4(KIND_WAVE, 4, 0.16f, 0.50f),
    Cookie6(KIND_WAVE, 6, 0.12f, 0.55f),
    Cookie7(KIND_WAVE, 7, 0.12f, 0.55f),
    Cookie9(KIND_WAVE, 9, 0.11f, 0.60f),
    Cookie12(KIND_WAVE, 12, 0.08f, 0.62f),
    SoftBurst(KIND_WAVE, 10, 0.16f, 1.6f),
    Burst(KIND_WAVE, 12, 0.30f, 2.6f),
    Sunny(KIND_WAVE, 8, 0.10f, 1.2f),
    Pentagon(KIND_WAVE, 5, 0.13f, 0.32f),
    Gem(KIND_WAVE, 6, 0.09f, 0.30f),
    Clover4(KIND_WAVE, 4, 0.40f, 0.45f),
    Clover8(KIND_WAVE, 8, 0.26f, 0.50f),
    Flower(KIND_WAVE, 8, 0.30f, 0.80f),
    Puffy(KIND_WAVE, 5, 0.18f, 0.70f),
    Square(KIND_SUPER, lobes = 4, sharp = 4.2f),
    Diamond(KIND_SUPER, lobes = 4, sharp = 2.6f, depth = 0.785398f),
    Pill(KIND_CIRCLE, squash = 0.62f),
    ;

    /** Normalised radius at angle [theta] (radians). Max is 1. */
    fun radius(theta: Float): Float = when (kind) {
        KIND_CIRCLE -> if (squash >= 1f) 1f else {
            // ellipse with semi-axes 1 and squash, in polar form
            val c = cos(theta)
            val s = sin(theta)
            (squash / kotlin.math.sqrt((squash * c) * (squash * c) + s * s))
        }
        KIND_SUPER -> {
            val t = theta + depth // depth reused as rotation for the diamond
            val p = sharp
            val c = abs(cos(t)).toDouble().pow(p.toDouble())
            val s = abs(sin(t)).toDouble().pow(p.toDouble())
            (1.0 / (c + s).pow(1.0 / p)).toFloat() * SUPER_NORMALISE
        }
        else -> {
            val w = ((1f + cos(lobes * theta)) / 2f).coerceIn(0f, 1f)
            val shaped = w.toDouble().pow(sharp.toDouble()).toFloat()
            1f - depth * (1f - shaped)
        }
    }

    /**
     * Writes the outline into [out] centred on (cx, cy). When [to] is given the outline is the
     * morph `this → to` at [progress] (values slightly outside 0..1 give a springy overshoot).
     */
    fun buildPath(
        out: Path,
        cx: Float,
        cy: Float,
        radius: Float,
        rotationDeg: Float = 0f,
        to: ZedShape? = null,
        progress: Float = 1f,
    ) {
        out.rewind()
        val rot = Math.toRadians(rotationDeg.toDouble()).toFloat()
        for (i in 0..STEPS) {
            val theta = (i.toFloat() / STEPS) * TAU
            val a = this.radius(theta)
            val r = if (to == null) a else a + (to.radius(theta) - a) * progress
            val x = cx + cos(theta + rot) * r * radius
            val y = cy + sin(theta + rot) * r * radius
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
    }

    private companion object {
        const val KIND_CIRCLE = 0
        const val KIND_WAVE = 1
        const val KIND_SUPER = 2
        const val STEPS = 144
        const val TAU = (2.0 * Math.PI).toFloat()
        const val SUPER_NORMALISE = 0.84f
    }
}

/** A filled Expressive shape as a Drawable — leading icons, badges, preview art. */
internal class ZedShapeDrawable(
    var shape: ZedShape,
    color: Int,
    var rotationDeg: Float = 0f,
    var inset: Float = 0f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()
    var shader: Shader? = null
        set(value) {
            field = value
            paint.shader = value
            invalidateSelf()
        }

    fun setColor(color: Int) {
        paint.color = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = minOf(b.width(), b.height()) / 2f - inset
        shape.buildPath(path, b.exactCenterX(), b.exactCenterY(), r, rotationDeg)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Material 3 colour roles derived from Cat Client's palette, so the Zed 3.1.6 designs (Pixel,
 * Portal) can use primary / primaryContainer / tertiary / surfaceContainer* like the original.
 */
internal data class ZedScheme(
    val isDark: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val tertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val surface: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val error: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
) {
    companion object {
        fun from(p: CatClientPalette): ZedScheme {
            val dark = p.isDark
            fun container(color: Int): Int {
                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(color, hsl)
                if (hsl[1] < 0.06f) {
                    // monochrome accent: neutral container
                    return if (dark) 0xFF3A3940.toInt() else 0xFFE3E1E8.toInt()
                }
                return if (dark) {
                    ColorUtils.HSLToColor(floatArrayOf(hsl[0], (hsl[1] * 0.85f).coerceIn(0.38f, 0.72f), 0.46f))
                } else {
                    ColorUtils.HSLToColor(floatArrayOf(hsl[0], (hsl[1]).coerceIn(0.45f, 1f), 0.90f))
                }
            }
            fun onContainer(color: Int): Int {
                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(color, hsl)
                return if (dark) {
                    ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceAtMost(0.9f), 0.93f))
                } else {
                    ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceAtMost(0.9f), 0.16f))
                }
            }
            val primaryContainer = container(p.teal)
            val tertiaryContainer = container(p.secondary)
            return ZedScheme(
                isDark = dark,
                primary = p.teal,
                onPrimary = p.onAccent,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onContainer(p.teal),
                tertiary = p.secondary,
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onContainer(p.secondary),
                surface = p.background,
                surfaceContainer = p.surfaceElevated1,
                surfaceContainerHigh = p.surfaceElevated2,
                surfaceContainerHighest = p.surfaceVariant,
                onSurface = p.textPrimary,
                onSurfaceVariant = p.textSecondary,
                outline = p.neutral,
                outlineVariant = p.outline,
                error = p.red,
                errorContainer = if (dark) 0xFF93000A.toInt() else 0xFFFFDAD6.toInt(),
                onErrorContainer = if (dark) 0xFFFFDAD6.toInt() else 0xFF410002.toInt(),
            )
        }

        fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((alpha.coerceIn(0f, 1f) * Color.alpha(color)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

        fun lerp(a: Int, b: Int, t: Float): Int = ColorUtils.blendARGB(a, b, t.coerceIn(0f, 1f))
    }
}

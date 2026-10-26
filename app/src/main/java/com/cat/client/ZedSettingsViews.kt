package com.cat.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/** Expressive leading icon: an icon centred on a filled Material shape (ZedSecure SettingsItem). */
internal class ZedShapeIconView(
    context: Context,
    private var shape: ZedShape,
    private var fillColor: Int,
    @DrawableRes iconRes: Int,
    private var iconTint: Int,
    private val rotationDeg: Float = 0f,
    private val iconScale: Float = 0.5f,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val icon: Drawable? = ContextCompat.getDrawable(context, iconRes)?.mutate()

    fun setColors(fill: Int, tint: Int) {
        fillColor = fill
        iconTint = tint
        invalidate()
    }

    fun setShape(newShape: ZedShape) {
        shape = newShape
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        if (r <= 0f) return
        paint.color = fillColor
        shape.buildPath(path, width / 2f, height / 2f, r, rotationDeg)
        canvas.drawPath(path, paint)
        icon?.let {
            val half = (r * 2f * iconScale / 2f).toInt()
            val cx = width / 2
            val cy = height / 2
            it.setBounds(cx - half, cy - half, cx + half, cy + half)
            it.setTint(iconTint)
            it.draw(canvas)
        }
    }
}

/** Settings hero art: a soft backdrop blob with the gradient Cat core in front (ZedSecure SettingsHero). */
internal class ZedHeroArtView(context: Context, private val scheme: ZedScheme) : View(context) {
    private val back = Paint(Paint.ANTI_ALIAS_FLAG)
    private val front = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val glyph: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_cat_glyph)?.mutate()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        back.color = scheme.surfaceContainer
        ZedShape.Cookie7.buildPath(path, w * 0.62f, h * 0.42f, minOf(w, h) * 0.62f, 12f)
        canvas.drawPath(path, back)
        val r = minOf(w, h) * 0.36f
        val cx = w * 0.56f
        val cy = h * 0.46f
        front.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, scheme.primary, scheme.tertiary, Shader.TileMode.CLAMP)
        ZedShape.Cookie12.buildPath(path, cx, cy, r)
        canvas.drawPath(path, front)
        glyph?.let {
            val half = (r * 0.62f).toInt()
            it.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            it.setTint(scheme.onPrimary)
            it.draw(canvas)
        }
    }
}

/** Mini phone previews for the App design picker. */
internal class DesignPreviewView(
    context: Context,
    private val design: AppDesign,
    private val scheme: ZedScheme,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val d = resources.displayMetrics.density

    private fun round(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int, canvas: Canvas) {
        paint.shader = null
        paint.color = color
        rect.set(l, t, r, b)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        when (design) {
            AppDesign.Pixel -> drawPixel(canvas, w, h)
            AppDesign.Portal -> drawPortal(canvas, w, h)
            AppDesign.Old -> drawOld(canvas, w, h)
        }
    }

    private fun drawPixel(canvas: Canvas, w: Float, h: Float) {
        val p = 7 * d
        round(p + 6 * d, p, p + 32 * d, p + 8 * d, 4 * d, scheme.surfaceContainerHigh, canvas)
        val cardTop = p + 14 * d
        val cardBottom = h - p - 34 * d
        round(p, cardTop, w - p, cardBottom, 16 * d, scheme.surfaceContainer, canvas)
        val cy = (cardTop + cardBottom) / 2f - 6 * d
        paint.color = scheme.primary
        ZedShape.Cookie12.buildPath(path, w / 2f, cy, 21 * d)
        canvas.drawPath(path, paint)
        round(w / 2f - 20 * d, cy + 28 * d, w / 2f + 20 * d, cy + 33 * d, 3 * d, ZedScheme.withAlpha(scheme.onSurface, 0.7f), canvas)
        round(p, h - p - 30 * d, w - p, h - p - 16 * d, 7 * d, scheme.surfaceContainerHigh, canvas)
        val half = (w - 2 * p - 2 * d) / 2f
        round(p, h - p - 12 * d, p + half, h - p, 6 * d, scheme.surfaceContainerHigh, canvas)
        round(p + half + 2 * d, h - p - 12 * d, w - p, h - p, 6 * d, scheme.surfaceContainerHigh, canvas)
    }

    private fun drawPortal(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.40f
        val r = w * 0.17f
        val far = sqrt(cx * cx + cy * cy)
        val step = 9 * d
        var y = step / 2f
        var row = 0
        while (y < h * 0.74f) {
            var x = if (row % 2 == 0) step / 2f else step
            while (x < w) {
                val dx = x - cx
                val dy = y - cy
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > r * 1.25f) {
                    val q = dist / r
                    val nd = dist + r * 0.78f / (1f + q * q * q)
                    val fade = (1f - nd / far).coerceIn(0f, 1f)
                    paint.shader = null
                    paint.color = ZedScheme.withAlpha(scheme.onSurface, 0.32f * fade * fade + 0.03f)
                    canvas.drawCircle(cx + dx / dist * nd, cy + dy / dist * nd, 1.1f * d, paint)
                }
                x += step
            }
            y += step
            row++
        }
        paint.shader = RadialGradient(
            cx, cy, r * 2.6f,
            intArrayOf(ZedScheme.withAlpha(scheme.primary, 0.45f), ZedScheme.withAlpha(scheme.tertiary, 0.15f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r * 2.6f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1 * d
        paint.color = ZedScheme.withAlpha(scheme.primary, 0.35f)
        canvas.drawCircle(cx, cy, r * 1.35f, paint)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, r, intArrayOf(scheme.primaryContainer, scheme.primary, scheme.tertiary), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        paint.color = scheme.tertiary
        canvas.drawCircle(cx + r * 1.35f * 0.7f, cy - r * 1.35f * 0.71f, 2.6f * d, paint)
        val pad = 10 * d
        val meterTop = h * 0.76f
        val half = (w - pad * 2 - 3 * d) / 2f
        round(pad, meterTop, pad + half, meterTop + 14 * d, 7 * d, scheme.surfaceContainerHigh, canvas)
        round(pad + half + 3 * d, meterTop, w - pad, meterTop + 14 * d, 7 * d, scheme.surfaceContainerHigh, canvas)
        round(pad, meterTop + 18 * d, w - pad, meterTop + 36 * d, 9 * d, scheme.primaryContainer, canvas)
    }

    private fun drawOld(canvas: Canvas, w: Float, h: Float) {
        val p = 10 * d
        val cx = w / 2f
        val cy = p + 4 * d + 31 * d
        paint.shader = LinearGradient(cx - 31 * d, cy - 31 * d, cx + 31 * d, cy + 31 * d, scheme.primary, scheme.tertiary, Shader.TileMode.CLAMP)
        ZedShape.Cookie9.buildPath(path, cx, cy, 31 * d)
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 20 * d
        paint.isFakeBoldText = true
        canvas.drawText("C", cx, cy + 7 * d, paint)
        val top = cy + 31 * d + 9 * d
        val half = (w - 2 * p - 5 * d) / 2f
        round(p, top, p + half, top + 26 * d, 7 * d, scheme.surfaceContainerHighest, canvas)
        round(p + half + 5 * d, top, w - p, top + 26 * d, 7 * d, scheme.surfaceContainerHighest, canvas)
        round(p, top + 33 * d, w - p, top + 51 * d, 7 * d, scheme.primaryContainer, canvas)
        round(p, top + 58 * d, w - p, top + 76 * d, 9 * d, scheme.primary, canvas)
    }
}

/** Colour-style swatch: a tilted primary cookie, a tertiary clover and a secondary pill. */
internal class ColorStylePreviewView(
    context: Context,
    private val scheme: ZedScheme,
    private val tilt: Float,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val d = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.color = scheme.primary
        ZedShape.Cookie9.buildPath(path, 10 * d + 28 * d, 12 * d + 28 * d, 28 * d, tilt)
        canvas.drawPath(path, paint)
        paint.color = scheme.tertiary
        ZedShape.Clover4.buildPath(path, w - 8 * d - 17 * d, 40 * d + 17 * d, 17 * d, -tilt * 2f)
        canvas.drawPath(path, paint)
        paint.color = scheme.tertiaryContainer
        rect.set(10 * d, h - 12 * d - 18 * d, 60 * d, h - 12 * d)
        canvas.drawRoundRect(rect, 9 * d, 9 * d, paint)
    }
}

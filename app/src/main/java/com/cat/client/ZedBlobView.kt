package com.cat.client

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.cat.client.core.VpnState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Direct port of ZedSecure's `AnimatedBlobButton` (HomeScreen.kt):
 * 4 concentric harmonic-summed wave layers (`r + a1*sin(k1*θ+φ) + a2*cos(k2*θ-φ*0.7) + a3*sin(k3*θ+φ*1.3)`)
 * closed with quadratic Beziers at 4° steps, plus a soft ambient halo and a gradient core disk with a bold
 * centre glyph ("C" idle, 2-letter ISO country code when connected).
 */
class ZedBlobView(context: Context) : View(context) {

    private var vpnState: VpnState = VpnState.Stopped
    private var centerText: String = "C"
    private var stateProgress: Float = 0f // 0 = disconnected, 1 = connected
    private val progressAnim = ValueAnimator().apply {
        duration = 600L
        interpolator = DecelerateInterpolator()
        addUpdateListener { stateProgress = it.animatedValue as Float; invalidate() }
    }
    private val startRealtime = SystemClock.uptimeMillis()

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val coreBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = CatClientHeadlineTypeface
    }
    private val path = Path()

    fun setVpnState(state: VpnState) {
        if (vpnState == state) return
        vpnState = state
        val target = if (state == VpnState.Started) 1f else 0f
        progressAnim.cancel()
        progressAnim.setFloatValues(stateProgress, target)
        progressAnim.start()
        invalidate()
    }

    fun setCenterText(text: String) {
        val clean = text.trim().ifEmpty { "C" }.take(3).uppercase()
        if (centerText != clean) { centerText = clean; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = CatClientDesignTokens.forContext(context)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val maxR = min(width, height) * 0.44f
        if (maxR <= 0f) return

        val connecting = vpnState == VpnState.Starting || vpnState == VpnState.Stopping
        val speedMul = if (connecting) 2.5f else 1f
        val ampMul = if (connecting) 1.5f else 1f
        val t = (SystemClock.uptimeMillis() - startRealtime) / 1000f * speedMul

        val primaryColor = ColorUtils.blendARGB(p.teal, p.secondary, stateProgress)
        val secondaryColor = ColorUtils.blendARGB(p.secondary, p.teal, stateProgress)

        val haloR = maxR * 1.14f
        haloPaint.shader = RadialGradient(
            cx,
            cy,
            haloR,
            intArrayOf(
                ColorUtils.setAlphaComponent(primaryColor, if (p.isDark) 68 else 44),
                ColorUtils.setAlphaComponent(secondaryColor, if (p.isDark) 28 else 16),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, haloR, haloPaint)

        // 4 layers from outer to inner (matches ZedSecure blob1..blob4)
        drawBlob(canvas, cx, cy, maxR * 1.00f, 0.08f * ampMul,  t * (2f * PI.toFloat() / 4.0f), 1, primaryColor, 0.15f)
        drawBlob(canvas, cx, cy, maxR * 0.90f, 0.10f * ampMul, -t * (2f * PI.toFloat() / 3.5f), 2, secondaryColor, 0.25f)
        drawBlob(canvas, cx, cy, maxR * 0.80f, 0.12f * ampMul,  t * (2f * PI.toFloat() / 3.0f), 3, primaryColor, 0.40f)
        drawBlob(canvas, cx, cy, maxR * 0.70f, 0.06f * ampMul, -t * (2f * PI.toFloat() / 2.5f), 4, secondaryColor, 0.60f)

        val coreR = maxR * 0.60f
        val g0 = ColorUtils.blendARGB(p.teal, p.tealGradientStart, stateProgress)
        val g1 = ColorUtils.blendARGB(p.secondary, p.tealGradientEnd, stateProgress)
        corePaint.shader = LinearGradient(cx - coreR, cy - coreR, cx + coreR, cy + coreR, g0, g1, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, coreR, corePaint)

        coreBorderPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, (0.3f * 255).toInt())
        canvas.drawCircle(cx, cy, coreR, coreBorderPaint)

        textPaint.color = ColorUtils.blendARGB(p.onAccent, Color.WHITE, stateProgress * 0.6f)
        textPaint.textSize = coreR * (if (centerText.length > 1) 0.56f else 0.72f)
        val fm = textPaint.fontMetrics
        canvas.drawText(centerText, cx, cy - (fm.ascent + fm.descent) * 0.5f, textPaint)

        if (isShown) postInvalidateOnAnimation()
    }

    private fun drawBlob(
        canvas: Canvas, cx: Float, cy: Float, baseR: Float,
        amp: Float, phase: Float, seed: Int, color: Int, alpha: Float,
    ) {
        path.reset()
        val step = 4
        var prevX = 0f
        var prevY = 0f
        var angle = 0
        while (angle <= 360) {
            val rad = angle * (PI.toFloat() / 180f)
            val w1 = sin(rad * (3 + seed % 2) + phase) * amp
            val w2 = cos(rad * (4 + seed % 3) - phase * 0.7f) * (amp * 0.6f)
            val w3 = sin(rad * (2 + seed) + phase * 1.3f) * (amp * 0.4f)
            val r = baseR * (1f + w1 + w2 + w3)
            val x = cx + r * cos(rad)
            val y = cy + r * sin(rad)
            if (angle == 0) {
                path.moveTo(x, y)
            } else {
                path.quadTo(prevX, prevY, (prevX + x) * 0.5f, (prevY + y) * 0.5f)
            }
            prevX = x; prevY = y
            angle += step
        }
        path.close()
        layerPaint.color = ColorUtils.setAlphaComponent(color, (alpha * 255).toInt().coerceIn(0, 255))
        canvas.drawPath(path, layerPaint)
    }
}

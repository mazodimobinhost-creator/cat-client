package com.cat.client

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * ZedSecure-style "hero" core:
 * - In Blob mode: a scalloped cookie shape that morphs into a soft burst while the tunnel is up,
 *   surrounded by multi-layer neon halo rings and orbiting particles.
 * - In Shield mode: a Cyber-Quantum Aether shield crest with concentric radar rings and rotating energy arc.
 */
class ZedBlobView(context: Context) : View(context) {
    private val palette = CatClientDesignTokens.forContext(context)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val orbitDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = CatClientDisplayTypeface
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = Color.WHITE
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = CatClientBodyBoldTypeface
        textAlign = Paint.Align.CENTER
        color = withAlpha(Color.WHITE, 225)
    }
    private val path = Path()
    private val innerShieldPath = Path()
    private val arcBounds = RectF()

    private var state: VpnState = VpnState.Stopped
    private var morph = 0f          // 0 = cookie (idle), 1 = soft burst (connected)
    private var breathe = 1f
    private var breathePhase = 0f
    private var spin = 0f
    private var centerText: String = "C"
    private var subText: String = ""
    private var shieldMode: Boolean = false
    private var shaderColors: IntArray = idleColors()
    private var sessionSeed = 0

    private var morphAnimator: ValueAnimator? = null
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_800L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            breathePhase = it.animatedValue as Float
            breathe = if (state == VpnState.Started) 0.96f + 0.075f * breathePhase else 0.99f + 0.025f * breathePhase
            spin = (spin + 0.012f) % 1f
            invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
    }

    fun setShieldMode(enabled: Boolean) {
        if (shieldMode == enabled) return
        shieldMode = enabled
        fillPaint.shader = null
        invalidate()
    }

    fun isShieldMode(): Boolean = shieldMode

    fun setSubText(text: String) {
        if (subText == text) return
        subText = text
        invalidate()
    }

    fun setVpnState(newState: VpnState) {
        if (state == newState) return
        if (newState == VpnState.Started && state != VpnState.Started) sessionSeed++
        state = newState
        shaderColors = when (newState) {
            VpnState.Started -> connectedColors(sessionSeed)
            VpnState.Starting, VpnState.Stopping -> intArrayOf(
                themePrimary ?: ZED_VIOLET,
                themeSecondary ?: ZED_CYAN,
                ZED_LIME,
            )
            is VpnState.Error, VpnState.DailyLimitReached -> intArrayOf(0xFF93000A.toInt(), 0xFFFF5FA2.toInt(), ZED_VIOLET)
            VpnState.Stopped -> idleColors()
        }
        fillPaint.shader = null
        val target = if (newState == VpnState.Started) 1f else 0f
        morphAnimator?.cancel()
        morphAnimator = ValueAnimator.ofFloat(morph, target).apply {
            duration = 650L
            addUpdateListener { morph = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    fun setCenterText(text: String) {
        if (centerText == text) return
        centerText = text
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ticker.isStarted) ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        morphAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fillPaint.shader = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = when {
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY -> h
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY -> w
            else -> dp(260f).toInt()
        }
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f
        val r = outer * 0.82f

        if (fillPaint.shader == null && width > 0 && height > 0) {
            fillPaint.shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                shaderColors,
                null,
                Shader.TileMode.CLAMP,
            )
        }

        val primaryTone = themePrimary ?: palette.teal
        val secondaryTone = themeSecondary ?: palette.secondary

        // Soft ambient radial glow behind the core
        if (outer > 0f) {
            val glowAlpha = if (state == VpnState.Started) (68 + (28 * breathePhase).toInt()) else 36
            glowPaint.shader = RadialGradient(
                cx,
                cy,
                outer * 0.99f,
                intArrayOf(
                    withAlpha(primaryTone, glowAlpha),
                    withAlpha(secondaryTone, glowAlpha / 2),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0.25f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, outer * 0.99f, glowPaint)
        }

        // Multi-layer ZedSecure halo rings + orbiting particles
        ringPaint.strokeWidth = dp(1.4f)
        ringPaint.color = withAlpha(primaryTone, if (state == VpnState.Started) 92 else 44)
        canvas.drawCircle(cx, cy, outer * 0.96f, ringPaint)

        ringPaint.strokeWidth = dp(1.0f)
        ringPaint.color = withAlpha(secondaryTone, if (state == VpnState.Started) 64 else 28)
        canvas.drawCircle(cx, cy, outer * 0.90f, ringPaint)

        val particleCount = if (state == VpnState.Started) 6 else 3
        val orbitRadius = outer * 0.93f
        for (i in 0 until particleCount) {
            val angle = (spin * 2f * Math.PI.toFloat()) + (i * 2f * Math.PI.toFloat() / particleCount)
            val px = cx + orbitRadius * cos(angle)
            val py = cy + orbitRadius * sin(angle)
            orbitDotPaint.color = withAlpha(
                if (i % 2 == 0) primaryTone else secondaryTone,
                if (state == VpnState.Started) 215 else 120,
            )
            canvas.drawCircle(px, py, dp(if (state == VpnState.Started) 3.4f else 2.4f), orbitDotPaint)
        }

        canvas.save()
        val pressScale = if (isPressed) 0.95f else 1f
        canvas.scale(breathe * pressScale, breathe * pressScale, cx, cy)
        if (shieldMode) {
            buildShieldPath(cx, cy, r)
            canvas.drawPath(path, fillPaint)
            ringPaint.strokeWidth = dp(2f)
            ringPaint.color = withAlpha(Color.WHITE, if (state == VpnState.Started) 140 else 80)
            canvas.drawPath(path, ringPaint)
            buildShieldPath(cx, cy, r * 0.82f, targetPath = innerShieldPath)
            ringPaint.strokeWidth = dp(1.1f)
            ringPaint.color = withAlpha(Color.WHITE, 60)
            canvas.drawPath(innerShieldPath, ringPaint)
        } else {
            buildPath(cx, cy, r)
            canvas.drawPath(path, fillPaint)
        }
        canvas.restore()

        val transitional = state == VpnState.Starting || state == VpnState.Stopping
        if (transitional) {
            val sr = r * 0.30f
            arcBounds.set(cx - sr, cy - sr, cx + sr, cy + sr)
            canvas.drawArc(arcBounds, spin * 360f, 270f, false, spinnerPaint)
        } else {
            val mainText = if (shieldMode && state != VpnState.Started && centerText == "C") "🛡" else centerText
            textPaint.textSize = when {
                state == VpnState.Started -> r * 0.38f
                shieldMode -> r * 0.56f
                else -> r * 0.68f
            }
            val hasSub = subText.isNotBlank()
            val verticalOffset = if (hasSub) -r * 0.08f else 0f
            val baseline = cy + verticalOffset - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(mainText, cx, baseline, textPaint)
            if (hasSub) {
                subTextPaint.textSize = r * 0.145f
                val subBaseline = cy + r * 0.25f - (subTextPaint.descent() + subTextPaint.ascent()) / 2f
                canvas.drawText(subText, cx, subBaseline, subTextPaint)
            }
        }
    }

    /**
     * Cookie9Sided (gentle 9-lobe scallop) morphing into SoftBurst (sharper 10-lobe star).
     * Lobe count blends by cross-fading two radial waves; amplitude grows with [morph].
     */
    private fun buildPath(cx: Float, cy: Float, r: Float) {
        path.reset()
        val segments = 240
        val cookieAmp = 0.055f
        val burstAmp = 0.13f
        val rot = breathePhase * 0.15f
        for (i in 0..segments) {
            val a = i.toFloat() / segments * 2f * Math.PI.toFloat()
            val cookie = cos(9f * a + rot) * cookieAmp
            val c = cos(10f * a - rot)
            val soft = Math.signum(c) * Math.pow(abs(c).toDouble(), 0.72).toFloat()
            val burst = soft * burstAmp
            val wave = cookie * (1f - morph) + burst * morph
            val rr = r * (1f + wave) * (1f + 0.04f * morph)
            val x = cx + rr * cos(a)
            val y = cy + rr * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    /**
     * Cyber-Quantum Aether Shield crest path.
     */
    private fun buildShieldPath(cx: Float, cy: Float, r: Float, targetPath: Path = path) {
        targetPath.reset()
        val topY = cy - r * 0.92f
        val shoulderY = cy - r * 0.56f
        val waistY = cy + r * 0.18f
        val bottomY = cy + r * 0.96f
        val halfW = r * 0.84f
        targetPath.moveTo(cx, topY)
        targetPath.cubicTo(
            cx + halfW * 0.45f,
            topY + r * 0.08f,
            cx + halfW * 0.85f,
            shoulderY - r * 0.06f,
            cx + halfW,
            shoulderY,
        )
        targetPath.cubicTo(
            cx + halfW,
            waistY,
            cx + halfW * 0.58f,
             cy + r * 0.68f,
            cx,
            bottomY,
        )
        targetPath.cubicTo(
            cx - halfW * 0.58f,
            cy + r * 0.68f,
            cx - halfW,
            waistY,
            cx - halfW,
            shoulderY,
        )
        targetPath.cubicTo(
            cx - halfW * 0.85f,
            shoulderY - r * 0.06f,
            cx - halfW * 0.45f,
            topY + r * 0.08f,
            cx,
            topY,
        )
        targetPath.close()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    private var themePrimary: Int? = null
    private var themeSecondary: Int? = null

    /** Theme preset colours: the idle/connecting/connected blob follows the preset. */
    fun setThemeColors(primary: Int, secondary: Int, isDefault: Boolean) {
        themePrimary = if (isDefault) null else primary
        themeSecondary = if (isDefault) null else secondary
        shaderColors = if (state == VpnState.Started) connectedColors(sessionSeed) else idleColors()
        fillPaint.shader = null
        invalidate()
    }

    private fun idleColors(): IntArray {
        val p = themePrimary ?: return intArrayOf(ZED_DEEP_VIOLET, ZED_VIOLET, withAlpha(ZED_HOT_PINK, 180))
        val sec = themeSecondary ?: ZED_HOT_PINK
        return intArrayOf(
            androidx.core.graphics.ColorUtils.blendARGB(p, 0xFF000000.toInt(), 0.55f),
            p,
            withAlpha(sec, 195),
        )
    }

    private fun connectedColors(seed: Int): IntArray {
        val p = themePrimary
        val sec = themeSecondary
        if (p != null && sec != null) {
            return intArrayOf(
                androidx.core.graphics.ColorUtils.blendARGB(p, 0xFF000000.toInt(), 0.25f),
                p,
                sec,
            )
        }
        val sets = arrayOf(
            intArrayOf(0xFF5B8CFF.toInt(), ZED_CYAN, ZED_LIME),
            intArrayOf(ZED_VIOLET, ZED_HOT_PINK, ZED_LIME),
            intArrayOf(ZED_DEEP_VIOLET, ZED_VIOLET, ZED_CYAN),
            intArrayOf(ZED_HOT_PINK, ZED_VIOLET, ZED_CYAN),
            intArrayOf(ZED_VIOLET, ZED_CYAN, ZED_LIME),
            intArrayOf(ZED_DEEP_VIOLET, ZED_HOT_PINK, ZED_VIOLET),
        )
        return sets[((seed % sets.size) + sets.size) % sets.size]
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(max(0, min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        const val ZED_LIME = 0xFFC7F24E.toInt()
        const val ZED_HOT_PINK = 0xFFFF5FA2.toInt()
        const val ZED_VIOLET = 0xFF7A5CFF.toInt()
        const val ZED_DEEP_VIOLET = 0xFF2A1361.toInt()
        const val ZED_CYAN = 0xFF37E0D8.toInt()
    }
}

/**
 * ZedSecure DecorativeBackdrop: one big soft gradient blob bleeding in from the top-right corner.
 * Drawn as a plain Drawable so it can sit behind any scrolling content.
 */
class ZedDecorDrawable(private var alpha: Float = 1f) : android.graphics.drawable.Drawable() {
    fun setFraction(fraction: Float) { alpha = fraction.coerceIn(0f, 1f) }
    private var colors: IntArray? = null
    fun setThemeColors(primary: Int, secondary: Int, isDefault: Boolean) {
        colors = if (isDefault) null else intArrayOf(
            androidx.core.graphics.ColorUtils.blendARGB(primary, 0xFF000000.toInt(), 0.5f),
            primary,
            (secondary and 0x00FFFFFF) or (0xB3 shl 24),
        )
        paint.shader = null
        invalidateSelf()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val cx = b.right + w * 0.02f
        val cy = b.top - w * 0.10f
        val r = w * 0.52f
        if (paint.shader == null) {
            paint.shader = LinearGradient(
                cx - r,
                cy - r,
                cx + r * 0.2f,
                cy + r,
                colors ?: intArrayOf(
                    ZedBlobView.ZED_DEEP_VIOLET,
                    ZedBlobView.ZED_VIOLET,
                    (ZedBlobView.ZED_HOT_PINK and 0x00FFFFFF) or (0xB3 shl 24),
                ),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        path.reset()
        val segments = 140
        for (i in 0..segments) {
            val a = i.toFloat() / segments * 2f * Math.PI.toFloat()
            val wave = 1f + 0.075f * cos(7f * a)
            val x = cx + r * wave * cos(a)
            val y = cy + r * wave * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

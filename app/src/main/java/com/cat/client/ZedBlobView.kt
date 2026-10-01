package com.cat.client

import android.animation.ValueAnimator
import kotlin.math.abs
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.animation.LinearInterpolator
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * ZedSecure-style "hero" core: a scalloped cookie shape that morphs into a soft burst while
 * the tunnel is up, filled with a diagonal violet → pink → lime gradient, gently breathing.
 * The caller supplies the centre text (elapsed time when connected, brand glyph otherwise);
 * while connecting/disconnecting a small arc spinner is drawn instead.
 */
class ZedBlobView(context: Context) : View(context) {
    private val palette = CatClientDesignTokens.forContext(context)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
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
    private val path = Path()
    private val arcBounds = RectF()

    private var state: VpnState = VpnState.Stopped
    private var morph = 0f          // 0 = cookie (idle), 1 = soft burst (connected)
    private var breathe = 1f
    private var breathePhase = 0f
    private var spin = 0f
    private var centerText: String = "C"
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
            breathe = if (state == VpnState.Started) 0.97f + 0.07f * breathePhase else 1f
            spin = (spin + 0.012f) % 1f
            invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
    }

    fun setVpnState(newState: VpnState) {
        if (state == newState) return
        if (newState == VpnState.Started && state != VpnState.Started) sessionSeed++
        state = newState
        shaderColors = when (newState) {
            VpnState.Started -> connectedColors(sessionSeed)
            VpnState.Starting, VpnState.Stopping -> intArrayOf(ZED_VIOLET, ZED_CYAN, ZED_LIME)
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
        val r = outer * 0.80f

        if (fillPaint.shader == null) {
            fillPaint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), shaderColors, null, Shader.TileMode.CLAMP)
        }

        // Thin halo ring behind the core, like the faint circle around Zed's blob.
        ringPaint.color = withAlpha(Color.WHITE, if (state == VpnState.Started) 56 else 28)
        canvas.drawCircle(cx, cy, outer * 0.97f, ringPaint)

        canvas.save()
        val pressScale = if (isPressed) 0.96f else 1f
        canvas.scale(breathe * pressScale, breathe * pressScale, cx, cy)
        buildPath(cx, cy, r)
        canvas.drawPath(path, fillPaint)
        canvas.restore()

        val transitional = state == VpnState.Starting || state == VpnState.Stopping
        if (transitional) {
            val sr = r * 0.30f
            arcBounds.set(cx - sr, cy - sr, cx + sr, cy + sr)
            canvas.drawArc(arcBounds, spin * 360f, 270f, false, spinnerPaint)
        } else {
            textPaint.textSize = if (state == VpnState.Started) r * 0.42f else r * 0.72f
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(centerText, cx, baseline, textPaint)
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
            // Cookie9Sided: gentle 9-lobe scallop. SoftBurst: 10 rounded tips — the cosine is pushed
            // through a soft-clip so the tips are blunt and the valleys stay shallow, like M3's shape.
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

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    private fun idleColors() = intArrayOf(ZED_DEEP_VIOLET, ZED_VIOLET, withAlpha(ZED_HOT_PINK, 180))

    private fun connectedColors(seed: Int): IntArray {
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
                cx - r, cy - r, cx + r * 0.2f, cy + r,
                intArrayOf(ZedBlobView.ZED_DEEP_VIOLET, ZedBlobView.ZED_VIOLET, (ZedBlobView.ZED_HOT_PINK and 0x00FFFFFF) or (0xB3 shl 24)),
                null, Shader.TileMode.CLAMP,
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

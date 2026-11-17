package com.cat.client

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ZedSecure 3.1.6 "Portal" home stage (and the "Pixel" core), rebuilt for Views.
 *
 * Portal: a hex grid of dots ("wall of light") that parts around a central door when the tunnel
 * opens, a breathing glow, two orbit rings with download/upload "moons", and particles that fly
 * in (download) or out (upload) at a rate driven by the live traffic. The door is an Expressive
 * shape: cookie when idle, cycling search shapes while connecting, a circle with tunnel rings and
 * the elapsed time when connected, a shaking burst on error.
 *
 * Pixel: only the door (cookie idle → soft burst connected), drawn inside the Pixel hero card.
 *
 * Design and motion follow ZedSecure's PortalHome.kt / PixelHome.kt (CluvexStudio, AGPL-3.0).
 */
class ZedPortalView(context: Context, private val pixelMode: Boolean = false) : View(context) {
    private val scheme = ZedScheme.from(CatClientDesignTokens.forContext(context))
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ---------------------------------------------------------------- state
    private var state: VpnState = VpnState.Stopped
    private var centerText: String = ""
    private var downFraction = 0f
    private var upFraction = 0f

    private var opening = 0f
    private var glow = 0f
    private var wave = 0f
    private var clock = 0f
    private var lastFrameNanos = 0L
    private var settleUntilNanos = 0L

    private var fromShape = ZedShape.Cookie12
    private var toShape = ZedShape.Cookie12
    private var morphStartNanos = 0L
    private var morphProgress = 1f
    private var searchStep = 0

    private var shakeStartNanos = 0L
    private var squash = 1f
    private var squashTarget = 1f

    private var c1 = scheme.surfaceContainerHighest
    private var c2 = scheme.surfaceContainerHighest
    private var c3 = scheme.surfaceContainerHighest
    private var rim = scheme.outlineVariant
    private var ink = scheme.onSurface
    private var glowInner = scheme.primary
    private var glowOuter = scheme.tertiary
    private var sealed = 1f
    private var tunnel = 0f

    // ---------------------------------------------------------------- wall of light
    private var gridW = -1
    private var gridH = -1
    private var baseX = FloatArray(0)
    private var baseY = FloatArray(0)
    private val layers = Array(4) { FloatArray(0) }
    private val fill = IntArray(4)
    private val layerAlpha = floatArrayOf(0.52f, 0.36f, 0.22f, 0.10f)

    private val pAngle = FloatArray(MAX_PARTICLES)
    private val pReach = FloatArray(MAX_PARTICLES)
    private val pSpeed = FloatArray(MAX_PARTICLES)
    private val pInbound = BooleanArray(MAX_PARTICLES)
    private val pAlive = BooleanArray(MAX_PARTICLES)
    private var downDebt = 0f
    private var upDebt = 0f
    private var moonA = 0f
    private var moonB = 0f
    private val random = java.util.Random(19)

    // ---------------------------------------------------------------- paints
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.4f)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val doorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
    }
    private val tunnelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = runCatching { CatClientDisplayTypeface }.getOrNull()
        isFakeBoldText = false
    }
    private val doorPath = Path()
    private val glyph: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_cat_glyph)?.mutate()

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { onFrame() }
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        applyTargetsImmediately()
    }

    // ---------------------------------------------------------------- public API
    fun setVpnState(newState: VpnState) {
        if (newState == state) return
        val previous = state
        state = newState
        if (newState is VpnState.Error && previous !is VpnState.Error) shakeStartNanos = System.nanoTime()
        if (newState == VpnState.Started && previous == VpnState.Starting) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM.takeIf { android.os.Build.VERSION.SDK_INT >= 30 } ?: HapticFeedbackConstants.LONG_PRESS)
        }
        retargetShape()
        settleUntilNanos = System.nanoTime() + SETTLE_NANOS
        if (!animationsEnabled()) applyTargetsImmediately()
        updateTicker()
        invalidate()
    }

    /** Elapsed time shown in the open door. */
    fun setCenterText(text: String) {
        if (text == centerText) return
        centerText = text
        if (!ticker.isRunning) invalidate()
    }

    fun setRates(downloadBps: Long, uploadBps: Long) {
        downFraction = rateFraction(downloadBps)
        upFraction = rateFraction(uploadBps)
    }

    // ---------------------------------------------------------------- lifecycle
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTicker()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) updateTicker() else ticker.cancel()
    }

    private fun animationsEnabled() = ValueAnimator.areAnimatorsEnabled()

    private fun wantsFrames(): Boolean {
        if (!animationsEnabled() || !isAttachedToWindow || windowVisibility != VISIBLE) return false
        val active = state == VpnState.Started || state == VpnState.Starting || state == VpnState.Stopping
        return active || System.nanoTime() < settleUntilNanos || morphProgress < 1f
    }

    private fun updateTicker() {
        if (wantsFrames()) {
            if (!ticker.isStarted) {
                lastFrameNanos = 0L
                ticker.start()
            }
        } else if (ticker.isStarted) {
            ticker.cancel()
        }
    }

    // ---------------------------------------------------------------- targets
    private val searching get() = state == VpnState.Starting
    private val live get() = state == VpnState.Started
    private val error get() = state is VpnState.Error

    private fun targetShape(): ZedShape = when {
        live -> if (pixelMode) ZedShape.SoftBurst else ZedShape.Circle
        error -> ZedShape.Burst
        searching -> SEARCH_SHAPES[searchStep.mod(SEARCH_SHAPES.size)]
        else -> ZedShape.Cookie12
    }

    private fun retargetShape() {
        val target = targetShape()
        if (target == toShape) return
        fromShape = if (morphProgress >= 0.5f) toShape else fromShape
        toShape = target
        morphStartNanos = System.nanoTime()
        morphProgress = if (animationsEnabled()) 0f else 1f
    }

    private fun targetOpening() = when {
        live -> 1f
        searching -> 0.34f
        state == VpnState.Stopping -> 0.12f
        else -> 0f
    }

    private fun targetGlow() = when {
        live -> 1f
        searching -> 0.7f
        error -> 0.75f
        state == VpnState.Stopping -> 0.3f
        else -> 0f
    }

    private fun targetColors(): IntArray = when {
        live -> intArrayOf(scheme.primaryContainer, scheme.primary, scheme.tertiary)
        searching -> intArrayOf(scheme.primary, scheme.tertiary, scheme.primary)
        error -> intArrayOf(scheme.errorContainer, scheme.errorContainer, scheme.errorContainer)
        else -> intArrayOf(scheme.surfaceContainerHighest, scheme.surfaceContainerHighest, scheme.surfaceContainerHighest)
    }

    private fun targetRim() = when {
        error -> scheme.error
        state == VpnState.Stopped || state == VpnState.Stopping || state == VpnState.DailyLimitReached -> scheme.outlineVariant
        else -> Color.TRANSPARENT
    }

    private fun targetInk() = when {
        error -> scheme.onErrorContainer
        live || searching -> scheme.onPrimary
        else -> scheme.onSurface
    }

    private fun applyTargetsImmediately() {
        opening = targetOpening()
        glow = targetGlow()
        wave = if (searching) 1f else 0f
        val c = targetColors()
        c1 = c[0]; c2 = c[1]; c3 = c[2]
        rim = targetRim()
        ink = targetInk()
        glowInner = if (error) scheme.error else scheme.primary
        glowOuter = if (error) scheme.errorContainer else scheme.tertiary
        sealed = if (state == VpnState.Stopped || state == VpnState.Stopping || state == VpnState.DailyLimitReached) 1f else 0f
        tunnel = if (live) 1f else 0f
        morphProgress = 1f
        fromShape = targetShape()
        toShape = fromShape
    }

    // ---------------------------------------------------------------- frame step
    private fun onFrame() {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now
        if (dt > 0f) {
            clock += dt
            fun approach(current: Float, target: Float, rate: Float) = current + (target - current) * (1f - exp(-dt * rate))
            opening = approach(opening, targetOpening(), 2.6f)
            glow = approach(glow, targetGlow(), 3.2f)
            wave = approach(wave, if (searching) 1f else 0f, 4f)
            sealed = approach(sealed, if (state == VpnState.Stopped || state == VpnState.Stopping || state == VpnState.DailyLimitReached) 1f else 0f, 5f)
            tunnel = approach(tunnel, if (live) 1f else 0f, 3f)
            squash = approach(squash, squashTarget, 18f)
            val k = (1f - exp(-dt * 6f))
            val c = targetColors()
            c1 = ZedScheme.lerp(c1, c[0], k); c2 = ZedScheme.lerp(c2, c[1], k); c3 = ZedScheme.lerp(c3, c[2], k)
            rim = ZedScheme.lerp(rim, targetRim(), k)
            ink = ZedScheme.lerp(ink, targetInk(), k)
            glowInner = ZedScheme.lerp(glowInner, if (error) scheme.error else scheme.primary, k)
            glowOuter = ZedScheme.lerp(glowOuter, if (error) scheme.errorContainer else scheme.tertiary, k)
            if (searching) {
                val step = (clock / SEARCH_STEP_SECONDS).toInt()
                if (step != searchStep) {
                    searchStep = step
                    retargetShape()
                }
            }
            if (morphProgress < 1f) {
                val t = ((now - morphStartNanos) / 1e9f / MORPH_SECONDS).coerceIn(0f, 1f)
                morphProgress = if (t >= 1f) 1f else t
            }
            if (!pixelMode) stepSky(dt)
        }
        invalidate()
        if (!wantsFrames()) ticker.cancel()
    }

    private fun stepSky(dt: Float) {
        moonA += dt * (0.35f + downFraction * 3.4f)
        moonB -= dt * (0.28f + upFraction * 2.8f)
        if (live) {
            downDebt += dt * downFraction * 22f
            upDebt += dt * upFraction * 16f
            while (downDebt >= 1f) { spawn(true); downDebt -= 1f }
            while (upDebt >= 1f) { spawn(false); upDebt -= 1f }
        }
        for (i in 0 until MAX_PARTICLES) {
            if (!pAlive[i]) continue
            pReach[i] += pSpeed[i] * dt
            if (pReach[i] < 1f || pReach[i] > 3.4f) pAlive[i] = false
        }
    }

    private fun spawn(inward: Boolean) {
        val slot = pAlive.indexOfFirst { !it }
        if (slot < 0) return
        pAlive[slot] = true
        pInbound[slot] = inward
        pAngle[slot] = random.nextFloat() * TAU
        pReach[slot] = if (inward) 2.9f + random.nextFloat() * 0.4f else 1.02f
        pSpeed[slot] = (0.75f + random.nextFloat() * 0.8f) * if (inward) -1f else 1f
    }

    private fun ensureGrid(w: Int, h: Int) {
        if (w == gridW && h == gridH) return
        gridW = w
        gridH = h
        val s = dp(19f)
        val cols = floor(w / s).toInt() + 3
        val rows = floor(h / s).toInt() + 3
        val n = cols * rows
        baseX = FloatArray(n)
        baseY = FloatArray(n)
        val ox = (w - (cols - 1) * s) / 2f
        val oy = (h - (rows - 1) * s) / 2f
        var i = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            baseX[i] = ox + c * s + if (r % 2 == 1) s / 2f else 0f
            baseY[i] = oy + r * s
            i++
        }
        for (k in layers.indices) layers[k] = FloatArray(n * 2)
    }

    // ---------------------------------------------------------------- geometry
    private fun doorRadius(): Float {
        val minSide = min(width, height).toFloat()
        return if (pixelMode) minSide / 2f * 0.96f
        else (minSide * 0.56f).coerceIn(dp(124f), dp(272f)) / 2f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val dx = event.x - width / 2f
            val dy = event.y - height / 2f
            if (sqrt(dx * dx + dy * dy) > doorRadius() * 1.08f) return false
        }
        return super.onTouchEvent(event)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        squashTarget = if (pressed) 0.92f else 1f
        if (!animationsEnabled()) squash = squashTarget
        settleUntilNanos = max(settleUntilNanos, System.nanoTime() + 400_000_000L)
        updateTicker()
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val cx = w / 2f
        val cy = h / 2f
        val r = doorRadius()
        if (!pixelMode) drawWall(canvas, cx, cy, r)
        drawDoor(canvas, cx, cy, r)
    }

    private fun drawWall(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val t = clock
        if (glow > 0.01f) {
            val breath = when {
                searching -> 0.82f + 0.18f * sin(t * 3.1f)
                live -> 0.9f + 0.1f * sin(t * 1.3f)
                else -> 1f
            }
            val g = (glow * breath).coerceIn(0f, 1f)
            glowPaint.shader = RadialGradient(
                cx, cy, r * 2.7f,
                intArrayOf(ZedScheme.withAlpha(glowInner, 0.40f * g), ZedScheme.withAlpha(glowOuter, 0.14f * g), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r * 2.7f, glowPaint)
        }

        ensureGrid(width, height)
        for (k in 0 until 4) fill[k] = 0
        val reachMax = sqrt(cx * cx + cy * cy)
        val gain = 0.55f + 0.45f * opening
        val n = baseX.size
        for (i in 0 until n) {
            val dx = baseX[i] - cx
            val dy = baseY[i] - cy
            val d = sqrt(dx * dx + dy * dy)
            val q = d / r
            val nd = d + opening * r * 0.78f / (1f + q * q * q)
            if (nd < r * 1.04f) continue
            val swirl = opening * 0.42f * (r / (nd + r * 0.6f)) * sin(t * 0.38f + q * 0.55f)
            val a = atan2(dy, dx) + swirl
            val fade = (1f - nd / reachMax).coerceIn(0f, 1f)
            val ripple = wave * (0.5f + 0.5f * sin(nd / r * 3.3f - t * 5.2f))
            val alpha = fade * gain * (0.8f + 0.9f * ripple)
            val layer = when {
                alpha > 0.62f -> 0
                alpha > 0.40f -> 1
                alpha > 0.20f -> 2
                alpha > 0.05f -> 3
                else -> continue
            }
            val arr = layers[layer]
            val at = fill[layer] * 2
            arr[at] = cx + cos(a) * nd
            arr[at + 1] = cy + sin(a) * nd
            fill[layer]++
        }
        val tint = ZedScheme.lerp(scheme.onSurface, scheme.primary, (opening * 0.85f + wave * 0.35f).coerceAtMost(1f))
        for (k in 0 until 4) {
            val count = fill[k]
            if (count == 0) continue
            dotPaint.color = ZedScheme.withAlpha(tint, layerAlpha[k])
            canvas.drawPoints(layers[k], 0, count * 2, dotPaint)
        }

        val ringAlpha = 0.10f + 0.16f * opening + 0.08f * wave
        val ringColor = ZedScheme.lerp(scheme.onSurface, scheme.primary, opening)
        val solid = opening > 0.6f
        ringPaint.pathEffect = if (solid) null else DashPathEffect(floatArrayOf(dp(2f), dp(7f)), if (searching) -t * 60f else 0f)
        ringPaint.color = ZedScheme.withAlpha(ringColor, ringAlpha)
        canvas.drawCircle(cx, cy, r * 1.30f, ringPaint)
        ringPaint.color = ZedScheme.withAlpha(ringColor, ringAlpha * 0.7f)
        canvas.drawCircle(cx, cy, r * 1.62f, ringPaint)

        if (opening > 0.5f) {
            val moonAlpha = ((opening - 0.5f) * 2f).coerceIn(0f, 1f)
            val moonSize = dp(4f)
            for (trail in 0 until 5) {
                val lag = trail * 0.09f
                val fadeOut = moonAlpha * (1f - trail / 5f)
                dotFill.color = ZedScheme.withAlpha(scheme.tertiary, fadeOut)
                canvas.drawCircle(cx + cos(moonA - lag) * r * 1.30f, cy + sin(moonA - lag) * r * 1.30f, moonSize * (1f - trail * 0.14f), dotFill)
                dotFill.color = ZedScheme.withAlpha(scheme.primary, fadeOut)
                canvas.drawCircle(cx + cos(moonB + lag) * r * 1.62f, cy + sin(moonB + lag) * r * 1.62f, moonSize * 0.8f * (1f - trail * 0.14f), dotFill)
            }
        }

        val particle = dp(2.2f)
        for (i in 0 until MAX_PARTICLES) {
            if (!pAlive[i]) continue
            val dist = pReach[i]
            val life = if (pInbound[i]) ((dist - 1f) / 2.2f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
            else (1f - (dist - 1f) / 2.4f).coerceIn(0f, 1f)
            val fadeNear = ((dist - 1f) / 0.25f).coerceIn(0f, 1f)
            dotFill.color = ZedScheme.withAlpha(if (pInbound[i]) scheme.tertiary else scheme.primary, 0.85f * life * fadeNear)
            canvas.drawCircle(cx + cos(pAngle[i]) * dist * r, cy + sin(pAngle[i]) * dist * r, particle, dotFill)
        }
    }

    private fun drawDoor(canvas: Canvas, cx0: Float, cy: Float, r: Float) {
        val shake = shakeOffset()
        val cx = cx0 + shake
        val s = squash
        canvas.save()
        canvas.scale(s, s, cx, cy)
        val progress = springProgress(morphProgress)
        val turn = when {
            searching && animationsEnabled() -> clock * 150f
            live && animationsEnabled() -> clock * 8f
            else -> 0f
        }
        toShape.let { target ->
            fromShape.buildPath(doorPath, cx, cy, r, turn, target, progress)
        }
        doorPaint.shader = if (searching) {
            SweepGradient(cx, cy, intArrayOf(c1, c2, c3, c1), null)
        } else {
            RadialGradient(cx, cy, r, intArrayOf(c1, c2, c3), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawPath(doorPath, doorPaint)
        if (Color.alpha(rim) > 3) {
            rimPaint.color = rim
            rimPaint.strokeWidth = dp(1.6f)
            canvas.drawPath(doorPath, rimPaint)
            if (sealed > 0.01f) {
                canvas.save()
                canvas.scale(0.78f, 0.78f, cx, cy)
                rimPaint.color = ZedScheme.withAlpha(rim, 0.55f * sealed)
                rimPaint.strokeWidth = dp(1.6f) / 0.78f
                canvas.drawPath(doorPath, rimPaint)
                canvas.restore()
            }
        }
        if (tunnel > 0.01f) {
            val drift = if (animationsEnabled()) clock * 0.22f else 0f
            for (k in 0 until TUNNEL_RINGS) {
                val phase = (drift + k.toFloat() / TUNNEL_RINGS) % 1f
                val ringRadius = r * (1f - phase) * 0.92f
                val life = 4f * phase * (1f - phase)
                tunnelPaint.color = ZedScheme.withAlpha(ink, 0.30f * life * tunnel)
                tunnelPaint.strokeWidth = dp(1.6f) * (0.6f + (1f - phase) * 0.9f)
                canvas.drawCircle(cx, cy, ringRadius, tunnelPaint)
            }
        }
        // centre: elapsed time while open, the Cat glyph otherwise
        if (live && centerText.isNotEmpty()) {
            textPaint.color = ink
            textPaint.textSize = r * 2f * 0.15f * (if (centerText.length > 5) 0.86f else 1f)
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(centerText, cx, baseline, textPaint)
        } else glyph?.let { d ->
            val half = (r * 2f * 0.36f / 2f).toInt()
            d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            d.setTint(ink)
            d.draw(canvas)
        }
        canvas.restore()
    }

    private fun shakeOffset(): Float {
        if (shakeStartNanos == 0L || !animationsEnabled()) return 0f
        val elapsed = (System.nanoTime() - shakeStartNanos) / 1e9f
        val keys = floatArrayOf(0f, 14f, -11f, 8f, -5f, 2f, 0f)
        val step = 0.055f
        val idx = (elapsed / step).toInt()
        if (idx >= keys.size - 1) {
            shakeStartNanos = 0L
            return 0f
        }
        val f = (elapsed - idx * step) / step
        return (keys[idx] + (keys[idx + 1] - keys[idx]) * f) * density
    }

    /** Under-damped spring feel (ζ≈0.62) on a 0..1 timeline. */
    private fun springProgress(t: Float): Float {
        if (t >= 1f) return 1f
        val zeta = 0.62f
        val omega = 9.5f
        val x = t * 1.35f
        val wd = omega * sqrt(1f - zeta * zeta)
        return (1f - exp(-zeta * omega * x) * (cos(wd * x) + zeta / sqrt(1f - zeta * zeta) * sin(wd * x)))
    }

    private companion object {
        const val MAX_PARTICLES = 72
        const val TUNNEL_RINGS = 6
        const val TAU = (2.0 * Math.PI).toFloat()
        const val SEARCH_STEP_SECONDS = 0.72f
        const val MORPH_SECONDS = 0.55f
        const val SETTLE_NANOS = 1_800_000_000L
        val SEARCH_SHAPES = listOf(
            ZedShape.SoftBurst, ZedShape.Cookie9, ZedShape.Pentagon, ZedShape.Pill,
            ZedShape.Sunny, ZedShape.Cookie4, ZedShape.Oval,
        )

        /** Log scale like ZedSecure's rateFraction: ~1 KB/s → 0, ~10 MB/s → 1. */
        fun rateFraction(bps: Long): Float {
            if (bps <= 1_000L) return 0f
            return ((log10(bps.toDouble()) - 3.0) / 4.0).toFloat().coerceIn(0f, 1f)
        }
    }
}

package com.cat.client

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/**
 * ZedSecure's ConnectingBackdrop + ParticleBurst + DecorativeBackdrop as one animated drawable:
 *  • a full-page vertical gradient whose intensity eases in/out with the VPN state (900 ms);
 *  • four expanding white rings pulsing from the hero centre (2.6 s loop) while connected/connecting;
 *  • a 56-piece confetti burst (squares + circles, Zed palette) fired on every successful connect;
 *  • the slowly spinning gradient "decor" blob peeking in from the top-right while idle.
 */
class ZedLiveBackdropDrawable(density: Float) : Drawable() {
    private val d = density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.WHITE }
    private val decor = ZedDecorDrawable(1f)

    private var state: VpnState = VpnState.Stopped
    private var intensity = 0f
    private var pulse = 0f
    private var spin = 0f
    private var stops: IntArray = CONNECTING
    private var gradient: Shader? = null
    private var gradientHeight = -1
    private var sessionSeed = 0

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, val size: Float, val square: Boolean, var life: Float = 1f)
    private val particles = ArrayList<Particle>()
    private var lastFrameNs = 0L

    private var intensityAnimator: ValueAnimator? = null
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_600L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            spin = (spin + 360f / (120f * 60f)) % 360f
            stepParticles()
            invalidateSelf()
        }
    }

    fun setThemeColors(primary: Int, secondary: Int, isDefault: Boolean) = decor.setThemeColors(primary, secondary, isDefault)

    fun start() { if (!ticker.isStarted) ticker.start() }
    fun stop() { ticker.cancel(); intensityAnimator?.cancel() }

    fun setVpnState(newState: VpnState) {
        if (newState == VpnState.Started && state != VpnState.Started) {
            sessionSeed++
            burst()
        }
        state = newState
        stops = if (newState == VpnState.Started) connectedStops(sessionSeed) else CONNECTING
        gradientHeight = -1
        val target = when (newState) {
            VpnState.Starting -> 0.72f
            VpnState.Started -> 1f
            VpnState.Stopping -> 0.35f
            else -> 0f
        }
        intensityAnimator?.cancel()
        intensityAnimator = ValueAnimator.ofFloat(intensity, target).apply {
            duration = 900L; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { intensity = it.animatedValue as Float; invalidateSelf() }
            start()
        }
        start()
    }

    private fun burst() {
        val b = bounds
        if (b.isEmpty) return
        val cx = b.exactCenterX(); val cy = b.top + b.height() * 0.42f
        repeat(56) {
            val angle = Random.nextFloat() * (2f * PI.toFloat())
            val speed = (8f + Random.nextFloat() * 20f) * d / 2.75f
            particles += Particle(
                cx, cy, cos(angle) * speed, sin(angle) * speed - 8f * d / 2.75f,
                BURST[Random.nextInt(BURST.size)], (7f + Random.nextFloat() * 11f) * d / 2.75f, Random.nextBoolean(),
            )
        }
        lastFrameNs = 0L
    }

    private fun stepParticles() {
        if (particles.isEmpty()) return
        val now = System.nanoTime()
        val step = if (lastFrameNs == 0L) 1f else ((now - lastFrameNs) / 16_666_666f).coerceIn(0f, 3f)
        lastFrameNs = now
        val k = d / 2.75f
        particles.forEach { p ->
            p.vy += 0.9f * k * step
            p.vx *= 1f - 0.02f * step
            p.x += p.vx * step; p.y += p.vy * step
            p.life -= 0.014f * step
        }
        val limit = bounds.bottom + 60f * d
        particles.removeAll { it.life <= 0f || it.y > limit }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (intensity > 0.002f) {
            if (gradientHeight != b.height()) {
                gradient = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), stops, null, Shader.TileMode.CLAMP)
                gradientHeight = b.height()
            }
            paint.shader = gradient
            paint.alpha = (255 * 0.9f * intensity).toInt()
            canvas.drawRect(b, paint)
            paint.shader = null; paint.alpha = 255
            val cx = b.exactCenterX(); val cy = b.top + b.height() * 0.42f
            for (i in 0 until 4) {
                val p = (pulse + i / 4f) % 1f
                ring.alpha = (255 * 0.14f * (1f - p) * intensity).toInt()
                canvas.drawCircle(cx, cy, b.height() * 0.06f + p * b.height() * 0.42f, ring)
            }
        }
        // decor blob fades as the connected gradient takes over
        val decorAlpha = 1f - 0.45f * intensity
        decor.setBounds(b.left, b.top, b.right, b.bottom)
        decor.setFraction(decorAlpha)
        canvas.save()
        canvas.rotate(spin, b.right + b.width() * 0.02f, b.top - b.width() * 0.10f)
        decor.draw(canvas)
        canvas.restore()

        particles.forEach { p ->
            paint.color = p.color; paint.alpha = (255 * p.life.coerceIn(0f, 1f)).toInt()
            if (p.square) canvas.drawRect(p.x - p.size / 2f, p.y - p.size / 2f, p.x + p.size / 2f, p.y + p.size / 2f, paint)
            else canvas.drawCircle(p.x, p.y, p.size / 2f, paint)
        }
        paint.alpha = 255
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun connectedStops(seed: Int): IntArray {
        val sets = arrayOf(
            intArrayOf(ZedBlobView.ZED_VIOLET, ZedBlobView.ZED_HOT_PINK, ZedBlobView.ZED_LIME),
            intArrayOf(ZedBlobView.ZED_DEEP_VIOLET, ZedBlobView.ZED_VIOLET, ZedBlobView.ZED_CYAN),
            intArrayOf(ZedBlobView.ZED_HOT_PINK, ZedBlobView.ZED_VIOLET, ZedBlobView.ZED_CYAN),
            intArrayOf(ZedBlobView.ZED_VIOLET, ZedBlobView.ZED_CYAN, ZedBlobView.ZED_LIME),
            intArrayOf(ZedBlobView.ZED_DEEP_VIOLET, ZedBlobView.ZED_HOT_PINK, ZedBlobView.ZED_VIOLET),
        )
        return intArrayOf(ZedBlobView.ZED_DEEP_VIOLET) + sets[((seed % sets.size) + sets.size) % sets.size]
    }

    private companion object {
        val CONNECTING = intArrayOf(ZedBlobView.ZED_DEEP_VIOLET, ZedBlobView.ZED_VIOLET, ZedBlobView.ZED_HOT_PINK)
        val BURST = intArrayOf(ZedBlobView.ZED_VIOLET, ZedBlobView.ZED_HOT_PINK, ZedBlobView.ZED_LIME, ZedBlobView.ZED_CYAN)
    }
}

/**
 * Material 3 Expressive LinearWavyProgressIndicator, as used by Zed's traffic tiles: the
 * progressed part is a travelling sine wave whose amplitude follows the live transfer rate
 * (log scale, 10 MB/s = full), the rest is a thin track with a stop dot.
 */
class ZedWavyProgressView(context: Context) : View(context) {
    var color: Int = 0xFFC7BFFF.toInt()
    var trackColor: Int = 0x40FFFFFF.toInt()
    var idleColor: Int = 0x80FFFFFF.toInt()

    private var progress = 0f
    private var amplitude = 0f
    private var targetAmplitude = 0f
    private var phase = 0f
    private var flowing = false
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 4f * resources.displayMetrics.density }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 4f * resources.displayMetrics.density }
    private val path = Path()
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            phase = (phase + 0.045f) % 1f
            amplitude += (targetAmplitude - amplitude) * 0.08f
            invalidate()
        }
    }

    /** Feed the current rate; 0 collapses the wave into a flat line. */
    fun setRate(bytesPerSecond: Long, live: Boolean) {
        val fraction = if (!live || bytesPerSecond <= 0L) 0f
        else (ln(1.0 + bytesPerSecond / 1024.0) / FULL_SCALE_LN).toFloat().coerceIn(0f, 1f)
        progress = fraction
        targetAmplitude = fraction
        flowing = live && bytesPerSecond > 0L
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.start() }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (14f * resources.displayMetrics.density).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val inset = wave.strokeWidth
        val cy = h / 2f
        val usable = w - inset * 2
        val end = inset + usable * progress
        val gap = 6f * resources.displayMetrics.density
        // track
        track.color = trackColor
        if (end + gap < w - inset) canvas.drawLine(end + gap, cy, w - inset, cy, track)
        track.color = color
        canvas.drawPoint(w - inset, cy, track)
        // wave
        val amp = (h / 2f - inset / 2f) * amplitude
        val wavelength = 40f * resources.displayMetrics.density
        wave.color = if (flowing) color else idleColor
        if (end - inset < 1f) { canvas.drawPoint(inset, cy, wave); return }
        path.rewind()
        var x = inset
        path.moveTo(x, cy + amp * sin((x / wavelength - phase) * 2f * PI.toFloat()))
        while (x < end) {
            x = (x + 3f).coerceAtMost(end)
            path.lineTo(x, cy + amp * sin((x / wavelength - phase) * 2f * PI.toFloat()))
        }
        canvas.drawPath(path, wave)
    }

    private companion object {
        val FULL_SCALE_LN = ln(1.0 + 10 * 1024.0)
    }
}

/** Zed RouteBadge connector: dotted line, a head dot, and a dot travelling origin → exit (1.8 s). */
class ZedRouteConnectorView(context: Context) : View(context) {
    var color: Int = 0xFFC7BFFF.toInt()
    private var travel = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_800L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { travel = it.animatedValue as Float; invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.start() }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val y = height / 2f
        val w = width.toFloat()
        var t = 0f
        paint.color = color; paint.alpha = 77
        while (t <= 1f) { canvas.drawCircle(w * t, y, 1.6f * d, paint); t += 0.18f }
        paint.alpha = 255
        canvas.drawCircle(w - 3f * d, y, 3.2f * d, paint)
        val x = w * travel
        paint.alpha = 64; canvas.drawCircle(x, y, 6f * d, paint)
        paint.alpha = 255; canvas.drawCircle(x, y, 2.6f * d, paint)
    }
}

/**
 * iOS-style press squish: the view scales down under the finger and springs
 * back with a slight overshoot on release. Purely visual — the touch listener
 * never consumes the event, so clicks and ripples keep working untouched.
 */
object ZedIosMotion {
    fun press(view: View, pressedScale: Float = 0.94f) {
        fun animateTo(scale: Float, durationMs: Long) {
            val ti = if (scale < 1f) DecelerateInterpolator() else OvershootInterpolator(2.2f)
            for (prop in listOf(View.SCALE_X, View.SCALE_Y)) {
                ObjectAnimator.ofFloat(view, prop, scale).apply {
                    duration = durationMs
                    interpolator = ti
                    start()
                }
            }
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateTo(pressedScale, 120)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateTo(1f, 260)
            }
            false // never consume
        }
    }
}

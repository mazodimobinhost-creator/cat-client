package com.cat.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Home strip shown while connected: hops on this line and a packet moving along them.
 * Not a new button. Uses the measured IPs and the connected protocol.
 */
class ConnectionPathView(context: Context) : View(context) {
    private val palette = CatClientDesignTokens.forContext(context)

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val packetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = palette.textPrimary
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        color = palette.textSecondary
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = palette.textPrimary
    }

    private var snapshot: ConnectionPathSnapshot? = null
    private var phase = 0f
    private var lastFrameAt = 0L
    private var frameRunning = false
    private val panel = RectF()
    private val xs = FloatArray(6)
    private val ys = FloatArray(6)

    private val frame = object : Runnable {
        override fun run() {
            if (!frameRunning || visibility != VISIBLE) return
            val now = System.nanoTime()
            val dt = if (lastFrameAt == 0L) 0.016f
            else ((now - lastFrameAt) / 1_000_000_000f).coerceIn(0.001f, 0.08f)
            lastFrameAt = now
            phase = (phase + dt * 0.28f) % 1f
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun bind(next: ConnectionPathSnapshot?) {
        snapshot = next
        contentDescription = next?.caption.orEmpty()
        val show = next != null && next.hops.size >= 2
        visibility = if (show) VISIBLE else GONE
        if (show) startFrame() else stopFrame()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startFrame()
    }

    override fun onDetachedFromWindow() {
        stopFrame()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && snapshot != null) startFrame() else stopFrame()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = dp(118f).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val hops = snapshot?.hops ?: return
        if (hops.size < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val n = min(hops.size, 6)
        val accent = palette.teal
        val dark = palette.isDark

        panel.set(dp(2f), dp(2f), w - dp(2f), h - dp(2f))
        panelPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                withAlpha(palette.surfaceElevated1, if (dark) 235 else 250),
                withAlpha(palette.surface, if (dark) 210 else 240),
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(panel, dp(22f), dp(22f), panelPaint)
        panelPaint.shader = null

        val left = panel.left + dp(22f)
        val right = panel.right - dp(22f)
        val nodeY = panel.top + dp(44f)
        for (i in 0 until n) {
            val t = if (n == 1) 0.5f else i / (n - 1).toFloat()
            xs[i] = if (rtl) right - t * (right - left) else left + t * (right - left)
            ys[i] = nodeY
        }

        linePaint.color = withAlpha(accent, 180)
        for (i in 0 until n - 1) {
            canvas.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1], linePaint)
        }

        val packetT = phase
        val seg = packetT * (n - 1)
        val i0 = seg.toInt().coerceIn(0, n - 2)
        val local = seg - i0
        val px = xs[i0] + (xs[i0 + 1] - xs[i0]) * local
        val py = ys[i0] + (ys[i0 + 1] - ys[i0]) * local
        packetPaint.color = withAlpha(accent, 70)
        canvas.drawCircle(px, py, dp(9f) + dp(2f) * pulse(), packetPaint)
        packetPaint.color = accent
        canvas.drawCircle(px, py, dp(5.5f), packetPaint)
        packetPaint.color = palette.onAccent
        canvas.drawCircle(px, py, dp(2.4f), packetPaint)

        titlePaint.textSize = dp(11f)
        detailPaint.textSize = dp(9f)
        for (i in 0 until n) {
            val hop = hops[i]
            nodePaint.color = if (i == 0 || i == n - 1) accent else withAlpha(accent, 200)
            canvas.drawCircle(xs[i], ys[i], dp(7f), nodePaint)
            nodePaint.color = palette.surface
            canvas.drawCircle(xs[i], ys[i], dp(3.2f), nodePaint)
            canvas.drawText(ellipsize(hop.title, titlePaint, dp(72f)), xs[i], ys[i] - dp(16f), titlePaint)
            canvas.drawText(ellipsize(hop.detail, detailPaint, dp(86f)), xs[i], ys[i] + dp(20f), detailPaint)
        }

        captionPaint.textSize = dp(12f)
        val caption = snapshot?.caption.orEmpty()
        canvas.drawText(ellipsize(caption, captionPaint, panel.width() - dp(20f)), panel.centerX(), panel.bottom - dp(14f), captionPaint)
    }

    private fun startFrame() {
        if (frameRunning) return
        frameRunning = true
        lastFrameAt = 0L
        postOnAnimation(frame)
    }

    private fun stopFrame() {
        frameRunning = false
        removeCallbacks(frame)
    }

    private fun ellipsize(text: String, paint: Paint, max: Float): String {
        if (text.isEmpty() || paint.measureText(text) <= max) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.take(end) + "…") > max) end--
        return text.take(end) + "…"
    }

    private fun pulse(): Float = ((sin((phase * 2f * Math.PI).toDouble()) + 1.0) / 2.0).toFloat()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

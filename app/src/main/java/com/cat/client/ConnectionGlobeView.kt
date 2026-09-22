package com.cat.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A lightweight ProtonVPN-inspired route globe, drawn without a map asset. */
class ConnectionGlobeView(context: Context) : View(context) {
    private val palette = CatClientDesignTokens.forContext(context)
    private val globePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = CatClientBodyTypeface
        textSize = dp(11f)
    }
    private val path = Path()
    private var state: VpnState = VpnState.Stopped
    private var destinationFlag = "🌐"
    private var destinationLabel = ""
    private var destinationCode = ""
    private var routeProgress = 0f
    private var lastFrame = 0L

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun setVpnState(newState: VpnState) {
        if (state == newState) return
        state = newState
        routeProgress = if (newState == VpnState.Started) 1f else 0f
        invalidate()
    }

    fun setDestination(flag: String, label: String = "", ip: String = "") {
        destinationFlag = flag.ifBlank { "🌐" }
        destinationLabel = listOf(label, ip).filter { it.isNotBlank() }.joinToString(" · ")
        destinationCode = flagToCode(destinationFlag).orEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = min(w * 0.29f, h * 0.38f).coerceAtLeast(dp(52f))
        val cx = w / 2f
        val cy = h * 0.47f
        val accent = when (state) {
            VpnState.Started -> palette.teal
            VpnState.Starting, VpnState.Stopping -> palette.amber
            is VpnState.Error, VpnState.DailyLimitReached -> palette.red
            VpnState.Stopped -> palette.primary
        }

        // Soft glass halo.
        globePaint.style = Paint.Style.FILL
        globePaint.color = withAlpha(accent, if (state == VpnState.Started) 18 else 12)
        canvas.drawCircle(cx, cy, radius + dp(18f), globePaint)
        globePaint.color = withAlpha(palette.surfaceElevated2, 225)
        canvas.drawCircle(cx, cy, radius, globePaint)

        gridPaint.color = withAlpha(accent, 72)
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawOval(oval, gridPaint)
        canvas.drawOval(RectF(cx - radius * .43f, cy - radius, cx + radius * .43f, cy + radius), gridPaint)
        canvas.drawOval(RectF(cx - radius * .78f, cy - radius, cx + radius * .78f, cy + radius), gridPaint)
        canvas.drawOval(RectF(cx - radius, cy - radius * .36f, cx + radius, cy + radius * .36f), gridPaint)
        canvas.drawOval(RectF(cx - radius, cy - radius * .72f, cx + radius, cy + radius * .72f), gridPaint)

        // Dotted continents: deterministic latitude/longitude points so the view stays tiny.
        dotPaint.color = withAlpha(palette.textPrimary, 100)
        val continents = listOf(
            -120f to 40f, -105f to 48f, -90f to 38f, -75f to 42f, -65f to 25f,
            -45f to -10f, -60f to -25f, -50f to -40f,
            -10f to 48f, 5f to 52f, 20f to 42f, 30f to 55f, 45f to 42f,
            60f to 25f, 75f to 45f, 90f to 30f, 110f to 45f, 125f to 32f,
            140f to 20f, 115f to 5f, 80f to 5f, 35f to 5f, 20f to -20f,
            135f to -25f, 150f to -35f,
        )
        continents.forEach { (lon, lat) ->
            val p = globePoint(cx, cy, radius * .92f, lon, lat)
            canvas.drawCircle(p.first, p.second, dp(1.8f), dotPaint)
        }

        val iran = globePoint(cx, cy, radius * .92f, 51.4f, 35.7f)
        val destination = destinationPoint(cx, cy, radius * .92f)
        val connected = state == VpnState.Started || state == VpnState.Starting
        if (connected) {
            routePaint.color = withAlpha(accent, 210)
            path.reset()
            path.moveTo(iran.first, iran.second)
            val controlX = (iran.first + destination.first) / 2f
            val controlY = min(iran.second, destination.second) - radius * .72f
            path.quadTo(controlX, controlY, destination.first, destination.second)
            canvas.drawPath(path, routePaint)
        }
        markerPaint.color = accent
        canvas.drawCircle(iran.first, iran.second, dp(if (connected) 5f else 4f), markerPaint)
        markerPaint.color = if (state == VpnState.Started) palette.teal else withAlpha(accent, 190)
        canvas.drawCircle(destination.first, destination.second, dp(if (state == VpnState.Started) 5f else 3.5f), markerPaint)
        labelPaint.color = palette.textSecondary
        canvas.drawText("ایران", iran.first, iran.second + radius * .22f, labelPaint)
        canvas.drawText(destinationFlag, destination.first, destination.second - radius * .16f, labelPaint)

        labelPaint.color = palette.textPrimary
        labelPaint.textSize = dp(12f)
        val title = when (state) {
            VpnState.Started -> if (destinationLabel.isBlank()) "مسیر امن فعال" else destinationLabel
            VpnState.Starting -> "در حال برقراری مسیر…"
            VpnState.Stopping -> "در حال بستن مسیر…"
            is VpnState.Error -> "مسیر در دسترس نیست"
            else -> "از ایران تا مقصد شما"
        }
        canvas.drawText(title, cx, h - dp(13f), labelPaint)
        labelPaint.textSize = dp(11f)
    }

    private fun destinationPoint(cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val coordinates = when (destinationCode) {
            "DE" -> 10.5f to 51f
            "NL" -> 5f to 52f
            "FR" -> 2f to 46f
            "GB" -> -2f to 54f
            "TR" -> 35f to 39f
            "AE" -> 54f to 24f
            "SG" -> 104f to 1f
            "JP" -> 139f to 36f
            "KR" -> 127f to 37f
            "US" -> -100f to 38f
            "CA" -> -106f to 56f
            "AU" -> 134f to -25f
            "IN" -> 78f to 22f
            "BR" -> -52f to -10f
            else -> 15f to 48f
        }
        return globePoint(cx, cy, radius, coordinates.first, coordinates.second)
    }

    private fun globePoint(cx: Float, cy: Float, radius: Float, lon: Float, lat: Float): Pair<Float, Float> =
        (cx + radius * (lon / 180f) * .92f) to (cy - radius * (lat / 90f) * .78f)

    private fun flagToCode(flag: String): String? {
        val chars = flag.codePoints().toArray()
        if (chars.size != 2 || chars.any { it !in 0x1F1E6..0x1F1FF }) return null
        return chars.map { (it - 0x1F1E6 + 'A'.code).toChar() }.joinToString("")
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

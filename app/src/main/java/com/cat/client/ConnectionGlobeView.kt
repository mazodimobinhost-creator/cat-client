package com.cat.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * ConnectionGlobeView — a real, flat world map (Natural Earth 110m land
 * silhouette) for the Cat dashboard, ProtonVPN-style:
 *
 *  - actual continents drawn from [WorldMapData], not a dotted sphere,
 *  - edge-location dots, an Iran marker and the live destination marker,
 *  - an animated Iran → destination route with a travelling packet,
 *  - pulsing halos + a slow light sweep so the map always feels alive,
 *  - the real country + flag + exit IP drawn under the map.
 */
class ConnectionGlobeView(context: Context) : View(context) {
    private val palette = CatClientDesignTokens.forContext(context)

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
    }
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val landStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }

    private val rings by lazy { WorldMapData.rings() }
    private var landPath: Path? = null
    private var landBuiltW = 0f
    private var landBuiltH = 0f

    private data class WorldDot(val code: String, val lon: Float, val lat: Float)

    // Representative edge locations across every continent (Cloudflare anycast
    // has no fixed country; the selected colo is the location we highlight).
    private val worldDots = listOf(
        WorldDot("CA", -106f, 56f), WorldDot("US", -100f, 38f), WorldDot("MX", -102f, 23f),
        WorldDot("BR", -52f, -10f), WorldDot("AR", -64f, -34f), WorldDot("CL", -71f, -33f),
        WorldDot("GB", -2f, 54f), WorldDot("IE", -8f, 53f), WorldDot("FR", 2f, 46f),
        WorldDot("ES", -4f, 40f), WorldDot("PT", -8f, 39f), WorldDot("DE", 10.5f, 51f),
        WorldDot("NL", 5f, 52f), WorldDot("BE", 4f, 50.8f), WorldDot("CH", 8f, 46.8f),
        WorldDot("IT", 12f, 42f), WorldDot("AT", 14f, 47.5f), WorldDot("PL", 19f, 52f),
        WorldDot("SE", 18f, 62f), WorldDot("NO", 10f, 62f), WorldDot("FI", 26f, 64f),
        WorldDot("GR", 22f, 39f), WorldDot("TR", 35f, 39f), WorldDot("RU", 90f, 56f),
        WorldDot("MA", -6f, 32f), WorldDot("EG", 30f, 27f), WorldDot("ZA", 24f, -30f),
        WorldDot("NG", 8f, 9f), WorldDot("KE", 37f, -1f), WorldDot("AE", 54f, 24f),
        WorldDot("SA", 45f, 24f), WorldDot("IL", 35f, 31f), WorldDot("IN", 78f, 22f),
        WorldDot("PK", 70f, 30f), WorldDot("CN", 105f, 35f), WorldDot("JP", 139f, 36f),
        WorldDot("KR", 127f, 37f), WorldDot("HK", 114f, 22f), WorldDot("SG", 104f, 1f),
        WorldDot("MY", 102f, 4f), WorldDot("ID", 118f, -2f), WorldDot("AU", 134f, -25f),
        WorldDot("NZ", 174f, -41f),
    )

    private var state: VpnState = VpnState.Stopped
    private var destinationFlag = ""
    private var destinationLabel = ""
    private var destinationCode = ""
    private var destinationIp = ""
    private var routeProgress = 0f
    private var routeTarget = 0f
    private var phase = 0f
    private var lastFrameAt = 0L
    private var frameRunning = false

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (lastFrameAt == 0L) 0.016f
            else ((now - lastFrameAt) / 1_000_000_000f).coerceIn(0.001f, 0.08f)
            lastFrameAt = now
            phase = (phase + dt) % 1f
            routeProgress = if (abs(routeTarget - routeProgress) <= dt * 1.5f) routeTarget
            else routeProgress + (if (routeTarget > routeProgress) dt * 1.5f else -dt * 2.2f)
            invalidate()
            if (frameRunning) postOnAnimation(this)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frameRunning = true
        lastFrameAt = 0L
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        frameRunning = false
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    fun setVpnState(newState: VpnState) {
        if (state == newState) return
        state = newState
        routeTarget = if (newState == VpnState.Started || newState == VpnState.Starting) 1f else 0f
        if (newState == VpnState.Starting) routeProgress = 0f
        invalidate()
    }

    fun setDestination(flag: String, label: String = "", ip: String = "") {
        destinationFlag = flag.orEmpty()
        destinationLabel = label.ifBlank { "Cloudflare edge" }
        destinationCode = flagToCode(destinationFlag).orEmpty()
        destinationIp = ip.orEmpty()
        invalidate()
    }

    private fun accentColor(): Int = when (state) {
        VpnState.Started -> palette.teal
        VpnState.Starting, VpnState.Stopping -> palette.amber
        is VpnState.Error, VpnState.DailyLimitReached -> palette.red
        VpnState.Stopped -> palette.neutral
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val accent = accentColor()
        val dark = palette.isDark

        // ── map panel ────────────────────────────────────────────────────
        val panelLeft = dp(10f)
        val panelRight = w - dp(10f)
        val panelTop = dp(8f)
        val panelBottom = h - dp(58f)
        val panel = RectF(panelLeft, panelTop, panelRight, panelBottom)
        panelPaint.shader = LinearGradient(
            0f, panelTop, 0f, panelBottom,
            intArrayOf(
                withAlpha(palette.surfaceElevated1, if (dark) 235 else 250),
                withAlpha(palette.surface, if (dark) 205 else 235),
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(panel, dp(22f), dp(22f), panelPaint)
        panelPaint.shader = null
        panelStroke.color = withAlpha(accent, if (state == VpnState.Started) 90 else 55)
        canvas.drawRoundRect(panel, dp(22f), dp(22f), panelStroke)

        // ── equirectangular map area (lat 90..-85.6) ────────────────────
        val innerPad = dp(12f)
        var mapW = panel.width() - innerPad * 2
        var mapH = mapW * (MAP_LAT_SPAN / 360f)
        val maxH = panel.height() - innerPad * 2
        if (mapH > maxH) {
            mapH = maxH
            mapW = mapH * (360f / MAP_LAT_SPAN)
        }
        val mx = panel.centerX() - mapW / 2f
        val my = panel.centerY() - mapH / 2f

        fun px(lon: Float): Float = mx + (lon + 180f) / 360f * mapW
        fun py(lat: Float): Float = my + (90f - lat) / MAP_LAT_SPAN * mapH

        // graticule
        gridPaint.color = withAlpha(palette.textPrimary, if (dark) 16 else 26)
        var lonLine = -150f
        while (lonLine <= 150f) {
            canvas.drawLine(px(lonLine), my, px(lonLine), my + mapH, gridPaint)
            lonLine += 30f
        }
        var latLine = 60f
        while (latLine >= -60f) {
            canvas.drawLine(mx, py(latLine), mx + mapW, py(latLine), gridPaint)
            latLine -= 30f
        }

        // ── real continents ──────────────────────────────────────────────
        if (landPath == null || landBuiltW != mapW || landBuiltH != mapH) {
            val path = Path()
            for (ring in rings) {
                if (ring.size < 8) continue
                var x = ring[0]
                var y = ring[1]
                path.moveTo(px(x / 10f), py(y / 10f))
                var i = 2
                while (i < ring.size) {
                    x += ring[i]
                    y += ring[i + 1]
                    path.lineTo(px(x / 10f), py(y / 10f))
                    i += 2
                }
                path.close()
            }
            landPath = path
            landBuiltW = mapW
            landBuiltH = mapH
        }
        landPath?.let { land ->
            landPaint.shader = LinearGradient(
                0f, my, 0f, my + mapH,
                intArrayOf(
                    withAlpha(accent, if (dark) 150 else 120),
                    withAlpha(palette.neutral, if (dark) 105 else 85),
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(land, landPaint)
            landPaint.shader = null
            landStroke.color = withAlpha(palette.textPrimary, if (dark) 70 else 90)
            canvas.drawPath(land, landStroke)
        }

        // slow light sweep keeps the map alive even when idle
        val sweepX = mx + ((phase + 0.18f) % 1f) * mapW
        sweepPaint.shader = LinearGradient(
            sweepX - dp(46f), 0f, sweepX + dp(46f), 0f,
            intArrayOf(Color.TRANSPARENT, withAlpha(palette.textPrimary, if (dark) 14 else 22), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.clipPath(landPath ?: Path())
        canvas.drawRect(mx, my, mx + mapW, my + mapH, sweepPaint)
        canvas.restore()
        sweepPaint.shader = null

        // ── edge-location dots ───────────────────────────────────────────
        dotPaint.color = withAlpha(palette.textPrimary, if (dark) 90 else 120)
        worldDots.forEach { dot ->
            canvas.drawCircle(px(dot.lon), py(dot.lat), dp(1.7f), dotPaint)
        }

        // ── Iran ─────────────────────────────────────────────────────────
        val iranX = px(51.4f)
        val iranY = py(35.7f)
        val connected = state == VpnState.Started || state == VpnState.Starting
        markerPaint.color = palette.red
        canvas.drawCircle(iranX, iranY, dp(if (connected) 4.6f else 3.6f), markerPaint)
        labelPaint.textSize = dp(10f)
        labelPaint.color = palette.textSecondary
        canvas.drawText(context.getString(R.string.globe_iran), iranX, iranY + dp(16f), labelPaint)

        // ── destination ──────────────────────────────────────────────────
        if (destinationCode.isNotEmpty()) {
            val coords = coordinatesForCode(destinationCode)
            val destX = px(coords.first)
            val destY = py(coords.second)
            if (connected) {
                drawRoute(canvas, iranX, iranY, destX, destY, accent)
                haloPaint.color = withAlpha(accent, 40)
                canvas.drawCircle(destX, destY, dp(9f) + dp(3.5f) * pulse(), haloPaint)
            }
            markerPaint.color = if (state == VpnState.Started) palette.teal else withAlpha(accent, 220)
            canvas.drawCircle(destX, destY, dp(if (state == VpnState.Started) 4.8f else 3.4f), markerPaint)
            labelPaint.textSize = dp(15f)
            canvas.drawText(destinationFlag, destX, destY - dp(10f), labelPaint)
        }

        // ── status line (country + real exit IP) ─────────────────────────
        val title = when (state) {
            VpnState.Started -> destinationLabel.ifBlank { context.getString(R.string.globe_route_active) }
            VpnState.Starting -> context.getString(R.string.globe_route_connecting)
            VpnState.Stopping -> context.getString(R.string.globe_route_closing)
            is VpnState.Error -> context.getString(R.string.globe_route_unavailable)
            VpnState.DailyLimitReached -> context.getString(R.string.globe_route_limit)
            VpnState.Stopped -> context.getString(R.string.globe_route_idle)
        }
        labelPaint.textSize = dp(12.5f)
        labelPaint.color = palette.textPrimary
        canvas.drawText(title, panel.centerX(), h - dp(34f), labelPaint)
        if (destinationIp.isNotBlank() && state == VpnState.Started) {
            labelPaint.textSize = dp(10f)
            labelPaint.color = palette.textSecondary
            canvas.drawText(destinationIp, panel.centerX(), h - dp(17f), labelPaint)
        }
    }

    private fun drawRoute(canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float, accent: Int) {
        val path = Path()
        path.moveTo(startX, startY)
        val controlX = (startX + endX) / 2f
        val controlY = min(startY, endY) - dp(34f)
        path.quadTo(controlX, controlY, endX, endY)
        val measure = PathMeasure(path, false)
        val length = measure.length
        if (length <= 0f) return

        val visible = length * routeProgress.coerceIn(0f, 1f)
        val segment = Path()
        measure.getSegment(0f, visible, segment, true)
        routePaint.color = withAlpha(accent, 235)
        routePaint.pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(7f)), -(phase * 260f))
        canvas.drawPath(segment, routePaint)
        routePaint.pathEffect = null

        if (routeProgress > 0.01f && routeProgress < 1f) {
            val pos = FloatArray(2)
            measure.getPosTan(visible, pos, null)
            markerPaint.color = palette.onAccent
            canvas.drawCircle(pos[0], pos[1], dp(3f), markerPaint)
            markerPaint.color = accent
            canvas.drawCircle(pos[0], pos[1], dp(5f), markerPaint)
        }
    }

    private fun coordinatesForCode(code: String): Pair<Float, Float> = when (code) {
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
        else -> worldDots.firstOrNull { it.code == code }?.let { it.lon to it.lat } ?: (15f to 48f)
    }

    private fun flagToCode(flag: String): String? {
        val chars = flag.codePoints().toArray()
        if (chars.size != 2 || chars.any { it !in 0x1F1E6..0x1F1FF }) return null
        return chars.map { (it - 0x1F1E6 + 'A'.code).toChar() }.joinToString("")
    }

    private fun pulse(): Float = ((kotlin.math.sin((phase * 2f * Math.PI).toDouble()) + 1.0) / 2.0).toFloat()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** Latitudes span 90°N..-85.6°S in the Natural Earth land extract. */
        const val MAP_LAT_SPAN = 175.6f
    }
}

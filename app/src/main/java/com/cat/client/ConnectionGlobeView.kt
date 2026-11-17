package com.cat.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * ConnectionGlobeView — a rotating 3D orthographic globe on the Cat dashboard:
 *
 *  - Natural Earth 110m land projected onto a sphere (not a flat map),
 *  - slow idle spin; when connected the globe turns toward the exit,
 *  - Iran + destination markers, a great-circle packet, atmosphere rim,
 *  - the real country + flag + exit IP under the globe.
 */
class ConnectionGlobeView(context: Context) : View(context) {
    private val palette = CatClientDesignTokens.forContext(context)

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.7f)
    }
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val landStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.7f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }

    private val rings by lazy { WorldMapData.rings() }

    private data class WorldDot(val code: String, val lon: Float, val lat: Float)

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
    private var yaw = -51.4f
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
            val connected = state == VpnState.Started || state == VpnState.Starting
            val targetYaw = if (connected && destinationCode.isNotEmpty()) {
                val destLon = coordinatesForCode(destinationCode).first
                -((51.4f + destLon) * 0.5f)
            } else {
                yaw + dt * 12f
            }
            val delta = ((targetYaw - yaw + 540f) % 360f) - 180f
            yaw += if (connected && destinationCode.isNotEmpty()) delta * (dt * 1.15f) else dt * 12f
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

        val r = min(panel.width(), panel.height()) * 0.42f
        val cx = panel.centerX()
        val cy = panel.centerY()
        val tilt = 16f

        fun sx(v: Globe3d.Vec) = cx + v.x * r
        fun sy(v: Globe3d.Vec) = cy - v.y * r

        oceanPaint.shader = RadialGradient(
            cx - r * 0.28f, cy - r * 0.32f, r * 1.35f,
            intArrayOf(
                withAlpha(accent, if (dark) 90 else 70),
                withAlpha(palette.surfaceElevated2, if (dark) 230 else 245),
                withAlpha(palette.neutral, if (dark) 200 else 180),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, oceanPaint)
        oceanPaint.shader = null

        rimPaint.strokeWidth = dp(3.2f)
        rimPaint.color = withAlpha(accent, if (dark) 70 else 50)
        canvas.drawCircle(cx, cy, r + dp(2.5f), rimPaint)
        rimPaint.strokeWidth = dp(1.1f)
        rimPaint.color = withAlpha(palette.textPrimary, if (dark) 40 else 55)
        canvas.drawCircle(cx, cy, r, rimPaint)

        gridPaint.color = withAlpha(palette.textPrimary, if (dark) 28 else 36)
        var lat = -60f
        while (lat <= 60f) {
            drawParallel(canvas, lat, yaw, tilt, r, cx, cy)
            lat += 30f
        }
        var lon = -180f
        while (lon < 180f) {
            drawMeridian(canvas, lon, yaw, tilt, r, cx, cy)
            lon += 30f
        }

        val land = Path()
        val outline = Path()
        for (ring in rings) {
            if (ring.size < 8) continue
            var x = ring[0].toFloat()
            var y = ring[1].toFloat()
            var started = false
            var i = 0
            while (true) {
                val v = Globe3d.sphere(x / 10f, y / 10f, yaw, tilt)
                if (Globe3d.front(v)) {
                    val px = sx(v)
                    val py = sy(v)
                    if (!started) {
                        land.moveTo(px, py)
                        outline.moveTo(px, py)
                        started = true
                    } else {
                        land.lineTo(px, py)
                        outline.lineTo(px, py)
                    }
                } else if (started) {
                    started = false
                }
                i += 2
                if (i >= ring.size) break
                x += ring[i]
                y += ring[i + 1]
            }
        }
        landPaint.color = withAlpha(accent, if (dark) 155 else 125)
        canvas.drawPath(land, landPaint)
        landStroke.color = withAlpha(palette.textPrimary, if (dark) 80 else 100)
        canvas.drawPath(outline, landStroke)

        worldDots.forEach { dot ->
            val v = Globe3d.sphere(dot.lon, dot.lat, yaw, tilt)
            if (!Globe3d.front(v, 0.12f)) return@forEach
            val lit = Globe3d.light(v)
            dotPaint.color = withAlpha(palette.textPrimary, (70 + 80 * lit).toInt())
            canvas.drawCircle(sx(v), sy(v), dp(1.8f), dotPaint)
        }

        val iran = Globe3d.sphere(51.4f, 35.7f, yaw, tilt)
        val connected = state == VpnState.Started || state == VpnState.Starting
        if (Globe3d.front(iran, 0.02f)) {
            markerPaint.color = palette.red
            canvas.drawCircle(sx(iran), sy(iran), dp(if (connected) 4.8f else 3.8f), markerPaint)
            labelPaint.textSize = dp(10f)
            labelPaint.color = palette.textSecondary
            canvas.drawText(context.getString(R.string.globe_iran), sx(iran), sy(iran) + dp(16f), labelPaint)
        }

        if (destinationCode.isNotEmpty()) {
            val coords = coordinatesForCode(destinationCode)
            val dest = Globe3d.sphere(coords.first, coords.second, yaw, tilt)
            if (connected) {
                drawArc(canvas, iran, dest, accent, r, cx, cy)
            }
            if (Globe3d.front(dest, 0.02f)) {
                if (connected) {
                    haloPaint.color = withAlpha(accent, 45)
                    canvas.drawCircle(sx(dest), sy(dest), dp(9f) + dp(3.5f) * pulse(), haloPaint)
                }
                markerPaint.color = if (state == VpnState.Started) palette.teal else withAlpha(accent, 220)
                canvas.drawCircle(sx(dest), sy(dest), dp(if (state == VpnState.Started) 5f else 3.6f), markerPaint)
                labelPaint.textSize = dp(15f)
                canvas.drawText(destinationFlag, sx(dest), sy(dest) - dp(10f), labelPaint)
            }
        }

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

    private fun drawParallel(canvas: Canvas, lat: Float, yaw: Float, tilt: Float, r: Float, cx: Float, cy: Float) {
        val path = Path()
        var started = false
        var lon = -180f
        while (lon <= 180f) {
            val v = Globe3d.sphere(lon, lat, yaw, tilt)
            if (Globe3d.front(v, 0.02f)) {
                val px = cx + v.x * r
                val py = cy - v.y * r
                if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            } else started = false
            lon += 8f
        }
        canvas.drawPath(path, gridPaint)
    }

    private fun drawMeridian(canvas: Canvas, lon: Float, yaw: Float, tilt: Float, r: Float, cx: Float, cy: Float) {
        val path = Path()
        var started = false
        var lat = -80f
        while (lat <= 80f) {
            val v = Globe3d.sphere(lon, lat, yaw, tilt)
            if (Globe3d.front(v, 0.02f)) {
                val px = cx + v.x * r
                val py = cy - v.y * r
                if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            } else started = false
            lat += 6f
        }
        canvas.drawPath(path, gridPaint)
    }

    private fun drawArc(
        canvas: Canvas,
        start: Globe3d.Vec,
        end: Globe3d.Vec,
        accent: Int,
        r: Float,
        cx: Float,
        cy: Float,
    ) {
        val path = Path()
        var started = false
        val steps = 36
        val visible = (steps * routeProgress.coerceIn(0f, 1f)).toInt()
        if (visible < 1) return
        var last: Globe3d.Vec? = null
        for (i in 0..visible) {
            val v = Globe3d.lerp(start, end, i / steps.toFloat())
            last = v
            if (!Globe3d.front(v, 0.02f)) {
                started = false
                continue
            }
            val px = cx + v.x * r
            val py = cy - v.y * r
            if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
        }
        routePaint.color = withAlpha(accent, 235)
        routePaint.pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(7f)), -(phase * 260f))
        canvas.drawPath(path, routePaint)
        routePaint.pathEffect = null
        if (routeProgress > 0.01f && routeProgress < 1f) {
            val packet = last ?: return
            if (!Globe3d.front(packet, 0.02f)) return
            val px = cx + packet.x * r
            val py = cy - packet.y * r
            markerPaint.color = palette.onAccent
            canvas.drawCircle(px, py, dp(3f), markerPaint)
            markerPaint.color = accent
            canvas.drawCircle(px, py, dp(5f), markerPaint)
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

    private fun pulse(): Float = ((sin((phase * 2f * Math.PI).toDouble()) + 1.0) / 2.0).toFloat()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

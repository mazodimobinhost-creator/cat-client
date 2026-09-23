package com.cat.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A small, asset-free 3D route globe for the Cat dashboard.
 *
 * It is deliberately drawn as a sphere rather than a flat map: the shaded disc,
 * perspective grid, depth-sorted country dots, slow rotation and animated Iran →
 * destination route all remain crisp on phones, TVs and low-power devices.
 */
class ConnectionGlobeView(context: Context) : View(context) {
    private val palette = CatClientDesignTokens.forContext(context)
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val path = Path()
    private val routeMeasure = PathMeasure()
    private val particlePath = Path()

    private data class WorldDot(
        val code: String,
        val name: String,
        val lon: Float,
        val lat: Float,
        val continent: String,
    )

    private data class Projection(val x: Float, val y: Float, val depth: Float)

    // Representative edge locations across every continent. Cloudflare anycast
    // does not have a fixed country; the selected colo is the location we show.
    private val worldDots = listOf(
        WorldDot("CA", "Canada", -106f, 56f, "North America"),
        WorldDot("US", "United States", -100f, 38f, "North America"),
        WorldDot("MX", "Mexico", -102f, 23f, "North America"),
        WorldDot("BR", "Brazil", -52f, -10f, "South America"),
        WorldDot("AR", "Argentina", -64f, -34f, "South America"),
        WorldDot("CL", "Chile", -71f, -33f, "South America"),
        WorldDot("GB", "United Kingdom", -2f, 54f, "Europe"),
        WorldDot("IE", "Ireland", -8f, 53f, "Europe"),
        WorldDot("FR", "France", 2f, 46f, "Europe"),
        WorldDot("ES", "Spain", -4f, 40f, "Europe"),
        WorldDot("PT", "Portugal", -8f, 39f, "Europe"),
        WorldDot("DE", "Germany", 10.5f, 51f, "Europe"),
        WorldDot("NL", "Netherlands", 5f, 52f, "Europe"),
        WorldDot("BE", "Belgium", 4f, 50.8f, "Europe"),
        WorldDot("CH", "Switzerland", 8f, 46.8f, "Europe"),
        WorldDot("IT", "Italy", 12f, 42f, "Europe"),
        WorldDot("AT", "Austria", 14f, 47.5f, "Europe"),
        WorldDot("PL", "Poland", 19f, 52f, "Europe"),
        WorldDot("SE", "Sweden", 18f, 62f, "Europe"),
        WorldDot("NO", "Norway", 10f, 62f, "Europe"),
        WorldDot("FI", "Finland", 26f, 64f, "Europe"),
        WorldDot("GR", "Greece", 22f, 39f, "Europe"),
        WorldDot("TR", "Turkey", 35f, 39f, "Europe"),
        WorldDot("RU", "Russia", 90f, 56f, "Europe"),
        WorldDot("MA", "Morocco", -6f, 32f, "Africa"),
        WorldDot("EG", "Egypt", 30f, 27f, "Africa"),
        WorldDot("ZA", "South Africa", 24f, -30f, "Africa"),
        WorldDot("NG", "Nigeria", 8f, 9f, "Africa"),
        WorldDot("KE", "Kenya", 37f, -1f, "Africa"),
        WorldDot("AE", "United Arab Emirates", 54f, 24f, "Asia"),
        WorldDot("SA", "Saudi Arabia", 45f, 24f, "Asia"),
        WorldDot("IL", "Israel", 35f, 31f, "Asia"),
        WorldDot("IN", "India", 78f, 22f, "Asia"),
        WorldDot("PK", "Pakistan", 70f, 30f, "Asia"),
        WorldDot("CN", "China", 105f, 35f, "Asia"),
        WorldDot("JP", "Japan", 139f, 36f, "Asia"),
        WorldDot("KR", "South Korea", 127f, 37f, "Asia"),
        WorldDot("HK", "Hong Kong", 114f, 22f, "Asia"),
        WorldDot("SG", "Singapore", 104f, 1f, "Asia"),
        WorldDot("MY", "Malaysia", 102f, 4f, "Asia"),
        WorldDot("ID", "Indonesia", 118f, -2f, "Asia"),
        WorldDot("AU", "Australia", 134f, -25f, "Oceania"),
        WorldDot("NZ", "New Zealand", 174f, -41f, "Oceania"),
    )

    private val continentLabels = listOf(
        "North America" to (-105f to 48f),
        "South America" to (-60f to -18f),
        "Europe" to (18f to 60f),
        "Africa" to (20f to 4f),
        "Asia" to (95f to 48f),
        "Oceania" to (145f to -14f),
    )

    private var state: VpnState = VpnState.Stopped
    private var destinationFlag = "🌐"
    private var destinationLabel = ""
    private var destinationCode = ""
    private var rotation = -18f
    private var routeProgress = 0f
    private var routeTarget = 0f
    private var routePhase = 0f
    private var lastFrameAt = 0L
    private var frameRunning = false

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (lastFrameAt == 0L) 0.016f
            else ((now - lastFrameAt) / 1_000_000_000f).coerceIn(0.001f, 0.08f)
            lastFrameAt = now
            rotation = (rotation + dt * if (state == VpnState.Started) 2.8f else 1.2f) % 360f
            val routeDelta = dt * if (routeTarget > routeProgress) 1.45f else 2.4f
            routeProgress = approach(routeProgress, routeTarget, routeDelta)
            routePhase = (routePhase + dt * 80f) % 1000f
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
        destinationFlag = flag.ifBlank { "🌐" }
        // Keep the dashboard label country-first; the clean IP remains a detail
        // elsewhere and should not clutter the route/globe presentation.
        destinationLabel = label.ifBlank { "Cloudflare edge" }
        destinationCode = flagToCode(destinationFlag).orEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = min(w * 0.34f, h * 0.42f).coerceAtLeast(dp(56f))
        val cx = w / 2f
        val cy = h * 0.45f
        val accent = when (state) {
            VpnState.Started, VpnState.Stopped -> palette.teal
            VpnState.Starting, VpnState.Stopping -> palette.amber
            is VpnState.Error, VpnState.DailyLimitReached -> palette.red
        }

        // Ambient glass halo and a radial shade make the flat Canvas circle read as
        // a three-dimensional sphere even without a bitmap or OpenGL texture.
        spherePaint.style = Paint.Style.FILL
        spherePaint.shader = RadialGradient(
            cx - radius * .34f,
            cy - radius * .42f,
            radius * 1.25f,
            intArrayOf(
                withAlpha(accent, if (palette.isDark) 55 else 30),
                withAlpha(palette.surfaceElevated1, 245),
                withAlpha(palette.surface, 250),
                withAlpha(Color.BLACK, if (palette.isDark) 150 else 20),
            ),
            floatArrayOf(0f, .38f, .76f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius + dp(14f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(accent, if (state == VpnState.Started) 20 else 12)
        })
        canvas.drawCircle(cx, cy, radius, spherePaint)
        spherePaint.shader = null

        gridPaint.color = withAlpha(accent, if (palette.isDark) 70 else 46)
        drawPerspectiveGrid(canvas, cx, cy, radius, gridPaint)

        // Dots are depth shaded: dots on the far side fade instead of drawing a
        // flat wall of points, which is what gives the globe its 3D read.
        worldDots.forEach { dot ->
            val projected = project(cx, cy, radius * .94f, dot.lon, dot.lat)
            if (projected.depth < -0.16f) return@forEach
            dotPaint.color = withAlpha(
                palette.textPrimary,
                (42 + 125 * ((projected.depth + 1f) / 2f)).toInt().coerceIn(35, 175),
            )
            canvas.drawCircle(cx + projected.x, cy + projected.y, dp(if (projected.depth > .55f) 2.0f else 1.35f), dotPaint)
        }

        val iran = project(cx, cy, radius * .96f, 51.4f, 35.7f)
        val destinationCoords = coordinatesForCode(destinationCode)
        val destination = project(cx, cy, radius * .96f, destinationCoords.first, destinationCoords.second)
        val connected = state == VpnState.Started || state == VpnState.Starting
        if (connected) {
            drawRoute(canvas, cx + iran.x, cy + iran.y, cx + destination.x, cy + destination.y, radius, accent)
        }

        markerPaint.color = accent
        val iranX = cx + iran.x
        val iranY = cy + iran.y
        canvas.drawCircle(iranX, iranY, dp(if (connected) 5.2f else 4f), markerPaint)
        if (connected) {
            markerPaint.color = withAlpha(accent, 32)
            canvas.drawCircle(iranX, iranY, dp(10f) + dp(3f) * pulse(), markerPaint)
        }
        val destX = cx + destination.x
        val destY = cy + destination.y
        markerPaint.color = if (state == VpnState.Started) palette.teal else withAlpha(accent, 205)
        canvas.drawCircle(destX, destY, dp(if (state == VpnState.Started) 5.4f else 3.8f), markerPaint)

        labelPaint.textSize = dp(9f)
        labelPaint.color = withAlpha(palette.textSecondary, 120)
        continentLabels.forEach { (name, coords) ->
            val p = project(cx, cy, radius * .94f, coords.first, coords.second)
            if (p.depth > .2f) canvas.drawText(name.uppercase(), cx + p.x, cy + p.y, labelPaint)
        }
        labelPaint.textSize = dp(10.5f)
        labelPaint.color = palette.textSecondary
        canvas.drawText(context.getString(R.string.globe_iran), iranX, iranY + radius * .18f, labelPaint)
        canvas.drawText(destinationFlag, destX, destY - radius * .14f, labelPaint)

        labelPaint.color = palette.textPrimary
        labelPaint.textSize = dp(12f)
        val title = when (state) {
            VpnState.Started -> destinationLabel.ifBlank { context.getString(R.string.globe_route_active) }
            VpnState.Starting -> context.getString(R.string.globe_route_connecting)
            VpnState.Stopping -> context.getString(R.string.globe_route_closing)
            is VpnState.Error -> context.getString(R.string.globe_route_unavailable)
            VpnState.DailyLimitReached -> context.getString(R.string.globe_route_limit)
            VpnState.Stopped -> context.getString(R.string.globe_route_idle)
        }
        canvas.drawText(title, cx, h - dp(10f), labelPaint)
        labelPaint.textSize = dp(10f)
    }

    private fun drawPerspectiveGrid(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint) {
        // Latitudes: curved horizontal rings. The ellipse width is reduced at the poles.
        listOf(-60f, -30f, 0f, 30f, 60f).forEach { latitude ->
            val y = cy - radius * sin(Math.toRadians(latitude.toDouble())).toFloat()
            val width = radius * cos(Math.toRadians(latitude.toDouble())).toFloat().coerceAtLeast(.18f)
            canvas.drawOval(RectF(cx - width, y - radius * .035f, cx + width, y + radius * .035f), paint)
        }
        // Meridians: several perspective ellipses that rotate with the world.
        listOf(-72f, -45f, -20f, 0f, 20f, 45f, 72f).forEach { offset ->
            val xScale = cos(Math.toRadians(offset + rotation.toDouble())).toFloat().coerceIn(.12f, 1f)
            canvas.drawOval(RectF(cx - radius * xScale, cy - radius, cx + radius * xScale, cy + radius), paint)
        }
        // Soft outline last so the grid stays inside the glass edge.
        paint.color = withAlpha(paint.color, min(255, Color.alpha(paint.color) + 35))
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private fun drawRoute(canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float, radius: Float, accent: Int) {
        path.reset()
        path.moveTo(startX, startY)
        val controlX = (startX + endX) / 2f
        val controlY = min(startY, endY) - radius * .62f
        path.quadTo(controlX, controlY, endX, endY)
        routeMeasure.setPath(path, false)
        val length = routeMeasure.length
        if (length <= 0f) return

        val visibleLength = length * routeProgress.coerceIn(0f, 1f)
        particlePath.reset()
        routeMeasure.getSegment(0f, visibleLength, particlePath, true)
        routePaint.color = withAlpha(accent, 225)
        routePaint.pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(7f)), -routePhase)
        canvas.drawPath(particlePath, routePaint)
        routePaint.pathEffect = null

        if (routeProgress > .01f) {
            val position = FloatArray(2)
            routeMeasure.getPosTan(visibleLength, position, null)
            markerPaint.color = palette.onAccent
            canvas.drawCircle(position[0], position[1], dp(3.2f), markerPaint)
            markerPaint.color = accent
            canvas.drawCircle(position[0], position[1], dp(5.2f), markerPaint)
        }
    }

    private fun project(cx: Float, cy: Float, radius: Float, lon: Float, lat: Float): Projection {
        val lonRad = Math.toRadians((lon + rotation).toDouble())
        val latRad = Math.toRadians(lat.toDouble())
        val cosLat = cos(latRad)
        val x = (sin(lonRad) * cosLat).toFloat()
        val depth = (cos(lonRad) * cosLat).toFloat()
        val y = (-sin(latRad)).toFloat()
        return Projection(radius * x, radius * y * .82f, depth)
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

    private fun approach(current: Float, target: Float, amount: Float): Float {
        if (kotlin.math.abs(target - current) <= amount) return target
        return current + if (target > current) amount else -amount
    }

    private fun pulse(): Float = ((sin((rotation * 0.08f).toDouble()) + 1.0) / 2.0).toFloat()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

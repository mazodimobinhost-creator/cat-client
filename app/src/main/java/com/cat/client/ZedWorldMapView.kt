package com.cat.client

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * View port of ZedSecure's WorldMapCanvas: faint graticule + land outlines, highlighted origin
 * (hollow, tertiary tint) and exit (accent) countries, a bowed route that reveals itself over
 * 1.1 s with a travelling pulse, breathing halos and circular flag markers. Pinch to zoom, drag
 * to pan, double-tap to re-frame.
 */
class ZedWorldMapView(context: Context) : View(context) {

    data class Point(val x: Float, val y: Float, val label: String, val code: String)

    var accent: Int = 0xFFC7BFFF.toInt()
    var originColor: Int = 0xFFFF5FA2.toInt()
    var landColor: Int = Color.WHITE

    private var countries: List<WorldMap.Country> = emptyList()
    private val land = Path()
    private var originPath: Path? = null
    private var exitPath: Path? = null
    private var origin: Point? = null
    private var exit: Point? = null

    private var scale = 0f
    private var offX = 0f
    private var offY = 0f

    private var reveal = 0f
    private var pulse = 0f
    private var breathe = 0f
    private var revealAnimator: ValueAnimator? = null
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_600L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }
    private val breatheAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_200L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener { breathe = it.animatedValue as Float }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val measure = PathMeasure()
    private val routeFull = Path()
    private val routeShown = Path()
    private val arrow = Path()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private var bgShader: Shader? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val floor = wholeWorldScale()
            val next = (scale * d.scaleFactor).coerceIn(floor, floor * 24f)
            val applied = if (scale == 0f) 1f else next / scale
            offX = d.focusX - (d.focusX - offX) * applied
            offY = d.focusY - (d.focusY - offY) * applied
            scale = next
            clampOffset()
            invalidate()
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            offX -= dx; offY -= dy; clampOffset(); invalidate(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { frame(); invalidate(); return true }
    })

    fun setCountries(list: List<WorldMap.Country>) {
        countries = list
        land.rewind()
        list.forEach { c -> c.rings.forEach { appendRing(land, it) } }
        rebuildHighlights()
        if (width > 0) frame()
        invalidate()
    }

    fun setRoute(origin: Point?, exit: Point?) {
        val wasLinked = this.origin != null && this.exit != null
        this.origin = origin
        this.exit = exit
        rebuildHighlights()
        val linked = origin != null && exit != null
        if (linked != wasLinked || (linked && reveal < 1f)) {
            revealAnimator?.cancel()
            revealAnimator = ValueAnimator.ofFloat(reveal, if (linked) 1f else 0f).apply {
                duration = 1_100L; interpolator = LinearInterpolator()
                addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        if (width > 0) frame()
        invalidate()
    }

    private fun rebuildHighlights() {
        originPath = origin?.let { pathAround(it) }
        exitPath = exit?.let { pathAround(it) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseAnimator.start(); breatheAnimator.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel(); breatheAnimator.cancel(); revealAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(withAlpha(accent, 13), Color.TRANSPARENT, withAlpha(originColor, 10)),
            null, Shader.TileMode.CLAMP,
        )
        frame()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        fill.shader = bgShader; fill.color = Color.WHITE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null
        if (scale <= 0f) return
        val px = { dp: Float -> dp * resources.displayMetrics.density / scale }

        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)

        // graticule
        stroke.color = withAlpha(landColor, 15); stroke.strokeWidth = px(1f)
        var lon = -180f
        while (lon <= 180f) {
            val x = (lon + 180f) / 360f * WorldMap.WIDTH
            canvas.drawLine(x, 0f, x, WorldMap.HEIGHT, stroke); lon += 30f
        }
        var lat = -90f
        while (lat <= 90f) {
            val y = (90f - lat) / 180f * WorldMap.HEIGHT
            canvas.drawLine(0f, y, WorldMap.WIDTH, y, stroke); lat += 30f
        }

        fill.color = withAlpha(landColor, 26); canvas.drawPath(land, fill)
        stroke.color = withAlpha(landColor, 66); stroke.strokeWidth = px(1.1f); canvas.drawPath(land, stroke)

        originPath?.let {
            fill.color = withAlpha(originColor, 87); canvas.drawPath(it, fill)
            stroke.color = withAlpha(originColor, 217); stroke.strokeWidth = px(1.7f); canvas.drawPath(it, stroke)
        }
        exitPath?.let {
            fill.color = withAlpha(accent, 107); canvas.drawPath(it, fill)
            stroke.color = accent; stroke.strokeWidth = px(2f); canvas.drawPath(it, stroke)
        }

        val o = origin; val e = exit
        if (o != null && e != null && reveal > 0.01f) drawRoute(canvas, o, e, px)

        o?.let { halo(canvas, it, originColor, px) }
        e?.let { halo(canvas, it, accent, px) }
        canvas.restore()

        o?.let { marker(canvas, it, originColor) }
        e?.let { marker(canvas, it, accent) }
    }

    private fun drawRoute(canvas: Canvas, o: Point, e: Point, px: (Float) -> Float) {
        val dx = e.x - o.x; val dy = e.y - o.y
        val len = hypot(dx, dy)
        if (len < 1f) return
        val bow = len * 0.22f
        val cx = (o.x + e.x) / 2f - dy / len * bow
        val cy = (o.y + e.y) / 2f + dx / len * bow
        routeFull.rewind(); routeFull.moveTo(o.x, o.y); routeFull.quadTo(cx, cy, e.x, e.y)
        measure.setPath(routeFull, false)
        val drawnTo = measure.length * reveal
        routeShown.rewind(); measure.getSegment(0f, drawnTo, routeShown, true)
        stroke.color = withAlpha(accent, 31); stroke.strokeWidth = px(9f); canvas.drawPath(routeShown, stroke)
        stroke.color = withAlpha(accent, 115); stroke.strokeWidth = px(3.4f); canvas.drawPath(routeShown, stroke)
        stroke.color = withAlpha(accent, 242); stroke.strokeWidth = px(1.4f); canvas.drawPath(routeShown, stroke)

        measure.getPosTan(drawnTo, pos, tan)
        val tl = hypot(tan[0], tan[1])
        if (tl > 0.0001f) {
            val ux = tan[0] / tl; val uy = tan[1] / tl
            val head = px(15f); val wing = px(7.5f)
            val bx = pos[0] - ux * head; val by = pos[1] - uy * head
            arrow.rewind(); arrow.moveTo(pos[0], pos[1])
            arrow.lineTo(bx - uy * wing, by + ux * wing); arrow.lineTo(bx + uy * wing, by - ux * wing); arrow.close()
            stroke.color = withAlpha(accent, 64); stroke.strokeWidth = px(6f); canvas.drawPath(arrow, stroke)
            fill.color = accent; canvas.drawPath(arrow, fill)
        }
        if (reveal > 0.98f) {
            measure.getPosTan(measure.length * pulse, pos, null)
            fill.color = withAlpha(accent, 56); canvas.drawCircle(pos[0], pos[1], px(9f), fill)
            fill.color = accent; canvas.drawCircle(pos[0], pos[1], px(3.2f), fill)
        }
    }

    private fun halo(canvas: Canvas, p: Point, color: Int, px: (Float) -> Float) {
        fill.color = withAlpha(color, (25 + 25 * breathe).toInt()); canvas.drawCircle(p.x, p.y, px(26f + 8f * breathe), fill)
        fill.color = withAlpha(color, 56); canvas.drawCircle(p.x, p.y, px(16f), fill)
    }

    private val markerClip = Path()
    private val markerRect = android.graphics.RectF()

    private fun marker(canvas: Canvas, p: Point, ring: Int) {
        if (p.code.length != 2) return
        val x = offX + p.x * scale; val y = offY + p.y * scale
        val r = 13f * resources.displayMetrics.density
        if (x < -r * 2 || y < -r * 2 || x > width + r * 2 || y > height + r * 2) return
        fill.color = withAlpha(ring, 77); canvas.drawCircle(x, y, r, fill)
        val pic = FlagAssets.picture(context, p.code)
        if (pic != null) {
            markerRect.set(x - r, y - r, x + r, y + r)
            markerClip.rewind(); markerClip.addCircle(x, y, r, Path.Direction.CW)
            FlagAssets.draw(canvas, pic, markerRect, markerClip)
        } else {
            flagPaint.textSize = r * 0.9f
            flagPaint.color = Color.WHITE
            val fm = flagPaint.fontMetrics
            canvas.drawText(p.code, x, y - (fm.ascent + fm.descent) / 2f, flagPaint)
        }
        stroke.color = ring; stroke.strokeWidth = 2f * resources.displayMetrics.density; canvas.drawCircle(x, y, r, stroke)
    }

    private fun appendRing(path: Path, ring: FloatArray) {
        if (ring.size < 6) return
        path.moveTo(ring[0], ring[1])
        var i = 2
        while (i < ring.size) { path.lineTo(ring[i], ring[i + 1]); i += 2 }
        path.close()
    }

    private fun pathAround(p: Point): Path? {
        val c = countries.firstOrNull { abs(it.anchorX - p.x) < 0.5f && abs(it.anchorY - p.y) < 0.5f } ?: return null
        return Path().also { path -> c.rings.forEach { appendRing(path, it) } }
    }

    private fun wholeWorldScale(): Float =
        if (width <= 0 || height <= 0) 1f else min(width / WorldMap.WIDTH, height / WorldMap.HEIGHT)

    private fun clampOffset() {
        if (width <= 0 || height <= 0) return
        val mw = WorldMap.WIDTH * scale; val mh = WorldMap.HEIGHT * scale
        offX = if (mw <= width) (width - mw) / 2f else offX.coerceIn(width - mw, 0f)
        offY = if (mh <= height) (height - mh) / 2f else offY.coerceIn(height - mh, 0f)
    }

    private fun frame() {
        val focus = listOfNotNull(origin, exit)
        val whole = wholeWorldScale()
        if (focus.isEmpty()) {
            scale = whole
            offX = (width - WorldMap.WIDTH * whole) / 2f; offY = (height - WorldMap.HEIGHT * whole) / 2f
            return
        }
        val cx = focus.map { it.x }.average().toFloat(); val cy = focus.map { it.y }.average().toFloat()
        scale = if (focus.size < 2) whole * 2.6f else {
            val spanX = (focus.maxOf { it.x } - focus.minOf { it.x }) * 1.9f + WorldMap.WIDTH * 0.06f
            val spanY = (focus.maxOf { it.y } - focus.minOf { it.y }) * 2.4f + WorldMap.HEIGHT * 0.06f
            min(width / max(spanX, 1f), height / max(spanY, 1f)).coerceIn(whole * 0.95f, whole * 4.5f)
        }
        offX = width / 2f - cx * scale; offY = height / 2f - cy * scale
        clampOffset()
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}

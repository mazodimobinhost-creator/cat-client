package com.cat.client

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * GlobeDrawable — a stylised flat globe rendered on a Canvas.
 * Draws a dark/light purple globe with grid lines, a marker for Iran
 * and a marker for the currently connected country, joined by an animated
 * arc + travelling dot (like an airplane / packet flying from IR -> dest).
 */
class GlobeDrawable : Drawable, Animatable {

    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x40FFFFFF
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = 0xFFA855F7.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val iranMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEF4444.toInt()
    }
    private val destMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22C55E.toInt()
    }
    private val packetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F3FF.toInt()
        setShadowLayer(12f, 0f, 0f, 0xFFA855F7.toInt())
    }

    private var destLat = 50.0   // Germany by default
    private var destLon = 10.0
    private var connected = false
    private var animT = 0f
    private var animator: ValueAnimator? = null

    private val iranLat = 35.6892
    private val iranLon = 51.3890

    fun setDestination(countryCode: String?, connected: Boolean) {
        this.destLat = COUNTRY_COORDS[countryCode?.uppercase()]?.first ?: 50.0
        this.destLon = COUNTRY_COORDS[countryCode?.uppercase()]?.second ?: 10.0
        this.connected = connected
        if (connected) start() else stop()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = (minOf(b.width(), b.height()) * 0.42f)

        // Sphere gradient fill
        spherePaint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(0xFF2E1065.toInt(), 0xFF7C3AED.toInt(), 0xFFA855F7.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, spherePaint)

        // Grid lines — latitude
        for (lat in -60..60 step 30) {
            val y = cy - (lat / 90.0) * r
            val rr = r * Math.cos(Math.toRadians(lat.toDouble())).toFloat()
            canvas.drawCircle(cx, y.toFloat(), rr, gridPaint)
        }
        // Longitude ellipses
        for (lon in -60..60 step 30) {
            val scale = kotlin.math.abs(cos(Math.toRadians(lon.toDouble()))).toFloat()
            val oval = RectF(cx - r * scale, cy - r, cx + r * scale, cy + r)
            canvas.drawOval(oval, gridPaint)
        }
        // Equator thick
        canvas.drawLine(cx - r, cy, cx + r, cy, gridPaint)

        if (connected) {
            val iranPt = project(iranLat, iranLon, cx, cy, r)
            val destPt = project(destLat, destLon, cx, cy, r)
            // Arc between the two points
            val path = Path()
            path.moveTo(iranPt.x, iranPt.y)
            val midX = (iranPt.x + destPt.x) / 2f
            val midY = minOf(iranPt.y, destPt.y) - r * 0.55f
            path.quadTo(midX, midY, destPt.x, destPt.y)
            canvas.drawPath(path, arcPaint)

            // Travelling packet
            val packetT = if (isRunning) animT else 0.25f
            val packetX = quadBezier(iranPt.x, midX, destPt.x, packetT)
            val packetY = quadBezier(iranPt.y, midY, destPt.y, packetT)
            canvas.drawCircle(packetX, packetY, 8f, packetPaint)

            // Destination marker
            canvas.drawCircle(destPt.x, destPt.y, 10f, destMarkerPaint)
            canvas.drawCircle(iranPt.x, iranPt.y, 8f, iranMarkerPaint)
        } else {
            // Just Iran marker when disconnected
            val iranPt = project(iranLat, iranLon, cx, cy, r)
            canvas.drawCircle(iranPt.x, iranPt.y, 8f, iranMarkerPaint)
        }
    }

    private fun project(lat: Double, lon: Double, cx: Float, cy: Float, r: Float): PointF {
        val x = cx + (lon / 180.0) * r
        val y = cy - (lat / 90.0) * r
        return PointF(x.toFloat().coerceIn(cx - r, cx + r), y.toFloat().coerceIn(cy - r, cy + r))
    }

    private fun quadBezier(p0: Float, p1: Float, p2: Float, t: Float): Float {
        val u = 1f - t
        return u * u * p0 + 2 * u * t * p1 + t * t * p2
    }

    override fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                animT = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun isRunning(): Boolean = animator?.isRunning == true

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        val COUNTRY_COORDS: Map<String, Pair<Double, Double>> = mapOf(
            "DE" to (51.0 to 10.0), "FR" to (46.0 to 2.0), "NL" to (52.0 to 5.0),
            "US" to (38.0 to -97.0), "GB" to (54.0 to -2.0), "TR" to (39.0 to 35.0),
            "RU" to (61.0 to 90.0), "CA" to (56.0 to -106.0), "JP" to (36.0 to 138.0),
            "SG" to (1.3 to 103.8), "HK" to (22.3 to 114.1), "AU" to (-25.0 to 133.0),
            "AT" to (47.5 to 14.5), "CH" to (46.8 to 8.2), "IT" to (42.5 to 12.5),
            "ES" to (40.0 to -4.0), "PL" to (52.0 to 20.0), "SE" to (62.0 to 15.0),
            "NO" to (62.0 to 10.0), "FI" to (64.0 to 26.0), "UA" to (49.0 to 32.0),
            "AE" to (24.0 to 54.0), "IR" to (32.4 to 53.7),
        )
    }
}

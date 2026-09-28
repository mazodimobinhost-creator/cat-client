package com.cat.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * UsageBarView — the Home tab's graphical quota bar.
 *
 * Draws the download / upload split of a subscription's reported quota as a
 * single rounded track, with percentage ticks and the free space left empty.
 * When no quota is reported by the panel it falls back to showing just the
 * consumed traffic, so the card stays useful for unlimited panels.
 */
class UsageBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val downloadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val uploadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    private var usedFraction = 0f
    private var uploadFraction = 0f
    private var trackColor = Color.argb(60, 124, 58, 237)

    /** Sets the fill split; both values are 0..1 of the total quota. */
    fun setSplit(usedFraction: Float, uploadShadeFraction: Float, track: Int) {
        this.usedFraction = usedFraction.coerceIn(0f, 1f)
        this.uploadFraction = uploadShadeFraction.coerceIn(0f, 1f)
        if (track != trackColor) trackColor = track
        invalidate()
    }

    fun configureColors(download: Int, upload: Int, track: Int) {
        downloadPaint.color = download
        uploadPaint.color = upload
        trackColor = track
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        val radius = height / 2f
        val inset = tickPaint.strokeWidth / 2f
        rect.set(inset, inset, width - inset, height - inset)

        // Empty track
        trackPaint.color = trackColor
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        if (usedFraction <= 0f) return

        val totalWidth = rect.width()
        val usedWidth = totalWidth * usedFraction
        val uploadWidth = totalWidth * (usedFraction * uploadFraction)
        val downloadWidth = (usedWidth - uploadWidth).coerceAtLeast(0f)

        canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.left + usedWidth, rect.bottom)
        // Download share (accent violet)
        canvas.drawRoundRect(rect, radius, radius, downloadPaint)
        // Upload share painted on the right edge of the used portion
        if (uploadWidth > 0.5f) {
            val uploadRect = RectF(
                rect.left + usedWidth - uploadWidth,
                rect.top,
                rect.right,
                rect.bottom,
            )
            canvas.drawRoundRect(uploadRect, radius, radius, uploadPaint)
        }
        canvas.restore()

        // Accent sweep on the filled part for a subtle "graph" gradient
        val shader = LinearGradient(
            rect.left,
            rect.top,
            rect.left + usedWidth,
            rect.bottom,
            intArrayOf(Color.TRANSPARENT, Color.argb(90, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        downloadPaint.shader = shader
        canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.left + usedWidth, rect.bottom)
        canvas.drawRoundRect(rect, radius, radius, downloadPaint)
        canvas.restore()
        downloadPaint.shader = null

        // Percentage ticks (25/50/75 %)
        tickPaint.color = Color.argb(70, 255, 255, 255)
        tickPaint.strokeWidth = tickStrokeWidth()
        for (step in 1..3) {
            val x = rect.left + totalWidth * (step / 4f)
            canvas.drawLine(x, rect.top + radius * 0.35f, x, rect.bottom - radius * 0.35f, tickPaint)
        }
    }

    private fun tickStrokeWidth(): Float = (height / 24f).coerceAtLeast(1f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (resources.displayMetrics.density * 14f).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth.coerceAtLeast(0), widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }
}

package com.cat.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Four rising bars — Cat's ping antenna. Lit bars follow a real delay; empty when none.
 */
class CatAntennaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private var lit = 0
    private var dimColor = 0x33FFFFFF
    private var litColor = 0xFF2EE6A6.toInt()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = CatAntenna.spoken(null, fa = false)
    }

    fun bind(delayMs: Int?, failed: Boolean = false, fa: Boolean = false) {
        val next = CatAntenna.bars(delayMs, failed)
        val color = when {
            failed || delayMs == null || delayMs <= 0 -> dimColor
            delayMs <= 200 -> 0xFF7CFF6B.toInt()
            delayMs <= 500 -> 0xFFFFB020.toInt()
            else -> 0xFFFF5A5A.toInt()
        }
        if (next != lit || color != litColor) {
            lit = next
            litColor = color
            litPaint.color = color
            invalidate()
        }
        contentDescription = CatAntenna.spoken(delayMs, failed, fa)
    }

    fun setTrack(dim: Int) {
        if (dim != dimColor) {
            dimColor = dim
            dimPaint.color = dim
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension(
            resolveSize((22 * d).toInt(), widthMeasureSpec),
            resolveSize((16 * d).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val n = CatAntenna.MAX
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w * 0.14f
        val barW = (w - gap * (n - 1)) / n
        val radius = barW.coerceAtMost(h) / 2.2f
        dimPaint.color = dimColor
        litPaint.color = litColor
        for (i in 0 until n) {
            val frac = 0.30f + 0.23f * i
            val bh = h * frac
            val left = i * (barW + gap)
            rect.set(left, h - bh, left + barW, h)
            canvas.drawRoundRect(rect, radius, radius, if (i < lit) litPaint else dimPaint)
        }
    }
}

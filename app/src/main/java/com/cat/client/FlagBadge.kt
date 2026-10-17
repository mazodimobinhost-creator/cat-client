package com.cat.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import android.view.View
import com.caverock.androidsvg.SVG
import java.util.Locale

/**
 * ZedSecure FlagBadge: flags come from the bundled SVG set (`assets/flags/<cc>.svg`, the same
 * artwork Zed ships — e.g. Iran is drawn with the lion-and-sun flag), rendered to a Picture once
 * and cached. Falls back to the two-letter code on a neutral chip.
 */
object FlagAssets {
    private val cache = HashMap<String, Picture?>()

    @Synchronized
    fun picture(context: Context, code: String?): Picture? {
        val cc = code?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.length == 2 } ?: return null
        if (cache.containsKey(cc)) return cache[cc]
        val pic = runCatching {
            context.assets.open("flags/$cc.svg").use { SVG.getFromInputStream(it) }
        }.getOrNull()?.let { svg ->
            if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
            svg.setDocumentWidth("100%"); svg.setDocumentHeight("100%")
            svg.renderToPicture(400, 300)
        }
        cache[cc] = pic
        return pic
    }

    /** Draws the flag cropped (ContentScale.Crop) into [dst] and clipped by [clip]. */
    fun draw(canvas: Canvas, picture: Picture, dst: RectF, clip: Path) {
        canvas.save()
        canvas.clipPath(clip)
        val pw = picture.width.toFloat(); val ph = picture.height.toFloat()
        val scale = maxOf(dst.width() / pw, dst.height() / ph)
        val w = pw * scale; val h = ph * scale
        val left = dst.centerX() - w / 2f; val top = dst.centerY() - h / 2f
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.drawPicture(picture)
        canvas.restore()
    }
}

class FlagBadgeView(context: Context) : View(context) {
    var circle: Boolean = false
        set(value) { field = value; invalidate() }
    var ringColor: Int = Color.TRANSPARENT
        set(value) { field = value; invalidate() }
    var ringWidthDp: Float = 0f
    var fallbackColor: Int = 0xFF2A2A33.toInt()
    var fallbackTextColor: Int = Color.WHITE

    private var code: String? = null
    private var picture: Picture? = null
    private val clip = Path()
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    fun setCountryCode(value: String?) {
        code = value?.uppercase(Locale.ROOT)
        picture = FlagAssets.picture(context, value)
        contentDescription = code
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val ring = ringWidthDp * d
        if (ring > 0f) rect.inset(ring, ring)
        clip.rewind()
        if (circle) clip.addCircle(rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) / 2f, Path.Direction.CW)
        else clip.addRoundRect(rect, 6f * d, 6f * d, Path.Direction.CW)
        val pic = picture
        if (pic != null) {
            FlagAssets.draw(canvas, pic, rect, clip)
        } else {
            paint.style = Paint.Style.FILL; paint.color = fallbackColor
            canvas.drawPath(clip, paint)
            text.color = fallbackTextColor; text.textSize = rect.height() * 0.42f
            val fm = text.fontMetrics
            canvas.drawText(code ?: "··", rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, text)
        }
        if (ring > 0f && ringColor != Color.TRANSPARENT) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = ring; paint.color = ringColor
            if (circle) canvas.drawCircle(rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) / 2f + ring / 2f, paint)
            else canvas.drawRoundRect(rect, 6f * d, 6f * d, paint)
            paint.style = Paint.Style.FILL
        }
    }
}

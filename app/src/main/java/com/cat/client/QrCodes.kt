package com.cat.client

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QrCodes — offline QR rendering for config share-links.
 *
 * ZXing ships with the scanner already (zxing-android-embedded), so generating a
 * QR needs no network and no extra dependency. Used by the Subscriptions tab to
 * show a scannable code for any config — the same flow v2box-style clients offer.
 */
object QrCodes {

    /** Renders [text] as a square QR bitmap with a light quiet zone. */
    fun bitmap(text: String, sizePx: Int = 512, margin: Int = 1): Bitmap? {
        if (text.isBlank()) return null
        val side = sizePx.coerceIn(96, 2048)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side, hints)
        }.getOrNull() ?: return null

        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}

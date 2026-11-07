package com.textlens.app

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Cleans a PHOTO of a page for OCR: an adaptive threshold removes shadows and uneven light
 * and returns clean black text on white. (Not used for screenshots, which are already clean.)
 */
object ImageEnhance {

    private val BLACK = 0xFF000000.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()

    fun adaptive(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        // Too small or too large for the integer maths below -> leave as is.
        if (w < 32 || h < 32 || w.toLong() * h > 8_000_000L) return src

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val gray = ByteArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val g = (((p shr 16) and 255) * 30 + ((p shr 8) and 255) * 59 + (p and 255) * 11) / 100
            gray[i] = g.toByte()
        }

        // Integral image: any window sum in O(1).
        val iw = w + 1
        val integral = IntArray(iw * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += gray[y * w + x].toInt() and 255
                integral[(y + 1) * iw + (x + 1)] = integral[y * iw + (x + 1)] + row
            }
        }

        val half = max(12, w / 24) // window is about 1/12 of the page width
        for (y in 0 until h) {
            val y1 = max(0, y - half)
            val y2 = min(h - 1, y + half)
            for (x in 0 until w) {
                val x1 = max(0, x - half)
                val x2 = min(w - 1, x + half)
                val count = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum = integral[(y2 + 1) * iw + (x2 + 1)] - integral[y1 * iw + (x2 + 1)] -
                    integral[(y2 + 1) * iw + x1] + integral[y1 * iw + x1]
                val g = gray[y * w + x].toInt() and 255
                // black when the pixel is darker than 85% of its neighbourhood average
                px[y * w + x] = if (g.toLong() * count * 100L <= sum.toLong() * 85L) BLACK else WHITE
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}

package com.textlens.app

import android.content.Context
import android.graphics.Bitmap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one place that decides HOW text is read from an image:
 *  - online + Gemini key saved + switch on -> Gemini (accurate, Google-Lens-like);
 *  - offline, or Gemini failed             -> Tesseract on the phone (as before).
 */
object OcrEngine {

    /** One short line about the last run (engine + time). The screens show it as a small toast. */
    @Volatile
    var lastInfo: String = ""
        private set

    private fun secs(since: Long): String =
        String.format(Locale.US, "%.1f", (System.currentTimeMillis() - since) / 1000.0)

    /** Blocking. Call from a background thread. [enhance] = true for photos of pages. */
    fun recognize(
        c: Context,
        bmp: Bitmap,
        enhance: Boolean,
        onProgress: (String) -> Unit = {}
    ): String {
        val app = c.applicationContext
        val start = System.currentTimeMillis()
        var note = ""

        if (GeminiHelper.isConfigured(app) && GeminiHelper.isOnline(app)) {
            onProgress(LocaleHelper.str(app, R.string.ocr_reading_gemini))
            try {
                val text = GeminiHelper.ocr(app, bmp)
                lastInfo = LocaleHelper.str(app, R.string.ocr_info_gemini, secs(start))
                return text
            } catch (t: Throwable) {
                note = LocaleHelper.str(app, R.string.ocr_gemini_failed, (t.message ?: "error").take(70))
            }
        }

        if (note.isNotEmpty()) onProgress(note)
        OcrHelper.ensureData(app, OcrHelper.neededFor(Prefs.ocrLang(app))) { msg -> onProgress(msg) }
        val reading = LocaleHelper.str(app, R.string.camera_recognizing)
        val base = if (note.isEmpty()) reading else "$note\n$reading"
        onProgress(base)

        // Show a running timer so a long offline read never looks frozen.
        val running = AtomicBoolean(true)
        val t1 = System.currentTimeMillis()
        val ticker = Thread {
            while (running.get()) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (running.get()) {
                    onProgress("$base\n" + LocaleHelper.str(app, R.string.ocr_seconds, (System.currentTimeMillis() - t1) / 1000))
                }
            }
        }
        ticker.isDaemon = true
        ticker.start()
        try {
            val text = OcrHelper.recognize(app, bmp, enhance = enhance)
            lastInfo = LocaleHelper.str(app, R.string.ocr_info_offline, OcrHelper.lastLangs, secs(start))
            return text
        } finally {
            running.set(false)
            ticker.interrupt()
        }
    }
}

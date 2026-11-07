package com.textlens.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.googlecode.tesseract.android.TessBaseAPI
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Offline OCR for Bengali, English and Arabic using Tesseract.
 * The language files (traineddata) are copied from app assets if bundled, otherwise
 * downloaded ONCE from the official tesseract-ocr GitHub repo. After that everything is offline.
 *
 * Accuracy/speed: mixing all three languages in ONE pass makes Tesseract invent Latin/Bengali
 * letters inside Arabic text and makes it slow. So we use one script per pass:
 *  - Settings can fix the language ("ara", "ben", "eng"), or
 *  - "auto": three quick probes run in parallel, the script with the best confidence wins.
 */
object OcrHelper {

    val LANGS = listOf("ben", "ara", "eng")

    /** Languages used by the last offline run (shown to the user next to the time). */
    @Volatile
    var lastLangs: String = ""
        private set

    private const val MAX_SIDE = 2200
    private const val PROBE_SIDE = 1400

    /** Bengali and Arabic use the more accurate "best" models; English is fine with the small "fast" one. */
    private fun url(lang: String): String {
        val repo = if (lang == "eng") "tessdata_fast" else "tessdata_best"
        return "https://github.com/tesseract-ocr/$repo/raw/main/$lang.traineddata"
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun tessDir(c: Context) = File(c.filesDir, "tessdata").apply { mkdirs() }

    /** Which language files a Settings choice needs. */
    fun neededFor(choice: String): List<String> = when (choice) {
        "ara" -> listOf("ara")
        "ben" -> listOf("ben", "eng")
        "eng" -> listOf("eng")
        else -> LANGS
    }

    fun missing(c: Context, langs: List<String> = LANGS): List<String> =
        langs.filter { !File(tessDir(c), "$it.traineddata").let { f -> f.exists() && f.length() > 100_000 } }

    fun isReady(c: Context) = missing(c).isEmpty()

    /** Blocking. Call from a background thread. Throws on network failure. */
    fun ensureData(c: Context, langs: List<String> = LANGS, onProgress: (String) -> Unit = {}) {
        for (lang in missing(c, langs)) {
            val target = File(tessDir(c), "$lang.traineddata")
            // 1) bundled copy (if the APK was built with the files in assets/tessdata)
            val bundled = runCatching { c.assets.open("tessdata/$lang.traineddata") }.getOrNull()
            if (bundled != null) {
                bundled.use { ins -> target.outputStream().use { ins.copyTo(it) } }
                if (target.length() > 100_000) continue
                target.delete()
            }
            // 2) download
            onProgress("${LocaleHelper.str(c, R.string.lang_downloading)}: $lang")
            val tmp = File(tessDir(c), "$lang.part")
            http.newCall(Request.Builder().url(url(lang)).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} ($lang)")
                r.body!!.byteStream().use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            }
            if (tmp.length() < 100_000) {
                tmp.delete()
                throw IllegalStateException("Bad download ($lang)")
            }
            tmp.renameTo(target)
        }
    }

    // ------------------------------------------------------------------ image preparation

    private fun shrink(src: Bitmap, side: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= side) return src
        val f = side.toFloat() / m
        return Bitmap.createScaledBitmap(
            src, max(1, (src.width * f).toInt()), max(1, (src.height * f).toInt()), true
        )
    }

    /** Cap the size, enlarge tiny crops, and add a white margin (Tesseract reads edge letters better). */
    private fun prepare(source: Bitmap): Bitmap {
        var bmp = shrink(source, MAX_SIDE)
        if (bmp.height < 160 || bmp.width < 400) {
            val f = 2.5f
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        }
        val pad = 16
        val out = Bitmap.createBitmap(bmp.width + 2 * pad, bmp.height + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bmp, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    // ------------------------------------------------------------------ recognition

    /**
     * Blocking. Call from a background thread.
     * @param choice "auto", "ara", "ben" or "eng" (see Settings)
     * @param enhance true for PHOTOS (removes shadows); false for clean screenshots
     */
    fun recognize(
        c: Context,
        source: Bitmap,
        choice: String = Prefs.ocrLang(c),
        enhance: Boolean = false
    ): String {
        var bmp = prepare(source)
        if (enhance) bmp = ImageEnhance.adaptive(bmp)
        val langs = if (choice == "auto") autoLangs(c, bmp) else langsFor(choice)
        lastLangs = langs
        return run(c, bmp, langs)
    }

    private fun langsFor(choice: String) = when (choice) {
        "ara" -> "ara"
        "ben" -> "ben+eng"
        "eng" -> "eng"
        else -> "ara+ben+eng"
    }

    private fun run(c: Context, bmp: Bitmap, langs: String): String {
        val api = TessBaseAPI()
        try {
            if (!api.init(c.filesDir.absolutePath, langs)) {
                throw IllegalStateException("OCR init failed ($langs)")
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(bmp)
            return (api.utF8Text ?: "").trim()
        } finally {
            api.recycle()
        }
    }

    /** Mean confidence (0-100) of a quick pass in one language, or -1 if it could not run. */
    private fun confidence(c: Context, bmp: Bitmap, lang: String): Int {
        val api = TessBaseAPI()
        try {
            if (!api.init(c.filesDir.absolutePath, lang)) return -1
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(bmp)
            if (api.utF8Text.isNullOrBlank()) return 0
            return api.meanConfidence()
        } finally {
            api.recycle()
        }
    }

    /** A tall page is probed on a band from its middle (a few lines are enough to tell the script). */
    private fun probeBitmap(bmp: Bitmap): Bitmap {
        val h = bmp.height
        val band = if (h > 480) {
            val bh = max(320, h / 4)
            Bitmap.createBitmap(bmp, 0, (h - bh) / 2, bmp.width, bh)
        } else bmp
        return shrink(band, PROBE_SIDE)
    }

    /** Three small passes in parallel (ara / ben / eng); the best-scoring script wins. */
    private fun autoLangs(c: Context, bmp: Bitmap): String {
        val probe = probeBitmap(bmp)
        val cands = listOf("ara", "ben", "eng")
        val scores = IntArray(cands.size) { -1 }
        val threads = cands.mapIndexed { i, lang ->
            Thread {
                scores[i] = runCatching { confidence(c, probe, lang) }.getOrDefault(-1)
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        val best = scores.indices.maxByOrNull { scores[it] } ?: 2
        if (scores[best] < 25) return "ara+ben+eng" // nothing is convincing: let Tesseract try all
        return langsFor(cands[best])
    }
}

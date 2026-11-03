package com.textlens.app

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Offline OCR for Bengali, English and Arabic using Tesseract.
 * The language files (traineddata) are copied from app assets if bundled, otherwise
 * downloaded ONCE from the official tesseract-ocr GitHub repo. After that everything is offline.
 */
object OcrHelper {

    val LANGS = listOf("ben", "ara", "eng")
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

    fun missing(c: Context): List<String> =
        LANGS.filter { !File(tessDir(c), "$it.traineddata").let { f -> f.exists() && f.length() > 100_000 } }

    fun isReady(c: Context) = missing(c).isEmpty()

    /** Blocking. Call from a background thread. Throws on network failure. */
    fun ensureData(c: Context, onProgress: (String) -> Unit = {}) {
        for (lang in missing(c)) {
            val target = File(tessDir(c), "$lang.traineddata")
            // 1) bundled copy (if the APK was built with the files in assets/tessdata)
            val bundled = runCatching { c.assets.open("tessdata/$lang.traineddata") }.getOrNull()
            if (bundled != null) {
                bundled.use { ins -> target.outputStream().use { ins.copyTo(it) } }
                if (target.length() > 100_000) continue
                target.delete()
            }
            // 2) download
            onProgress("${c.getString(R.string.lang_downloading)}: $lang")
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

    /** Blocking. Call from a background thread. */
    fun recognize(c: Context, source: Bitmap, langs: String = LANGS.joinToString("+")): String {
        var bmp = source
        // Tiny crops OCR badly: enlarge them.
        if (bmp.height < 160 || bmp.width < 400) {
            val f = 2.5f
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        }
        val api = TessBaseAPI()
        try {
            if (!api.init(c.filesDir.absolutePath, langs)) {
                throw IllegalStateException("OCR init failed")
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(bmp)
            return (api.utF8Text ?: "").trim()
        } finally {
            api.recycle()
        }
    }
}

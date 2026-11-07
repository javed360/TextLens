package com.textlens.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions

/**
 * Offline translation (Google ML Kit on-device models).
 * Each language model (~30 MB) is downloaded once; after that translation works without internet.
 */
object TranslateHelper {

    const val BN = "bn"
    const val EN = "en"
    const val AR = "ar"

    private fun mk(code: String) = when (code) {
        BN -> TranslateLanguage.BENGALI
        AR -> TranslateLanguage.ARABIC
        else -> TranslateLanguage.ENGLISH
    }

    /** Guess the language by script (works offline, no extra model needed). */
    fun detect(text: String): String {
        var bn = 0; var ar = 0; var en = 0
        for (ch in text) {
            when (ch.code) {
                in 0x0980..0x09FF -> bn++
                in 0x0600..0x06FF, in 0x0750..0x077F, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> ar++
                in 0x41..0x5A, in 0x61..0x7A -> en++
            }
        }
        return when {
            bn >= ar && bn >= en && bn > 0 -> BN
            ar >= en && ar > 0 -> AR
            else -> EN
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Online + Gemini key saved + switch on  -> Gemini (better quality).
     * Offline, or Gemini fails                -> offline ML Kit (as before).
     * [onDone] always runs on the main thread.
     */
    fun translate(
        c: Context,
        text: String,
        target: String,
        onDone: (Result<String>) -> Unit
    ) {
        val app = c.applicationContext
        if (detect(text) == target) {
            onDone(Result.success(text))
            return
        }
        if (GeminiHelper.isConfigured(app) && GeminiHelper.isOnline(app)) {
            Thread {
                val r = runCatching { GeminiHelper.translate(app, text, target) }
                main.post {
                    if (r.isSuccess) onDone(r) else offline(text, target, onDone)
                }
            }.start()
        } else {
            offline(text, target, onDone)
        }
    }

    private fun offline(
        text: String,
        target: String,
        onDone: (Result<String>) -> Unit
    ) {
        val source = detect(text)
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(mk(source))
                .setTargetLanguage(mk(target))
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { out -> onDone(Result.success(out)); translator.close() }
                    .addOnFailureListener { e -> onDone(Result.failure(e)); translator.close() }
            }
            .addOnFailureListener { e ->
                onDone(Result.failure(e))
                translator.close()
            }
    }
}

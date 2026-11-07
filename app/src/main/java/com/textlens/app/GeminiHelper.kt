package com.textlens.app

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Optional ONLINE helper that uses the Gemini API with your own key (set in Settings):
 *  - reads text from an image (OCR, very accurate, also for Arabic with harakat),
 *  - translates text.
 * It is used automatically only when: the switch is on + a key is saved + the phone is online.
 * Otherwise (or if a request fails) the app falls back to the offline engines (Tesseract / ML Kit).
 */
object GeminiHelper {

    const val DEFAULT_MODEL = "gemini-flash-latest"
    private const val MAX_IMAGE_SIDE = 1800

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun isOnline(c: Context): Boolean {
        val cm = c.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        // Only "has an internet-capable network". We deliberately do NOT require VALIDATED: on some
        // networks it is false even though requests work, and Gemini would then be skipped silently.
        // If the request really fails, OcrEngine / TranslateHelper fall back to the offline engines.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** True when the user has turned it on AND saved a key. */
    fun isConfigured(c: Context): Boolean = Prefs.geminiOn(c) && Prefs.geminiKey(c).isNotBlank()

    private fun langName(code: String) = when (code) {
        TranslateHelper.BN -> "Bengali (বাংলা)"
        TranslateHelper.AR -> "Arabic (العربية)"
        else -> "English"
    }

    private fun textPart(t: String) = JSONObject().put("text", t)

    /** Blocking. One request to the model. Returns the text of the answer (may be empty). */
    private fun generate(c: Context, parts: JSONArray): String {
        val key = Prefs.geminiKey(c).trim()
        val model = Prefs.geminiModel(c).trim().ifEmpty { DEFAULT_MODEL }
        require(key.isNotEmpty()) { LocaleHelper.str(c, R.string.gemini_err_no_key) }

        val root = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0))
        val body = root.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key) // the key goes in a header, never in the URL
            .post(body)
            .build()

        http.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching {
                    JSONObject(raw).getJSONObject("error").getString("message")
                }.getOrNull() ?: raw.take(120)
                throw IllegalStateException("Gemini ${r.code}: $msg")
            }
            val json = JSONObject(raw)
            val cands = json.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                val why = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                throw IllegalStateException((LocaleHelper.str(c, R.string.gemini_err_no_answer) + " " + why).trim())
            }
            val parts2 = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts2.length()) sb.append(parts2.getJSONObject(i).optString("text"))
            return sb.toString().trim()
        }
    }

    /** Blocking. Call from a background thread. Throws on any failure. */
    fun translate(c: Context, text: String, target: String): String {
        val prompt = "Translate the following text into ${langName(target)}.\n" +
            "- The text was read by OCR from a screenshot or a page, so it may contain small mistakes; " +
            "silently correct them using context.\n" +
            "- Keep the meaning faithful and precise. Keep Islamic / fiqh terms accurate " +
            "(transliterate a technical term if there is no exact equivalent).\n" +
            "- Output ONLY the translation, with no explanation, notes or quotes.\n\n" +
            "TEXT:\n" + text
        val out = generate(c, JSONArray().put(textPart(prompt)))
        if (out.isEmpty()) throw IllegalStateException(LocaleHelper.str(c, R.string.gemini_err_empty))
        return out
    }

    private const val OCR_PROMPT =
        "Read all the text in this image (OCR) and output it exactly as written.\n" +
            "- Keep the original language and script. Do NOT translate.\n" +
            "- For Arabic keep every diacritic (tashkeel) and every symbol such as ﷺ, ﴾ ﴿ and footnote numbers exactly.\n" +
            "- Keep the reading order (right-to-left for Arabic) and keep line / paragraph breaks.\n" +
            "- Ignore pen or pencil scribbles, page edges, shadows and text of neighbouring pages that is cut off.\n" +
            "- Output ONLY the text, with no explanation, no quotes, no markdown."

    /** Blocking. Call from a background thread. Throws on any failure. May return "" if there is no text. */
    fun ocr(c: Context, bmp: Bitmap): String {
        val side = max(bmp.width, bmp.height)
        val small = if (side > MAX_IMAGE_SIDE) {
            val f = MAX_IMAGE_SIDE.toFloat() / side
            Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * f).toInt()), max(1, (bmp.height * f).toInt()), true)
        } else bmp
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        if (small !== bmp) small.recycle()
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val image = JSONObject().put(
            "inline_data",
            JSONObject().put("mime_type", "image/jpeg").put("data", b64)
        )
        return generate(c, JSONArray().put(textPart(OCR_PROMPT)).put(image))
    }
}

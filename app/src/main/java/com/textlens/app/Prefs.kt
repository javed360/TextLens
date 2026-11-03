package com.textlens.app

import android.content.Context
import android.content.SharedPreferences

/** Small wrapper around SharedPreferences. Everything stays on the phone. */
object Prefs {
    private const val FILE = "textlens_prefs"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Selected colour theme id (see ThemeManager.palettes). Dark "midnight" by default. */
    fun palette(c: Context): String = sp(c).getString("palette", "midnight") ?: "midnight"
    fun setPalette(c: Context, v: String) = sp(c).edit().putString("palette", v).apply()

    /** When true: midnight at night-mode, mist in day-mode (follows the phone). */
    fun followSystem(c: Context): Boolean = sp(c).getBoolean("follow_system", false)
    fun setFollowSystem(c: Context, v: Boolean) = sp(c).edit().putBoolean("follow_system", v).apply()

    /** Copy the recognised text to the clipboard as soon as OCR finishes. */
    fun autoCopy(c: Context): Boolean = sp(c).getBoolean("auto_copy", true)
    fun setAutoCopy(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_copy", v).apply()

    fun bubbleX(c: Context): Int = sp(c).getInt("bubble_x", -1)
    fun bubbleY(c: Context): Int = sp(c).getInt("bubble_y", -1)
    fun setBubblePos(c: Context, x: Int, y: Int) =
        sp(c).edit().putInt("bubble_x", x).putInt("bubble_y", y).apply()

    /** Last translation target: "bn", "en" or "ar". */
    fun targetLang(c: Context): String = sp(c).getString("target_lang", "bn") ?: "bn"
    fun setTargetLang(c: Context, v: String) = sp(c).edit().putString("target_lang", v).apply()
}

package com.textlens.app

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatDelegate

/** One complete colour theme (background, surfaces, text and accent all change together). */
data class Palette(
    val id: String,
    val name: String,
    val dark: Boolean,
    val styleRes: Int,
    val bg: Int,
    val surface: Int,
    val text: Int,
    val primary: Int
)

object ThemeManager {

    val palettes: List<Palette> = listOf(
        Palette("midnight", "মিডনাইট", true, R.style.Theme_TextLens_Midnight, 0xFF121820.toInt(), 0xFF1B2430.toInt(), 0xFFEAF0F6.toInt(), 0xFF3FB8B0.toInt()),
        Palette("amoled", "অ্যামোলেড", true, R.style.Theme_TextLens_Amoled, 0xFF07090B.toInt(), 0xFF11151A.toInt(), 0xFFF0F3F6.toInt(), 0xFF8C9EFF.toInt()),
        Palette("forest", "ফরেস্ট", true, R.style.Theme_TextLens_Forest, 0xFF0F1A14.toInt(), 0xFF17261E.toInt(), 0xFFE6F2EA.toInt(), 0xFF5FBF8A.toInt()),
        Palette("ocean", "ওশান", true, R.style.Theme_TextLens_Ocean, 0xFF0B1626.toInt(), 0xFF12233B.toInt(), 0xFFE6EEF9.toInt(), 0xFF5AA2FF.toInt()),
        Palette("plum", "প্লাম", true, R.style.Theme_TextLens_Plum, 0xFF1A1220.toInt(), 0xFF261A2E.toInt(), 0xFFF3E9F7.toInt(), 0xFFC98CE6.toInt()),
        Palette("sand", "স্যান্ড", false, R.style.Theme_TextLens_Sand, 0xFFF5EFE6.toInt(), 0xFFFFFBF5.toInt(), 0xFF2B2418.toInt(), 0xFFB5651D.toInt()),
        Palette("mist", "মিস্ট", false, R.style.Theme_TextLens_Mist, 0xFFF4F7FA.toInt(), 0xFFFFFFFF.toInt(), 0xFF1B2430.toInt(), 0xFF1F7A7A.toInt()),
        Palette("mint", "মিন্ট", false, R.style.Theme_TextLens_Mint, 0xFFEEF6F1.toInt(), 0xFFFBFFFC.toInt(), 0xFF14241B.toInt(), 0xFF2E8B57.toInt())
    )

    private const val SYSTEM_DARK = "midnight"
    private const val SYSTEM_LIGHT = "mist"

    private fun byId(id: String) = palettes.firstOrNull { it.id == id } ?: palettes[0]

    /** The palette that is active right now (honours "follow phone setting"). */
    fun current(c: Context): Palette {
        if (Prefs.followSystem(c)) {
            val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return byId(if (night) SYSTEM_DARK else SYSTEM_LIGHT)
        }
        return byId(Prefs.palette(c))
    }

    fun themeRes(c: Context): Int = current(c).styleRes

    /** Keeps AppCompat dialogs etc. in the same light/dark family as the palette. */
    fun applyNightMode(c: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (current(c).dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /** Call in Activity.onCreate BEFORE super.onCreate / setContentView. */
    fun applyTo(a: Activity) {
        a.setTheme(themeRes(a))
    }

    /** A themed context for views created outside an Activity (floating bubble and panels). */
    fun themedContext(base: Context): Context = ContextThemeWrapper(base, themeRes(base))
}

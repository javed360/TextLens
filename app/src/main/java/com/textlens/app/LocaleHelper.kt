package com.textlens.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's own language (Bengali or English), independent of the phone's language.
 * Works for Activities, Services and the floating overlay panels alike.
 */
object LocaleHelper {
    const val BN = "bn"
    const val EN = "en"

    /** The language chosen in Settings ("bn" by default). */
    fun current(c: Context): String = if (Prefs.appLang(c) == EN) EN else BN

    /** The same context, but with the chosen app language. */
    fun localized(base: Context): Context {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(current(base)))
        return base.createConfigurationContext(cfg)
    }

    /** A string in the chosen language (use this outside Activities). */
    fun str(c: Context, id: Int, vararg args: Any): String {
        val ctx = localized(c)
        return if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
    }
}

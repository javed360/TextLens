package com.textlens.app

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.card.MaterialCardView
import com.textlens.app.databinding.ActivitySettingsBinding
import kotlin.concurrent.thread

class SettingsActivity : BaseActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTo(this)
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        BottomNav.attach(this, b.root, BottomNav.SETTINGS)

        buildPalettes()

        // App language (Bengali / English). The change is applied to every screen, the floating
        // panels, notifications and the Quick Settings tile label.
        if (LocaleHelper.current(this) == LocaleHelper.EN) b.rbLangEn.isChecked = true
        else b.rbLangBn.isChecked = true
        b.rgAppLang.setOnCheckedChangeListener { _, id ->
            val tag = if (id == R.id.rb_lang_en) LocaleHelper.EN else LocaleHelper.BN
            if (tag != LocaleHelper.current(this)) {
                Prefs.setAppLang(this, tag)
                BubbleTileService.requestRefresh(this)
                recreate()
            }
        }

        b.switchFollow.isChecked = Prefs.followSystem(this)
        b.switchFollow.setOnCheckedChangeListener { _, on ->
            Prefs.setFollowSystem(this, on)
            ThemeManager.applyNightMode(this)
            recreate()
        }

        b.switchAutoCopy.isChecked = Prefs.autoCopy(this)
        b.switchAutoCopy.setOnCheckedChangeListener { _, on -> Prefs.setAutoCopy(this, on) }

        b.switchGemini.isChecked = Prefs.geminiOn(this)
        b.switchGemini.setOnCheckedChangeListener { _, on -> Prefs.setGeminiOn(this, on) }
        b.etKey.setText(Prefs.geminiKey(this))
        b.etModel.setText(Prefs.geminiModel(this))
        b.btnGeminiSave.setOnClickListener { saveAndTestGemini() }

        when (Prefs.ocrLang(this)) {
            "ara" -> b.rbAra.isChecked = true
            "ben" -> b.rbBen.isChecked = true
            "eng" -> b.rbEng.isChecked = true
            else -> b.rbAuto.isChecked = true
        }
        b.rgOcr.setOnCheckedChangeListener { _, id ->
            Prefs.setOcrLang(
                this, when (id) {
                    R.id.rb_ara -> "ara"
                    R.id.rb_ben -> "ben"
                    R.id.rb_eng -> "eng"
                    else -> "auto"
                }
            )
        }

        refreshLangStatus()
        b.btnDownload.setOnClickListener { downloadLanguages() }
    }

    private fun saveAndTestGemini() {
        Prefs.setGeminiKey(this, b.etKey.text.toString())
        Prefs.setGeminiModel(this, b.etModel.text.toString().ifBlank { GeminiHelper.DEFAULT_MODEL })
        if (Prefs.geminiKey(this).isBlank()) {
            b.geminiStatus.text = getString(R.string.gemini_no_key)
            return
        }
        if (!GeminiHelper.isOnline(this)) {
            b.geminiStatus.text = getString(R.string.gemini_saved_offline)
            return
        }
        b.geminiStatus.text = getString(R.string.gemini_testing)
        b.btnGeminiSave.isEnabled = false
        val app = applicationContext
        thread {
            val r = runCatching { GeminiHelper.translate(app, "Hello, how are you?", TranslateHelper.BN) }
            runOnUiThread {
                b.btnGeminiSave.isEnabled = true
                b.geminiStatus.text = r.fold(
                    { getString(R.string.gemini_ok, it) },
                    { getString(R.string.gemini_fail, it.message ?: "") }
                )
            }
        }
    }

    /** Each card previews a whole theme: its background, surface and accent. */
    private fun buildPalettes() {
        b.paletteGrid.removeAllViews()
        val d = resources.displayMetrics.density
        val selected = Prefs.palette(this)
        val dimmed = Prefs.followSystem(this)

        for (p in ThemeManager.palettes) {
            val card = MaterialCardView(this).apply {
                radius = 16 * d
                cardElevation = 0f
                setCardBackgroundColor(p.bg)
                strokeWidth = ((if (p.id == selected && !dimmed) 3 else 1) * d).toInt()
                strokeColor = if (p.id == selected && !dimmed) p.primary else (p.text and 0x00FFFFFF) or 0x40000000
                alpha = if (dimmed) 0.45f else 1f
                isClickable = true
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins((5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt())
                }
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            }
            val name = TextView(this).apply {
                text = getString(themeNameRes(p.id))
                setTextColor(p.text)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val dots = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * d).toInt(), 0, 0)
            }
            for (c in intArrayOf(p.primary, p.surface, p.text)) {
                dots.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt())
                        .apply { marginEnd = (8 * d).toInt() }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        setStroke((1 * d).toInt(), (p.text and 0x00FFFFFF) or 0x40000000)
                    }
                })
            }
            inner.addView(name)
            inner.addView(dots)
            card.addView(inner)
            card.setOnClickListener {
                if (Prefs.followSystem(this)) {
                    Toast.makeText(this, R.string.toast_turn_off_follow, Toast.LENGTH_SHORT).show()
                } else if (p.id != Prefs.palette(this)) {
                    Prefs.setPalette(this, p.id)
                    ThemeManager.applyNightMode(this)
                    recreate()
                }
            }
            b.paletteGrid.addView(card)
        }
    }

    /** Theme names come from string resources so they follow the app language. */
    private fun themeNameRes(id: String): Int = when (id) {
        "amoled" -> R.string.theme_amoled
        "forest" -> R.string.theme_forest
        "ocean" -> R.string.theme_ocean
        "plum" -> R.string.theme_plum
        "sand" -> R.string.theme_sand
        "mist" -> R.string.theme_mist
        "mint" -> R.string.theme_mint
        else -> R.string.theme_midnight
    }

    private fun refreshLangStatus() {
        val missing = OcrHelper.missing(this)
        b.langStatus.text = if (missing.isEmpty()) getString(R.string.lang_ready)
        else getString(R.string.lang_missing, missing.joinToString(", "))
        b.btnDownload.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun downloadLanguages() {
        b.progress.visibility = View.VISIBLE
        b.btnDownload.isEnabled = false
        val app = applicationContext
        thread {
            val err = runCatching {
                OcrHelper.ensureData(app) { msg -> runOnUiThread { b.langStatus.text = msg } }
            }.exceptionOrNull()
            runOnUiThread {
                b.progress.visibility = View.GONE
                b.btnDownload.isEnabled = true
                refreshLangStatus()
                if (err != null) {
                    Toast.makeText(this, getString(R.string.err_download_failed, err.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

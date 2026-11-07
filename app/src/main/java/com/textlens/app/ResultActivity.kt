package com.textlens.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.textlens.app.databinding.ActivityResultBinding

/** Shows recognised text (editable), with Copy / Share / Translate. */
class ResultActivity : BaseActivity() {

    private lateinit var b: ActivityResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTo(this)
        super.onCreate(savedInstanceState)
        b = ActivityResultBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.editText.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())

        when (Prefs.targetLang(this)) {
            TranslateHelper.EN -> b.chipEn.isChecked = true
            TranslateHelper.AR -> b.chipAr.isChecked = true
            else -> b.chipBn.isChecked = true
        }

        b.btnCopy.setOnClickListener { copy(b.editText.text.toString()) }
        b.btnShare.setOnClickListener { share(b.editText.text.toString()) }
        b.btnCopyTr.setOnClickListener { copy(b.tvTranslated.text.toString()) }
        b.btnShareTr.setOnClickListener { share(b.tvTranslated.text.toString()) }
        b.btnTranslate.setOnClickListener { translate() }
    }

    private fun selectedTarget(): String = when (b.chipGroup.checkedChipId) {
        R.id.chip_en -> TranslateHelper.EN
        R.id.chip_ar -> TranslateHelper.AR
        else -> TranslateHelper.BN
    }

    private fun translate() {
        val text = b.editText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.result_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val target = selectedTarget()
        Prefs.setTargetLang(this, target)
        b.progress.visibility = View.VISIBLE
        b.btnTranslate.isEnabled = false
        TranslateHelper.translate(this, text, target) { r ->
            b.progress.visibility = View.GONE
            b.btnTranslate.isEnabled = true
            r.onSuccess {
                b.tvTranslated.text = it
                b.cardTranslated.visibility = View.VISIBLE
            }.onFailure {
                Toast.makeText(
                    this,
                    getString(R.string.err_translate_failed) + " (${it.message})",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("TextLens", text))
        Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show()
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.btn_share)))
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}

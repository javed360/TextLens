package com.textlens.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.textlens.app.databinding.ActivityPdfBinding
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.concurrent.Executors

/**
 * Open a PDF, read one page at a time, select any part of the text and send it to the result
 * screen (copy / translate / share). If a page has no text layer (scanned book) it is rendered
 * and read with OCR instead.
 */
class PdfTextActivity : BaseActivity() {

    private lateinit var b: ActivityPdfBinding
    private val worker = Executors.newSingleThreadExecutor()
    private var doc: PDDocument? = null
    private var file: File? = null
    private var pages = 0
    private var page = 0

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTo(this)
        super.onCreate(savedInstanceState)
        b = ActivityPdfBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnPrev.setOnClickListener { show(page - 1) }
        b.btnNext.setOnClickListener { show(page + 1) }
        b.btnUse.setOnClickListener { useSelection() }

        val uri: Uri? = intent?.data
        if (uri != null) load(uri) else picker.launch(arrayOf("application/pdf"))
    }

    private fun load(uri: Uri) {
        busy(true)
        worker.execute {
            try {
                val f = File(cacheDir, "current.pdf")
                contentResolver.openInputStream(uri)!!.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                val d = PDDocument.load(f)
                doc?.close()
                doc = d
                file = f
                pages = d.numberOfPages
                runOnUiThread {
                    b.tvTitle.text = uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"
                    show(0)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    busy(false)
                    Toast.makeText(this, getString(R.string.err_pdf_open, t.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun show(index: Int) {
        if (pages == 0) return
        page = index.coerceIn(0, pages - 1)
        b.tvPage.text = getString(R.string.pdf_page_of, page + 1, pages)
        b.tvText.text = ""
        busy(true)
        val d = doc ?: return
        val p = page
        worker.execute {
            val text = try {
                val s = PDFTextStripper().apply { startPage = p + 1; endPage = p + 1 }
                s.getText(d).trim()
            } catch (t: Throwable) { "" }

            val finalText = if (text.length >= 5) text else ocrPage(p)
            runOnUiThread {
                if (p == page) {
                    b.tvText.text = finalText.ifBlank { getString(R.string.result_empty) }
                    busy(false)
                }
            }
        }
    }

    /** Scanned page: render it and run offline OCR. */
    private fun ocrPage(p: Int): String = try {
        val app = applicationContext
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { r ->
            r.openPage(p).use { pg ->
                val scale = 2.2f
                val bmp = Bitmap.createBitmap(
                    (pg.width * scale).toInt(), (pg.height * scale).toInt(), Bitmap.Config.ARGB_8888
                )
                bmp.eraseColor(Color.WHITE)
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                OcrEngine.recognize(app, bmp, enhance = true) { msg -> runOnUiThread { b.tvText.text = msg } }
            }
        }
    } catch (t: Throwable) {
        ""
    }

    private fun useSelection() {
        val tv = b.tvText
        val full = tv.text.toString()
        val s = tv.selectionStart
        val e = tv.selectionEnd
        val chosen = if (s >= 0 && e > s) full.substring(s, e) else full
        if (chosen.isBlank()) {
            Toast.makeText(this, R.string.result_empty, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, ResultActivity::class.java).putExtra(ResultActivity.EXTRA_TEXT, chosen))
    }

    private fun busy(on: Boolean) {
        b.progress.visibility = if (on) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        runCatching { doc?.close() }
        worker.shutdown()
        super.onDestroy()
    }
}

package com.textlens.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.textlens.app.databinding.ActivityImageToPdfBinding
import java.io.File
import java.util.concurrent.Executors

/**
 * Pick one or more images (in order), combine them into one A4 PDF, then let the user
 * save it anywhere (Save) or send it to another app (Share). Works fully offline.
 */
class ImageToPdfActivity : BaseActivity() {

    private lateinit var b: ActivityImageToPdfBinding
    private val worker = Executors.newSingleThreadExecutor()
    private var images: List<Uri> = emptyList()
    private var pdfFile: File? = null

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        images = uris
        pdfFile = null
        b.btnSave.isEnabled = false
        b.btnShare.isEnabled = false
        b.tvCount.text = getString(R.string.img_count, uris.size)
        b.btnBuild.isEnabled = true
    }

    private val saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val src = pdfFile ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        worker.execute {
            try {
                contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                runOnUiThread { Toast.makeText(this, R.string.pdf_saved, Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, getString(R.string.err_save_failed, t.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTo(this)
        super.onCreate(savedInstanceState)
        b = ActivityImageToPdfBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnPick.setOnClickListener { pickImages.launch("image/*") }
        b.btnBuild.setOnClickListener { build() }
        b.btnSave.setOnClickListener { saveAs.launch("TextLens-${System.currentTimeMillis()}.pdf") }
        b.btnShare.setOnClickListener { share() }
        b.btnBuild.isEnabled = false
        b.btnSave.isEnabled = false
        b.btnShare.isEnabled = false
    }

    private fun build() {
        val list = images
        if (list.isEmpty()) return
        b.progress.visibility = View.VISIBLE
        b.btnBuild.isEnabled = false
        worker.execute {
            try {
                val out = File(cacheDir, "textlens-${System.currentTimeMillis()}.pdf")
                createPdf(list, out)
                pdfFile = out
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    b.btnBuild.isEnabled = true
                    b.btnSave.isEnabled = true
                    b.btnShare.isEnabled = true
                    b.tvCount.text = getString(R.string.img_pdf_done, list.size)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    b.progress.visibility = View.GONE
                    b.btnBuild.isEnabled = true
                    Toast.makeText(this, getString(R.string.err_pdf_build, t.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Each image is fitted into an A4 page, centred, keeping its aspect ratio. */
    private fun createPdf(uris: List<Uri>, out: File) {
        val pageW = 595   // A4 in points (72 dpi)
        val pageH = 842
        val margin = 24
        val doc = PdfDocument()
        try {
            uris.forEachIndexed { i, uri ->
                val bmp = decodeScaled(uri, 2000) ?: throw IllegalStateException(getString(R.string.err_image_read, i + 1))
                val info = PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create()
                val page = doc.startPage(info)
                val canvas: Canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                val availW = (pageW - 2 * margin).toFloat()
                val availH = (pageH - 2 * margin).toFloat()
                val scale = minOf(availW / bmp.width, availH / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                val left = (pageW - w) / 2f
                val top = (pageH - h) / 2f
                canvas.drawBitmap(
                    bmp, null,
                    android.graphics.RectF(left, top, left + w, top + h),
                    null
                )
                doc.finishPage(page)
                bmp.recycle()
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    /** Decode without loading huge photos into memory. */
    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun share() {
        val f = pdfFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.btn_share)))
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

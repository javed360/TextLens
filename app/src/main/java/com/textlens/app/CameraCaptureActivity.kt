package com.textlens.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.textlens.app.databinding.ActivityCameraBinding
import kotlin.concurrent.thread

/** Take a photo of a page, then OCR it (offline) and open the result screen. */
class CameraCaptureActivity : AppCompatActivity() {

    private lateinit var b: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            Toast.makeText(this, R.string.perm_camera_desc, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTo(this)
        super.onCreate(savedInstanceState)
        b = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnCapture.setOnClickListener { takePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.preview.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            imageCapture = capture
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        b.btnCapture.isEnabled = false
        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp: Bitmap
                try {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    // A 12 MP photo is huge for OCR and for the heap: rotate and shrink in ONE step.
                    val scale = minOf(1f, 2600f / maxOf(raw.width, raw.height))
                    bmp = if (rotation == 0 && scale >= 1f) raw
                    else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                        Matrix().apply { postRotate(rotation.toFloat()); postScale(scale, scale) }, true)
                } catch (t: Throwable) {
                    b.btnCapture.isEnabled = true
                    Toast.makeText(this@CameraCaptureActivity, t.message ?: "Camera error", Toast.LENGTH_LONG).show()
                    return
                } finally {
                    image.close()
                }
                recognize(bmp)
            }

            override fun onError(exception: ImageCaptureException) {
                b.btnCapture.isEnabled = true
                Toast.makeText(this@CameraCaptureActivity, exception.message ?: "Camera error", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun recognize(bmp: Bitmap) {
        b.progressOverlay.visibility = View.VISIBLE
        val app = applicationContext
        thread {
            try {
                OcrHelper.ensureData(app) { msg -> runOnUiThread { b.progressText.text = msg } }
                runOnUiThread { b.progressText.setText(R.string.camera_recognizing) }
                val text = OcrHelper.recognize(app, bmp)
                runOnUiThread {
                    startActivity(Intent(this, ResultActivity::class.java).putExtra(ResultActivity.EXTRA_TEXT, text))
                    finish()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    b.progressOverlay.visibility = View.GONE
                    b.btnCapture.isEnabled = true
                    Toast.makeText(this, "OCR ব্যর্থ: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

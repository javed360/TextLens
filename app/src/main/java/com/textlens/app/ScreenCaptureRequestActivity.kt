package com.textlens.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * FALLBACK only (used when the Accessibility screenshot service is not enabled).
 * Shows the system "start recording / casting?" dialog, takes the screenshot and hands it
 * to the floating selection overlay. Invisible: the user stays visually in their app.
 */
class ScreenCaptureRequestActivity : AppCompatActivity() {

    private lateinit var mode: String

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            CaptureStore.clear()
            CaptureStore.listener = { ok -> onCaptured(ok) }
            val svc = Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_CODE, r.resultCode)
                .putExtra(ScreenCaptureService.EXTRA_DATA, r.data)
            ContextCompat.startForegroundService(this, svc)
        } else {
            BubbleService.show(this)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(SelectMode.EXTRA) ?: SelectMode.BOX
        val mpm = getSystemService(MediaProjectionManager::class.java)
        consent.launch(mpm.createScreenCaptureIntent())
    }

    private fun onCaptured(ok: Boolean) {
        val bmp = CaptureStore.bitmap
        val svc = BubbleService.instance
        if (ok && bmp != null && svc != null) {
            svc.showSelection(bmp, mode)
        } else {
            Toast.makeText(this, "স্ক্রিনশট নেওয়া যায়নি", Toast.LENGTH_SHORT).show()
            BubbleService.show(this)
        }
        CaptureStore.bitmap = null
        finish()
    }

    override fun onDestroy() {
        CaptureStore.listener = null
        super.onDestroy()
    }
}

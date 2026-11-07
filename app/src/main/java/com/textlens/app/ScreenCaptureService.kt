package com.textlens.app

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/** Takes exactly one screenshot through MediaProjection, then shuts itself down. */
class ScreenCaptureService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var done = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, TextLensApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_bubble)
            .setContentTitle(LocaleHelper.str(this, R.string.notif_taking_screenshot))
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            finish(false)
            return START_NOT_STICKY
        }
        // Small delay so the consent dialog has fully disappeared and is not in the screenshot.
        main.postDelayed({ capture(code, data) }, 450)
        return START_NOT_STICKY
    }

    private fun capture(code: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            projection = mp
            // Required on Android 14+ before creating a virtual display.
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    release()
                }
            }, main)

            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            val w = dm.widthPixels
            val h = dm.heightPixels

            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            display = mp.createVirtualDisplay(
                "textlens-capture", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, main
            )
            r.setOnImageAvailableListener({ ir ->
                if (done) return@setOnImageAvailableListener
                val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                var ok = false
                try {
                    val plane = img.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * w
                    val raw = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                    raw.copyPixelsFromBuffer(plane.buffer)
                    val bmp = if (raw.width != w) Bitmap.createBitmap(raw, 0, 0, w, h) else raw
                    CaptureStore.bitmap = bmp
                    ok = true
                } catch (t: Throwable) {
                    ok = false
                } finally {
                    img.close() // close the image BEFORE the reader is released in finish()
                }
                finish(ok)
            }, main)

            // Safety net: give up after 4 seconds.
            main.postDelayed({ if (!done) finish(false) }, 4000)
        } catch (t: Throwable) {
            finish(false)
        }
    }

    private fun finish(ok: Boolean) {
        if (done) return
        done = true
        release()
        CaptureStore.listener?.invoke(ok)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private var released = false

    private fun release() {
        if (released) return
        released = true
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null
        reader = null
        projection = null
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 12
    }
}

package com.textlens.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * Used ONLY to take a screenshot (Android 11+, no recording dialog each time).
 * It does not read any screen content or events.
 */
class TextLensAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Calls back on the main thread with a software ARGB bitmap, or null on failure. */
    fun capture(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            onResult(null)
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (t: Throwable) {
                        null
                    } finally {
                        buffer.close()
                    }
                    onResult(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    onResult(null)
                }
            })
        } catch (t: Throwable) {
            onResult(null)
        }
    }

    companion object {
        @Volatile
        var instance: TextLensAccessibilityService? = null

        fun isEnabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(c, TextLensAccessibilityService::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}

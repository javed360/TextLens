package com.textlens.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class TextLensApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applyNightMode(this)
        PDFBoxResourceLoader.init(applicationContext)
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            // MIN importance: no status-bar icon, no sound, only a quiet line in the shade.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TextLens", NotificationManager.IMPORTANCE_MIN).apply {
                    description = LocaleHelper.str(this@TextLensApp, R.string.channel_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "textlens_service"
    }
}

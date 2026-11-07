package com.textlens.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.textlens.app.databinding.ActivityMainBinding

class MainActivity : BaseActivity() {

    private lateinit var b: ActivityMainBinding
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        // Opening animation: the lens icon draws itself, then the splash fades/zooms away.
        val splash = installSplashScreen()
        ThemeManager.applyTo(this)
        super.onCreate(savedInstanceState)
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f).scaleX(1.12f).scaleY(1.12f)
                .setDuration(260)
                .withEndAction { provider.remove() }
                .start()
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        BottomNav.attach(this, b.root, BottomNav.HOME)

        // Home content slides up and fades in after the splash.
        b.root.alpha = 0f
        b.root.translationY = 24f * resources.displayMetrics.density
        b.root.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(380).start()

        b.cardCamera.setOnClickListener { startActivity(Intent(this, CameraCaptureActivity::class.java)) }
        b.cardPdf.setOnClickListener { startActivity(Intent(this, PdfTextActivity::class.java)) }
        b.cardImgPdf.setOnClickListener { startActivity(Intent(this, ImageToPdfActivity::class.java)) }
        b.btnToggle.setOnClickListener { toggleBubble() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun toggleBubble() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            return
        }
        runCatching { BubbleService.toggle(this) }
        render()
        main.postDelayed({ render() }, 500)
    }

    private fun render() {
        val on = BubbleService.running
        val overlay = Settings.canDrawOverlays(this)
        b.tvStatus.text = when {
            on -> getString(R.string.status_bubble_on)
            !overlay -> getString(R.string.status_need_overlay)
            else -> getString(R.string.status_bubble_off)
        }
        b.tvStatusSub.text =
            if (!TextLensAccessibilityService.isEnabled(this)) getString(R.string.status_hint_a11y)
            else getString(R.string.status_ready)
        b.btnToggle.text = getString(if (on) R.string.btn_bubble_off else R.string.btn_bubble_on)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

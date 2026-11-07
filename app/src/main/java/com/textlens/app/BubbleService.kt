package com.textlens.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import com.textlens.app.databinding.OverlayResultBinding
import com.textlens.app.databinding.OverlaySelectionBinding
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Owns the floating bubble AND the selection / result panels, all as overlay windows,
 * so the user never leaves the app they are reading (Facebook, browser, ...).
 */
class BubbleService : Service() {

    private lateinit var wm: WindowManager
    /** Fresh each time, so a changed language or theme shows up in the next panel that opens. */
    private val themed: Context get() = ThemeManager.themedContext(this)
    private val main = Handler(Looper.getMainLooper())

    private var bubble: View? = null
    private var menu: View? = null
    private var removeZone: View? = null
    private var selection: OverlaySelectionBinding? = null
    private var result: OverlayResultBinding? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var removeParams: WindowManager.LayoutParams

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        instance = this
        running = true
        BubbleTileService.requestRefresh(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_HIDE -> setBubbleVisible(false)
            ACTION_SHOW -> setBubbleVisible(true)
            else -> if (bubble == null) addBubble()
        }
        // Not sticky on purpose: after a phone restart / kill the bubble must NOT come back by itself.
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, TextLensApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_bubble)
            .setContentTitle(str(R.string.notif_title_running))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, str(R.string.notif_action_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** One clean exit path (X button, tile, notification). Posted so no view is removed mid-touch. */
    private fun shutdown() {
        main.post {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- bubble

    private fun screen(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun addBubble() {
        val dm = screen()
        val size = dp(56)
        val v = LayoutInflater.from(themed).inflate(R.layout.overlay_bubble, null)

        var x = Prefs.bubbleX(this)
        var y = Prefs.bubbleY(this)
        if (x < 0 || y < 0) {
            x = dm.widthPixels - size - dp(8)
            y = dm.heightPixels / 3
        }
        x = x.coerceIn(0, dm.widthPixels - size)
        y = y.coerceIn(0, dm.heightPixels - size)

        bubbleParams = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        val rz = LayoutInflater.from(themed).inflate(R.layout.overlay_remove, null)
        removeParams = WindowManager.LayoutParams(
            dp(52), dp(52), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (dm.widthPixels - dp(52)) / 2
            this.y = dm.heightPixels - dp(52) - dp(72)
        }
        rz.visibility = View.GONE
        removeZone = rz
        wm.addView(rz, removeParams)

        v.setOnTouchListener(dragListener())
        bubble = v
        wm.addView(v, bubbleParams)
    }

    private fun dragListener() = object : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var origX = 0
        private var origY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    origX = bubbleParams.x; origY = bubbleParams.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        hideMenu()
                        removeZone?.visibility = View.VISIBLE
                    }
                    if (dragging) {
                        val dm = screen()
                        bubbleParams.x = (origX + dx).toInt().coerceIn(0, dm.widthPixels - bubbleParams.width)
                        bubbleParams.y = (origY + dy).toInt().coerceIn(0, dm.heightPixels - bubbleParams.height)
                        wm.updateViewLayout(v, bubbleParams)
                        removeZone?.alpha = if (overRemoveZone()) 1f else 0.7f
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeZone?.visibility = View.GONE
                    if (dragging) {
                        if (e.actionMasked == MotionEvent.ACTION_UP && overRemoveZone()) {
                            shutdown()
                        } else {
                            snapToEdge()
                            Prefs.setBubblePos(this@BubbleService, bubbleParams.x, bubbleParams.y)
                        }
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        toggleMenu()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun overRemoveZone(): Boolean {
        val bx = bubbleParams.x + bubbleParams.width / 2f
        val by = bubbleParams.y + bubbleParams.height / 2f
        val rx = removeParams.x + removeParams.width / 2f
        val ry = removeParams.y + removeParams.height / 2f
        return hypot(bx - rx, by - ry) < dp(60)
    }

    private fun snapToEdge() {
        val dm = screen()
        val center = bubbleParams.x + bubbleParams.width / 2
        bubbleParams.x = if (center < dm.widthPixels / 2) dp(4) else dm.widthPixels - bubbleParams.width - dp(4)
        bubble?.let { wm.updateViewLayout(it, bubbleParams) }
    }

    private fun setBubbleVisible(visible: Boolean) {
        if (bubble == null) addBubble()
        bubble?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (!visible) hideMenu()
    }

    // ------------------------------------------------------------------ menu

    private fun toggleMenu() {
        if (menu != null) hideMenu() else showMenu()
    }

    private fun showMenu() {
        closeResult()
        val v = LayoutInflater.from(themed).inflate(R.layout.overlay_menu, null)
        v.findViewById<View>(R.id.btn_pen).setOnClickListener { startCapture(SelectMode.PEN) }
        v.findViewById<View>(R.id.btn_box).setOnClickListener { startCapture(SelectMode.BOX) }
        v.findViewById<View>(R.id.btn_camera).setOnClickListener {
            hideMenu()
            startActivity(Intent(this, CameraCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) hideMenu()
            false
        }
        v.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val dm = screen()
        val w = v.measuredWidth
        val h = v.measuredHeight
        val onRight = bubbleParams.x + bubbleParams.width / 2 > dm.widthPixels / 2
        val mx = if (onRight) bubbleParams.x - w - dp(8) else bubbleParams.x + bubbleParams.width + dp(8)
        val my = bubbleParams.y + (bubbleParams.height - h) / 2

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = mx.coerceAtLeast(0)
            y = my.coerceAtLeast(0)
        }
        menu = v
        wm.addView(v, p)
    }

    private fun hideMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }

    // --------------------------------------------------------------- capture

    private fun startCapture(mode: String) {
        hideMenu()
        val a11y = TextLensAccessibilityService.instance
        if (a11y != null && Build.VERSION.SDK_INT >= 30) {
            setBubbleVisible(false) // keep the bubble out of the screenshot
            // No dialog: the accessibility service takes the screenshot directly.
            main.postDelayed({
                a11y.capture { bmp ->
                    if (bmp != null) showSelection(bmp, mode)
                    else {
                        setBubbleVisible(true)
                        toast(str(R.string.toast_capture_protected))
                    }
                }
            }, 180)
        } else {
            // Fallback: system screen-capture dialog (shown every time).
            toast(str(R.string.toast_enable_a11y))
            // Start the activity while the bubble window is still visible (Android only allows
            // background activity starts while an overlay window is showing), then hide the bubble.
            startActivity(
                Intent(this, ScreenCaptureRequestActivity::class.java)
                    .putExtra(SelectMode.EXTRA, mode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            setBubbleVisible(false)
        }
    }

    // ------------------------------------------------------------- selection

    fun showSelection(bmp: Bitmap, mode: String) {
        closeSelection()
        closeResult()
        val b = OverlaySelectionBinding.inflate(LayoutInflater.from(themed))
        b.selectionView.bitmap = bmp
        b.selectionView.mode = mode
        b.toggleGroup.check(if (mode == SelectMode.PEN) R.id.btn_mode_pen else R.id.btn_mode_box)
        b.toggleGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) b.selectionView.mode = if (id == R.id.btn_mode_pen) SelectMode.PEN else SelectMode.BOX
        }
        b.selectionView.onSelectionChanged = { has ->
            b.btnConfirm.isEnabled = has
            b.hint.visibility = if (has) View.GONE else View.VISIBLE
        }
        b.btnCancel.setOnClickListener { closeSelection(); setBubbleVisible(true) }
        b.btnConfirm.setOnClickListener { runOcr(b) }

        val dm = screen()
        val p = WindowManager.LayoutParams(
            dm.widthPixels, dm.heightPixels, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        selection = b
        wm.addView(b.root, p)
    }

    private fun closeSelection() {
        selection?.let { runCatching { wm.removeView(it.root) } }
        selection = null
    }

    private fun runOcr(b: OverlaySelectionBinding) {
        val crop = b.selectionView.cropSelection()
        if (crop == null) {
            toast(str(R.string.result_empty))
            return
        }
        b.progressOverlay.visibility = View.VISIBLE
        val app = applicationContext
        thread {
            try {
                val text = OcrEngine.recognize(app, crop, enhance = false) { msg ->
                    main.post { b.progressText.text = msg }
                }
                main.post {
                    closeSelection()
                    setBubbleVisible(true)
                    showResult(text)
                    toast(OcrEngine.lastInfo)
                }
            } catch (t: Throwable) {
                main.post {
                    b.progressOverlay.visibility = View.GONE
                    toast(str(R.string.err_ocr_failed, t.message ?: ""))
                }
            }
        }
    }

    // ---------------------------------------------------------------- result

    private fun showResult(text: String) {
        closeResult()
        val b = OverlayResultBinding.inflate(LayoutInflater.from(themed))
        b.tvText.text = text.ifBlank { str(R.string.result_empty) }
        b.tvText.movementMethod = android.text.method.ScrollingMovementMethod()
        b.tvTranslated.movementMethod = android.text.method.ScrollingMovementMethod()
        when (Prefs.targetLang(this)) {
            TranslateHelper.EN -> b.chipEn.isChecked = true
            TranslateHelper.AR -> b.chipAr.isChecked = true
            else -> b.chipBn.isChecked = true
        }

        b.btnClose.setOnClickListener { closeResult() }
        b.btnCopy.setOnClickListener { copy(text); }
        b.btnCopyTr.setOnClickListener { copy(b.tvTranslated.text.toString()) }
        b.btnShare.setOnClickListener { share(text) }
        b.btnTranslate.setOnClickListener {
            if (text.isBlank()) return@setOnClickListener
            val target = when (b.chipGroup.checkedChipId) {
                R.id.chip_en -> TranslateHelper.EN
                R.id.chip_ar -> TranslateHelper.AR
                else -> TranslateHelper.BN
            }
            Prefs.setTargetLang(this, target)
            b.progress.visibility = View.VISIBLE
            b.btnTranslate.isEnabled = false
            TranslateHelper.translate(this, text, target) { r ->
                b.progress.visibility = View.GONE
                b.btnTranslate.isEnabled = true
                r.onSuccess {
                    b.tvTranslated.text = it
                    b.boxTranslated.visibility = View.VISIBLE
                }.onFailure {
                    toast(str(R.string.err_translate_failed))
                }
            }
        }
        b.root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) closeResult()
            false
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        result = b
        wm.addView(b.root, p)

        if (Prefs.autoCopy(this) && text.isNotBlank()) copy(text)
    }

    private fun closeResult() {
        result?.let { runCatching { wm.removeView(it.root) } }
        result = null
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("TextLens", text))
        toast(str(R.string.copied_toast))
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(
            Intent.createChooser(send, str(R.string.btn_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun str(id: Int, vararg a: Any): String = LocaleHelper.str(this, id, *a)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        hideMenu()
        closeSelection()
        closeResult()
        bubble?.let { runCatching { wm.removeView(it) } }
        removeZone?.let { runCatching { wm.removeView(it) } }
        bubble = null
        removeZone = null
        running = false
        instance = null
        BubbleTileService.requestRefresh(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.textlens.app.STOP"
        const val ACTION_HIDE = "com.textlens.app.HIDE"
        const val ACTION_SHOW = "com.textlens.app.SHOW"
        private const val NOTIF_ID = 11

        @Volatile
        var running = false

        @Volatile
        var instance: BubbleService? = null

        /** Bring the bubble back (e.g. after a cancelled capture). */
        fun show(c: Context) {
            instance?.setBubbleVisible(true)
        }

        /** Start the bubble, or stop it if it is already running. Used by the tile and the home screen. */
        fun toggle(c: Context) {
            val app = c.applicationContext
            if (running) {
                app.startService(Intent(app, BubbleService::class.java).setAction(ACTION_STOP))
            } else {
                app.startForegroundService(Intent(app, BubbleService::class.java))
            }
        }
    }
}

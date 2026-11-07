package com.textlens.app

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.textlens.app.databinding.ActivityPermissionsBinding

/** Live status of everything the app needs, with one-tap shortcuts to the right system screen. */
class PermissionsActivity : BaseActivity() {

    private lateinit var b: ActivityPermissionsBinding

    private class Row(
        val title: Int, val desc: Int,
        val granted: () -> Boolean?,   // null = cannot be detected
        val buttonText: () -> Int,
        val action: () -> Unit
    )

    private lateinit var rows: List<Row>
    private val renderers = mutableListOf<() -> Unit>()

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val requestNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTo(this)
        super.onCreate(savedInstanceState)
        overlayAsked = savedInstanceState?.getBoolean("overlay_asked") ?: false
        b = ActivityPermissionsBinding.inflate(layoutInflater)
        BottomNav.attach(this, b.root, BottomNav.PERMISSIONS)

        rows = listOf(
            Row(R.string.perm_a11y_title, R.string.perm_a11y_desc,
                { TextLensAccessibilityService.isEnabled(this) }, { R.string.perm_open_settings }) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Row(R.string.perm_overlay_title, R.string.perm_overlay_desc,
                { Settings.canDrawOverlays(this) }, { R.string.perm_open_settings }) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            },
            Row(R.string.perm_camera_title, R.string.perm_camera_desc,
                { has(Manifest.permission.CAMERA) }, { R.string.perm_open_settings }) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
                    !has(Manifest.permission.CAMERA)
                ) requestCamera.launch(Manifest.permission.CAMERA)
                else openAppSettings()
            },
            Row(R.string.perm_notif_title, R.string.perm_notif_desc,
                { if (Build.VERSION.SDK_INT >= 33) has(Manifest.permission.POST_NOTIFICATIONS) else true },
                { R.string.perm_open_settings }) {
                if (Build.VERSION.SDK_INT >= 33) requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                else openAppSettings()
            },
            Row(R.string.perm_tile_title, R.string.perm_tile_desc,
                { null }, { R.string.perm_add_tile }) { addTile() },
            Row(R.string.perm_battery_title, R.string.perm_battery_desc,
                {
                    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                }, { R.string.perm_open_settings }) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        )

        for (row in rows) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_permission, b.list, false)
            v.findViewById<TextView>(R.id.perm_title).setText(row.title)
            v.findViewById<TextView>(R.id.perm_desc).setText(row.desc)
            val status = v.findViewById<TextView>(R.id.perm_status)
            val btn = v.findViewById<MaterialButton>(R.id.perm_button)
            btn.setOnClickListener { row.action() }
            renderers += {
                val g = row.granted()
                when (g) {
                    true -> {
                        status.setText(R.string.perm_granted)
                        status.setTextColor(ContextCompat.getColor(this, R.color.success))
                        btn.visibility = android.view.View.GONE
                    }
                    false -> {
                        status.setText(R.string.perm_not_granted)
                        status.setTextColor(ContextCompat.getColor(this, R.color.danger))
                        btn.visibility = android.view.View.VISIBLE
                        btn.setText(row.buttonText())
                    }
                    null -> {
                        status.text = "—"
                        status.setTextColor(
                            MaterialColors.getColor(status, com.google.android.material.R.attr.colorOnSurfaceVariant)
                        )
                        btn.visibility = android.view.View.VISIBLE
                        btn.setText(row.buttonText())
                    }
                }
            }
            b.list.addView(v)
        }
    }

    private var overlayAsked = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("overlay_asked", overlayAsked)
    }

    override fun onResume() {
        super.onResume()
        if (intent.getBooleanExtra(EXTRA_FROM_TILE, false)) {
            if (Settings.canDrawOverlays(this)) {
                // Permission just granted from the tile flow: start the bubble and get out of the way.
                if (!BubbleService.running) BubbleService.toggle(this)
                finish()
                return
            }
            if (!overlayAsked) {
                overlayAsked = true
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                return
            }
        }
        render()
    }

    companion object {
        const val EXTRA_FROM_TILE = "from_tile"
    }

    private fun render() = renderers.forEach { it() }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, BubbleTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_tile_bubble),
                mainExecutor
            ) { }
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_tile_title)
                .setMessage(R.string.tile_howto)
                .setPositiveButton(R.string.btn_ok, null)
                .show()
        }
    }
}

package com.textlens.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The Quick Settings tile (like Shazam). Tap = show/hide the floating bubble. */
class BubbleTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            openPermissions()
            return
        }
        runCatching { BubbleService.toggle(this) }
        // The service reports its real state; re-check shortly so the tile never gets stuck.
        refresh()
        main.postDelayed({ refresh() }, 400)
        main.postDelayed({ refresh() }, 1200)
    }

    private fun openPermissions() {
        val i = Intent(this, PermissionsActivity::class.java)
            .putExtra(PermissionsActivity.EXTRA_FROM_TILE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.label = LocaleHelper.str(this, R.string.tile_label)
        t.state = if (BubbleService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** Ask the system to refresh the tile (called by BubbleService on start/stop). */
        fun requestRefresh(c: Context) {
            runCatching {
                TileService.requestListeningState(c, ComponentName(c, BubbleTileService::class.java))
            }
        }
    }
}

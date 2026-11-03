package com.textlens.app

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Puts a Home / Permissions / Settings bar at the bottom of a screen, like other apps. */
object BottomNav {
    const val HOME = R.id.nav_home
    const val PERMISSIONS = R.id.nav_permissions
    const val SETTINGS = R.id.nav_settings

    fun attach(a: Activity, content: View, selected: Int) {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        content.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        root.addView(content)

        val nav = LayoutInflater.from(a).inflate(R.layout.view_bottom_nav, root, false) as BottomNavigationView
        nav.selectedItemId = selected
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selected) return@setOnItemSelectedListener true
            val target = when (item.itemId) {
                PERMISSIONS -> PermissionsActivity::class.java
                SETTINGS -> SettingsActivity::class.java
                else -> MainActivity::class.java
            }
            val i = Intent(a, target)
            if (item.itemId == HOME) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            a.startActivity(i)
            @Suppress("DEPRECATION")
            a.overridePendingTransition(0, 0)
            if (selected != HOME) a.finish()
            false
        }
        root.addView(nav)
        a.setContentView(root)
    }
}

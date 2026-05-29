package com.mediaguard.service

import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * MediaGuardTileService – Bug-bereinigt
 * Fix Bug 8: subtitle nur ab API 29 setzen
 */
class MediaGuardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            qsTile?.apply {
                state = Tile.STATE_UNAVAILABLE
                label = "Berechtigung fehlt"
                // BUG 8 FIX: subtitle erst ab API 29
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    subtitle = "Einrichten"
                }
                updateTile()
            }
            return
        }

        val prefs = getSharedPreferences("mediaguard", MODE_PRIVATE)
        val isRunning = prefs.getBoolean("overlay_running", false)

        try {
            if (isRunning) {
                OverlayService.stop(this)
                prefs.edit().putBoolean("overlay_running", false).apply()
            } else {
                OverlayService.start(this)
                prefs.edit().putBoolean("overlay_running", true).apply()
            }
        } catch (_: Exception) {}

        updateTile()
    }

    private fun updateTile() {
        val prefs = getSharedPreferences("mediaguard", MODE_PRIVATE)
        val isRunning = prefs.getBoolean("overlay_running", false)
        val hasPermission = Settings.canDrawOverlays(this)

        qsTile?.apply {
            when {
                !hasPermission -> {
                    state = Tile.STATE_UNAVAILABLE
                    label = "MediaGuard"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = "Berechtigung nötig"
                    }
                    icon = Icon.createWithResource(
                        this@MediaGuardTileService,
                        android.R.drawable.ic_lock_idle_lock
                    )
                }
                isRunning -> {
                    state = Tile.STATE_ACTIVE
                    label = "MediaGuard"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = "Aktiv"
                    }
                    icon = Icon.createWithResource(
                        this@MediaGuardTileService,
                        android.R.drawable.ic_lock_lock
                    )
                }
                else -> {
                    state = Tile.STATE_INACTIVE
                    label = "MediaGuard"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = "Aus"
                    }
                    icon = Icon.createWithResource(
                        this@MediaGuardTileService,
                        android.R.drawable.ic_lock_idle_lock
                    )
                }
            }
            updateTile()
        }
    }
}

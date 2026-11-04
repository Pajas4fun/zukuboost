package cz.zukuboost

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Dlaždice v rychlém nastavení: rychle pozastaví / obnoví boost. */
class BoostTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val prefs = Prefs(this)
        if (GameMonitorService.running) {
            prefs.paused = !prefs.paused
        } else {
            prefs.paused = false
            prefs.monitorEnabled = true
            val started = runCatching { GameMonitorService.start(this) }.isSuccess
            if (!started) openApp()
        }
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val running = GameMonitorService.running || Prefs(this).monitorEnabled
        val on = running && !Prefs(this).paused
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "ZukuBoost"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                on -> "On"
                running -> "Paused"
                else -> "Off"
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

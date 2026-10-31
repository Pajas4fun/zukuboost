package cz.zukuboost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Po restartu telefonu nebo aktualizaci appky znovu spustí hlídání her. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs(ctx).monitorEnabled && !GameMonitorService.running) {
            runCatching { GameMonitorService.start(ctx) }
        }
    }
}

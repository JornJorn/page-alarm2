package dev.pagealarm.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts monitoring after a reboot or an app update, if it was switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        if (Store.monitoring(context) && Store.url(context).isNotBlank()) {
            MonitorService.start(context)
        }
    }
}

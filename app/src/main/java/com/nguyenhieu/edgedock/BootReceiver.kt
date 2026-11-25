package com.nguyenhieu.edgedock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (DockPrefs.isDockEnabled(context) && Settings.canDrawOverlays(context)) {
            try {
                EdgeOverlayService.start(context)
            } catch (_: Throwable) {
            }
        }
    }
}

package com.nguyenhieu.edgedock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (DockPrefs.isDockEnabled(context) && Settings.canDrawOverlays(context)) {
            EdgeOverlayService.start(context)
        }
    }
}

package com.nguyenhieu.edgedock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object AppLauncherEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    // FLAG_ACTIVITY_NEW_TASK (0x10000000) | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS (0x00800000)
    private const val FLAGS_NEW_EXCLUDE_RECENTS = 0x10800000

    private fun getHomePackages(context: Context): Set<String> {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun launchDockApp(context: Context, entry: DockAppEntry) {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(entry.packageName)
        if (launchIntent == null) {
            Toast.makeText(context, "Không tìm thấy app ${entry.label}", Toast.LENGTH_SHORT).show()
            return
        }

        val currentFg = DockSessionTracker.currentForegroundPackage
        val homePkgs = getHomePackages(context)
        val isFromHome = currentFg.isNullOrBlank() || currentFg in homePkgs
        val prevTarget = if (!isFromHome && currentFg != entry.packageName && currentFg != context.packageName) {
            currentFg
        } else null

        val wantPopup = DockPrefs.isPopupMode(context) && ShizukuBridge.getStatus(context) == ShizukuBridge.Status.READY
        val hideRecents = DockPrefs.isHideFromRecents(context)

        DockSessionTracker.startSession(
            dockPkg = entry.packageName,
            previousPkg = prevTarget,
            fromHome = isFromHome,
            inPopupMode = wantPopup
        )

        val comp = launchIntent.component
        if (wantPopup && comp != null) {
            val compStr = "${comp.packageName}/${comp.className}"
            val flagInt = if (hideRecents) FLAGS_NEW_EXCLUDE_RECENTS else Intent.FLAG_ACTIVITY_NEW_TASK
            val cmd = "am start --windowingMode 5 -f $flagInt -n '$compStr'"
            ShizukuBridge.execAsync(context, cmd) { output ->
                if (output == null || output.contains("Error", ignoreCase = true) || output.startsWith("ERR:")) {
                    // Graceful fallback to native launch if One UI rejects freeform flag
                    launchNativeIntent(context, launchIntent, hideRecents)
                }
            }
        } else {
            launchNativeIntent(context, launchIntent, hideRecents)
        }
    }

    private fun launchNativeIntent(context: Context, baseIntent: Intent, hideRecents: Boolean) {
        try {
            val intent = Intent(baseIntent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (hideRecents) {
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(context, "Không thể mở ứng dụng này", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * One-tap dismiss from the dim Edge Handle badge:
     * - Restores the previous app (e.g. YouTube) right where you left off!
     * - If keepAlive == true: keeps the dock app warm in RAM (instant re-open) & hidden from Recents.
     * - If keepAlive == false: force-stops the dock app via Shizuku after returning cleanly.
     */
    fun dismissActiveDockApp(context: Context) {
        val dockPkg = DockSessionTracker.activeDockPackage
        val returnPkg = DockSessionTracker.returnTargetPackage
        val wasHome = DockSessionTracker.returnWasHome
        val wasPopup = DockSessionTracker.openedInPopupMode

        DockSessionTracker.clearSession()

        val entry = dockPkg?.let { DockPrefs.getAppEntry(context, it) }
        val keepAlive = entry?.keepAlive ?: true
        val shizukuReady = ShizukuBridge.getStatus(context) == ShizukuBridge.Status.READY

        // 1. Restore previous app (e.g. YouTube) immediately for zero visual flash
        var restored = false
        if (!wasHome && !returnPkg.isNullOrBlank()) {
            restored = bringPackageToFront(context, returnPkg)
        }
        if (!restored) {
            val acc = EdgeDockAccessibilityService.instance
            if (wasHome) {
                if (acc == null || !acc.goHome()) {
                    goHomeViaIntent(context)
                }
            } else if (acc != null) {
                if (!acc.goBack()) acc.goHome()
            } else {
                goHomeViaIntent(context)
            }
        }

        // 2. Apply exact Keep-Alive ON/OFF lifecycle policy for the dismissed Dock app
        if (!dockPkg.isNullOrBlank() && shizukuReady) {
            if (!keepAlive) {
                // Turn OFF keep-alive: clean force-stop 180ms after YouTube is back on top
                mainHandler.postDelayed({
                    ShizukuBridge.execAsync(context, "am force-stop '$dockPkg'")
                }, 180L)
            } else if (wasPopup) {
                // In Pop-up mode with keepAlive ON, move the floating task behind returnPkg cleanly
                if (!returnPkg.isNullOrBlank()) {
                    val retComp = context.packageManager.getLaunchIntentForPackage(returnPkg)?.component
                    if (retComp != null) {
                        val c = "${retComp.packageName}/${retComp.className}"
                        mainHandler.postDelayed({
                            ShizukuBridge.execAsync(context, "am start --windowingMode 1 -f 0x10020000 -n '$c'")
                        }, 60L)
                    }
                }
            }
        }
    }

    private fun bringPackageToFront(context: Context, pkg: String): Boolean {
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            context.startActivity(launch)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun goHomeViaIntent(context: Context) {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(home)
        } catch (_: Throwable) {
        }
    }
}

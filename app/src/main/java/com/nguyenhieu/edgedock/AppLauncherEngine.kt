package com.nguyenhieu.edgedock

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object AppLauncherEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    // FLAG_ACTIVITY_NEW_TASK (0x10000000) | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS (0x00800000)
    private const val FLAGS_NEW_EXCLUDE_RECENTS = 0x10800000

    fun launchDockApp(context: Context, entry: DockAppEntry) {
        val launchIntent = DockAppCache.getLaunchIntent(context, entry.packageName)
        if (launchIntent == null) {
            Toast.makeText(context, "Không tìm thấy ${entry.label}", Toast.LENGTH_SHORT).show()
            return
        }

        val currentFg = DockSessionTracker.currentForegroundPackage
        val homePkgs = DockAppCache.getHomePackages(context)
        val isFromHome = currentFg.isNullOrBlank() || currentFg in homePkgs
        val prevTarget = if (!isFromHome && currentFg != entry.packageName && currentFg != context.packageName) {
            currentFg
        } else null

        // Pre-warm the return app (e.g. YouTube) LaunchIntent in RAM so 1-tap close has 0ms IPC delay
        if (!prevTarget.isNullOrBlank()) {
            DockAppCache.warmLaunchIntentAsync(context, prevTarget)
        }

        val wantPopup = DockPrefs.isPopupMode(context) && ShizukuBridge.isReadyCached
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

    fun dismissActiveDockApp(context: Context) {
        val dockPkg = DockSessionTracker.activeDockPackage
        val returnPkg = DockSessionTracker.returnTargetPackage
        val wasHome = DockSessionTracker.returnWasHome
        val wasPopup = DockSessionTracker.openedInPopupMode

        DockSessionTracker.clearSession()

        val entry = dockPkg?.let { p -> DockAppCache.getPinnedApps(context).firstOrNull { it.packageName == p } }
        val keepAlive = entry?.keepAlive ?: true
        val shizukuReady = ShizukuBridge.isReadyCached

        // 1. Instant 0ms restore of previous app (e.g. YouTube) from pre-warmed LaunchIntent cache
        var restored = false
        if (!wasHome && !returnPkg.isNullOrBlank()) {
            restored = bringPackageToFront(context, returnPkg)
        }
        if (!restored) {
            val acc = EdgeDockAccessibilityService.instance
            if (wasHome) {
                if (acc == null || !acc.goHome()) goHomeViaIntent(context)
            } else if (acc != null) {
                if (!acc.goBack()) acc.goHome()
            } else {
                goHomeViaIntent(context)
            }
        }

        // 2. Apply Keep-Alive policy off the UI thread
        if (!dockPkg.isNullOrBlank() && shizukuReady) {
            if (!keepAlive) {
                mainHandler.postDelayed({
                    ShizukuBridge.execAsync(context, "am force-stop '$dockPkg'")
                }, 150L)
            } else if (wasPopup && !returnPkg.isNullOrBlank()) {
                val retComp = DockAppCache.getLaunchIntent(context, returnPkg)?.component
                if (retComp != null) {
                    val c = "${retComp.packageName}/${retComp.className}"
                    mainHandler.postDelayed({
                        ShizukuBridge.execAsync(context, "am start --windowingMode 1 -f 0x10020000 -n '$c'")
                    }, 50L)
                }
            }
        }
    }

    private fun bringPackageToFront(context: Context, pkg: String): Boolean {
        return try {
            val launch = DockAppCache.getLaunchIntent(context, pkg) ?: return false
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

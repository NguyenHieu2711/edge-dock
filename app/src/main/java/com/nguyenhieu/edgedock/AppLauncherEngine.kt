package com.nguyenhieu.edgedock

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object AppLauncherEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Captures the exact foreground app (e.g. YouTube) immediately on Edge touch,
     * using non-blocking Accessibility + lightweight Shizuku task check.
     */
    fun captureUnderlyingAppSnapshot(context: Context) {
        if (DockSessionTracker.isSessionActive()) return

        val homePkgs = DockAppCache.getHomePackages(context)
        val accPkg = EdgeDockAccessibilityService.instance?.detectTopAppPackageNow()
            ?: DockSessionTracker.currentForegroundPackage
        if (!accPkg.isNullOrBlank() && accPkg != context.packageName) {
            val isHome = accPkg in homePkgs
            DockSessionTracker.savePreOpenSnapshot(accPkg, -1, isHome)
            if (!isHome) DockAppCache.warmLaunchIntentAsync(context, accPkg)
        }

        if (ShizukuBridge.isReadyCached) {
            val ignoreCsv = "${context.packageName},com.android.systemui,android"
            ShizukuBridge.getTopTaskAsync(context, ignoreCsv) { tid, pkg ->
                if (pkg.isNotBlank() && pkg != context.packageName) {
                    val isHome = pkg in homePkgs
                    DockSessionTracker.savePreOpenSnapshot(pkg, tid, isHome)
                    if (!isHome) DockAppCache.warmLaunchIntentAsync(context, pkg)
                }
            }
        }
    }

    fun launchDockApp(context: Context, entry: DockAppEntry) {
        val launchIntent = DockAppCache.getLaunchIntent(context, entry.packageName)
        if (launchIntent == null) {
            Toast.makeText(context, "Không tìm thấy ${entry.label}", Toast.LENGTH_SHORT).show()
            return
        }

        val homePkgs = DockAppCache.getHomePackages(context)
        val snapPkg = DockSessionTracker.snapshotUnderlyingPackage
            ?: EdgeDockAccessibilityService.instance?.detectTopAppPackageNow()
            ?: DockSessionTracker.currentForegroundPackage
        val snapTid = DockSessionTracker.snapshotUnderlyingTaskId

        val isFromHome = !snapPkg.isNullOrBlank() && snapPkg in homePkgs
        val prevTarget = if (!snapPkg.isNullOrBlank() && !isFromHome && snapPkg != entry.packageName && snapPkg != context.packageName) {
            snapPkg
        } else null

        if (!prevTarget.isNullOrBlank()) {
            DockAppCache.warmLaunchIntentAsync(context, prevTarget)
        }

        val shizukuReady = ShizukuBridge.isReadyCached
        val hideRecents = DockPrefs.isHideFromRecents(context)

        DockSessionTracker.startSession(
            dockPkg = entry.packageName,
            previousPkg = prevTarget,
            previousTaskId = if (prevTarget != null) snapTid else -1,
            fromHome = isFromHome
        )

        // When keepAlive is ON & Shizuku is ready, resume existing taskId in RAM (zero reload)
        if (shizukuReady && entry.keepAlive) {
            val knownTid = DockSessionTracker.getKnownDockTaskId(entry.packageName)
            ShizukuBridge.resumeExistingPackageTaskAsync(
                context = context,
                packageName = entry.packageName,
                knownTaskId = knownTid,
                excludeFromRecents = hideRecents
            ) { resumed, resolvedTid ->
                if (resumed) {
                    if (resolvedTid > 0) DockSessionTracker.rememberDockTaskId(entry.packageName, resolvedTid)
                } else {
                    launchColdAndTrackTask(context, entry.packageName, launchIntent, hideRecents)
                }
            }
            return
        }

        launchColdAndTrackTask(context, entry.packageName, launchIntent, hideRecents)
    }

    private fun launchColdAndTrackTask(
        context: Context,
        pkg: String,
        launchIntent: Intent,
        hideRecents: Boolean
    ) {
        launchNativeIntent(context, launchIntent, hideRecents)

        // Wait 850ms so Android 14 Shell Transition finishes completely BEFORE querying taskId!
        if (ShizukuBridge.isReadyCached) {
            mainHandler.postDelayed({
                ShizukuBridge.captureTaskIdAsync(context, pkg) { tid ->
                    if (tid > 0) DockSessionTracker.rememberDockTaskId(pkg, tid)
                }
            }, 850L)
        }
    }

    private fun launchNativeIntent(context: Context, baseIntent: Intent, hideRecents: Boolean) {
        try {
            val intent = Intent(baseIntent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
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
     * Safe, deadlock-free return to main app (e.g. YouTube):
     * - Always uses clean standard Intent / Home / Back FIRST (0ms, zero Shell Transition lock even mid-load!)
     * - If keepAlive == false: waits 550ms until transition finishes before executing force-stop.
     */
    fun dismissActiveDockApp(context: Context) {
        val dockPkg = DockSessionTracker.activeDockPackage
        val returnPkg = DockSessionTracker.returnTargetPackage
        val returnTid = DockSessionTracker.returnTargetTaskId
        val wasHome = DockSessionTracker.returnWasHome

        DockSessionTracker.clearSession()

        val entry = dockPkg?.let { p -> DockAppCache.getPinnedApps(context).firstOrNull { it.packageName == p } }
        val keepAlive = entry?.keepAlive ?: true
        val shizukuReady = ShizukuBridge.isReadyCached

        // 1. Bring YouTube back via standard Android Intent first (immune to Shell Transition deadlock!)
        var restoredViaIntent = false
        if (wasHome) {
            val acc = EdgeDockAccessibilityService.instance
            if (acc == null || !acc.goHome()) goHomeViaIntent(context)
            restoredViaIntent = true
        } else if (!returnPkg.isNullOrBlank()) {
            restoredViaIntent = bringPackageToFront(context, returnPkg)
        }

        // 2. Fallback via Shizuku or Accessibility Home if standard Intent failed
        if (!restoredViaIntent) {
            if (shizukuReady && (returnTid > 0 || !returnPkg.isNullOrBlank())) {
                ShizukuBridge.moveTaskOrPackageToFrontAsync(context, returnTid, returnPkg) { moved ->
                    if (!moved) goHomeOrBackFallback(context)
                }
            } else {
                goHomeOrBackFallback(context)
            }
        }

        // 3. Only if Keep-Alive is OFF, wait 550ms for window transition to settle then force-stop cleanly
        if (!dockPkg.isNullOrBlank() && shizukuReady && !keepAlive) {
            DockSessionTracker.forgetDockTaskId(dockPkg)
            mainHandler.postDelayed({
                ShizukuBridge.execAsync(context, "am force-stop '$dockPkg'")
            }, 550L)
        }
    }

    private fun goHomeOrBackFallback(context: Context) {
        val acc = EdgeDockAccessibilityService.instance
        if (acc != null) {
            if (!acc.goHome()) goHomeViaIntent(context)
        } else {
            goHomeViaIntent(context)
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

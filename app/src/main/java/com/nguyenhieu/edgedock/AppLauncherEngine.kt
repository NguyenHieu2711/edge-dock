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

    /**
     * Captures the exact foreground app (e.g. YouTube) + its integer taskId right when the user
     * starts swiping the Edge Handle, BEFORE the Dock Panel opens on top of it!
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
        val wantPopup = DockPrefs.isPopupMode(context) && shizukuReady
        val hideRecents = DockPrefs.isHideFromRecents(context)

        DockSessionTracker.startSession(
            dockPkg = entry.packageName,
            previousPkg = prevTarget,
            previousTaskId = if (prevTarget != null) snapTid else -1,
            fromHome = isFromHome,
            inPopupMode = wantPopup
        )

        // FAST-PATH (0-RELOAD RESUME!): If Shizuku is ready and app is kept alive in RAM,
        // resume its exact existing taskId via IActivityTaskManager.moveTaskToFront instead of cold startActivity!
        if (shizukuReady && entry.keepAlive && !wantPopup) {
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
                    // Cold launch first time (not in RAM yet), then capture its taskId for instant future resumes!
                    launchColdAndTrackTask(context, entry.packageName, launchIntent, hideRecents, wantPopup = false)
                }
            }
            return
        }

        launchColdAndTrackTask(context, entry.packageName, launchIntent, hideRecents, wantPopup)
    }

    private fun launchColdAndTrackTask(
        context: Context,
        pkg: String,
        launchIntent: Intent,
        hideRecents: Boolean,
        wantPopup: Boolean
    ) {
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

        if (ShizukuBridge.isReadyCached) {
            mainHandler.postDelayed({
                ShizukuBridge.captureAndHideTaskAsync(context, pkg, hideRecents) { tid ->
                    if (tid > 0) DockSessionTracker.rememberDockTaskId(pkg, tid)
                }
            }, 320L)
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
     * One-tap or quick-swipe dismiss on the dim Edge Handle:
     * - Restores YouTube's EXACT taskId (NEVER closes YouTube or throws to Home!)
     * - Keeps the Dock app alive & hidden in RAM if keepAlive == true (0ms zero-reload next open!)
     * - Force-stops the Dock app via Shizuku if keepAlive == false.
     */
    fun dismissActiveDockApp(context: Context) {
        val dockPkg = DockSessionTracker.activeDockPackage
        val returnPkg = DockSessionTracker.returnTargetPackage
        val returnTid = DockSessionTracker.returnTargetTaskId
        val wasHome = DockSessionTracker.returnWasHome

        DockSessionTracker.clearSession()

        val entry = dockPkg?.let { p -> DockAppCache.getPinnedApps(context).firstOrNull { it.packageName == p } }
        val keepAlive = entry?.keepAlive ?: true
        val hideRecents = DockPrefs.isHideFromRecents(context)
        val shizukuReady = ShizukuBridge.isReadyCached

        val afterRestore: () -> Unit = {
            if (!dockPkg.isNullOrBlank() && shizukuReady) {
                if (!keepAlive) {
                    DockSessionTracker.forgetDockTaskId(dockPkg)
                    mainHandler.postDelayed({
                        ShizukuBridge.execAsync(context, "am force-stop '$dockPkg'")
                    }, 180L)
                } else if (hideRecents) {
                    val dockTid = DockSessionTracker.getKnownDockTaskId(dockPkg)
                    mainHandler.postDelayed({
                        ShizukuBridge.captureAndHideTaskAsync(context, dockPkg, true) { tid ->
                            if (tid > 0) DockSessionTracker.rememberDockTaskId(dockPkg, tid)
                            else if (dockTid > 0) {
                                // Keep existing known taskId
                                DockSessionTracker.rememberDockTaskId(dockPkg, dockTid)
                            }
                        }
                    }, 120L)
                }
            }
        }

        // Case 1: Genuine Home Screen origin -> return cleanly to Home
        if (wasHome) {
            val acc = EdgeDockAccessibilityService.instance
            if (acc == null || !acc.goHome()) goHomeViaIntent(context)
            afterRestore()
            return
        }

        // Case 2: Shizuku ready & we have YouTube's exact taskId or packageName -> moveTaskToFront (0 reload, NEVER closes YouTube!)
        if (shizukuReady && (returnTid > 0 || !returnPkg.isNullOrBlank())) {
            ShizukuBridge.moveTaskOrPackageToFrontAsync(context, returnTid, returnPkg) { moved ->
                if (!moved) {
                    restoreViaIntentOrBack(context, returnPkg)
                }
                afterRestore()
            }
            return
        }

        // Case 3: Fallback without Shizuku -> restore YouTube via Intent or Back (NEVER blindly call goHome!)
        restoreViaIntentOrBack(context, returnPkg)
        afterRestore()
    }

    private fun restoreViaIntentOrBack(context: Context, returnPkg: String?) {
        if (!returnPkg.isNullOrBlank() && bringPackageToFront(context, returnPkg)) {
            return
        }
        val acc = EdgeDockAccessibilityService.instance
        if (acc != null) {
            acc.goBack()
        } else {
            Toast.makeText(context, "Bật quyền Accessibility hoặc Shizuku để quay về app trước", Toast.LENGTH_SHORT).show()
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

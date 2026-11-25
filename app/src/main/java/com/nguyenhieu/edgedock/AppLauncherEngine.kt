package com.nguyenhieu.edgedock

import android.content.Context
import android.content.Intent
import android.widget.Toast

object AppLauncherEngine {

    /**
     * Captures the exact foreground app (e.g. YouTube) immediately on Edge touch
     * using non-blocking Accessibility window inspection (zero Shizuku dependency).
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

        val isFromHome = !snapPkg.isNullOrBlank() && snapPkg in homePkgs
        val prevTarget = if (!snapPkg.isNullOrBlank() && !isFromHome && snapPkg != entry.packageName && snapPkg != context.packageName) {
            snapPkg
        } else null

        if (!prevTarget.isNullOrBlank()) {
            DockAppCache.warmLaunchIntentAsync(context, prevTarget)
        }

        DockSessionTracker.startSession(
            dockPkg = entry.packageName,
            previousPkg = prevTarget,
            previousTaskId = -1,
            fromHome = isFromHome
        )

        launchNativeIntent(context, launchIntent)
    }

    private fun launchNativeIntent(context: Context, baseIntent: Intent) {
        try {
            val intent = Intent(baseIntent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(context, "Không thể mở ứng dụng này", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Fast, zero-Shizuku return to main app (e.g. YouTube) or Home screen.
     */
    fun dismissActiveDockApp(context: Context) {
        val returnPkg = DockSessionTracker.returnTargetPackage
        val wasHome = DockSessionTracker.returnWasHome

        DockSessionTracker.clearSession()

        var restored = false
        if (wasHome) {
            val acc = EdgeDockAccessibilityService.instance
            if (acc == null || !acc.goHome()) goHomeViaIntent(context)
            restored = true
        } else if (!returnPkg.isNullOrBlank()) {
            restored = bringPackageToFront(context, returnPkg)
        }

        if (!restored) {
            goHomeOrBackFallback(context)
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
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

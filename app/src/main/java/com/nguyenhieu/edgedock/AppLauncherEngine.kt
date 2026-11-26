package com.nguyenhieu.edgedock

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

object AppLauncherEngine {

    const val OBSIDIAN_QUICK_PKG = "com.nguyenhieu.quicknotes"
    private const val OBSIDIAN_WIDGET_ACTION_CLS = "com.nguyenhieu.quicknotes.WidgetActionActivity"

    fun isObsidianQuickInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(OBSIDIAN_QUICK_PKG, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Pops up Obsidian Quick's translucent floating Quick Add Note sheet directly over
     * any running app (no app switch, no Recents entry, auto-returns on save/outside tap).
     */
    fun openObsidianQuickCapture(context: Context): Boolean {
        if (!isObsidianQuickInstalled(context)) {
            Toast.makeText(context, "Chưa cài Obsidian Quick", Toast.LENGTH_SHORT).show()
            return false
        }
        val intent = Intent().apply {
            component = ComponentName(OBSIDIAN_QUICK_PKG, OBSIDIAN_WIDGET_ACTION_CLS)
            putExtra("extra_widget_id", -1)
            putExtra("extra_widget_kind", "notes")
            putExtra("extra_mode", "add_note")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        val ok = startIntentSafely(context, intent)
        if (!ok) {
            Toast.makeText(context, "Không thể mở Ghi nhanh", Toast.LENGTH_SHORT).show()
        }
        return ok
    }

    /**
     * Instant one-thumb Alt-Tab to the previously used app.
     */
    fun switchToPreviousApp(context: Context): Boolean {
        val acc = EdgeDockAccessibilityService.instance
        val liveTop = acc?.detectTopAppPackageNow() ?: DockSessionTracker.currentForegroundPackage
        val isHome = liveTop != null && (liveTop in DockAppCache.getHomePackages(context))
        val targetPkg = DockSessionTracker.getPreviousAppSwitchTarget(liveTop, isHome)
        if (!targetPkg.isNullOrBlank() && targetPkg != context.packageName) {
            if (bringPackageToFront(context, targetPkg)) {
                return true
            }
        }
        if (acc != null && acc.openRecents()) {
            return true
        }
        Toast.makeText(context, "Chưa có app trước đó (bật Accessibility)", Toast.LENGTH_SHORT).show()
        return false
    }

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
        val intent = Intent(baseIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        if (!startIntentSafely(context, intent)) {
            Toast.makeText(context, "Không thể mở ứng dụng này", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startIntentSafely(context: Context, intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val opts = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }.toBundle()
                val reqCode = (intent.component?.flattenToShortString() ?: intent.`package` ?: "dock").hashCode()
                val pi = PendingIntent.getActivity(
                    context,
                    reqCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    opts
                )
                pi.send(context, 0, null, null, null, null, opts)
                return true
            } catch (_: Throwable) {
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
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
        val launch = DockAppCache.getLaunchIntent(context, pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return startIntentSafely(context, launch)
    }

    private fun goHomeViaIntent(context: Context) {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startIntentSafely(context, home)
    }
}

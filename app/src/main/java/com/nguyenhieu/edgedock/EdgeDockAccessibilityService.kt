package com.nguyenhieu.edgedock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

class EdgeDockAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: EdgeDockAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                enabled.contains("${context.packageName}/${EdgeDockAccessibilityService::class.java.name}") ||
                    enabled.contains("${context.packageName}/.EdgeDockAccessibilityService")
            } catch (_: Throwable) {
                false
            }
        }
    }

    private var cachedLauncherPkgs: Set<String> = emptySet()
    private var cachedImePkgs: Set<String> = emptySet()
    private var lastCacheAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshSystemPackageCaches()
        detectTopAppPackageNow()?.let { pkg ->
            DockSessionTracker.currentForegroundPackage = pkg
            DockAppCache.warmLaunchIntentAsync(this, pkg)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        if (System.currentTimeMillis() - lastCacheAt > 60_000L) {
            refreshSystemPackageCaches()
        }

        val eventPkg = event.packageName?.toString()?.trim().orEmpty()
        val pkg = if (eventPkg.isNotEmpty() && !shouldIgnoreTransientPackage(eventPkg)) {
            eventPkg
        } else {
            detectTopAppPackageNow().orEmpty()
        }
        if (pkg.isEmpty() || shouldIgnoreTransientPackage(pkg)) return

        val isLauncher = pkg in cachedLauncherPkgs
        if (!isLauncher) {
            DockAppCache.warmLaunchIntentAsync(this, pkg)
        }
        DockSessionTracker.onForegroundPackageChanged(pkg, isLauncher)
    }

    /**
     * Live inspection of the current interactive window stack.
     * Accurately identifies YouTube or any open app even if it was opened hours before Edge Dock started!
     */
    fun detectTopAppPackageNow(): String? {
        return try {
            val winList = windows ?: return null
            val appWindows = winList
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.layer }
            for (w in appWindows) {
                val root = w.root
                val p = root?.packageName?.toString()?.trim().orEmpty()
                if (p.isNotEmpty() && !shouldIgnoreTransientPackage(p)) {
                    return p
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun refreshSystemPackageCaches() {
        lastCacheAt = System.currentTimeMillis()
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            cachedLauncherPkgs = resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Throwable) {
        }
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            cachedImePkgs = imm?.enabledInputMethodList?.mapNotNull { it.packageName }?.toSet().orEmpty()
        } catch (_: Throwable) {
        }
    }

    private fun shouldIgnoreTransientPackage(pkg: String): Boolean {
        if (pkg == packageName) return true
        if (pkg == "com.android.systemui") return true
        if (pkg == "android") return true
        if (pkg in cachedImePkgs) return true
        if (pkg.contains("permissioncontroller", ignoreCase = true)) return true
        if (pkg.contains("inputmethod", ignoreCase = true)) return true
        if (pkg.contains("honeyboard", ignoreCase = true)) return true
        if (pkg.contains("labankey", ignoreCase = true)) return true
        if (pkg.contains("gboard", ignoreCase = true)) return true
        return false
    }

    fun isHomePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (cachedLauncherPkgs.isEmpty()) refreshSystemPackageCaches()
        return pkg in cachedLauncherPkgs
    }

    fun goHome(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (_: Throwable) {
            false
        }
    }

    fun goBack(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Throwable) {
            false
        }
    }
}

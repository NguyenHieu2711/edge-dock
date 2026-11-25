package com.nguyenhieu.edgedock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
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
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty()) return

        if (System.currentTimeMillis() - lastCacheAt > 60_000L) {
            refreshSystemPackageCaches()
        }

        if (shouldIgnoreTransientPackage(pkg)) return
        val isLauncher = pkg in cachedLauncherPkgs
        DockSessionTracker.onForegroundPackageChanged(pkg, isLauncher)
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

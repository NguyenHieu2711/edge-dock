package com.nguyenhieu.edgedock

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

object DockSessionTracker {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    var currentForegroundPackage: String? = null

    @Volatile
    var activeDockPackage: String? = null

    @Volatile
    var returnTargetPackage: String? = null

    @Volatile
    var returnWasHome: Boolean = false

    @Volatile
    var openedInPopupMode: Boolean = false

    @Volatile
    var sessionStartedAt: Long = 0L

    fun isSessionActive(): Boolean = !activeDockPackage.isNullOrBlank()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyStateChanged() {
        mainHandler.post {
            for (l in listeners) {
                try {
                    l()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun startSession(
        dockPkg: String,
        previousPkg: String?,
        fromHome: Boolean,
        inPopupMode: Boolean
    ) {
        // Keep the original underlying app (e.g. YouTube) if switching directly between 2 Dock apps
        if (!isSessionActive()) {
            returnTargetPackage = previousPkg
            returnWasHome = fromHome
        }
        activeDockPackage = dockPkg
        openedInPopupMode = inPopupMode
        sessionStartedAt = System.currentTimeMillis()
        notifyStateChanged()
    }

    fun clearSession() {
        val hadSession = isSessionActive()
        activeDockPackage = null
        returnTargetPackage = null
        returnWasHome = false
        openedInPopupMode = false
        sessionStartedAt = 0L
        if (hadSession) notifyStateChanged()
    }

    fun onForegroundPackageChanged(pkg: String, isLauncher: Boolean) {
        currentForegroundPackage = pkg
        val active = activeDockPackage ?: return
        // Give transition 1.4s grace window so launch animations don't prematurely reset the dim pill
        if (System.currentTimeMillis() - sessionStartedAt < 1400L) return
        if (pkg != active) {
            // In popup mode the underlying app (e.g. YouTube) can still emit window events, so keep dim pill visible
            if (openedInPopupMode && pkg == returnTargetPackage) return
            // User manually left the dock app (went Home or switched to another app)
            if (isLauncher || pkg != active) {
                clearSession()
            }
        }
    }
}

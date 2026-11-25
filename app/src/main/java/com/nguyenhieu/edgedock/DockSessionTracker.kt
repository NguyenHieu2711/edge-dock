package com.nguyenhieu.edgedock

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object DockSessionTracker {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val knownDockTaskIds = ConcurrentHashMap<String, Int>()

    @Volatile
    var currentForegroundPackage: String? = null

    @Volatile
    var snapshotUnderlyingPackage: String? = null

    @Volatile
    var snapshotUnderlyingTaskId: Int = -1

    @Volatile
    var snapshotWasHome: Boolean = false

    @Volatile
    var activeDockPackage: String? = null

    @Volatile
    var returnTargetPackage: String? = null

    @Volatile
    var returnTargetTaskId: Int = -1

    @Volatile
    var returnWasHome: Boolean = false

    @Volatile
    var openedInPopupMode: Boolean = false

    @Volatile
    var sessionStartedAt: Long = 0L

    fun isSessionActive(): Boolean = !activeDockPackage.isNullOrBlank()

    fun rememberDockTaskId(pkg: String, taskId: Int) {
        if (pkg.isNotBlank() && taskId > 0) knownDockTaskIds[pkg] = taskId
    }

    fun getKnownDockTaskId(pkg: String): Int = knownDockTaskIds[pkg] ?: -1

    fun forgetDockTaskId(pkg: String) {
        knownDockTaskIds.remove(pkg)
    }

    fun savePreOpenSnapshot(pkg: String?, taskId: Int, isHome: Boolean) {
        // Never overwrite YouTube snapshot while already inside a Dock session
        if (isSessionActive()) return
        if (!pkg.isNullOrBlank()) {
            snapshotUnderlyingPackage = pkg
            snapshotWasHome = isHome
        }
        if (taskId > 0) {
            snapshotUnderlyingTaskId = taskId
        }
    }

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
        previousTaskId: Int,
        fromHome: Boolean,
        inPopupMode: Boolean
    ) {
        if (!isSessionActive()) {
            returnTargetPackage = previousPkg
            returnTargetTaskId = previousTaskId
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
        returnTargetTaskId = -1
        returnWasHome = false
        openedInPopupMode = false
        sessionStartedAt = 0L
        snapshotUnderlyingPackage = null
        snapshotUnderlyingTaskId = -1
        snapshotWasHome = false
        if (hadSession) notifyStateChanged()
    }

    fun onForegroundPackageChanged(pkg: String, isLauncher: Boolean) {
        currentForegroundPackage = pkg
        val active = activeDockPackage ?: return
        if (System.currentTimeMillis() - sessionStartedAt < 1600L) return
        if (pkg != active) {
            if (openedInPopupMode && pkg == returnTargetPackage) return
            clearSession()
        }
    }
}

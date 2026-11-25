package com.nguyenhieu.edgedock

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@SuppressLint("PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi")
class ShizukuShellService : IShizukuShellService.Stub() {

    @Volatile
    private var cachedAtm: Any? = null

    @Volatile
    private var cachedAm: Any? = null

    private fun callerPackage(): String = if (Process.myUid() == 0) "android" else "com.android.shell"

    private fun getActivityTaskManager(): Any? {
        cachedAtm?.let { return it }
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getSvc = smClass.getDeclaredMethod("getService", String::class.java)
            val binder = getSvc.invoke(null, "activity_task") as? IBinder ?: return null
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val asIface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            val atm = asIface.invoke(null, binder)
            cachedAtm = atm
            atm
        } catch (_: Throwable) {
            try {
                val atmClass = Class.forName("android.app.ActivityTaskManager")
                val getSvc = atmClass.getDeclaredMethod("getService")
                val atm = getSvc.invoke(null)
                cachedAtm = atm
                atm
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun getActivityManager(): Any? {
        cachedAm?.let { return it }
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getSvc = smClass.getDeclaredMethod("getService", String::class.java)
            val binder = getSvc.invoke(null, "activity") as? IBinder ?: return null
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            val asIface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            val am = asIface.invoke(null, binder)
            cachedAm = am
            am
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryRunningTasks(): List<ActivityManager.RunningTaskInfo> {
        val atm = getActivityTaskManager()
        if (atm != null) {
            try {
                val methods = atm.javaClass.methods.filter { it.name == "getTasks" }
                // Try 4-arg Android 13/14/15: getTasks(maxNum, filterOnlyVisibleRecents=false, keepIntentExtra=false, displayId=-1)
                methods.firstOrNull { it.parameterTypes.size == 4 }?.let { m ->
                    val res = m.invoke(atm, 40, false, false, -1) as? List<ActivityManager.RunningTaskInfo>
                    if (res != null) return res
                }
                // Try 3-arg Android 12: getTasks(maxNum, filterOnlyVisibleRecents=false, keepIntentExtra=false)
                methods.firstOrNull { it.parameterTypes.size == 3 }?.let { m ->
                    val res = m.invoke(atm, 40, false, false) as? List<ActivityManager.RunningTaskInfo>
                    if (res != null) return res
                }
                // Try 2-arg
                methods.firstOrNull { it.parameterTypes.size == 2 }?.let { m ->
                    val res = m.invoke(atm, 40, false) as? List<ActivityManager.RunningTaskInfo>
                    if (res != null) return res
                }
                // Try 1-arg
                methods.firstOrNull { it.parameterTypes.size == 1 }?.let { m ->
                    val res = m.invoke(atm, 40) as? List<ActivityManager.RunningTaskInfo>
                    if (res != null) return res
                }
            } catch (_: Throwable) {
            }
        }

        val am = getActivityManager()
        if (am != null) {
            try {
                val m = am.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterTypes.size == 1 }
                val res = m?.invoke(am, 40) as? List<ActivityManager.RunningTaskInfo>
                if (res != null) return res
            } catch (_: Throwable) {
            }
        }
        return emptyList()
    }

    private fun extractPkg(info: ActivityManager.RunningTaskInfo): String {
        return info.topActivity?.packageName
            ?: info.baseActivity?.packageName
            ?: ""
    }

    override fun getTopTaskInfo(ignorePackagesCsv: String?): String {
        val ignore = ignorePackagesCsv.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val tasks = queryRunningTasks()
        for (t in tasks) {
            val pkg = extractPkg(t)
            if (pkg.isEmpty() || pkg in ignore) continue
            return "${t.taskId}|$pkg"
        }
        return ""
    }

    override fun findTaskIdForPackage(packageName: String?): Int {
        val target = packageName?.trim().orEmpty()
        if (target.isEmpty()) return -1
        val tasks = queryRunningTasks()
        for (t in tasks) {
            val topPkg = t.topActivity?.packageName.orEmpty()
            val basePkg = t.baseActivity?.packageName.orEmpty()
            if (topPkg == target || basePkg == target) {
                return t.taskId
            }
        }
        // Fallback: query recent tasks WITH ActivityManager.RECENT_WITH_EXCLUDED (0x0001)
        // so tasks hidden via EXCLUDE_FROM_RECENTS are still found and resumed without reload!
        return findInRecentTasksWithExcluded(target)
    }

    @Suppress("UNCHECKED_CAST")
    private fun findInRecentTasksWithExcluded(targetPkg: String): Int {
        val atm = getActivityTaskManager() ?: return -1
        return try {
            val m = atm.javaClass.methods.firstOrNull {
                it.name == "getRecentTasks" && it.parameterTypes.size == 3
            } ?: return -1
            val slice = m.invoke(atm, 35, 0x0001 or 0x0002, 0) ?: return -1
            val getList = slice.javaClass.methods.firstOrNull { it.name == "getList" } ?: return -1
            val list = getList.invoke(slice) as? List<ActivityManager.RecentTaskInfo> ?: return -1
            for (rt in list) {
                val basePkg = rt.baseIntent.component?.packageName.orEmpty()
                val topPkg = rt.topActivity?.packageName.orEmpty()
                val origPkg = rt.origActivity?.packageName.orEmpty()
                if (basePkg == targetPkg || topPkg == targetPkg || origPkg == targetPkg) {
                    val tid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rt.taskId else {
                        @Suppress("DEPRECATION")
                        rt.persistentId
                    }
                    if (tid > 0) return tid
                }
            }
            -1
        } catch (_: Throwable) {
            -1
        }
    }

    override fun moveTaskToFront(taskId: Int): Boolean {
        if (taskId <= 0) return false
        val caller = callerPackage()

        // 1. Primary: IActivityTaskManager.moveTaskToFront(appThread=null, callingPkg, taskId, flags=0, options=null)
        val atm = getActivityTaskManager()
        if (atm != null) {
            try {
                val m = atm.javaClass.methods.firstOrNull { it.name == "moveTaskToFront" && it.parameterTypes.size == 5 }
                if (m != null) {
                    m.invoke(atm, null, caller, taskId, 0, null)
                    return true
                }
            } catch (_: Throwable) {
            }
            // 2. Fallback: IActivityTaskManager.startActivityFromRecents(taskId, options=null)
            try {
                val m = atm.javaClass.methods.firstOrNull { it.name == "startActivityFromRecents" && it.parameterTypes.size == 2 }
                if (m != null) {
                    val res = m.invoke(atm, taskId, null as Bundle?)
                    if (res is Int && res >= 0) return true
                }
            } catch (_: Throwable) {
            }
        }

        // 3. Fallback: IActivityManager.moveTaskToFront
        val am = getActivityManager()
        if (am != null) {
            try {
                val m = am.javaClass.methods.firstOrNull { it.name == "moveTaskToFront" && it.parameterTypes.size == 5 }
                if (m != null) {
                    m.invoke(am, null, caller, taskId, 0, null)
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    override fun setTaskExcludedFromRecents(taskId: Int, excluded: Boolean): Boolean {
        if (taskId <= 0) return false
        val atm = getActivityTaskManager() ?: return false
        return try {
            val m = atm.javaClass.methods.firstOrNull {
                it.name == "setTaskExcludedFromRecents" && it.parameterTypes.size == 2
            }
            if (m != null) {
                m.invoke(atm, taskId, excluded)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    override fun execCommand(command: String?): String {
        val cmd = command?.trim().orEmpty()
        if (cmd.isEmpty()) return ""
        return try {
            val pb = ProcessBuilder("sh", "-c", cmd).apply {
                redirectErrorStream(true)
            }
            val proc = pb.start()
            val out = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    out.append(line).append('\n')
                    line = reader.readLine()
                }
            }
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) proc.destroyForcibly()
            out.toString().trim()
        } catch (e: Throwable) {
            "ERR:${e.message}"
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}

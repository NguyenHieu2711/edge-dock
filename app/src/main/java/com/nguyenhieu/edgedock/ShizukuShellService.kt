package com.nguyenhieu.edgedock

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass
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

    @Volatile
    private var lastError: String = ""

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (t: Throwable) {
                lastError = "bypass:${t.javaClass.simpleName}"
            }
        }
    }

    private fun callerPackage(): String = if (Process.myUid() == 0) "android" else "com.android.shell"

    private fun getActivityTaskManager(): Any? {
        cachedAtm?.let { return it }
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getSvc = smClass.getDeclaredMethod("getService", String::class.java)
            getSvc.isAccessible = true
            val binder = getSvc.invoke(null, "activity_task") as? IBinder ?: return null
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val asIface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asIface.isAccessible = true
            val atm = asIface.invoke(null, binder)
            cachedAtm = atm
            atm
        } catch (t: Throwable) {
            try {
                val atmClass = Class.forName("android.app.ActivityTaskManager")
                val getSvc = atmClass.getDeclaredMethod("getService")
                getSvc.isAccessible = true
                val atm = getSvc.invoke(null)
                cachedAtm = atm
                atm
            } catch (t2: Throwable) {
                lastError = "atm:${t2.javaClass.simpleName}:${t2.message}"
                null
            }
        }
    }

    private fun getActivityManager(): Any? {
        cachedAm?.let { return it }
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getSvc = smClass.getDeclaredMethod("getService", String::class.java)
            getSvc.isAccessible = true
            val binder = getSvc.invoke(null, "activity") as? IBinder ?: return null
            val stubClass = Class.forName("android.app.IActivityManager\$Stub")
            val asIface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asIface.isAccessible = true
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
            val methods = atm.javaClass.methods.filter { it.name == "getTasks" }
            for (m in methods.sortedByDescending { it.parameterTypes.size }) {
                try {
                    m.isAccessible = true
                    val res = when (m.parameterTypes.size) {
                        4 -> m.invoke(atm, 40, false, false, -1)
                        3 -> m.invoke(atm, 40, false, false)
                        2 -> m.invoke(atm, 40, false)
                        1 -> m.invoke(atm, 40)
                        else -> null
                    } as? List<ActivityManager.RunningTaskInfo>
                    if (res != null) return res
                } catch (t: Throwable) {
                    lastError = "getTasks:${t.cause?.javaClass?.simpleName ?: t.javaClass.simpleName}"
                }
            }
        }

        val am = getActivityManager()
        if (am != null) {
            try {
                val m = am.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterTypes.size == 1 }
                m?.isAccessible = true
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
            ?: info.origActivity?.packageName
            ?: info.baseIntent?.component?.packageName
            ?: info.baseIntent?.`package`
            ?: ""
    }

    private fun matchesPackage(info: ActivityManager.RunningTaskInfo, target: String): Boolean {
        return info.topActivity?.packageName == target ||
            info.baseActivity?.packageName == target ||
            info.origActivity?.packageName == target ||
            info.baseIntent?.component?.packageName == target ||
            info.baseIntent?.`package` == target
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

        // 1. Live running tasks
        val tasks = queryRunningTasks()
        for (t in tasks) {
            if (matchesPackage(t, target)) {
                return t.taskId
            }
        }

        // 2. Recent tasks WITH ActivityManager.RECENT_WITH_EXCLUDED (0x0001)
        val recentTid = findInRecentTasksWithExcluded(target)
        if (recentTid > 0) return recentTid

        // 3. Hard shell fallback via dumpsys activity recents
        return findTaskIdViaDumpsys(target)
    }

    @Suppress("UNCHECKED_CAST")
    private fun findInRecentTasksWithExcluded(targetPkg: String): Int {
        val atm = getActivityTaskManager() ?: return -1
        return try {
            val m = atm.javaClass.methods.firstOrNull {
                it.name == "getRecentTasks" && it.parameterTypes.size == 3
            } ?: return -1
            m.isAccessible = true
            val slice = m.invoke(atm, 40, 0x0001 or 0x0002, 0) ?: return -1
            val getList = slice.javaClass.methods.firstOrNull { it.name == "getList" } ?: return -1
            getList.isAccessible = true
            val list = getList.invoke(slice) as? List<ActivityManager.RecentTaskInfo> ?: return -1
            for (rt in list) {
                val matched = rt.topActivity?.packageName == targetPkg ||
                    rt.baseActivity?.packageName == targetPkg ||
                    rt.origActivity?.packageName == targetPkg ||
                    rt.baseIntent.component?.packageName == targetPkg ||
                    rt.baseIntent.`package` == targetPkg
                if (matched) {
                    val tid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rt.taskId else {
                        @Suppress("DEPRECATION")
                        rt.persistentId
                    }
                    if (tid > 0) return tid
                }
            }
            -1
        } catch (t: Throwable) {
            lastError = "recent:${t.cause?.javaClass?.simpleName ?: t.javaClass.simpleName}"
            -1
        }
    }

    private fun findTaskIdViaDumpsys(targetPkg: String): Int {
        val out = execCommand("dumpsys activity recents")
        if (out.isEmpty()) return -1
        var curId = -1
        var curHasPkg = false
        val idRegex = Regex("""#(\d+)|taskId=(\d+)""")
        for (line in out.lineSequence()) {
            val tr = line.trim()
            if (tr.startsWith("* Recent #") || tr.startsWith("Task{")) {
                if (curHasPkg && curId > 0) return curId
                val m = Regex("""(?:id|taskId)=?(\d+)""").find(tr)
                curId = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
                curHasPkg = tr.contains(targetPkg)
            } else if (tr.contains(targetPkg)) {
                curHasPkg = true
                if (curId <= 0) {
                    idRegex.find(tr)?.let { m ->
                        curId = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: -1
                    }
                }
            }
        }
        return if (curHasPkg && curId > 0) curId else -1
    }

    override fun moveTaskToFront(taskId: Int): Boolean {
        if (taskId <= 0) return false
        val caller = callerPackage()
        val optsBundle = ActivityOptions.makeBasic().toBundle()

        val atm = getActivityTaskManager()
        if (atm != null) {
            // 1. Primary: IActivityTaskManager.startActivityFromRecents (verified return >= 0!)
            try {
                val m = atm.javaClass.methods.firstOrNull {
                    it.name == "startActivityFromRecents" && it.parameterTypes.size == 2
                }
                if (m != null) {
                    m.isAccessible = true
                    val res = m.invoke(atm, taskId, optsBundle)
                    if (res is Int && res >= 0) return true
                }
            } catch (t: Throwable) {
                lastError = "recStart:${t.cause?.javaClass?.simpleName ?: t.javaClass.simpleName}:${t.cause?.message}"
            }

            // 2. Fallback: IActivityTaskManager.moveTaskToFront(IApplicationThread, callerPkg, taskId, flags, options)
            try {
                val m = atm.javaClass.methods.firstOrNull {
                    it.name == "moveTaskToFront" && it.parameterTypes.size == 5
                }
                if (m != null) {
                    m.isAccessible = true
                    m.invoke(atm, null, caller, taskId, 0, optsBundle)
                    return true
                }
            } catch (t: Throwable) {
                lastError = "moveFront:${t.cause?.javaClass?.simpleName ?: t.javaClass.simpleName}:${t.cause?.message}"
            }
        }

        // 3. Fallback: IActivityManager.moveTaskToFront
        val am = getActivityManager()
        if (am != null) {
            try {
                val m = am.javaClass.methods.firstOrNull {
                    it.name == "moveTaskToFront" && it.parameterTypes.size == 5
                }
                if (m != null) {
                    m.isAccessible = true
                    m.invoke(am, null, caller, taskId, 0, optsBundle)
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
            } ?: return false
            m.isAccessible = true
            m.invoke(atm, taskId, excluded)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun setTaskFullscreen(taskId: Int): Boolean {
        if (taskId <= 0) return false
        val atm = getActivityTaskManager() ?: return false
        return try {
            // WINDOWING_MODE_FULLSCREEN = 1
            val m = atm.javaClass.methods.firstOrNull {
                it.name == "setTaskWindowingMode" && it.parameterTypes.size == 3
            } ?: atm.javaClass.methods.firstOrNull {
                it.name == "setTaskWindowingMode" && it.parameterTypes.size == 2
            } ?: return false
            m.isAccessible = true
            if (m.parameterTypes.size == 3) {
                m.invoke(atm, taskId, 1, false)
            } else {
                m.invoke(atm, taskId, 1)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun getDiagnosticInfo(): String {
        val atmOk = getActivityTaskManager() != null
        val tasksCount = queryRunningTasks().size
        return "uid=${Process.myUid()} atm=$atmOk tasks=$tasksCount err=${lastError.ifEmpty { "ok" }}"
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

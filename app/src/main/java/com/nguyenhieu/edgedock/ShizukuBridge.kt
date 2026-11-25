package com.nguyenhieu.edgedock

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

object ShizukuBridge {

    enum class Status {
        NOT_INSTALLED,
        NOT_RUNNING,
        NEED_PERMISSION,
        READY
    }

    const val REQ_CODE_SHIZUKU = 9401
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    private val shellExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusListeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    private var shellService: IShizukuShellService? = null

    @Volatile
    var isReadyCached: Boolean = false
        private set

    @Volatile
    private var bindInProgress = false

    @Volatile
    private var listenersRegistered = false

    private var appContext: Context? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        appContext?.let { ctx ->
            shellExecutor.execute { getStatus(ctx) }
        }
        notifyListeners()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        isReadyCached = false
        bindInProgress = false
        notifyListeners()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_CODE_SHIZUKU) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    isReadyCached = true
                    appContext?.let { maybeBindService(it) }
                }
                notifyListeners()
            }
        }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bindInProgress = false
            shellService = if (service != null && service.pingBinder()) {
                IShizukuShellService.Stub.asInterface(service)
            } else {
                null
            }
            notifyListeners()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bindInProgress = false
            shellService = null
            notifyListeners()
        }
    }

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!listenersRegistered) {
            listenersRegistered = true
            try {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
            } catch (_: Throwable) {
            }
        }
        shellExecutor.execute { getStatus(ctx) }
    }

    fun addStatusListener(listener: () -> Unit) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: () -> Unit) {
        statusListeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post {
            for (l in statusListeners) {
                try {
                    l()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun isShizukuAppInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun getStatus(context: Context): Status {
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!binderAlive) {
            isReadyCached = false
            return if (isShizukuAppInstalled(context)) Status.NOT_RUNNING else Status.NOT_INSTALLED
        }
        return try {
            if (Shizuku.isPreV11()) {
                isReadyCached = false
                Status.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                isReadyCached = true
                maybeBindService(context.applicationContext)
                Status.READY
            } else {
                isReadyCached = false
                Status.NEED_PERMISSION
            }
        } catch (_: Throwable) {
            isReadyCached = false
            Status.NOT_RUNNING
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQ_CODE_SHIZUKU)
            }
        } catch (_: Throwable) {
        }
    }

    private fun buildServiceArgs(context: Context): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(ComponentName(context, ShizukuShellService::class.java))
            .daemon(false)
            .processNameSuffix("dock_shell_v3")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    fun maybeBindService(context: Context) {
        if (shellService != null || bindInProgress) return
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            bindInProgress = true
            Shizuku.bindUserService(buildServiceArgs(context.applicationContext), userServiceConnection)
        } catch (_: Throwable) {
            bindInProgress = false
        }
    }

    private fun awaitService(context: Context): IShizukuShellService? {
        shellService?.let { if (it.asBinder().pingBinder()) return it else shellService = null }
        if (getStatus(context) != Status.READY) return null
        maybeBindService(context)
        var tries = 0
        while (shellService == null && tries < 14) {
            try {
                Thread.sleep(35)
            } catch (_: InterruptedException) {
                break
            }
            tries++
        }
        return shellService
    }

    fun execAsync(context: Context, command: String, onDone: ((String?) -> Unit)? = null) {
        val ctx = context.applicationContext
        shellExecutor.execute {
            val res = try {
                awaitService(ctx)?.execCommand(command)
            } catch (_: Throwable) {
                shellService = null
                null
            }
            if (onDone != null) mainHandler.post { onDone(res) }
        }
    }

    fun getTopTaskAsync(context: Context, ignoreCsv: String, onDone: (Int, String) -> Unit) {
        val ctx = context.applicationContext
        shellExecutor.execute {
            var tid = -1
            var pkg = ""
            try {
                val raw = awaitService(ctx)?.getTopTaskInfo(ignoreCsv).orEmpty()
                val idx = raw.indexOf('|')
                if (idx > 0) {
                    tid = raw.substring(0, idx).toIntOrNull() ?: -1
                    pkg = raw.substring(idx + 1).trim()
                }
            } catch (_: Throwable) {
                shellService = null
            }
            mainHandler.post { onDone(tid, pkg) }
        }
    }

    fun resumeExistingPackageTaskAsync(
        context: Context,
        packageName: String,
        knownTaskId: Int,
        excludeFromRecents: Boolean,
        onResult: (Boolean, Int) -> Unit
    ) {
        val ctx = context.applicationContext
        shellExecutor.execute {
            var ok = false
            var resolvedTid = knownTaskId
            try {
                val svc = awaitService(ctx)
                if (svc != null) {
                    if (resolvedTid <= 0) {
                        resolvedTid = svc.findTaskIdForPackage(packageName)
                    }
                    if (resolvedTid > 0) {
                        ok = svc.moveTaskToFront(resolvedTid)
                        if (!ok) {
                            val freshTid = svc.findTaskIdForPackage(packageName)
                            if (freshTid > 0 && freshTid != resolvedTid) {
                                resolvedTid = freshTid
                                ok = svc.moveTaskToFront(resolvedTid)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                shellService = null
                ok = false
            }
            mainHandler.post { onResult(ok, resolvedTid) }
        }
    }

    fun captureTaskIdAsync(
        context: Context,
        packageName: String,
        onCaptured: (Int) -> Unit
    ) {
        val ctx = context.applicationContext
        shellExecutor.execute {
            var tid = -1
            try {
                val svc = awaitService(ctx)
                if (svc != null) {
                    tid = svc.findTaskIdForPackage(packageName)
                }
            } catch (_: Throwable) {
                shellService = null
            }
            mainHandler.post { onCaptured(tid) }
        }
    }

    fun moveTaskOrPackageToFrontAsync(
        context: Context,
        taskId: Int,
        packageName: String?,
        onDone: (Boolean) -> Unit
    ) {
        val ctx = context.applicationContext
        shellExecutor.execute {
            var ok = false
            try {
                val svc = awaitService(ctx)
                if (svc != null) {
                    if (taskId > 0) {
                        ok = svc.moveTaskToFront(taskId)
                    }
                    if (!ok && !packageName.isNullOrBlank()) {
                        val found = svc.findTaskIdForPackage(packageName)
                        if (found > 0) {
                            ok = svc.moveTaskToFront(found)
                        }
                    }
                }
            } catch (_: Throwable) {
                shellService = null
                ok = false
            }
            mainHandler.post { onDone(ok) }
        }
    }

    fun setTaskFullscreenAsync(context: Context, taskId: Int) {
        if (taskId <= 0) return
        val ctx = context.applicationContext
        shellExecutor.execute {
            try {
                awaitService(ctx)?.setTaskFullscreen(taskId)
            } catch (_: Throwable) {
            }
        }
    }

    fun getDiagnosticInfoAsync(context: Context, onDone: (String) -> Unit) {
        val ctx = context.applicationContext
        shellExecutor.execute {
            val info = try {
                awaitService(ctx)?.getDiagnosticInfo() ?: "service=null"
            } catch (t: Throwable) {
                "err=${t.message}"
            }
            mainHandler.post { onDone(info) }
        }
    }
}

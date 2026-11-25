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
    private var bindInProgress = false

    @Volatile
    private var listenersRegistered = false

    private var appContext: Context? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        appContext?.let { maybeBindService(it) }
        notifyListeners()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        bindInProgress = false
        notifyListeners()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_CODE_SHIZUKU) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
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
        maybeBindService(ctx)
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
            return if (isShizukuAppInstalled(context)) Status.NOT_RUNNING else Status.NOT_INSTALLED
        }
        return try {
            if (Shizuku.isPreV11()) {
                Status.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                maybeBindService(context.applicationContext)
                Status.READY
            } else {
                Status.NEED_PERMISSION
            }
        } catch (_: Throwable) {
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
            .processNameSuffix("dock_shell")
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

    fun execAsync(context: Context, command: String, onDone: ((String?) -> Unit)? = null) {
        val ctx = context.applicationContext
        shellExecutor.execute {
            val result = execBlocking(ctx, command)
            if (onDone != null) {
                mainHandler.post { onDone(result) }
            }
        }
    }

    fun execBlocking(context: Context, command: String): String? {
        if (getStatus(context) != Status.READY) return null
        maybeBindService(context)
        // Wait briefly if UserService is still binding
        var tries = 0
        while (shellService == null && tries < 18) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
            tries++
        }
        val svc = shellService ?: return null
        return try {
            svc.execCommand(command)
        } catch (_: Throwable) {
            shellService = null
            null
        }
    }
}

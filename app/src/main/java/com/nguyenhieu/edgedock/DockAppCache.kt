package com.nguyenhieu.edgedock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object DockAppCache {

    private val bgExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val iconMap = ConcurrentHashMap<String, Drawable>()
    private val launchIntentMap = ConcurrentHashMap<String, Intent>()

    @Volatile
    private var cachedPinnedApps: List<DockAppEntry>? = null

    @Volatile
    private var cachedHomePackages: Set<String>? = null

    @Volatile
    private var cachedInstalledApps: List<Pair<String, String>>? = null

    fun warmUp(context: Context) {
        val ctx = context.applicationContext
        val apps = DockPrefs.loadApps(ctx)
        cachedPinnedApps = apps
        bgExecutor.execute {
            getHomePackages(ctx)
            val pm = ctx.packageManager
            for (entry in apps) {
                if (!iconMap.containsKey(entry.packageName)) {
                    try {
                        iconMap[entry.packageName] = pm.getApplicationIcon(entry.packageName)
                    } catch (_: Throwable) {
                    }
                }
                if (!launchIntentMap.containsKey(entry.packageName)) {
                    try {
                        pm.getLaunchIntentForPackage(entry.packageName)?.let {
                            launchIntentMap[entry.packageName] = it
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    fun warmLaunchIntentAsync(context: Context, pkg: String?) {
        if (pkg.isNullOrBlank() || launchIntentMap.containsKey(pkg)) return
        val ctx = context.applicationContext
        bgExecutor.execute {
            try {
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                    launchIntentMap[pkg] = it
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun getPinnedApps(context: Context): List<DockAppEntry> {
        cachedPinnedApps?.let { return it }
        val loaded = DockPrefs.loadApps(context)
        cachedPinnedApps = loaded
        warmUp(context)
        return loaded
    }

    fun invalidatePinnedApps(context: Context) {
        cachedPinnedApps = DockPrefs.loadApps(context)
        warmUp(context)
    }

    fun getCachedIcon(pkg: String): Drawable? = iconMap[pkg]

    fun loadIconAsync(context: Context, pkg: String, onLoaded: (Drawable?) -> Unit) {
        iconMap[pkg]?.let {
            onLoaded(it)
            return
        }
        val ctx = context.applicationContext
        bgExecutor.execute {
            val d = try {
                ctx.packageManager.getApplicationIcon(pkg)
            } catch (_: Throwable) {
                null
            }
            if (d != null) iconMap[pkg] = d
            mainHandler.post { onLoaded(d) }
        }
    }

    fun getLaunchIntent(context: Context, pkg: String): Intent? {
        launchIntentMap[pkg]?.let { return Intent(it) }
        val resolved = try {
            context.packageManager.getLaunchIntentForPackage(pkg)
        } catch (_: Throwable) {
            null
        }
        if (resolved != null) {
            launchIntentMap[pkg] = resolved
            return Intent(resolved)
        }
        return null
    }

    fun getHomePackages(context: Context): Set<String> {
        cachedHomePackages?.let { return it }
        val resolved = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }
        cachedHomePackages = resolved
        return resolved
    }

    fun loadInstalledAppsAsync(context: Context, forceRefresh: Boolean = false, onReady: (List<Pair<String, String>>) -> Unit) {
        if (!forceRefresh) {
            cachedInstalledApps?.let {
                onReady(it)
                return
            }
        }
        val ctx = context.applicationContext
        bgExecutor.execute {
            val pm = ctx.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = try {
                pm.queryIntentActivities(mainIntent, 0)
                    .filter { it.activityInfo?.packageName != ctx.packageName }
                    .distinctBy { it.activityInfo.packageName }
                    .map {
                        Pair(
                            it.activityInfo.packageName,
                            it.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { it.activityInfo.packageName }
                        )
                    }
                    .sortedBy { it.second.lowercase() }
            } catch (_: Throwable) {
                emptyList()
            }
            cachedInstalledApps = list
            mainHandler.post { onReady(list) }
        }
    }
}

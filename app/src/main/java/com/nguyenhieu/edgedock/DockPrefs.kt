package com.nguyenhieu.edgedock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DockAppEntry(
    val packageName: String,
    val label: String,
    val keepAlive: Boolean = true
)

object DockPrefs {
    private const val PREF_NAME = "edge_dock_prefs"
    private const val KEY_ENABLED = "dock_enabled"
    private const val KEY_SIDE_RIGHT = "handle_side_right"
    private const val KEY_HANDLE_Y_PCT = "handle_y_pct"
    private const val KEY_HANDLE_ALPHA_PCT = "handle_alpha_pct"
    private const val KEY_HANDLE_HEIGHT_DP = "handle_height_dp"
    private const val KEY_HIDE_RECENTS = "hide_from_recents"
    private const val KEY_APPS_JSON = "dock_apps_json"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isDockEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setDockEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun isRightSide(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SIDE_RIGHT, true)
    fun setRightSide(ctx: Context, right: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SIDE_RIGHT, right).apply()

    fun getHandleYPct(ctx: Context): Int = prefs(ctx).getInt(KEY_HANDLE_Y_PCT, 44).coerceIn(12, 86)
    fun setHandleYPct(ctx: Context, pct: Int) =
        prefs(ctx).edit().putInt(KEY_HANDLE_Y_PCT, pct.coerceIn(12, 86)).apply()

    fun getHandleAlphaPct(ctx: Context): Int = prefs(ctx).getInt(KEY_HANDLE_ALPHA_PCT, 42).coerceIn(15, 90)
    fun setHandleAlphaPct(ctx: Context, pct: Int) =
        prefs(ctx).edit().putInt(KEY_HANDLE_ALPHA_PCT, pct.coerceIn(15, 90)).apply()

    fun getHandleHeightDp(ctx: Context): Int = prefs(ctx).getInt(KEY_HANDLE_HEIGHT_DP, 96).coerceIn(56, 160)
    fun setHandleHeightDp(ctx: Context, dp: Int) =
        prefs(ctx).edit().putInt(KEY_HANDLE_HEIGHT_DP, dp.coerceIn(56, 160)).apply()

    fun isHideFromRecents(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HIDE_RECENTS, false)
    fun setHideFromRecents(ctx: Context, hide: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_HIDE_RECENTS, hide).apply()

    fun loadApps(ctx: Context): MutableList<DockAppEntry> {
        val raw = prefs(ctx).getString(KEY_APPS_JSON, "[]") ?: "[]"
        val out = mutableListOf<DockAppEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("pkg", "").trim()
                if (pkg.isEmpty()) continue
                out.add(
                    DockAppEntry(
                        packageName = pkg,
                        label = o.optString("label", pkg).ifBlank { pkg },
                        keepAlive = o.optBoolean("keepAlive", true)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveApps(ctx: Context, apps: List<DockAppEntry>) {
        val arr = JSONArray()
        for (a in apps) {
            arr.put(
                JSONObject().apply {
                    put("pkg", a.packageName)
                    put("label", a.label)
                    put("keepAlive", a.keepAlive)
                }
            )
        }
        prefs(ctx).edit().putString(KEY_APPS_JSON, arr.toString()).apply()
    }

    fun getAppEntry(ctx: Context, pkg: String): DockAppEntry? =
        loadApps(ctx).firstOrNull { it.packageName == pkg }

    fun toggleKeepAlive(ctx: Context, pkg: String): Boolean {
        val list = loadApps(ctx)
        var newState = true
        val updated = list.map {
            if (it.packageName == pkg) {
                newState = !it.keepAlive
                it.copy(keepAlive = newState)
            } else it
        }
        saveApps(ctx, updated)
        return newState
    }
}

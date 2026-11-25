package com.nguyenhieu.edgedock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class EdgeOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "edge_dock_channel"
        private const val NOTIF_ID = 7021
        const val ACTION_REFRESH = "com.nguyenhieu.edgedock.ACTION_REFRESH"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var activeInstance: EdgeOverlayService? = null

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val i = Intent(context, EdgeOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun refresh(context: Context) {
            val inst = activeInstance
            if (inst != null) {
                inst.mainHandler.post { inst.applyHandleLayoutAndStyle() }
                return
            }
            if (!isRunning) return
            try {
                val i = Intent(context, EdgeOverlayService::class.java).apply {
                    action = ACTION_REFRESH
                }
                context.startService(i)
            } catch (_: Throwable) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EdgeOverlayService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fastDecel = DecelerateInterpolator(1.6f)

    private var handleRoot: FrameLayout? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var handleVisual: FrameLayout? = null
    private var handleCloseIcon: ImageView? = null

    private var dockBackdrop: FrameLayout? = null
    private var dockOpen: Boolean = false

    private val sessionListener: () -> Unit = {
        updateHandleAppearance()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        activeInstance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ShizukuBridge.init(this)
        DockAppCache.warmUp(this)
        DockSessionTracker.addListener(sessionListener)
        startForegroundCompat()
        attachHandleOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        DockAppCache.invalidatePinnedApps(this)
        if (handleRoot == null) {
            attachHandleOverlay()
        } else {
            applyHandleLayoutAndStyle()
            if (dockOpen) closeDockPanel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        if (activeInstance === this) activeInstance = null
        DockSessionTracker.removeListener(sessionListener)
        closeDockPanel()
        detachHandleOverlay()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Thanh gạt Edge Dock",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Edge Dock đang bật")
            .setContentText("Gạt từ cạnh màn hình để mở nhanh")
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun screenHeightPx(): Int = resources.displayMetrics.heightPixels

    private fun makeFlatIcon(resId: Int, sizeDp: Float, tintHex: String): ImageView {
        return ImageView(this).apply {
            setImageResource(resId)
            setColorFilter(Color.parseColor(tintHex))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    private fun vibrateLight(ms: Long = 10L) {
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {
        }
    }

    // =========================================================================
    // WINDOW 1: EDGE HANDLE (IDLE SLIM PILL  <-->  ACTIVE DIM CLOSE BADGE)
    // =========================================================================
    private fun attachHandleOverlay() {
        detachHandleOverlay()

        val root = FrameLayout(this)
        val visual = FrameLayout(this)
        val closeIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_flat_close)
            setColorFilter(Color.parseColor("#E2F5FF"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        visual.addView(
            closeIcon,
            FrameLayout.LayoutParams(dp(13f), dp(13f)).apply {
                gravity = Gravity.CENTER
            }
        )
        root.addView(visual)

        val params = WindowManager.LayoutParams(
            dp(26f),
            dp(DockPrefs.getHandleHeightDp(this).toFloat()),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        handleRoot = root
        handleVisual = visual
        handleCloseIcon = closeIcon
        handleParams = params

        setupHandleTouchGestures(root)
        applyHandleLayoutAndStyle()

        try {
            wm.addView(root, params)
        } catch (_: Throwable) {
        }
    }

    private fun detachHandleOverlay() {
        val r = handleRoot ?: return
        try {
            wm.removeView(r)
        } catch (_: Throwable) {
        }
        handleRoot = null
        handleVisual = null
        handleCloseIcon = null
        handleParams = null
    }

    private fun applyHandleLayoutAndStyle() {
        val params = handleParams ?: return
        val rightSide = DockPrefs.isRightSide(this)
        val activeSession = DockSessionTracker.isSessionActive()

        val heightPx = if (activeSession) dp(58f) else dp(DockPrefs.getHandleHeightDp(this).toFloat())
        val widthPx = if (activeSession) dp(30f) else dp(24f)
        val yPx = ((screenHeightPx() - heightPx) * (DockPrefs.getHandleYPct(this) / 100f)).toInt()

        params.width = widthPx
        params.height = heightPx
        params.gravity = (if (rightSide) Gravity.END else Gravity.START) or Gravity.TOP
        params.x = 0
        params.y = yPx.coerceAtLeast(dp(32f))

        updateHandleVisualInternal(rightSide, activeSession)

        val r = handleRoot
        if (r != null && r.isAttachedToWindow) {
            try {
                wm.updateViewLayout(r, params)
            } catch (_: Throwable) {
            }
        }
    }

    private fun updateHandleAppearance() {
        applyHandleLayoutAndStyle()
    }

    private fun updateHandleVisualInternal(rightSide: Boolean, activeSession: Boolean) {
        val visual = handleVisual ?: return
        val icon = handleCloseIcon ?: return
        val baseAlpha = DockPrefs.getHandleAlphaPct(this) / 100f

        if (activeSession) {
            val lp = FrameLayout.LayoutParams(dp(26f), dp(50f)).apply {
                gravity = (if (rightSide) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            }
            visual.layoutParams = lp
            val rPx = dp(14f).toFloat()
            val radii = if (rightSide) {
                floatArrayOf(rPx, rPx, 0f, 0f, 0f, 0f, rPx, rPx)
            } else {
                floatArrayOf(0f, 0f, rPx, rPx, rPx, rPx, 0f, 0f)
            }
            visual.background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC161B24"))
                setStroke(dp(1.2f).coerceAtLeast(1), Color.parseColor("#9938BDF8"))
                cornerRadii = radii
            }
            visual.alpha = (baseAlpha + 0.14f).coerceIn(0.38f, 0.72f)
            icon.visibility = View.VISIBLE
        } else {
            val hPx = dp(DockPrefs.getHandleHeightDp(this).toFloat())
            val lp = FrameLayout.LayoutParams(dp(4.8f), hPx).apply {
                gravity = (if (rightSide) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                marginEnd = if (rightSide) dp(1.5f) else 0
                marginStart = if (rightSide) 0 else dp(1.5f)
            }
            visual.layoutParams = lp
            visual.background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6CBD5E1"))
                setStroke(dp(0.6f).coerceAtLeast(1), Color.parseColor("#44000000"))
                cornerRadius = dp(6f).toFloat()
            }
            visual.alpha = baseAlpha
            icon.visibility = View.GONE
        }
    }

    private fun setupHandleTouchGestures(root: FrameLayout) {
        var downRawX = 0f
        var downRawY = 0f
        var startY = 0
        var dragMode = false
        var swipeTriggered = false
        val longPressRunnable = Runnable {
            if (DockSessionTracker.isSessionActive()) {
                // Holding the dim edge indicator opens Dock Panel to jump to another quick app (keeping YouTube!)
                swipeTriggered = true
                vibrateLight(14L)
                openDockPanel()
            } else {
                dragMode = true
                vibrateLight(18L)
                handleVisual?.alpha = 0.92f
            }
        }

        root.setOnTouchListener { _, ev ->
            val rightSide = DockPrefs.isRightSide(this)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startY = handleParams?.y ?: 0
                    dragMode = false
                    swipeTriggered = false
                    // Capture YouTube's live taskId & package RIGHT ON TOUCH before any overlay appears!
                    if (!DockSessionTracker.isSessionActive()) {
                        AppLauncherEngine.captureUnderlyingAppSnapshot(this)
                    }
                    mainHandler.postDelayed(longPressRunnable, if (DockSessionTracker.isSessionActive()) 280L else 380L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY

                    if (dragMode) {
                        val p = handleParams ?: return@setOnTouchListener true
                        val maxY = (screenHeightPx() - p.height - dp(24f)).coerceAtLeast(dp(40f))
                        p.y = (startY + dy.toInt()).coerceIn(dp(32f), maxY)
                        try {
                            wm.updateViewLayout(root, p)
                        } catch (_: Throwable) {
                        }
                        return@setOnTouchListener true
                    }

                    if (abs(dx) > dp(8f) || abs(dy) > dp(8f)) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }

                    // Hair-trigger 11dp swipe:
                    // - Idle (e.g. on YouTube): opens Dock immediately!
                    // - Active (inside Dock app like Anti): swiping back immediately returns to YouTube!
                    val inwardDx = if (rightSide) -dx else dx
                    if (!swipeTriggered && inwardDx > dp(11f) && inwardDx > abs(dy) * 1.05f) {
                        swipeTriggered = true
                        vibrateLight(8L)
                        if (DockSessionTracker.isSessionActive()) {
                            AppLauncherEngine.dismissActiveDockApp(this)
                        } else {
                            openDockPanel()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (dragMode) {
                        dragMode = false
                        val p = handleParams
                        if (p != null) {
                            val usable = (screenHeightPx() - p.height).coerceAtLeast(1)
                            val pct = ((p.y.toFloat() / usable) * 100f).toInt().coerceIn(12, 86)
                            DockPrefs.setHandleYPct(this, pct)
                        }
                        updateHandleAppearance()
                        return@setOnTouchListener true
                    }

                    if (!swipeTriggered) {
                        val dx = abs(ev.rawX - downRawX)
                        val dy = abs(ev.rawY - downRawY)
                        if (dx < dp(14f) && dy < dp(14f)) {
                            vibrateLight(10L)
                            if (DockSessionTracker.isSessionActive()) {
                                AppLauncherEngine.dismissActiveDockApp(this)
                            } else {
                                openDockPanel()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // =========================================================================
    // WINDOW 2: 0ms RAM-CACHED SLIDE-OUT DOCK PANEL (100% FLAT VECTOR ICONS)
    // =========================================================================
    private fun openDockPanel() {
        if (dockOpen) return
        dockOpen = true

        val rightSide = DockPrefs.isRightSide(this)
        val apps = DockAppCache.getPinnedApps(this)
        val activePkg = DockSessionTracker.activeDockPackage

        val backdrop = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#34000000"))
            setOnClickListener { closeDockPanel() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5181C25"))
                setStroke(dp(1f).coerceAtLeast(1), Color.parseColor("#2F384A"))
                cornerRadius = dp(18f).toFloat()
            }
            setPadding(dp(10f), dp(12f), dp(10f), dp(10f))
            elevation = dp(10f).toFloat()
            isClickable = true
        }

        if (apps.isEmpty()) {
            val emptyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(14f), dp(18f), dp(14f), dp(18f))
                setOnClickListener {
                    closeDockPanel()
                    openSettingsActivity()
                }
            }
            emptyRow.addView(makeFlatIcon(R.drawable.ic_flat_plus, 15f, "#38BDF8"))
            emptyRow.addView(
                TextView(this).apply {
                    text = "Chọn app"
                    setTextColor(Color.parseColor("#38BDF8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(6f), 0, 0, 0)
                }
            )
            card.addView(emptyRow)
        } else {
            val cols = if (apps.size > 5) 2 else 1
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
            val grid = GridLayout(this).apply { columnCount = cols }

            for (entry in apps) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
                    val isCurrent = entry.packageName == activePkg
                    background = GradientDrawable().apply {
                        setColor(if (isCurrent) Color.parseColor("#253349") else Color.TRANSPARENT)
                        cornerRadius = dp(12f).toFloat()
                    }
                }

                val iconWrap = FrameLayout(this)
                val iconView = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val cached = DockAppCache.getCachedIcon(entry.packageName)
                    if (cached != null) {
                        setImageDrawable(cached)
                    } else {
                        setImageResource(R.drawable.ic_launcher_fg)
                        DockAppCache.loadIconAsync(this@EdgeOverlayService, entry.packageName) { d ->
                            if (d != null && isAttachedToWindow) setImageDrawable(d)
                        }
                    }
                }
                iconWrap.addView(iconView, FrameLayout.LayoutParams(dp(42f), dp(42f)))

                val dot = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(if (entry.keepAlive) "#38BDF8" else "#475569"))
                        setStroke(dp(1.5f).coerceAtLeast(1), Color.parseColor("#181C25"))
                    }
                }
                iconWrap.addView(
                    dot,
                    FrameLayout.LayoutParams(dp(10f), dp(10f)).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                    }
                )
                cell.addView(iconWrap, LinearLayout.LayoutParams(dp(44f), dp(44f)))

                val label = TextView(this).apply {
                    text = entry.label
                    setTextColor(Color.parseColor("#E2E8F0"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4f), 0, 0)
                }
                cell.addView(label, LinearLayout.LayoutParams(dp(64f), LinearLayout.LayoutParams.WRAP_CONTENT))

                cell.setOnClickListener {
                    vibrateLight(8L)
                    closeDockPanel()
                    AppLauncherEngine.launchDockApp(this@EdgeOverlayService, entry)
                }

                cell.setOnLongClickListener {
                    vibrateLight(16L)
                    val nowOn = DockPrefs.toggleKeepAlive(this@EdgeOverlayService, entry.packageName)
                    DockAppCache.invalidatePinnedApps(this@EdgeOverlayService)
                    val msg = if (nowOn) "Chạy ngầm BẬT · ${entry.label}" else "Chạy ngầm TẮT · ${entry.label}"
                    Toast.makeText(this@EdgeOverlayService, msg, Toast.LENGTH_SHORT).show()
                    closeDockPanel()
                    openDockPanel()
                    true
                }

                val glp = GridLayout.LayoutParams().apply {
                    setMargins(dp(2f), dp(2f), dp(2f), dp(2f))
                }
                grid.addView(cell, glp)
            }

            scroll.addView(grid)
            val maxScrollH = (screenHeightPx() * 0.56f).toInt()
            card.addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (apps.size > 6) height = maxScrollH
                }
            )
        }

        val divider = View(this).apply { setBackgroundColor(Color.parseColor("#2A3142")) }
        card.addView(
            divider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f).coerceAtLeast(1)).apply {
                topMargin = dp(8f)
                bottomMargin = dp(6f)
            }
        )

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        if (DockSessionTracker.isSessionActive()) {
            val closeBtn = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#21344B"))
                    cornerRadius = dp(9f).toFloat()
                }
                addView(
                    makeFlatIcon(R.drawable.ic_flat_close, 15f, "#38BDF8"),
                    FrameLayout.LayoutParams(dp(15f), dp(15f)).apply { gravity = Gravity.CENTER }
                )
                setOnClickListener {
                    vibrateLight(10L)
                    closeDockPanel()
                    AppLauncherEngine.dismissActiveDockApp(this@EdgeOverlayService)
                }
            }
            footer.addView(
                closeBtn,
                LinearLayout.LayoutParams(dp(34f), dp(30f)).apply { marginEnd = dp(6f) }
            )
        }

        val settingsBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222836"))
                cornerRadius = dp(9f).toFloat()
            }
            addView(
                makeFlatIcon(R.drawable.ic_flat_settings, 15f, "#94A3B8"),
                FrameLayout.LayoutParams(dp(15f), dp(15f)).apply { gravity = Gravity.CENTER }
            )
            setOnClickListener {
                vibrateLight(8L)
                closeDockPanel()
                openSettingsActivity()
            }
        }
        footer.addView(settingsBtn, LinearLayout.LayoutParams(dp(34f), dp(30f)))
        card.addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val cardLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = (if (rightSide) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            marginEnd = if (rightSide) dp(10f) else 0
            marginStart = if (rightSide) 0 else dp(10f)
        }
        backdrop.addView(card, cardLp)

        // Snappy 70ms slide-in (no sluggish lag)
        card.alpha = 0f
        card.translationX = if (rightSide) dp(16f).toFloat() else -dp(16f).toFloat()
        card.animate().alpha(1f).translationX(0f).setDuration(70L).setInterpolator(fastDecel).start()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        dockBackdrop = backdrop
        try {
            wm.addView(backdrop, params)
        } catch (_: Throwable) {
            dockOpen = false
            dockBackdrop = null
        }
    }

    private fun closeDockPanel() {
        val bd = dockBackdrop ?: return
        dockBackdrop = null
        dockOpen = false
        try {
            wm.removeViewImmediate(bd)
        } catch (_: Throwable) {
            try {
                wm.removeView(bd)
            } catch (_: Throwable) {
            }
        }
    }

    private fun openSettingsActivity() {
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(i)
        } catch (_: Throwable) {
        }
    }
}

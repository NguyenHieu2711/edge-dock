package com.nguyenhieu.edgedock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import kotlin.math.hypot

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

        fun onContextStateChanged(foregroundPkg: String?, imeTopPx: Int) {
            val inst = activeInstance ?: return
            inst.mainHandler.post {
                inst.handleContextUpdate(foregroundPkg, imeTopPx)
            }
        }
    }

    private data class DockHitTarget(
        val view: View,
        val normalBgColor: Int,
        val normalCornerDp: Float,
        val labelView: TextView? = null,
        val onInvoke: () -> Unit
    )

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fastDecel = DecelerateInterpolator(1.6f)

    private var handleRoot: FrameLayout? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var handleVisual: FrameLayout? = null
    private var handleCloseIcon: ImageView? = null

    private var gestureHintRoot: FrameLayout? = null
    private var gestureHintPill: TextView? = null
    private var gestureHintParams: WindowManager.LayoutParams? = null

    private var dockBackdrop: FrameLayout? = null
    private var dockOpen: Boolean = false
    private var dockOpenedAtMs: Long = 0L
    private val dockHitTargets = mutableListOf<DockHitTarget>()
    private var hoveredDockTarget: DockHitTarget? = null
    private var watchdogMissCount: Int = 0

    private var isLandscapeNow: Boolean = false
    private var currentForegroundPkg: String? = null
    private var currentImeTopPx: Int = 0
    private var handleDragInProgress: Boolean = false

    private val sessionListener: () -> Unit = {
        updateHandleAppearance()
        syncSessionWatchdog()
    }

    private val sessionWatchdog = object : Runnable {
        override fun run() {
            val activePkg = DockSessionTracker.activeDockPackage ?: return
            val elapsed = System.currentTimeMillis() - DockSessionTracker.sessionStartedAt
            val readyToVerify = DockSessionTracker.dockAppSeenOnScreen || elapsed >= 3500L
            if (readyToVerify) {
                val acc = EdgeDockAccessibilityService.instance
                if (acc != null) {
                    if (acc.isPackageVisibleNow(activePkg)) {
                        DockSessionTracker.markActiveAppVisible(activePkg)
                        watchdogMissCount = 0
                    } else {
                        watchdogMissCount++
                        if (watchdogMissCount >= 2) {
                            DockSessionTracker.clearSession()
                            return
                        }
                    }
                }
            }
            if (DockSessionTracker.isSessionActive()) {
                mainHandler.postDelayed(this, 650L)
            }
        }
    }

    private fun syncSessionWatchdog() {
        mainHandler.removeCallbacks(sessionWatchdog)
        watchdogMissCount = 0
        if (DockSessionTracker.isSessionActive()) {
            mainHandler.postDelayed(sessionWatchdog, 1200L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        activeInstance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        isLandscapeNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val acc = EdgeDockAccessibilityService.instance
        currentForegroundPkg = acc?.detectTopAppPackageNow() ?: DockSessionTracker.currentForegroundPackage
        currentImeTopPx = acc?.detectImeTopPx() ?: 0
        DockAppCache.warmUp(this)
        DockSessionTracker.addListener(sessionListener)
        startForegroundCompat()
        attachHandleOverlay()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val land = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land != isLandscapeNow) {
            isLandscapeNow = land
            if (land && DockPrefs.isAutoHideLandscape(this) && !DockSessionTracker.isSessionActive()) {
                closeDockPanel()
                hideGestureHint()
            }
            applyHandleLayoutAndStyle()
        }
    }

    private fun handleContextUpdate(foregroundPkg: String?, imeTopPx: Int) {
        val cleanIme = imeTopPx.coerceAtLeast(0)
        if (foregroundPkg == currentForegroundPkg && cleanIme == currentImeTopPx) return
        currentForegroundPkg = foregroundPkg
        currentImeTopPx = cleanIme
        if (!handleDragInProgress) {
            applyHandleLayoutAndStyle()
        }
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
        mainHandler.removeCallbacks(sessionWatchdog)
        DockSessionTracker.removeListener(sessionListener)
        hideGestureHint()
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
            FrameLayout.LayoutParams(dp(15f), dp(15f)).apply {
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
        val r = handleRoot ?: return
        val rightSide = DockPrefs.isRightSide(this)
        val activeSession = DockSessionTracker.isSessionActive()

        val hideForLandscape = !activeSession && DockPrefs.isAutoHideLandscape(this) && isLandscapeNow
        val fgPkg = currentForegroundPkg
        val hideForExcluded = !activeSession && !fgPkg.isNullOrBlank() && DockPrefs.isPackageExcluded(this, fgPkg)
        if (hideForLandscape || hideForExcluded) {
            if (r.visibility != View.GONE) {
                r.visibility = View.GONE
            }
            if (dockOpen) closeDockPanel()
            hideGestureHint()
            return
        }
        if (r.visibility != View.VISIBLE) {
            r.visibility = View.VISIBLE
        }

        val heightPx = if (activeSession) dp(72f) else dp(DockPrefs.getHandleHeightDp(this).toFloat())
        val widthPx = if (activeSession) dp(42f) else dp(26f)
        var yPx = ((screenHeightPx() - heightPx) * (DockPrefs.getHandleYPct(this) / 100f)).toInt()
        if (DockPrefs.isAvoidKeyboard(this) && currentImeTopPx > 0) {
            val maxAboveIme = (currentImeTopPx - heightPx - dp(16f)).coerceAtLeast(dp(36f))
            if (yPx > maxAboveIme) yPx = maxAboveIme
        }

        params.width = widthPx
        params.height = heightPx
        params.gravity = (if (rightSide) Gravity.END else Gravity.START) or Gravity.TOP
        params.x = 0
        params.y = yPx.coerceAtLeast(dp(32f))

        updateHandleVisualInternal(rightSide, activeSession)

        if (r.isAttachedToWindow) {
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
            val lp = FrameLayout.LayoutParams(dp(28f), dp(54f)).apply {
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
                setColor(Color.parseColor("#E8141821"))
                setStroke(dp(1.4f).coerceAtLeast(1), Color.parseColor("#B338BDF8"))
                cornerRadii = radii
            }
            visual.alpha = (baseAlpha + 0.45f).coerceIn(0.82f, 0.96f)
            icon.setColorFilter(Color.parseColor("#F1F5F9"))
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

    private fun showGestureHint(action: Int, armed: Boolean, anchorRawY: Int) {
        val rightSide = DockPrefs.isRightSide(this)
        val label = if (action == 1) "⇄ App trước" else "✎ Ghi nhanh"

        if (gestureHintRoot == null) {
            val container = FrameLayout(this)
            val pill = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(13f), dp(7f), dp(13f), dp(7f))
                elevation = dp(8f).toFloat()
            }
            container.addView(
                pill,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            gestureHintRoot = container
            gestureHintPill = pill
            gestureHintParams = params
            try {
                wm.addView(container, params)
            } catch (_: Throwable) {
                gestureHintRoot = null
                gestureHintPill = null
                gestureHintParams = null
                return
            }
        }

        val pill = gestureHintPill ?: return
        val params = gestureHintParams ?: return
        val root = gestureHintRoot ?: return

        pill.text = label
        pill.setTextColor(Color.parseColor(if (armed) "#F8FAFC" else "#94A3B8"))
        pill.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (armed) "#F217283D" else "#E8181C25"))
            setStroke(dp(1.2f).coerceAtLeast(1), Color.parseColor(if (armed) "#38BDF8" else "#334155"))
            cornerRadius = dp(99f).toFloat()
        }
        pill.scaleX = if (armed) 1.04f else 0.96f
        pill.scaleY = if (armed) 1.04f else 0.96f

        params.gravity = (if (rightSide) Gravity.END else Gravity.START) or Gravity.TOP
        params.x = dp(28f)
        params.y = (anchorRawY - dp(22f)).coerceIn(dp(44f), (screenHeightPx() - dp(80f)).coerceAtLeast(dp(48f)))

        if (root.isAttachedToWindow) {
            try {
                wm.updateViewLayout(root, params)
            } catch (_: Throwable) {
            }
        }
    }

    private fun hideGestureHint() {
        val root = gestureHintRoot ?: return
        gestureHintRoot = null
        gestureHintPill = null
        gestureHintParams = null
        try {
            wm.removeViewImmediate(root)
        } catch (_: Throwable) {
            try {
                wm.removeView(root)
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyTargetHoverStyle(target: DockHitTarget, hovered: Boolean) {
        val v = target.view
        v.animate().cancel()
        if (hovered) {
            v.animate().scaleX(1.13f).scaleY(1.13f).setDuration(55L).setInterpolator(fastDecel).start()
            v.background = GradientDrawable().apply {
                setColor(Color.parseColor("#283E59"))
                setStroke(dp(1.3f).coerceAtLeast(1), Color.parseColor("#38BDF8"))
                cornerRadius = dp(target.normalCornerDp).toFloat()
            }
            target.labelView?.setTextColor(Color.parseColor("#38BDF8"))
        } else {
            v.animate().scaleX(1f).scaleY(1f).setDuration(55L).setInterpolator(fastDecel).start()
            v.background = GradientDrawable().apply {
                setColor(target.normalBgColor)
                cornerRadius = dp(target.normalCornerDp).toFloat()
            }
            target.labelView?.setTextColor(Color.parseColor("#E2E8F0"))
        }
    }

    private fun updateContinuousDockHover(rawX: Float, rawY: Float) {
        if (!dockOpen) return
        // Brief arming gate prevents accidental launches on fast horizontal flick-and-release
        if (System.currentTimeMillis() - dockOpenedAtMs < 115L) return

        val slop = dp(4f)
        val loc = IntArray(2)
        var hit: DockHitTarget? = null
        for (t in dockHitTargets) {
            val v = t.view
            if (!v.isAttachedToWindow || v.width <= 0 || v.height <= 0) continue
            v.getLocationOnScreen(loc)
            val left = loc[0] - slop
            val top = loc[1] - slop
            val right = loc[0] + v.width + slop
            val bottom = loc[1] + v.height + slop
            if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom) {
                hit = t
                break
            }
        }

        if (hit !== hoveredDockTarget) {
            hoveredDockTarget?.let { applyTargetHoverStyle(it, false) }
            hoveredDockTarget = hit
            if (hit != null) {
                applyTargetHoverStyle(hit, true)
                vibrateLight(6L)
            }
        }
    }

    private fun clearContinuousDockHover() {
        hoveredDockTarget?.let { applyTargetHoverStyle(it, false) }
        hoveredDockTarget = null
    }

    private fun finishContinuousDockGesture() {
        val selected = hoveredDockTarget
        clearContinuousDockHover()
        if (selected != null) {
            vibrateLight(10L)
            selected.onInvoke()
        }
    }

    private fun setupHandleTouchGestures(root: FrameLayout) {
        var downRawX = 0f
        var downRawY = 0f
        var startY = 0
        var dragMode = false
        var swipeTriggered = false
        var continuousDockTracking = false
        var pendingDiagAction = 0 // 0=none, 1=prevApp (diag up), 2=quickNote (diag down)
        var lastDiagArmed = false

        val longPressRunnable = Runnable {
            if (DockSessionTracker.isSessionActive()) {
                // Holding the dim edge indicator opens Dock Panel to jump to another quick app (keeping YouTube!)
                swipeTriggered = true
                vibrateLight(14L)
                openDockPanel()
                if (DockPrefs.isNoLiftLaunchEnabled(this)) {
                    continuousDockTracking = true
                }
            } else {
                dragMode = true
                handleDragInProgress = true
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
                    handleDragInProgress = false
                    swipeTriggered = false
                    continuousDockTracking = false
                    pendingDiagAction = 0
                    lastDiagArmed = false
                    // Capture underlying foreground app RIGHT ON TOUCH before any overlay appears
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

                    // Active continuous 1-swipe No-Lift tracking over open Dock panel
                    if (continuousDockTracking) {
                        updateContinuousDockHover(ev.rawX, ev.rawY)
                        return@setOnTouchListener true
                    }

                    val inwardDx = if (rightSide) -dx else dx
                    val activeSession = DockSessionTracker.isSessionActive()

                    if (activeSession) {
                        if (!swipeTriggered && inwardDx > dp(12f) && inwardDx > abs(dy) * 1.15f) {
                            swipeTriggered = true
                            vibrateLight(8L)
                            AppLauncherEngine.dismissActiveDockApp(this)
                        }
                        return@setOnTouchListener true
                    }

                    // Active diagonal gesture preview (cancelable by sliding back to edge)
                    if (pendingDiagAction != 0) {
                        if (inwardDx < dp(10f)) {
                            pendingDiagAction = 0
                            lastDiagArmed = false
                            hideGestureHint()
                            return@setOnTouchListener true
                        }
                        val nextAction = when {
                            dy < -dp(14f) -> 1
                            dy > dp(14f) -> 2
                            else -> pendingDiagAction
                        }
                        if (nextAction != pendingDiagAction) {
                            pendingDiagAction = nextAction
                            vibrateLight(6L)
                        }
                        val dist = hypot(inwardDx.toDouble(), dy.toDouble())
                        val armed = dist >= dp(26f)
                        if (armed && !lastDiagArmed) vibrateLight(6L)
                        lastDiagArmed = armed
                        showGestureHint(pendingDiagAction, armed, ev.rawY.toInt())
                        return@setOnTouchListener true
                    }

                    // Initial direction commit once user swipes inward >= 14dp
                    if (!swipeTriggered && inwardDx >= dp(14f)) {
                        val slope = dy / inwardDx.coerceAtLeast(1f)
                        val diagEnabled = DockPrefs.isDiagonalGesturesEnabled(this)
                        when {
                            diagEnabled && slope <= -0.58f -> {
                                pendingDiagAction = 1
                                lastDiagArmed = false
                                vibrateLight(7L)
                                showGestureHint(1, false, ev.rawY.toInt())
                            }
                            diagEnabled && slope >= 0.58f -> {
                                pendingDiagAction = 2
                                lastDiagArmed = false
                                vibrateLight(7L)
                                showGestureHint(2, false, ev.rawY.toInt())
                            }
                            abs(slope) < 0.85f -> {
                                swipeTriggered = true
                                vibrateLight(8L)
                                openDockPanel()
                                if (DockPrefs.isNoLiftLaunchEnabled(this)) {
                                    continuousDockTracking = true
                                }
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (dragMode) {
                        dragMode = false
                        handleDragInProgress = false
                        updateHandleAppearance()
                    }
                    if (continuousDockTracking) {
                        continuousDockTracking = false
                        clearContinuousDockHover()
                    }
                    if (pendingDiagAction != 0) {
                        pendingDiagAction = 0
                        lastDiagArmed = false
                        hideGestureHint()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (dragMode) {
                        dragMode = false
                        handleDragInProgress = false
                        val p = handleParams
                        if (p != null) {
                            val usable = (screenHeightPx() - p.height).coerceAtLeast(1)
                            val pct = ((p.y.toFloat() / usable) * 100f).toInt().coerceIn(12, 86)
                            DockPrefs.setHandleYPct(this, pct)
                        }
                        updateHandleAppearance()
                        return@setOnTouchListener true
                    }

                    if (continuousDockTracking) {
                        continuousDockTracking = false
                        finishContinuousDockGesture()
                        return@setOnTouchListener true
                    }

                    if (pendingDiagAction != 0) {
                        val action = pendingDiagAction
                        val inwardDx = if (rightSide) -(ev.rawX - downRawX) else (ev.rawX - downRawX)
                        val dy = ev.rawY - downRawY
                        val dist = hypot(inwardDx.toDouble(), dy.toDouble())
                        pendingDiagAction = 0
                        lastDiagArmed = false
                        hideGestureHint()
                        if (inwardDx >= dp(14f) && dist >= dp(24f)) {
                            vibrateLight(10L)
                            if (action == 1) {
                                AppLauncherEngine.switchToPreviousApp(this)
                            } else {
                                AppLauncherEngine.openObsidianQuickCapture(this)
                            }
                        }
                        return@setOnTouchListener true
                    }

                    if (!swipeTriggered && DockSessionTracker.isSessionActive()) {
                        val dx = abs(ev.rawX - downRawX)
                        val dy = abs(ev.rawY - downRawY)
                        if (dx < dp(26f) && dy < dp(26f)) {
                            vibrateLight(10L)
                            AppLauncherEngine.dismissActiveDockApp(this)
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
        dockOpenedAtMs = System.currentTimeMillis()
        dockHitTargets.clear()
        hoveredDockTarget = null

        val rightSide = DockPrefs.isRightSide(this)
        val apps = DockAppCache.getPinnedApps(this)
        val activePkg = DockSessionTracker.activeDockPackage

        val backdrop = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#34000000"))
            setOnClickListener { closeDockPanel() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2181C25"))
                setStroke(dp(1f).coerceAtLeast(1), Color.parseColor("#2F384A"))
                cornerRadius = dp(14f).toFloat()
            }
            setPadding(dp(5f), dp(6f), dp(5f), dp(6f))
            elevation = dp(10f).toFloat()
            isClickable = true
        }

        if (apps.isEmpty()) {
            val emptyBtn = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A2D42"))
                    cornerRadius = dp(11f).toFloat()
                }
                addView(
                    makeFlatIcon(R.drawable.ic_flat_plus, 18f, "#38BDF8"),
                    FrameLayout.LayoutParams(dp(18f), dp(18f)).apply { gravity = Gravity.CENTER }
                )
                setOnClickListener {
                    vibrateLight(8L)
                    closeDockPanel()
                    openSettingsActivity()
                }
            }
            card.addView(emptyBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        } else {
            val cols = if (apps.size > 6) 2 else 1
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
            val grid = GridLayout(this).apply { columnCount = cols }

            for (entry in apps) {
                val isCurrent = entry.packageName == activePkg
                val cellNormalBg = if (isCurrent) Color.parseColor("#253349") else Color.TRANSPARENT
                val cell = FrameLayout(this).apply {
                    setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
                    background = GradientDrawable().apply {
                        setColor(cellNormalBg)
                        cornerRadius = dp(11f).toFloat()
                    }
                }

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
                cell.addView(
                    iconView,
                    FrameLayout.LayoutParams(dp(38f), dp(38f)).apply { gravity = Gravity.CENTER }
                )

                val invokeLaunch: () -> Unit = {
                    // Launch FIRST while focused overlay window is still attached (Android 14 BAL exemption!)
                    dockBackdrop?.alpha = 0f
                    AppLauncherEngine.launchDockApp(this@EdgeOverlayService, entry)
                    mainHandler.postDelayed({ closeDockPanel() }, 80L)
                }
                cell.setOnClickListener {
                    vibrateLight(8L)
                    invokeLaunch()
                }
                // Long-press any icon to open dock settings without cluttering the dock with a gear button
                cell.setOnLongClickListener {
                    vibrateLight(14L)
                    dockBackdrop?.alpha = 0f
                    openSettingsActivity()
                    mainHandler.postDelayed({ closeDockPanel() }, 80L)
                    true
                }
                dockHitTargets.add(
                    DockHitTarget(
                        view = cell,
                        normalBgColor = cellNormalBg,
                        normalCornerDp = 11f,
                        labelView = null,
                        onInvoke = invokeLaunch
                    )
                )

                val glp = GridLayout.LayoutParams().apply {
                    setMargins(dp(1f), dp(2f), dp(1f), dp(2f))
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
                    if (apps.size > 7) height = maxScrollH
                }
            )
        }

        // Only show a slim dismiss button while a dock overlay session is active (no permanent + or settings footer)
        if (DockSessionTracker.isSessionActive()) {
            val closeNormalBg = Color.parseColor("#21344B")
            val invokeDismiss: () -> Unit = {
                dockBackdrop?.alpha = 0f
                AppLauncherEngine.dismissActiveDockApp(this@EdgeOverlayService)
                mainHandler.postDelayed({ closeDockPanel() }, 80L)
            }
            val closeBtn = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(closeNormalBg)
                    cornerRadius = dp(9f).toFloat()
                }
                addView(
                    makeFlatIcon(R.drawable.ic_flat_close, 14f, "#38BDF8"),
                    FrameLayout.LayoutParams(dp(14f), dp(14f)).apply { gravity = Gravity.CENTER }
                )
                setOnClickListener {
                    vibrateLight(10L)
                    invokeDismiss()
                }
            }
            dockHitTargets.add(DockHitTarget(closeBtn, closeNormalBg, 9f, null, invokeDismiss))
            card.addView(
                closeBtn,
                LinearLayout.LayoutParams(dp(42f), dp(28f)).apply { topMargin = dp(4f) }
            )
        }

        val cardLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = (if (rightSide) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            marginEnd = if (rightSide) dp(8f) else 0
            marginStart = if (rightSide) 0 else dp(8f)
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
        dockHitTargets.clear()
        hoveredDockTarget = null
        val bd = dockBackdrop ?: return
        dockBackdrop = null
        dockOpen = false
        try {
            bd.clearAnimation()
            for (i in 0 until bd.childCount) {
                bd.getChildAt(i)?.animate()?.cancel()
            }
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

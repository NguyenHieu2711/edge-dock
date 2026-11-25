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

    private var handleRoot: FrameLayout? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var handleVisual: FrameLayout? = null
    private var handleCloseGlyph: TextView? = null

    private var dockBackdrop: FrameLayout? = null
    private var dockOpen: Boolean = false

    private val sessionListener: () -> Unit = {
        updateHandleAppearance()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ShizukuBridge.init(this)
        DockSessionTracker.addListener(sessionListener)
        startForegroundCompat()
        attachHandleOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (handleRoot == null) {
            attachHandleOverlay()
        } else {
            applyHandleLayoutAndStyle()
            if (dockOpen) {
                closeDockPanel()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
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

    private fun vibrateLight(ms: Long = 12L) {
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
        val closeGlyph = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#E2F5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        visual.addView(
            closeGlyph,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
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
        handleCloseGlyph = closeGlyph
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
        handleCloseGlyph = null
        handleParams = null
    }

    private fun applyHandleLayoutAndStyle() {
        val params = handleParams ?: return
        val rightSide = DockPrefs.isRightSide(this)
        val activeSession = DockSessionTracker.isSessionActive()

        val heightPx = if (activeSession) dp(60f) else dp(DockPrefs.getHandleHeightDp(this).toFloat())
        val widthPx = if (activeSession) dp(30f) else dp(22f)
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
        val glyph = handleCloseGlyph ?: return
        val baseAlpha = DockPrefs.getHandleAlphaPct(this) / 100f

        if (activeSession) {
            // Subtle dim half-pill indicator flush against the edge: 1-tap dismisses & returns to YouTube!
            val lp = FrameLayout.LayoutParams(dp(26f), dp(52f)).apply {
                gravity = (if (rightSide) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            }
            visual.layoutParams = lp
            val radii = if (rightSide) {
                floatArrayOf(dp(14f).toFloat(), dp(14f).toFloat(), 0f, 0f, 0f, 0f, dp(14f).toFloat(), dp(14f).toFloat())
            } else {
                floatArrayOf(0f, 0f, dp(14f).toFloat(), dp(14f).toFloat(), dp(14f).toFloat(), dp(14f).toFloat(), 0f, 0f)
            }
            visual.background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC161B24"))
                setStroke(dp(1.2f).coerceAtLeast(1), Color.parseColor("#9938BDF8"))
                cornerRadii = radii
            }
            visual.alpha = (baseAlpha + 0.14f).coerceIn(0.38f, 0.72f)
            glyph.visibility = View.VISIBLE
        } else {
            // Ultra-slim frosted Edge Handle (Samsung Edge Panel vibe)
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
            glyph.visibility = View.GONE
        }
    }

    private fun setupHandleTouchGestures(root: FrameLayout) {
        var downRawX = 0f
        var downRawY = 0f
        var startY = 0
        var dragMode = false
        var swipeTriggered = false
        val longPressRunnable = Runnable {
            dragMode = true
            vibrateLight(20L)
            handleVisual?.alpha = 0.92f
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
                    mainHandler.postDelayed(longPressRunnable, 420L)
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

                    if (abs(dx) > dp(10f) || abs(dy) > dp(10f)) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }

                    val inwardDx = if (rightSide) -dx else dx
                    if (!swipeTriggered && inwardDx > dp(18f) && inwardDx > abs(dy) * 1.15f) {
                        swipeTriggered = true
                        vibrateLight(10L)
                        openDockPanel()
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
                            vibrateLight(12L)
                            if (DockSessionTracker.isSessionActive()) {
                                // 1-tap on the dim edge indicator -> close/dismiss & return to YouTube!
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
    // WINDOW 2: MINIMALIST SLIDE-OUT DOCK PANEL
    // =========================================================================
    private fun openDockPanel() {
        if (dockOpen) return
        dockOpen = true

        val rightSide = DockPrefs.isRightSide(this)
        val apps = DockPrefs.loadApps(this)
        val activePkg = DockSessionTracker.activeDockPackage

        val backdrop = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#38000000"))
            setOnClickListener { closeDockPanel() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4181C25"))
                setStroke(dp(1f).coerceAtLeast(1), Color.parseColor("#2F384A"))
                cornerRadius = dp(18f).toFloat()
            }
            setPadding(dp(10f), dp(12f), dp(10f), dp(10f))
            elevation = dp(10f).toFloat()
            isClickable = true
        }

        if (apps.isEmpty()) {
            val emptyBtn = TextView(this).apply {
                text = "+ Chọn app"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(16f), dp(22f), dp(16f), dp(22f))
                setOnClickListener {
                    closeDockPanel()
                    openSettingsActivity()
                }
            }
            card.addView(emptyBtn)
        } else {
            val cols = if (apps.size > 5) 2 else 1
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
            }
            val grid = GridLayout(this).apply {
                columnCount = cols
            }

            val pm = packageManager
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
                    try {
                        setImageDrawable(pm.getApplicationIcon(entry.packageName))
                    } catch (_: Throwable) {
                        setImageResource(R.drawable.ic_launcher_fg)
                    }
                }
                iconWrap.addView(iconView, FrameLayout.LayoutParams(dp(42f), dp(42f)))

                // Subtle Keep-Alive status dot at bottom-right of app icon
                val dot = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (entry.keepAlive) {
                            setColor(Color.parseColor("#38BDF8"))
                            setStroke(dp(1.5f).coerceAtLeast(1), Color.parseColor("#181C25"))
                        } else {
                            setColor(Color.parseColor("#475569"))
                            setStroke(dp(1.5f).coerceAtLeast(1), Color.parseColor("#181C25"))
                        }
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
                    vibrateLight(10L)
                    closeDockPanel()
                    AppLauncherEngine.launchDockApp(this, entry)
                }

                // Long-press any app icon in Dock to toggle Keep-Alive ON/OFF on the spot!
                cell.setOnLongClickListener {
                    vibrateLight(18L)
                    val nowOn = DockPrefs.toggleKeepAlive(this, entry.packageName)
                    val msg = if (nowOn) "Chạy ngầm BẬT · ${entry.label}" else "Chạy ngầm TẮT · ${entry.label}"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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

        // Minimalist divider + action icons (Return/Close if active + Settings)
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#2A3142"))
        }
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
            val closeBtn = TextView(this).apply {
                text = "✕"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#21344B"))
                    cornerRadius = dp(9f).toFloat()
                }
                setOnClickListener {
                    vibrateLight(12L)
                    closeDockPanel()
                    AppLauncherEngine.dismissActiveDockApp(this@EdgeOverlayService)
                }
            }
            footer.addView(
                closeBtn,
                LinearLayout.LayoutParams(dp(34f), dp(30f)).apply {
                    marginEnd = dp(6f)
                }
            )
        }

        val settingsBtn = TextView(this).apply {
            text = "⚙"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222836"))
                cornerRadius = dp(9f).toFloat()
            }
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

        // Smooth quick slide-in animation
        card.alpha = 0f
        card.translationX = if (rightSide) dp(24f).toFloat() else -dp(24f).toFloat()
        card.animate().alpha(1f).translationX(0f).setDuration(140L).start()

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
            wm.removeView(bd)
        } catch (_: Throwable) {
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

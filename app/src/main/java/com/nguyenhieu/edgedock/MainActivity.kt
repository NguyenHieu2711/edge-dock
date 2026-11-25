package com.nguyenhieu.edgedock

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val GITHUB_REPO = "NguyenHieu2711/edge-dock"
    }

    private val netExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootList: LinearLayout
    private lateinit var masterSwitch: Switch
    private lateinit var statusOverlayBadge: TextView
    private lateinit var statusAccessBadge: TextView
    private lateinit var statusShizukuBadge: TextView
    private lateinit var appsContainer: LinearLayout
    private lateinit var sideLeftBtn: TextView
    private lateinit var sideRightBtn: TextView

    private var activeDownloadId: Long = -1L
    private var installTriggeredForId: Long = -1L
    private var activeUpdateApkFile: File? = null
    private var pendingInstallApkFile: File? = null
    private var mandatoryUpdateDialog: AlertDialog? = null
    private var autoTriggeredUpdateTag: String = ""
    private var lastAutoCheckAt: Long = 0L
    private var downloadPollRunnable: Runnable? = null

    private val shizukuListener: () -> Unit = {
        refreshStatusBadges()
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L || id != activeDownloadId) return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
            handleDownloadStatusCheck(dm, id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuBridge.init(this)
        ShizukuBridge.addStatusListener(shizukuListener)
        DockAppCache.warmUp(this)
        DockAppCache.loadInstalledAppsAsync(this) {}

        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        window.statusBarColor = Color.parseColor("#14161C")
        window.navigationBarColor = Color.parseColor("#14161C")
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        buildUi()
        refreshAll()
        checkForUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingInstallApkFile
        if (pending != null && pending.exists() && packageManager.canRequestPackageInstalls()) {
            pendingInstallApkFile = null
            promptInstallApk(pending)
            return
        }
        refreshAll()
        checkForUpdate(manual = false)
    }

    override fun onDestroy() {
        stopDownloadPoll()
        mandatoryUpdateDialog?.dismiss()
        mandatoryUpdateDialog = null
        ShizukuBridge.removeStatusListener(shizukuListener)
        try {
            unregisterReceiver(downloadReceiver)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun cardBg(colorHex: String = "#1D212B", strokeHex: String = "#2A3142", radiusDp: Float = 14f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            setStroke(dp(1f).coerceAtLeast(1), Color.parseColor(strokeHex))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun makeIconBtn(resId: Int, iconDp: Float, tintHex: String, boxDp: Float = 30f, onClick: () -> Unit): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(boxDp), dp(boxDp))
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(resId)
                    setColorFilter(Color.parseColor(tintHex))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                FrameLayout.LayoutParams(dp(iconDp), dp(iconDp)).apply { gravity = Gravity.CENTER }
            )
            setOnClickListener { onClick() }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#14161C"))
            isVerticalScrollBarEnabled = false
        }
        rootList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(28f))
        }

        // 1. Minimal Header + Update Icon + Master Switch
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(4f), dp(2f), dp(14f))
        }
        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = "Edge Dock"
            setTextColor(Color.parseColor("#F3F5F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val verTv = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8f), dp(3f), 0, 0)
        }
        titleWrap.addView(titleTv)
        titleWrap.addView(verTv)
        header.addView(titleWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val updateBtn = makeIconBtn(R.drawable.ic_flat_refresh, 17f, "#94A3B8", 34f) {
            checkForUpdate(manual = true)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8f)
        }
        header.addView(updateBtn)

        masterSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, checked -> onMasterToggled(checked) }
        }
        header.addView(masterSwitch)
        rootList.addView(header)

        // 2. Permissions & Shizuku Card
        val permCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        }
        statusOverlayBadge = addStatusRow(permCard, "Vẽ đè màn hình", false) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addDivider(permCard)
        statusAccessBadge = addStatusRow(permCard, "Quay về app cũ (Accessibility)", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addDivider(permCard)
        statusShizukuBadge = addStatusRow(permCard, "Shizuku (Chạy ngầm & Ẩn tab)", false) {
            handleShizukuAction()
        }
        rootList.addView(permCard, cardLp())

        // 3. Pinned Apps Card
        val appsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(16f), dp(2f), dp(8f))
        }
        val appsTitle = TextView(this).apply {
            text = "App trên thanh gạt"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        appsHeader.addView(appsTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val addBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#38BDF8"))
                cornerRadius = dp(99f).toFloat()
            }
            setPadding(dp(11f), dp(6f), dp(13f), dp(6f))
            setOnClickListener { openAppPickerDialog() }
        }
        addBtn.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_flat_plus)
                setColorFilter(Color.parseColor("#061826"))
            },
            LinearLayout.LayoutParams(dp(13f), dp(13f)).apply { marginEnd = dp(4f) }
        )
        addBtn.addView(
            TextView(this).apply {
                text = "Thêm app"
                setTextColor(Color.parseColor("#061826"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        appsHeader.addView(addBtn)
        rootList.addView(appsHeader)

        appsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        }
        rootList.addView(appsContainer, cardLp(topDp = 0f))

        // 4. Behavior & Handle Geometry Card
        val cfgTitle = TextView(this).apply {
            text = "Thanh gạt & chế độ mở"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4f), dp(16f), dp(4f), dp(8f))
        }
        rootList.addView(cfgTitle)

        val cfgCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(14f), dp(8f), dp(14f), dp(12f))
        }

        addToggleRow(cfgCard, "Ẩn khỏi Đa nhiệm (chỉ app tắt ngầm)", DockPrefs.isHideFromRecents(this)) { on ->
            DockPrefs.setHideFromRecents(this, on)
        }
        addDivider(cfgCard)

        val sideRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        val sideLbl = TextView(this).apply {
            text = "Cạnh đặt thanh gạt"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        sideRow.addView(sideLbl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val segWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg("#14161C", "#2A3142", 10f)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        sideLeftBtn = makeSegBtn("Trái") {
            DockPrefs.setRightSide(this, false)
            refreshSideButtons()
            EdgeOverlayService.refresh(this)
        }
        sideRightBtn = makeSegBtn("Phải") {
            DockPrefs.setRightSide(this, true)
            refreshSideButtons()
            EdgeOverlayService.refresh(this)
        }
        segWrap.addView(sideLeftBtn)
        segWrap.addView(sideRightBtn)
        sideRow.addView(segWrap)
        cfgCard.addView(sideRow)
        addDivider(cfgCard)

        addSliderRow(cfgCard, "Vị trí cao / thấp", DockPrefs.getHandleYPct(this), 12, 86) { value ->
            DockPrefs.setHandleYPct(this, value)
            EdgeOverlayService.refresh(this)
        }
        addSliderRow(cfgCard, "Độ rõ thanh gạt", DockPrefs.getHandleAlphaPct(this), 15, 90) { value ->
            DockPrefs.setHandleAlphaPct(this, value)
            EdgeOverlayService.refresh(this)
        }

        rootList.addView(cfgCard, cardLp(topDp = 0f))

        scroll.addView(rootList)
        setContentView(scroll)
    }

    private fun cardLp(topDp: Float = 4f): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }
    }

    private fun addDivider(parent: LinearLayout) {
        val v = View(this).apply { setBackgroundColor(Color.parseColor("#262D3D")) }
        parent.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f).coerceAtLeast(1)))
    }

    private fun addStatusRow(parent: LinearLayout, title: String, initialOk: Boolean, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
            setOnClickListener { onClick() }
        }
        val tv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val badge = TextView(this).apply {
            text = if (initialOk) "Đã bật" else "Bật"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
        }
        row.addView(badge)
        parent.addView(row)
        return badge
    }

    private fun addToggleRow(parent: LinearLayout, title: String, initial: Boolean, onChanged: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9f), 0, dp(9f))
        }
        val tv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
        row.addView(sw)
        parent.addView(row)
    }

    private fun addSliderRow(parent: LinearLayout, label: String, current: Int, minVal: Int, maxVal: Int, onChange: (Int) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, dp(4f))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lbl = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val valTv = TextView(this).apply {
            text = "$current%"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(lbl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(valTv)
        box.addView(top)

        val seek = SeekBar(this).apply {
            max = maxVal - minVal
            progress = (current - minVal).coerceIn(0, maxVal - minVal)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val actual = minVal + p
                    valTv.text = "$actual%"
                    if (fromUser) onChange(actual)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(box)
    }

    private fun makeSegBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(13f), dp(5f), dp(13f), dp(5f))
            setOnClickListener { onClick() }
        }
    }

    private fun styleBadge(tv: TextView, text: String, ok: Boolean, warn: Boolean = false) {
        tv.text = text
        val bg = when {
            ok -> "#163143"
            warn -> "#3A2814"
            else -> "#262C3B"
        }
        val fg = when {
            ok -> "#38BDF8"
            warn -> "#FBBF24"
            else -> "#94A3B8"
        }
        tv.setTextColor(Color.parseColor(fg))
        tv.background = GradientDrawable().apply {
            setColor(Color.parseColor(bg))
            cornerRadius = dp(99f).toFloat()
        }
    }

    private fun refreshAll() {
        val overlayOk = Settings.canDrawOverlays(this)
        masterSwitch.setOnCheckedChangeListener(null)
        masterSwitch.isChecked = DockPrefs.isDockEnabled(this) && overlayOk && EdgeOverlayService.isRunning
        masterSwitch.setOnCheckedChangeListener { _, checked -> onMasterToggled(checked) }

        refreshStatusBadges()
        refreshSideButtons()
        renderPinnedAppsList()
    }

    private fun refreshStatusBadges() {
        val overlayOk = Settings.canDrawOverlays(this)
        styleBadge(statusOverlayBadge, if (overlayOk) "Đã bật" else "Cấp quyền", overlayOk)

        val accOk = EdgeDockAccessibilityService.isEnabled(this)
        styleBadge(statusAccessBadge, if (accOk) "Đã bật" else "Cấp quyền", accOk)

        when (ShizukuBridge.getStatus(this)) {
            ShizukuBridge.Status.READY -> styleBadge(statusShizukuBadge, "Sẵn sàng", ok = true)
            ShizukuBridge.Status.NEED_PERMISSION -> styleBadge(statusShizukuBadge, "Cấp quyền", ok = false, warn = true)
            ShizukuBridge.Status.NOT_RUNNING -> styleBadge(statusShizukuBadge, "Mở Shizuku", ok = false, warn = true)
            ShizukuBridge.Status.NOT_INSTALLED -> styleBadge(statusShizukuBadge, "Chưa cài", ok = false)
        }
    }

    private fun refreshSideButtons() {
        val right = DockPrefs.isRightSide(this)
        styleSeg(sideLeftBtn, !right)
        styleSeg(sideRightBtn, right)
    }

    private fun styleSeg(tv: TextView, active: Boolean) {
        tv.setTextColor(Color.parseColor(if (active) "#061826" else "#94A3B8"))
        tv.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (active) "#38BDF8" else "#00000000"))
            cornerRadius = dp(7f).toFloat()
        }
    }

    private fun onMasterToggled(checked: Boolean) {
        if (checked) {
            if (!Settings.canDrawOverlays(this)) {
                masterSwitch.isChecked = false
                Toast.makeText(this, "Hãy bật quyền Vẽ đè màn hình trước", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            DockPrefs.setDockEnabled(this, true)
            EdgeOverlayService.start(this)
        } else {
            DockPrefs.setDockEnabled(this, false)
            EdgeOverlayService.stop(this)
        }
    }

    private fun handleShizukuAction() {
        when (ShizukuBridge.getStatus(this)) {
            ShizukuBridge.Status.READY -> {
                ShizukuBridge.getDiagnosticInfoAsync(this) { info ->
                    Toast.makeText(this, "Shizuku: $info", Toast.LENGTH_LONG).show()
                }
            }
            ShizukuBridge.Status.NEED_PERMISSION -> ShizukuBridge.requestPermission()
            ShizukuBridge.Status.NOT_RUNNING -> {
                val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (launch != null) startActivity(launch)
                else Toast.makeText(this, "Mở app Shizuku và bấm Start", Toast.LENGTH_LONG).show()
            }
            ShizukuBridge.Status.NOT_INSTALLED -> {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"))
                    )
                } catch (_: Throwable) {
                    Toast.makeText(this, "Cài Shizuku từ CH Play", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderPinnedAppsList() {
        appsContainer.removeAllViews()
        val apps = DockAppCache.getPinnedApps(this)
        if (apps.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Chưa có app nào"
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                gravity = Gravity.CENTER
                setPadding(0, dp(22f), 0, dp(22f))
            }
            appsContainer.addView(empty)
            return
        }

        apps.forEachIndexed { idx, entry ->
            if (idx > 0) addDivider(appsContainer)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8f), 0, dp(8f))
            }

            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val cached = DockAppCache.getCachedIcon(entry.packageName)
                if (cached != null) {
                    setImageDrawable(cached)
                } else {
                    setImageResource(R.drawable.ic_launcher_fg)
                    DockAppCache.loadIconAsync(this@MainActivity, entry.packageName) { d ->
                        if (d != null && isAttachedToWindow) setImageDrawable(d)
                    }
                }
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { marginEnd = dp(10f) })

            val nameTv = TextView(this).apply {
                text = entry.label
                setTextColor(Color.parseColor("#F1F5F9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(nameTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val bgPill = TextView(this).apply {
                text = if (entry.keepAlive) "Ngầm · Bật" else "Ngầm · Tắt"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(if (entry.keepAlive) "#38BDF8" else "#94A3B8"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (entry.keepAlive) "#163143" else "#262C3B"))
                    setStroke(dp(1f).coerceAtLeast(1), Color.parseColor(if (entry.keepAlive) "#38BDF8" else "#334155"))
                    cornerRadius = dp(99f).toFloat()
                }
                setPadding(dp(9f), dp(4f), dp(9f), dp(4f))
                setOnClickListener {
                    DockPrefs.toggleKeepAlive(this@MainActivity, entry.packageName)
                    DockAppCache.invalidatePinnedApps(this@MainActivity)
                    renderPinnedAppsList()
                    EdgeOverlayService.refresh(this@MainActivity)
                }
            }
            row.addView(
                bgPill,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(6f)
                    marginEnd = dp(4f)
                }
            )

            // Flat Vector Battery/Settings shortcut (replaces emoji)
            row.addView(
                makeIconBtn(R.drawable.ic_flat_bolt, 15f, "#94A3B8", 28f) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${entry.packageName}"))
                        )
                    } catch (_: Throwable) {
                    }
                }
            )

            if (idx > 0) {
                row.addView(
                    makeIconBtn(R.drawable.ic_flat_chev_up, 15f, "#94A3B8", 26f) {
                        val list = DockPrefs.loadApps(this@MainActivity)
                        if (idx in 1 until list.size) {
                            val tmp = list[idx - 1]
                            list[idx - 1] = list[idx]
                            list[idx] = tmp
                            DockPrefs.saveApps(this@MainActivity, list)
                            DockAppCache.invalidatePinnedApps(this@MainActivity)
                            renderPinnedAppsList()
                            EdgeOverlayService.refresh(this@MainActivity)
                        }
                    }
                )
            }

            row.addView(
                makeIconBtn(R.drawable.ic_flat_close, 14f, "#F87171", 26f) {
                    val list = DockPrefs.loadApps(this@MainActivity).filter { it.packageName != entry.packageName }
                    DockPrefs.saveApps(this@MainActivity, list)
                    DockAppCache.invalidatePinnedApps(this@MainActivity)
                    renderPinnedAppsList()
                    EdgeOverlayService.refresh(this@MainActivity)
                }
            )

            appsContainer.addView(row)
        }
    }

    // =========================================================================
    // 120FPS RECYCLED LISTVIEW APP PICKER (ZERO MAIN-THREAD ICON DECODING!)
    // =========================================================================
    private fun openAppPickerDialog() {
        val selectedMap = DockPrefs.loadApps(this).associateBy { it.packageName }.toMutableMap()
        var allApps: List<Pair<String, String>> = emptyList()
        var filteredApps: List<Pair<String, String>> = emptyList()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181C25"))
            setPadding(dp(14f), dp(14f), dp(14f), dp(10f))
        }

        val searchBox = EditText(this).apply {
            hint = "Tìm tên ứng dụng..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = cardBg("#14161C", "#2A3142", 10f)
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            isSingleLine = true
        }
        root.addView(searchBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = filteredApps.size
            override fun getItem(pos: Int): Pair<String, String> = filteredApps[pos]
            override fun getItemId(pos: Int): Long = pos.toLong()

            override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                val row: LinearLayout
                val iconIv: ImageView
                val titleTv: TextView
                val stateIv: ImageView

                if (convertView == null) {
                    row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                    }
                    iconIv = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    titleTv = TextView(this@MainActivity).apply {
                        setTextColor(Color.parseColor("#F1F5F9"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    stateIv = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    row.addView(iconIv, LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { marginEnd = dp(10f) })
                    row.addView(titleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(stateIv, LinearLayout.LayoutParams(dp(16f), dp(16f)).apply { marginStart = dp(8f) })
                    row.tag = Triple(iconIv, titleTv, stateIv)
                } else {
                    row = convertView as LinearLayout
                    @Suppress("UNCHECKED_CAST")
                    val holder = row.tag as Triple<ImageView, TextView, ImageView>
                    iconIv = holder.first
                    titleTv = holder.second
                    stateIv = holder.third
                }

                val (pkg, label) = filteredApps[pos]
                val isSel = selectedMap.containsKey(pkg)
                row.background = GradientDrawable().apply {
                    setColor(if (isSel) Color.parseColor("#1E2C40") else Color.TRANSPARENT)
                    cornerRadius = dp(9f).toFloat()
                }
                titleTv.text = label
                stateIv.setImageResource(if (isSel) R.drawable.ic_flat_check else R.drawable.ic_flat_plus)
                stateIv.setColorFilter(Color.parseColor(if (isSel) "#38BDF8" else "#64748B"))

                iconIv.tag = pkg
                val cached = DockAppCache.getCachedIcon(pkg)
                if (cached != null) {
                    iconIv.setImageDrawable(cached)
                } else {
                    iconIv.setImageResource(R.drawable.ic_launcher_fg)
                    DockAppCache.loadIconAsync(this@MainActivity, pkg) { d ->
                        if (d != null && iconIv.tag == pkg) {
                            iconIv.setImageDrawable(d)
                        }
                    }
                }
                return row
            }
        }

        val listView = ListView(this).apply {
            this.adapter = adapter
            divider = null
            dividerHeight = dp(2f)
            setOnItemClickListener { _, _, pos, _ ->
                if (pos !in filteredApps.indices) return@setOnItemClickListener
                val (pkg, label) = filteredApps[pos]
                if (selectedMap.containsKey(pkg)) selectedMap.remove(pkg)
                else selectedMap[pkg] = DockAppEntry(pkg, label, keepAlive = true)
                adapter.notifyDataSetChanged()
            }
        }
        root.addView(
            listView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360f)).apply {
                topMargin = dp(8f)
            }
        )

        fun applyFilter(qRaw: String) {
            val q = qRaw.trim().lowercase()
            filteredApps = if (q.isEmpty()) allApps else allApps.filter {
                it.second.lowercase().contains(q) || it.first.lowercase().contains(q)
            }
            adapter.notifyDataSetChanged()
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10f), 0, dp(2f))
        }
        val cancelBtn = TextView(this).apply {
            text = "Hủy"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        val saveBtn = TextView(this).apply {
            text = "Xong"
            setTextColor(Color.parseColor("#061826"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#38BDF8"))
                cornerRadius = dp(99f).toFloat()
            }
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(saveBtn)
        root.addView(btnRow)

        val dlg = AlertDialog.Builder(this).setView(root).create()
        dlg.window?.setBackgroundDrawable(cardBg("#181C25", "#2F384A", 16f))
        cancelBtn.setOnClickListener { dlg.dismiss() }
        saveBtn.setOnClickListener {
            val oldOrder = DockPrefs.loadApps(this)
            val finalList = mutableListOf<DockAppEntry>()
            for (old in oldOrder) selectedMap[old.packageName]?.let { finalList.add(it) }
            val existingPkgs = finalList.map { it.packageName }.toSet()
            for ((pkg, entry) in selectedMap) {
                if (pkg !in existingPkgs) finalList.add(entry)
            }
            DockPrefs.saveApps(this, finalList)
            DockAppCache.invalidatePinnedApps(this)
            renderPinnedAppsList()
            EdgeOverlayService.refresh(this)
            dlg.dismiss()
        }
        dlg.show()

        DockAppCache.loadInstalledAppsAsync(this) { loaded ->
            allApps = loaded
            applyFilter(searchBox.text.toString())
        }
    }

    // =========================================================================
    // GITHUB IN-APP APK SELF-UPDATER (SHA BYPASS CDN + ONRESUME + 600ms POLL)
    // =========================================================================
    private fun fetchFromGithubWebRedirect(): Pair<String, String>? {
        val url = "https://github.com/$GITHUB_REPO/releases/latest?t=${System.currentTimeMillis()}"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "EdgeDock-Android/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location")?.trim().orEmpty()
            if (code in 300..399 && loc.contains("/releases/tag/")) {
                val tag = loc.substringAfter("/releases/tag/").substringBefore("?").substringBefore("#").trim()
                if (tag.isNotEmpty()) {
                    return Pair(tag, "https://github.com/$GITHUB_REPO/releases/download/$tag/edge-dock-$tag.apk")
                }
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun checkForUpdate(manual: Boolean) {
        if (activeDownloadId != -1L) return
        if (!manual) {
            val now = System.currentTimeMillis()
            if (now - lastAutoCheckAt < 5000L) return
            lastAutoCheckAt = now
        }
        netExecutor.execute {
            var releaseInfo: Pair<String, String>? = fetchFromGithubWebRedirect()
            if (releaseInfo == null) {
                val manifest = fetchLatestManifest()
                releaseInfo = if (manifest != null) {
                    val t = manifest.optString("tag_name", "").trim()
                    val u = manifest.optString("apk_url", "").trim()
                    if (t.isNotEmpty() && u.isNotEmpty()) Pair(t, u) else null
                } else {
                    null
                }
            }
            if (releaseInfo == null) {
                releaseInfo = fetchFromGitHubReleasesApi()
            }

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (releaseInfo != null) {
                    val (tag, apkUrl) = releaseInfo
                    if (isNewerVersion(tag, BuildConfig.VERSION_NAME)) {
                        if (manual || autoTriggeredUpdateTag != tag) {
                            autoTriggeredUpdateTag = tag
                            triggerImmediateApkUpdate(tag, apkUrl)
                        }
                        return@post
                    }
                }
                if (manual) {
                    val msg = if (releaseInfo == null) {
                        "Không thể kiểm tra cập nhật lúc này."
                    } else {
                        "Đang ở bản mới nhất (v${BuildConfig.VERSION_NAME})."
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resolveLatestGithubCommitSha(): String? {
        val apiUrl = "https://api.github.com/repos/$GITHUB_REPO/commits/main"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "EdgeDock-Android/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("sha", "").trim().ifEmpty { null }
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchLatestManifest(): JSONObject? {
        val ref = resolveLatestGithubCommitSha() ?: "main"
        val url = "https://raw.githubusercontent.com/$GITHUB_REPO/$ref/releases/latest.json?t=${System.currentTimeMillis()}"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "EdgeDock-Android/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchFromGitHubReleasesApi(): Pair<String, String>? {
        val apiUrl = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "EdgeDock-Android/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tagName = json.optString("tag_name", "").trim()
            if (tagName.isEmpty()) return null
            val assets = json.optJSONArray("assets") ?: return null
            var chosenUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val u = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && u.isNotEmpty()) {
                    chosenUrl = u
                    if (name.contains("edge-dock", ignoreCase = true)) break
                }
            }
            if (chosenUrl.isEmpty()) null else Pair(tagName, chosenUrl)
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val r = remote.trim().removePrefix("v").removePrefix("V").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.trim().removePrefix("v").removePrefix("V").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    private fun triggerImmediateApkUpdate(tagName: String, apkUrl: String) {
        if (isFinishing || isDestroyed) return
        val safeTag = tagName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val existingApk = activeUpdateApkFile
        if (existingApk != null && existingApk.exists() && existingApk.name.contains(safeTag, ignoreCase = true)) {
            mandatoryUpdateDialog?.dismiss()
            mandatoryUpdateDialog = null
            promptInstallApk(existingApk)
            return
        }
        showDownloadingLockDialog(tagName)
        downloadAndInstallApk(tagName, apkUrl)
    }

    private fun showDownloadingLockDialog(tagName: String) {
        if (isFinishing || isDestroyed) return
        mandatoryUpdateDialog?.let { if (it.isShowing) return }
        val progress = AlertDialog.Builder(this)
            .setTitle("Đang tự cập nhật lên $tagName...")
            .setMessage("Đang tải gói cài đặt, bộ cài đặt sẽ tự bật lên ngay khi tải xong.")
            .setCancelable(true)
            .create()
        mandatoryUpdateDialog = progress
        progress.show()
    }

    private fun stopDownloadPoll() {
        downloadPollRunnable?.let { mainHandler.removeCallbacks(it) }
        downloadPollRunnable = null
    }

    private fun handleDownloadStatusCheck(dm: DownloadManager, downloadId: Long): Boolean {
        if (downloadId == -1L || installTriggeredForId == downloadId) return true
        val query = DownloadManager.Query().setFilterById(downloadId)
        dm.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (idx >= 0) {
                    when (cursor.getInt(idx)) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            installTriggeredForId = downloadId
                            activeDownloadId = -1L
                            stopDownloadPoll()
                            mandatoryUpdateDialog?.dismiss()
                            mandatoryUpdateDialog = null
                            activeUpdateApkFile?.let { if (it.exists()) promptInstallApk(it) }
                            return true
                        }
                        DownloadManager.STATUS_FAILED -> {
                            installTriggeredForId = downloadId
                            activeDownloadId = -1L
                            autoTriggeredUpdateTag = ""
                            stopDownloadPoll()
                            mandatoryUpdateDialog?.dismiss()
                            mandatoryUpdateDialog = null
                            Toast.makeText(this, "Tải bản cập nhật thất bại, vui lòng thử lại.", Toast.LENGTH_SHORT).show()
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun downloadAndInstallApk(tag: String, apkUrl: String) {
        try {
            val safeTag = tag.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            dir.listFiles()?.forEach { if (it.name.endsWith(".apk", true)) it.delete() }
            val name = "edge-dock-$safeTag.apk"
            activeUpdateApkFile = File(dir, name)
            val req = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Cập nhật Edge Dock ($tag)")
                .setDescription("Đang tải gói cài đặt từ GitHub Releases...")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            installTriggeredForId = -1L
            activeDownloadId = dm.enqueue(req)

            stopDownloadPoll()
            val r = object : Runnable {
                override fun run() {
                    if (isFinishing || isDestroyed || activeDownloadId == -1L) return
                    if (!handleDownloadStatusCheck(dm, activeDownloadId)) {
                        mainHandler.postDelayed(this, 600L)
                    }
                }
            }
            downloadPollRunnable = r
            mainHandler.postDelayed(r, 600L)
        } catch (e: Throwable) {
            activeDownloadId = -1L
            autoTriggeredUpdateTag = ""
            stopDownloadPoll()
            mandatoryUpdateDialog?.dismiss()
            mandatoryUpdateDialog = null
            Toast.makeText(this, "Lỗi tải bản cập nhật: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptInstallApk(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstallApkFile = file
            Toast.makeText(this, "Bật quyền Cài ứng dụng không xác định cho Edge Dock", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}

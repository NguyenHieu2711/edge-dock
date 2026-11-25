package com.nguyenhieu.edgedock

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var rootList: LinearLayout
    private lateinit var masterSwitch: Switch
    private lateinit var statusOverlayBadge: TextView
    private lateinit var statusAccessBadge: TextView
    private lateinit var statusShizukuBadge: TextView
    private lateinit var appsContainer: LinearLayout
    private lateinit var sideLeftBtn: TextView
    private lateinit var sideRightBtn: TextView

    private val shizukuListener: () -> Unit = {
        refreshStatusBadges()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuBridge.init(this)
        ShizukuBridge.addStatusListener(shizukuListener)

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
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroy() {
        ShizukuBridge.removeStatusListener(shizukuListener)
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

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#14161C"))
            isVerticalScrollBarEnabled = false
        }
        rootList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(28f))
        }

        // 1. Minimal Header + Master Switch
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(4f), dp(2f), dp(14f))
        }
        val titleTv = TextView(this).apply {
            text = "Edge Dock"
            setTextColor(Color.parseColor("#F3F5F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(titleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        masterSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, checked ->
                onMasterToggled(checked)
            }
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
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(i)
        }
        addDivider(permCard)
        statusAccessBadge = addStatusRow(permCard, "Quay về app cũ (Accessibility)", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addDivider(permCard)
        statusShizukuBadge = addStatusRow(permCard, "Shizuku (Tắt ngầm & Pop-up)", false) {
            handleShizukuAction()
        }
        rootList.addView(permCard, cardLp())

        // 3. Pinned Apps Card (Core Feature)
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

        val addBtn = TextView(this).apply {
            text = "+ Thêm app"
            setTextColor(Color.parseColor("#061826"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#38BDF8"))
                cornerRadius = dp(99f).toFloat()
            }
            setPadding(dp(13f), dp(6f), dp(13f), dp(6f))
            setOnClickListener { openAppPickerDialog() }
        }
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

        addToggleRow(cfgCard, "Ẩn khỏi Đa nhiệm", DockPrefs.isHideFromRecents(this)) { on ->
            DockPrefs.setHideFromRecents(this, on)
        }
        addDivider(cfgCard)
        addToggleRow(cfgCard, "Mở cửa sổ nổi Pop-up (Shizuku)", DockPrefs.isPopupMode(this)) { on ->
            DockPrefs.setPopupMode(this, on)
            if (on && ShizukuBridge.getStatus(this) != ShizukuBridge.Status.READY) {
                Toast.makeText(this, "Cần bật & cấp quyền Shizuku để mở cửa sổ nổi", Toast.LENGTH_SHORT).show()
            }
        }
        addDivider(cfgCard)

        // Side picker: Left | Right
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

        // Vertical position slider
        addSliderRow(cfgCard, "Vị trí cao / thấp", DockPrefs.getHandleYPct(this), 12, 86) { value ->
            DockPrefs.setHandleYPct(this, value)
            EdgeOverlayService.refresh(this)
        }
        // Opacity slider
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
        ).apply {
            topMargin = dp(topDp)
        }
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
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
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
                Toast.makeText(this, "Shizuku đã kết nối sẵn sàng", Toast.LENGTH_SHORT).show()
            }
            ShizukuBridge.Status.NEED_PERMISSION -> {
                ShizukuBridge.requestPermission()
            }
            ShizukuBridge.Status.NOT_RUNNING -> {
                val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (launch != null) {
                    startActivity(launch)
                } else {
                    Toast.makeText(this, "Mở app Shizuku và bấm Start qua Wireless Debugging", Toast.LENGTH_LONG).show()
                }
            }
            ShizukuBridge.Status.NOT_INSTALLED -> {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"))
                    )
                } catch (_: Throwable) {
                    Toast.makeText(this, "Cài Shizuku từ CH Play để bật tắt chạy ngầm triệt để", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderPinnedAppsList() {
        appsContainer.removeAllViews()
        val apps = DockPrefs.loadApps(this)
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

        val pm = packageManager
        apps.forEachIndexed { idx, entry ->
            if (idx > 0) addDivider(appsContainer)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9f), 0, dp(9f))
            }

            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                try {
                    setImageDrawable(pm.getApplicationIcon(entry.packageName))
                } catch (_: Throwable) {
                    setImageResource(R.drawable.ic_launcher_fg)
                }
            }
            row.addView(
                icon,
                LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { marginEnd = dp(10f) }
            )

            val nameTv = TextView(this).apply {
                text = entry.label
                setTextColor(Color.parseColor("#F1F5F9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(nameTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // Per-app Keep-Alive toggle pill (Chạy ngầm BẬT / TẮT)
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
                    renderPinnedAppsList()
                    EdgeOverlayService.refresh(this@MainActivity)
                }
            }
            row.addView(bgPill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6f)
            })

            // Battery settings shortcut (for keeping app unrestricted on Samsung One UI)
            val batBtn = TextView(this).apply {
                text = "⚡"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(7f), dp(4f), dp(7f), dp(4f))
                setOnClickListener {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${entry.packageName}"))
                        )
                    } catch (_: Throwable) {
                    }
                }
            }
            row.addView(batBtn)

            // Move Up
            if (idx > 0) {
                val upBtn = TextView(this).apply {
                    text = "↑"
                    setTextColor(Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
                    setOnClickListener {
                        val list = DockPrefs.loadApps(this@MainActivity)
                        if (idx in 1 until list.size) {
                            val tmp = list[idx - 1]
                            list[idx - 1] = list[idx]
                            list[idx] = tmp
                            DockPrefs.saveApps(this@MainActivity, list)
                            renderPinnedAppsList()
                            EdgeOverlayService.refresh(this@MainActivity)
                        }
                    }
                }
                row.addView(upBtn)
            }

            // Remove
            val delBtn = TextView(this).apply {
                text = "✕"
                setTextColor(Color.parseColor("#F87171"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(7f), dp(4f), dp(2f), dp(4f))
                setOnClickListener {
                    val list = DockPrefs.loadApps(this@MainActivity).filter { it.packageName != entry.packageName }
                    DockPrefs.saveApps(this@MainActivity, list)
                    renderPinnedAppsList()
                    EdgeOverlayService.refresh(this@MainActivity)
                }
            }
            row.addView(delBtn)

            appsContainer.addView(row)
        }
    }

    private fun openAppPickerDialog() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo?.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                Pair(
                    it.activityInfo.packageName,
                    it.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { it.activityInfo.packageName }
                )
            }
            .sortedBy { it.second.lowercase() }

        val selectedMap = DockPrefs.loadApps(this).associateBy { it.packageName }.toMutableMap()

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

        val listScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
        }
        val listHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, dp(4f))
        }
        listScroll.addView(listHost)
        root.addView(
            listScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360f)).apply {
                topMargin = dp(8f)
            }
        )

        fun renderPicker(query: String) {
            listHost.removeAllViews()
            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) resolved else resolved.filter {
                it.second.lowercase().contains(q) || it.first.lowercase().contains(q)
            }
            for ((pkg, label) in filtered) {
                val isSel = selectedMap.containsKey(pkg)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
                    background = GradientDrawable().apply {
                        setColor(if (isSel) Color.parseColor("#1E2C40") else Color.TRANSPARENT)
                        cornerRadius = dp(9f).toFloat()
                    }
                }
                val iv = ImageView(this).apply {
                    try {
                        setImageDrawable(pm.getApplicationIcon(pkg))
                    } catch (_: Throwable) {
                    }
                }
                row.addView(iv, LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { marginEnd = dp(10f) })

                val tv = TextView(this).apply {
                    text = label
                    setTextColor(Color.parseColor("#F1F5F9"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                val check = TextView(this).apply {
                    text = if (isSel) "✓" else "+"
                    setTextColor(Color.parseColor(if (isSel) "#38BDF8" else "#64748B"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8f), 0, dp(4f), 0)
                }
                row.addView(check)

                row.setOnClickListener {
                    if (selectedMap.containsKey(pkg)) {
                        selectedMap.remove(pkg)
                    } else {
                        selectedMap[pkg] = DockAppEntry(pkg, label, keepAlive = true)
                    }
                    renderPicker(searchBox.text.toString())
                }
                listHost.addView(row)
            }
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderPicker(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderPicker("")

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

        val dlg = AlertDialog.Builder(this)
            .setView(root)
            .create()
        dlg.window?.setBackgroundDrawable(cardBg("#181C25", "#2F384A", 16f))
        cancelBtn.setOnClickListener { dlg.dismiss() }
        saveBtn.setOnClickListener {
            val oldOrder = DockPrefs.loadApps(this)
            val finalList = mutableListOf<DockAppEntry>()
            for (old in oldOrder) {
                selectedMap[old.packageName]?.let { finalList.add(it) }
            }
            val existingPkgs = finalList.map { it.packageName }.toSet()
            for ((pkg, entry) in selectedMap) {
                if (pkg !in existingPkgs) finalList.add(entry)
            }
            DockPrefs.saveApps(this, finalList)
            renderPinnedAppsList()
            EdgeOverlayService.refresh(this)
            dlg.dismiss()
        }
        dlg.show()
    }
}

package com.xiaomi.fixnotification

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.xiaomi.fixnotification.databinding.ActivityThermalMonitorBinding
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class ThermalMonitorActivity : AppCompatActivity(), ThermalDataListener {

    private lateinit var binding: ActivityThermalMonitorBinding
    private var auroraBgAnimator: ValueAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var activeTab = TAB_BATTERY
    private val coreViews = mutableListOf<CoreViewHolder>()

    private val batteryHistoryPoints = mutableListOf<Float>()
    private val batteryLevelHistoryPoints = mutableListOf<Float>()
    private val realtimeFpsPoints = mutableListOf<Float>()
    private val realtimePowerPoints = mutableListOf<Float>()

    private var selectedSessionId: String? = null
    private var selectedChartMode: ThermalChartMode = ThermalChartMode.FPS_POWER
    private val realtimeSamples = mutableListOf<ThermalSample>()
    private var lastObservedRecording: Boolean? = null

    private var isUserScrolling = false
    private val scrollDebounceHandler = Handler(Looper.getMainLooper())
    private val scrollDebounceRunnable = Runnable {
        isUserScrolling = false
        if (activeTab == TAB_FPS && !isFinishing && !isDestroyed) {
            updateSessionChartsAndStats()
        }
    }

    private class CoreSummaryRowHolder(
        val rowView: View,
        val tvTitle: TextView,
        val tvStats: TextView,
        val progressBar: android.widget.ProgressBar
    )
    private val coreSummaryHolders = mutableListOf<CoreSummaryRowHolder>()

    companion object {
        const val TAB_BATTERY = 0
        const val TAB_CPU = 1
        const val TAB_GPU = 2
        const val TAB_FPS = 3
    }

    private class CoreViewHolder(
        val rootView: View,
        val tvTitle: TextView,
        val tvFreq: TextView,
        val chart: ThermalChartView,
        val coreIndex: Int,
        val archName: String,
        val maxFreqMHz: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtils.applySavedTheme(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        binding = ActivityThermalMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auroraBgAnimator = ThemeUtils.attachAuroraBackground(binding.thermalMonitorRoot)

        ViewCompat.setOnApplyWindowInsetsListener(binding.thermalMonitorRoot) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.topThermalHeader.updatePadding(top = (12 * resources.displayMetrics.density).toInt() + statusBars.top)
            binding.scrollThermalContent.updatePadding(bottom = navBars.bottom + (32 * resources.displayMetrics.density).toInt())
            insets
        }

        ThermalSessionManager.init(this)
        initHeaderAndTabs()
        initCharts()
        setupSessionTabsAndControls()
        setupCpuCoreCards()

        binding.btnConnectShizuku.setOnClickListener {
            ViewAnimationExtensions.animateBounce(it)
            if (ShizukuUtils.isShizukuAvailable()) {
                ShizukuUtils.requestShizukuPermission()
            } else {
                try {
                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) {
                        startActivity(intent)
                    } else {
                        Toast.makeText(this, "Vui lòng cài đặt và khởi chạy ứng dụng Shizuku trên máy!", Toast.LENGTH_LONG).show()
                    }
                } catch (_: Throwable) {
                    Toast.makeText(this, "Không thể mở ứng dụng Shizuku", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.scrollThermalContent.setOnScrollChangeListener { _, _, _, _, _ ->
            isUserScrolling = true
            scrollDebounceHandler.removeCallbacks(scrollDebounceRunnable)
            scrollDebounceHandler.postDelayed(scrollDebounceRunnable, 350L)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedTab = intent?.getIntExtra("KEY_ACTIVE_TAB", -1) ?: -1
        if (requestedTab != -1) {
            val tabs = listOf(
                Triple(binding.tabBattery, binding.layoutTabBatteryContent, TAB_BATTERY),
                Triple(binding.tabCpu, binding.layoutTabCpuContent, TAB_CPU),
                Triple(binding.tabGpu, binding.layoutTabGpuContent, TAB_GPU),
                Triple(binding.tabFps, binding.layoutTabFpsContent, TAB_FPS)
            )
            switchTab(requestedTab, tabs, animated = true)
        }
    }

    private fun initHeaderAndTabs() {
        binding.btnBackThermal.setOnClickListener {
            ViewAnimationExtensions.animateBounce(it)
            finish()
        }
        ViewAnimationExtensions.applySpringTouch(binding.btnBackThermal)
        ViewAnimationExtensions.applySpringTouch(binding.cardPopupIconHeader)

        binding.cardPopupIconHeader.setOnClickListener {
            handlePopupOverlayClick()
        }

        val tabs = listOf(
            Triple(binding.tabBattery, binding.layoutTabBatteryContent, TAB_BATTERY),
            Triple(binding.tabCpu, binding.layoutTabCpuContent, TAB_CPU),
            Triple(binding.tabGpu, binding.layoutTabGpuContent, TAB_GPU),
            Triple(binding.tabFps, binding.layoutTabFpsContent, TAB_FPS)
        )

        for ((tabView, _, index) in tabs) {
            ViewAnimationExtensions.applySpringTouch(tabView)
            tabView.setOnClickListener {
                switchTab(index, tabs, animated = true)
            }
        }

        val initialTab = intent?.getIntExtra("KEY_ACTIVE_TAB", TAB_BATTERY) ?: TAB_BATTERY
        binding.scrollThermalTabs.post {
            switchTab(initialTab, tabs, animated = false)
        }
    }

    private fun switchTab(
        selectedIndex: Int,
        tabs: List<Triple<TextView, LinearLayout, Int>>,
        animated: Boolean = true
    ) {
        activeTab = selectedIndex
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val secondaryTextColor = ContextCompat.getColor(this, R.color.text_secondary)

        var selectedTabView: TextView? = null

        for ((tabView, layoutView, index) in tabs) {
            val isSelected = index == selectedIndex
            if (isSelected) selectedTabView = tabView
            tabView.setBackgroundResource(R.drawable.bg_theme_item_inactive)
            tabView.setTextColor(if (isSelected) primaryColor else secondaryTextColor)
            tabView.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            layoutView.visibility = if (isSelected) View.VISIBLE else View.GONE
        }

        when (selectedIndex) {
            TAB_BATTERY -> refreshBatteryCharts()
            TAB_CPU -> refreshCpuCharts()
            TAB_GPU -> refreshGpuCharts()
            TAB_FPS -> updateSessionChartsAndStats()
        }

        selectedTabView?.let { targetView ->
            binding.scrollThermalTabs.post {
                val pill = binding.tabThermalIndicatorPill
                val targetX = targetView.left.toFloat()
                val targetWidth = targetView.width

                val lp = pill.layoutParams
                if (targetWidth > 0 && lp.width != targetWidth) {
                    lp.width = targetWidth
                    pill.layoutParams = lp
                }

                val maxBoundary = maxOf(0f, (binding.layoutThermalTabsContainer.width - targetWidth).toFloat())
                if (!animated || pill.visibility != View.VISIBLE) {
                    pill.visibility = View.VISIBLE
                    pill.x = targetX.coerceIn(0f, maxBoundary)
                    pill.scaleX = 1.0f
                    pill.scaleY = 1.0f
                } else {
                    pill.visibility = View.VISIBLE
                    ViewAnimationExtensions.animateFluidJellyPill(
                        pill,
                        targetX,
                        targetWidth,
                        duration = 240L,
                        maxBoundaryX = maxBoundary
                    )
                }

                val scrollX = (targetView.left - (binding.scrollThermalTabs.width - targetWidth) / 2).coerceAtLeast(0)
                binding.scrollThermalTabs.smoothScrollTo(scrollX, 0)
            }
        }
    }

    private fun handlePopupOverlayClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {

            startFloatingThermalHUD()
        } else {

            showOverlayPermissionRequestDialog()
        }
    }

    private fun startFloatingThermalHUD() {
        try {
            val serviceIntent = Intent(this, FloatingThermalOverlayService::class.java).apply {
                action = FloatingThermalOverlayService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Đã kích hoạt 2 Cửa Sổ Nổi (Nhiệt Độ & FPS/W)!", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Lỗi khi bật cửa sổ nổi: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOverlayPermissionRequestDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_overlay_permission_request, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.attributes?.windowAnimations = R.style.DialogPopAnimation

        val btnClose = dialogView.findViewById<ImageView>(R.id.btnDialogOverlayPermClose)
        val btnShizuku = dialogView.findViewById<View>(R.id.btnGrantOverlayViaShizuku)
        val btnSettings = dialogView.findViewById<View>(R.id.btnOpenSettingsForOverlay)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelOverlayPerm)

        btnClose?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnShizuku?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnSettings?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnCancel?.let { ViewAnimationExtensions.applySpringTouch(it) }

        btnClose?.setOnClickListener { dialog.dismiss() }
        btnCancel?.setOnClickListener { dialog.dismiss() }

        btnShizuku?.setOnClickListener {
            if (!ShizukuUtils.hasShizukuPermission()) {
                Toast.makeText(this, "Chưa cấp quyền Shizuku. Vui lòng cấp quyền trước hoặc dùng Cài đặt hệ thống!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            bgExecutor.execute {
                val r1 = ShizukuUtils.execShizukuCommand("appops set $packageName SYSTEM_ALERT_WINDOW allow")
                val r2 = ShizukuUtils.execShizukuCommand("appops set $packageName 10021 allow")
                val r3 = ShizukuUtils.execShizukuCommand("appops set $packageName 10022 allow")

                mainHandler.post {
                    if (r1.exitCode == 0) {
                        Toast.makeText(this@ThermalMonitorActivity, "Đã tự động cấp quyền Cửa Sổ Nổi thành công qua Shizuku!", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        startFloatingThermalHUD()
                    } else {
                        Toast.makeText(this@ThermalMonitorActivity, "Không thể cấp quyền tự động: ${r1.stderr}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnSettings?.setOnClickListener {
            dialog.dismiss()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (_: Throwable) {
                try {
                    val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        putExtra("extra_pkgname", packageName)
                    }
                    startActivity(intent)
                } catch (_: Throwable) {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    startActivity(intent)
                }
            }
        }

        dialog.show()
    }

    private fun initCharts() {

        binding.chartBatteryRealtime.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 180,
            isCpu = false,
            showLabels = true,
            unit = "°C"
        )

        binding.chartBatteryHistory.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 180,
            isCpu = false,
            showLabels = true,
            unit = "°C"
        )

        binding.chartBatteryLevelHistory.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 180,
            isCpu = false,
            showLabels = true,
            unit = "%"
        )

        binding.chartCpuRealtime.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 180,
            isCpu = true,
            showLabels = true,
            unit = "°C"
        )

        binding.chartGpuTempRealtime.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 180,
            isCpu = false,
            showLabels = true,
            customColor = Color.parseColor("#2979FF"),
            unit = "°C"
        )

        val maxGpu = DeviceInfoUtils.getGpuMaxFrequencyMHz().coerceAtLeast(950)
        binding.chartGpuFreqRealtime.setChartConfig(
            min = 0f,
            max = (maxGpu + 50).toFloat(),
            capacity = 180,
            isCpu = false,
            showLabels = true,
            customColor = Color.parseColor("#00E5FF"),
            unit = "Mhz"
        )

        binding.chartGpuUsageRealtime.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 180,
            isCpu = false,
            showLabels = true,
            customColor = Color.parseColor("#F59E0B"),
            unit = "%"
        )
    }

    private fun setupSessionTabsAndControls() {

        val activeBg = ContextCompat.getColor(this, R.color.primary)
        val inactiveBg = ContextCompat.getColor(this, R.color.card_stroke)
        val activeText = ContextCompat.getColor(this, R.color.on_primary)
        val inactiveText = ContextCompat.getColor(this, R.color.text_primary)

        val updateTabButtonsUI = { mode: ThermalChartMode ->
            selectedChartMode = mode
            val isFps = mode == ThermalChartMode.FPS_POWER
            val isTemp = mode == ThermalChartMode.TEMPERATURE
            val isUsage = mode == ThermalChartMode.USAGE
            val isCores = mode == ThermalChartMode.CORES

            binding.tabMetricFpsPower.backgroundTintList = android.content.res.ColorStateList.valueOf(if (isFps) activeBg else inactiveBg)
            binding.tabMetricFpsPower.setTextColor(if (isFps) activeText else inactiveText)

            binding.tabMetricTemp.backgroundTintList = android.content.res.ColorStateList.valueOf(if (isTemp) activeBg else inactiveBg)
            binding.tabMetricTemp.setTextColor(if (isTemp) activeText else inactiveText)

            binding.tabMetricUsage.backgroundTintList = android.content.res.ColorStateList.valueOf(if (isUsage) activeBg else inactiveBg)
            binding.tabMetricUsage.setTextColor(if (isUsage) activeText else inactiveText)

            binding.tabMetricCores.backgroundTintList = android.content.res.ColorStateList.valueOf(if (isCores) activeBg else inactiveBg)
            binding.tabMetricCores.setTextColor(if (isCores) activeText else inactiveText)

            updateSessionChartsAndStats()
        }

        binding.tabMetricFpsPower.setOnClickListener { updateTabButtonsUI(ThermalChartMode.FPS_POWER) }
        binding.tabMetricTemp.setOnClickListener { updateTabButtonsUI(ThermalChartMode.TEMPERATURE) }
        binding.tabMetricUsage.setOnClickListener { updateTabButtonsUI(ThermalChartMode.USAGE) }
        binding.tabMetricCores.setOnClickListener { updateTabButtonsUI(ThermalChartMode.CORES) }

        updateTabButtonsUI(ThermalChartMode.FPS_POWER)

        binding.btnToggleRecording.setOnClickListener {
            val isRec = ThermalTelemetryHub.toggleRecording(this)
            lastObservedRecording = isRec
            updateRecordingButtonState(isRec)
            if (isRec) {
                selectedSessionId = null
                Toast.makeText(this, "Bắt đầu đo phiên mới", Toast.LENGTH_SHORT).show()
            } else {
                val latest = ThermalSessionManager.getSavedSessions().firstOrNull()
                selectedSessionId = latest?.id
                Toast.makeText(this, "Đã dừng & lưu phiên đo vào danh sách", Toast.LENGTH_SHORT).show()
            }
            renderSessionChips()
            updateSessionChartsAndStats()
            binding.scrollThermalSessions.smoothScrollTo(0, 0)
        }

        binding.btnClearRecordedData.setOnClickListener {
            if (ThermalTelemetryHub.isRecording) {
                ThermalTelemetryHub.toggleRecording(this)
                lastObservedRecording = false
                updateRecordingButtonState(false)
                val latest = ThermalSessionManager.getSavedSessions().firstOrNull()
                selectedSessionId = latest?.id
                Toast.makeText(this, "Đã kết thúc & lưu phiên đo vào danh sách!", Toast.LENGTH_SHORT).show()
                binding.scrollThermalSessions.smoothScrollTo(0, 0)
            } else {
                realtimeSamples.clear()
                Toast.makeText(this, "Đã làm mới dữ liệu biểu đồ trực tiếp", Toast.LENGTH_SHORT).show()
            }
            renderSessionChips()
            updateSessionChartsAndStats()
        }

        binding.btnDeleteAllSessions.setOnClickListener {
            val count = ThermalSessionManager.getSavedSessions().size
            if (count == 0) {
                Toast.makeText(this, "Không có phiên đo nào trong lịch sử", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Xóa lịch sử phiên đo")
                .setMessage("Bạn có chắc chắn muốn xóa toàn bộ $count phiên đo đã lưu không?")
                .setPositiveButton("Xóa tất cả") { _, _ ->
                    ThermalSessionManager.clearAll(this)
                    selectedSessionId = null
                    renderSessionChips()
                    updateSessionChartsAndStats()
                    Toast.makeText(this, "Đã xóa toàn bộ các phiên đo đã lưu", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        renderSessionChips()
    }

    private fun renderSessionChips() {
        val container = binding.layoutThermalSessionChips
        container.removeAllViews()
        val density = resources.displayMetrics.density

        val activeColor = ContextCompat.getColor(this, R.color.primary)
        val inactiveBg = ContextCompat.getColor(this, R.color.card_stroke)
        val inactiveText = ContextCompat.getColor(this, R.color.text_primary)

        val isLiveSelected = selectedSessionId == null
        val liveChip = TextView(this).apply {
            text = if (ThermalTelemetryHub.isRecording) "Đang đo" else "Trực tiếp"
            textSize = 11.5f
            setTypeface(null, if (isLiveSelected) Typeface.BOLD else Typeface.NORMAL)
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            setBackgroundResource(R.drawable.badge_pill_bg)
            if (isLiveSelected) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(activeColor)
                setTextColor(Color.WHITE)
            } else {
                backgroundTintList = android.content.res.ColorStateList.valueOf(inactiveBg)
                setTextColor(inactiveText)
            }
            setOnClickListener {
                selectedSessionId = null
                renderSessionChips()
                updateSessionChartsAndStats()
            }
        }
        val lpLive = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = (8 * density).toInt()
        }
        container.addView(liveChip, lpLive)

        val savedSessions = ThermalSessionManager.getSavedSessions()
        for (session in savedSessions) {
            val isSelected = selectedSessionId == session.id
            val chip = TextView(this).apply {
                text = session.title
                textSize = 11.5f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                setBackgroundResource(R.drawable.badge_pill_bg)
                if (isSelected) {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(activeColor)
                    setTextColor(Color.WHITE)
                } else {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(inactiveBg)
                    setTextColor(inactiveText)
                }
                setOnClickListener {
                    selectedSessionId = session.id
                    renderSessionChips()
                    updateSessionChartsAndStats()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (8 * density).toInt()
            }
            container.addView(chip, lp)
        }
    }

    private fun updateSessionChartsAndStats() {
        val currentSamples: List<ThermalSample>
        if (selectedSessionId == null) {

            val activeRecSession = ThermalSessionManager.getCurrentSession()
            currentSamples = if (activeRecSession != null && activeRecSession.samples.isNotEmpty()) {
                activeRecSession.samples.toList()
            } else {
                realtimeSamples.toList()
            }
            binding.tvActiveSessionInfo.text = if (ThermalTelemetryHub.isRecording) "Đang đo" else "Trực tiếp"
            binding.tvActiveSessionInfo.setTextColor(if (ThermalTelemetryHub.isRecording) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
        } else {

            val session = ThermalSessionManager.getSavedSessions().find { it.id == selectedSessionId }
            currentSamples = session?.samples ?: emptyList()
            binding.tvActiveSessionInfo.text = session?.formattedDate ?: "Đã lưu"
            binding.tvActiveSessionInfo.setTextColor(Color.parseColor("#06B6D4"))
        }

        binding.chartThermalSession.setChartData(currentSamples, selectedChartMode)

        when (selectedChartMode) {
            ThermalChartMode.FPS_POWER -> {
                binding.tvLegendItem1.visibility = View.VISIBLE
                binding.tvLegendItem1.text = "■ FPS"
                binding.tvLegendItem1.setTextColor(Color.parseColor("#C084FC"))

                binding.tvLegendItem2.visibility = View.VISIBLE
                binding.tvLegendItem2.text = "■ Công suất Chip (W)"
                binding.tvLegendItem2.setTextColor(Color.parseColor("#00E5FF"))

                binding.tvLegendItem3.visibility = View.GONE
            }
            ThermalChartMode.TEMPERATURE -> {
                binding.tvLegendItem1.visibility = View.VISIBLE
                binding.tvLegendItem1.text = "■ CPU (°C)"
                binding.tvLegendItem1.setTextColor(Color.parseColor("#EF4444"))

                binding.tvLegendItem2.visibility = View.VISIBLE
                binding.tvLegendItem2.text = "■ GPU (°C)"
                binding.tvLegendItem2.setTextColor(Color.parseColor("#06B6D4"))

                binding.tvLegendItem3.visibility = View.VISIBLE
                binding.tvLegendItem3.text = "■ Pin (°C)"
                binding.tvLegendItem3.setTextColor(Color.parseColor("#F59E0B"))
            }
            ThermalChartMode.USAGE -> {
                binding.tvLegendItem1.visibility = View.VISIBLE
                binding.tvLegendItem1.text = "■ CPU Tải (%)"
                binding.tvLegendItem1.setTextColor(Color.parseColor("#EF4444"))

                binding.tvLegendItem2.visibility = View.VISIBLE
                binding.tvLegendItem2.text = "■ GPU Tải (%)"
                binding.tvLegendItem2.setTextColor(Color.parseColor("#06B6D4"))

                binding.tvLegendItem3.visibility = View.GONE
            }
            ThermalChartMode.CORES -> {
                binding.tvLegendItem1.visibility = View.VISIBLE
                binding.tvLegendItem1.text = "■ C0..C3 (Little)"
                binding.tvLegendItem1.setTextColor(Color.parseColor("#60A5FA"))

                binding.tvLegendItem2.visibility = View.VISIBLE
                binding.tvLegendItem2.text = "■ C4..C6 (Big)"
                binding.tvLegendItem2.setTextColor(Color.parseColor("#A78BFA"))

                binding.tvLegendItem3.visibility = View.VISIBLE
                binding.tvLegendItem3.text = "■ C7 (Prime)"
                binding.tvLegendItem3.setTextColor(Color.parseColor("#FB923C"))
            }
        }

        if (currentSamples.isNotEmpty()) {
            val durationSec = (currentSamples.last().elapsedSec - currentSamples.first().elapsedSec).coerceAtLeast(currentSamples.size.toLong())
            val m = durationSec / 60
            val s = durationSec % 60
            val durStr = if (m > 0) "${m}m ${s}s" else "${s}s"
            binding.tvSessionDurationSamples.text = "$durStr · ${currentSamples.size} mẫu"

            val startBat = currentSamples.first().batPercent
            val endBat = currentSamples.last().batPercent
            val diffBat = endBat - startBat
            val diffStr = if (diffBat <= 0) "$diffBat%" else "+$diffBat%"
            binding.tvSessionBatteryDrain.text = "Mức pin: $startBat% → $endBat% ($diffStr)"

            val maxFpsForStab = currentSamples.maxOf { it.fps }.coerceAtLeast(30f)
            val stableCount = currentSamples.count { it.fps >= (maxFpsForStab * 0.85f) }
            val stabilityPct = ((stableCount.toFloat() / currentSamples.size) * 100).toInt()

            val validFps = currentSamples.map { it.fps }.filter { it > 0f }
            val fps1Low = if (validFps.isNotEmpty()) {
                val sorted = validFps.sorted()
                val count = kotlin.math.round((sorted.size * 0.01).coerceAtLeast(1.0)).toInt()
                sorted.take(count).average().toFloat()
            } else 0f
            binding.tvSessionFpsStability.text = if (fps1Low > 0f) {
                "Độ ổn định: $stabilityPct% · 1% Low: ${String.format(Locale.US, "%.0f", fps1Low)}"
            } else {
                "Độ ổn định: $stabilityPct%"
            }

            val avgFps = currentSamples.map { it.fps }.average().toFloat()
            val minFps = currentSamples.minOf { it.fps }
            val maxFps = currentSamples.maxOf { it.fps }
            binding.tvFpsAvg.text = "${String.format(Locale.US, "%.1f", avgFps)} FPS"
            binding.tvFpsMinMax.text = "${String.format(Locale.US, "%.0f", minFps)} / ${String.format(Locale.US, "%.0f", maxFps)}"

            val totalFrames = currentSamples.sumOf { it.frameCount }
            val totalJank = currentSamples.sumOf { it.jankCount }
            val totalBigJank = currentSamples.sumOf { it.bigJankCount }
            val frameActiveSec = currentSamples.sumOf { it.avgFrameTimeMs.toDouble() * it.frameCount } / 1000.0
            val jankRate = if (frameActiveSec > 0.0) (totalJank * 600.0 / frameActiveSec).toFloat() else 0f
            val avgFrameTime = if (totalFrames > 0) (frameActiveSec * 1000.0 / totalFrames).toFloat() else 0f
            val maxFrameTime = currentSamples.maxOfOrNull { it.maxFrameTimeMs } ?: 0f

            binding.tvSessionJankCount.text = "$totalJank / $totalBigJank"
            binding.tvSessionJankRate.text = String.format(Locale.US, "%.1f", jankRate)
            binding.tvSessionFrameTime.text = if (totalFrames > 0) {
                "${String.format(Locale.US, "%.1f", avgFrameTime)} / ${String.format(Locale.US, "%.1f", maxFrameTime)} ms"
            } else {
                "-- / -- ms"
            }

            val avgPower = currentSamples.map { it.powerWatts }.average().toFloat()
            val maxPower = currentSamples.maxOf { it.powerWatts }
            binding.tvPowerMax.text = "${String.format(Locale.US, "%.1f", avgPower)}W / ${String.format(Locale.US, "%.1f", maxPower)}W"

            val avgCpu = currentSamples.map { it.cpuTempC }.average().toInt()
            val maxCpu = currentSamples.maxOf { it.cpuTempC }.toInt()
            binding.tvSessionMaxCpuTemp.text = "TB $avgCpu°C\nĐỉnh $maxCpu°C"

            val avgGpu = currentSamples.map { it.gpuTempC }.average().toInt()
            val maxGpu = currentSamples.maxOf { it.gpuTempC }.toInt()
            binding.tvSessionMaxGpuTemp.text = "TB $avgGpu°C\nĐỉnh $maxGpu°C"

            val avgBat = currentSamples.map { it.batTempC }.average()
            val maxBat = currentSamples.maxOf { it.batTempC }
            binding.tvSessionMaxBatTemp.text = "TB ${String.format(Locale.US, "%.1f", avgBat)}°C\nĐỉnh ${String.format(Locale.US, "%.1f", maxBat)}°C"

            val avgCpuU = currentSamples.map { it.cpuUsagePercent }.average().toInt()
            val maxCpuU = currentSamples.maxOf { it.cpuUsagePercent }
            binding.tvSessionCpuUsageStats.text = "TB $avgCpuU% · Đỉnh $maxCpuU%"

            val avgGpuU = currentSamples.map { it.gpuUsagePercent }.average().toInt()
            val maxGpuU = currentSamples.maxOf { it.gpuUsagePercent }
            binding.tvSessionGpuUsageStats.text = "TB $avgGpuU% · Đỉnh $maxGpuU%"

            updateSessionCoresSummary(currentSamples)
        } else {
            binding.tvSessionDurationSamples.text = "0s · 0 mẫu"
            binding.tvSessionBatteryDrain.text = "Mức pin: -- → -- (-0%)"
            binding.tvSessionFpsStability.text = "Độ ổn định: --%"
            binding.tvFpsAvg.text = "-- FPS"
            binding.tvFpsMinMax.text = "-- / --"
            binding.tvSessionJankCount.text = "0 / 0"
            binding.tvSessionJankRate.text = "0.0"
            binding.tvSessionFrameTime.text = "-- / -- ms"
            binding.tvPowerMax.text = "-- W"
            binding.tvSessionMaxCpuTemp.text = "--°C"
            binding.tvSessionMaxGpuTemp.text = "--°C"
            binding.tvSessionMaxBatTemp.text = "--°C"
            binding.tvSessionCpuUsageStats.text = "--% / --%"
            binding.tvSessionGpuUsageStats.text = "--% / --%"
            binding.layoutSessionCoresSummary.removeAllViews()
            coreSummaryHolders.clear()
        }
    }

    private fun updateSessionCoresSummary(samples: List<ThermalSample>) {
        if (samples.isEmpty()) return
        val container = binding.layoutSessionCoresSummary
        val density = resources.displayMetrics.density
        val coreCount = samples.first().coreFreqs.size

        if (coreSummaryHolders.size != coreCount) {
            container.removeAllViews()
            coreSummaryHolders.clear()

            for (coreIdx in 0 until coreCount) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                }

                val rowHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val tvTitle = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setTextColor(ContextCompat.getColor(this@ThermalMonitorActivity, R.color.text_secondary))
                    textSize = 11.5f
                }

                val tvStats = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setTextColor(ContextCompat.getColor(this@ThermalMonitorActivity, R.color.text_primary))
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                }

                rowHeader.addView(tvTitle)
                rowHeader.addView(tvStats)

                val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (4.5f * density).toInt()
                    ).apply {
                        topMargin = (3 * density).toInt()
                    }
                    max = 100
                }

                row.addView(rowHeader)
                row.addView(progressBar)
                container.addView(row)

                coreSummaryHolders.add(CoreSummaryRowHolder(row, tvTitle, tvStats, progressBar))
            }
        }

        val coreColors = intArrayOf(
            Color.parseColor("#60A5FA"), Color.parseColor("#34D399"), Color.parseColor("#FBBF24"),
            Color.parseColor("#F87171"), Color.parseColor("#A78BFA"), Color.parseColor("#F472B6"),
            Color.parseColor("#38BDF8"), Color.parseColor("#FB923C")
        )

        for (coreIdx in 0 until coreCount) {
            val holder = coreSummaryHolders.getOrNull(coreIdx) ?: continue
            val freqs = samples.mapNotNull { it.coreFreqs[coreIdx] }
            if (freqs.isEmpty()) continue

            val maxFreqFile = File("/sys/devices/system/cpu/cpu$coreIdx/cpufreq/cpuinfo_max_freq")
            val maxFreqKHz = DeviceInfoUtils.readIntFromFile(maxFreqFile, 0)
            val coreHardwareMaxMHz = if (maxFreqKHz > 0) {
                (maxFreqKHz / 1000)
            } else {
                val scalingMaxFile = File("/sys/devices/system/cpu/cpu$coreIdx/cpufreq/scaling_max_freq")
                val sKHz = DeviceInfoUtils.readIntFromFile(scalingMaxFile, 0)
                if (sKHz > 0) (sKHz / 1000) else (freqs.maxOrNull() ?: 2400)
            }

            val avgFreq = freqs.average().toInt()
            val maxCeil = coreHardwareMaxMHz.coerceAtLeast(1800)
            val avgPct = ((avgFreq.toFloat() / maxCeil) * 100).toInt().coerceIn(0, 100)

            holder.tvTitle.text = "• Nhân $coreIdx:"
            holder.tvStats.text = "TB: $avgPct% (${avgFreq}MHz) · Max: ${coreHardwareMaxMHz}MHz"
            holder.progressBar.progress = avgPct
            holder.progressBar.progressTintList = android.content.res.ColorStateList.valueOf(coreColors[coreIdx % coreColors.size])
        }
    }

    private fun updateRecordingButtonState(isRecording: Boolean) {
        if (isRecording) {
            binding.btnToggleRecording.text = "Dừng đo"
            binding.btnToggleRecording.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4B5563"))
            binding.btnClearRecordedData.text = "Lưu phiên đo"
        } else {
            binding.btnToggleRecording.text = "Bắt đầu đo"
            binding.btnToggleRecording.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444"))
            binding.btnClearRecordedData.text = "Làm mới"
        }
    }

    private fun setupCpuCoreCards() {
        val container = binding.containerCpuCoresGrid
        container.removeAllViews()
        coreViews.clear()

        val mainInfo = DeviceInfoUtils.getMainDeviceInfo(this)
        val socName = mainInfo.socName
        val coreCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

        binding.tvCpuChipName.text = socName
        binding.tvCpuSpeedRange.text = mainInfo.cpuFreqRange
        binding.tvCpuCoreCount.text = "$coreCount"

        val minGpu = DeviceInfoUtils.getGpuMinFrequencyMHz()
        val maxGpu = DeviceInfoUtils.getGpuMaxFrequencyMHz()
        binding.tvGpuClockRange.text = "Tối thiểu: ${minGpu}Mhz - Tối đa: ${maxGpu}Mhz"

        val coreArchMap = resolveCoreArchMap(socName, coreCount)

        var currentRow: LinearLayout? = null
        val density = resources.displayMetrics.density

        for (i in (coreCount - 1) downTo 0) {
            val isEven = (coreCount - 1 - i) % 2 == 0
            if (isEven) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (10 * density).toInt()
                    }
                }
                container.addView(currentRow)
            }

            val itemView = LayoutInflater.from(this).inflate(R.layout.item_cpu_core_card, currentRow, false)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (isEven) {
                    rightMargin = (5 * density).toInt()
                } else {
                    leftMargin = (5 * density).toInt()
                }
            }
            itemView.layoutParams = params

            val tvTitle = itemView.findViewById<TextView>(R.id.tvCoreTitle)
            val tvFreq = itemView.findViewById<TextView>(R.id.tvCoreFreqLive)
            val chart = itemView.findViewById<ThermalChartView>(R.id.chartCoreFreq)

            val arch = coreArchMap[i] ?: "Cortex"
            val titleStr = "Lõi $i($arch)"
            tvTitle.text = titleStr

            val maxFreqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            val maxFreqKHz = DeviceInfoUtils.readIntFromFile(maxFreqFile, 3200000)
            val maxFreqMHz = (maxFreqKHz / 1000).coerceAtLeast(1800)

            chart.setChartConfig(
                min = 0f,
                max = maxFreqMHz.toFloat(),
                capacity = 180,
                isCpu = true,
                showLabels = false,
                unit = "Mhz"
            )

            coreViews.add(
                CoreViewHolder(
                    rootView = itemView,
                    tvTitle = tvTitle,
                    tvFreq = tvFreq,
                    chart = chart,
                    coreIndex = i,
                    archName = arch,
                    maxFreqMHz = maxFreqMHz
                )
            )

            currentRow?.addView(itemView)
        }
    }

    private fun resolveCoreArchMap(socName: String, coreCount: Int): Map<Int, String> {
        val map = mutableMapOf<Int, String>()

        try {
            val cpuInfoFile = File("/proc/cpuinfo")
            if (cpuInfoFile.exists() && cpuInfoFile.canRead()) {
                val lines = cpuInfoFile.readLines()
                var currentProcessor = -1
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("processor", ignoreCase = true)) {
                        val parts = trimmed.split(":")
                        if (parts.size >= 2) {
                            currentProcessor = parts[1].trim().toIntOrNull() ?: -1
                        }
                    } else if (trimmed.startsWith("CPU part", ignoreCase = true) && currentProcessor >= 0) {
                        val parts = trimmed.split(":")
                        if (parts.size >= 2) {
                            val partHex = parts[1].trim().lowercase()
                            val archName = mapArmCpuPartToName(partHex)
                            if (archName.isNotEmpty()) {
                                map[currentProcessor] = archName
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        if (map.size >= coreCount && map.values.none { it.isEmpty() }) {
            return map
        }

        val upperSoc = socName.uppercase()
        when {
            upperSoc.contains("8S GEN 4") || upperSoc.contains("8S ELITE") -> {
                map[7] = "Cortex-X4"; map[6] = "Cortex-A720"; map[5] = "Cortex-A720"
                map[4] = "Cortex-A720"; map[3] = "Cortex-A720"; map[2] = "Cortex-A520"
                map[1] = "Cortex-A520"; map[0] = "Cortex-A520"
            }
            upperSoc.contains("8 GEN 3") || upperSoc.contains("8S GEN 3") -> {
                map[7] = "Cortex-X4"; map[6] = "Cortex-A720"; map[5] = "Cortex-A720"
                map[4] = "Cortex-A720"; map[3] = "Cortex-A720"; map[2] = "Cortex-A720"
                map[1] = "Cortex-A520"; map[0] = "Cortex-A520"
            }
            upperSoc.contains("8 GEN 2") -> {
                map[7] = "Cortex-X3"; map[6] = "Cortex-A715"; map[5] = "Cortex-A715"
                map[4] = "Cortex-A710"; map[3] = "Cortex-A710"; map[2] = "Cortex-A510"
                map[1] = "Cortex-A510"; map[0] = "Cortex-A510"
            }
            upperSoc.contains("8 GEN 1") || upperSoc.contains("8+ GEN 1") -> {
                map[7] = "Cortex-X2"; map[6] = "Cortex-A710"; map[5] = "Cortex-A710"
                map[4] = "Cortex-A710"; map[3] = "Cortex-A510"; map[2] = "Cortex-A510"
                map[1] = "Cortex-A510"; map[0] = "Cortex-A510"
            }
            upperSoc.contains("8 ELITE") || upperSoc.contains("ORYON") -> {
                map[7] = "Oryon Prime"; map[6] = "Oryon Prime"; map[5] = "Oryon Perf"
                map[4] = "Oryon Perf"; map[3] = "Oryon Perf"; map[2] = "Oryon Perf"
                map[1] = "Oryon Perf"; map[0] = "Oryon Perf"
            }
            upperSoc.contains("9400") || upperSoc.contains("9300") -> {
                map[7] = "Cortex-X4"; map[6] = "Cortex-X4"; map[5] = "Cortex-X4"
                map[4] = "Cortex-X4"; map[3] = "Cortex-A720"; map[2] = "Cortex-A720"
                map[1] = "Cortex-A720"; map[0] = "Cortex-A720"
            }
            upperSoc.contains("9200") -> {
                map[7] = "Cortex-X3"; map[6] = "Cortex-A715"; map[5] = "Cortex-A715"
                map[4] = "Cortex-A715"; map[3] = "Cortex-A510"; map[2] = "Cortex-A510"
                map[1] = "Cortex-A510"; map[0] = "Cortex-A510"
            }
            upperSoc.contains("8300") -> {
                map[7] = "Cortex-A715"; map[6] = "Cortex-A715"; map[5] = "Cortex-A715"
                map[4] = "Cortex-A715"; map[3] = "Cortex-A510"; map[2] = "Cortex-A510"
                map[1] = "Cortex-A510"; map[0] = "Cortex-A510"
            }
            upperSoc.contains("8200") || upperSoc.contains("8100") -> {
                map[7] = "Cortex-A78"; map[6] = "Cortex-A78"; map[5] = "Cortex-A78"
                map[4] = "Cortex-A78"; map[3] = "Cortex-A55"; map[2] = "Cortex-A55"
                map[1] = "Cortex-A55"; map[0] = "Cortex-A55"
            }
            upperSoc.contains("888") -> {
                map[7] = "Kryo 680 Prime"; map[6] = "Kryo 680 Gold"; map[5] = "Kryo 680 Gold"
                map[4] = "Kryo 680 Gold"; map[3] = "Kryo 680 Silver"; map[2] = "Kryo 680 Silver"
                map[1] = "Kryo 680 Silver"; map[0] = "Kryo 680 Silver"
            }
            upperSoc.contains("870") || upperSoc.contains("865") -> {
                map[7] = "Kryo 585 Prime"; map[6] = "Kryo 585 Gold"; map[5] = "Kryo 585 Gold"
                map[4] = "Kryo 585 Gold"; map[3] = "Kryo 585 Silver"; map[2] = "Kryo 585 Silver"
                map[1] = "Kryo 585 Silver"; map[0] = "Kryo 585 Silver"
            }
            upperSoc.contains("7+ GEN 2") || upperSoc.contains("7+ GEN 3") -> {
                map[7] = "Cortex-X4"; map[6] = "Cortex-A720"; map[5] = "Cortex-A720"
                map[4] = "Cortex-A720"; map[3] = "Cortex-A520"; map[2] = "Cortex-A520"
                map[1] = "Cortex-A520"; map[0] = "Cortex-A520"
            }
            upperSoc.contains("778G") || upperSoc.contains("780G") -> {
                map[7] = "Kryo 670 Prime"; map[6] = "Kryo 670 Gold"; map[5] = "Kryo 670 Gold"
                map[4] = "Kryo 670 Gold"; map[3] = "Kryo 670 Silver"; map[2] = "Kryo 670 Silver"
                map[1] = "Kryo 670 Silver"; map[0] = "Kryo 670 Silver"
            }
            else -> {
                for (i in 0 until coreCount) {
                    if (map[i].isNullOrEmpty()) {
                        map[i] = if (i >= coreCount - 2) "Cortex-X" else if (i >= coreCount / 2) "Cortex-A7" else "Cortex-A5"
                    }
                }
            }
        }
        return map
    }

    private fun mapArmCpuPartToName(partHex: String): String {
        return when (partHex) {
            "0xd85" -> "Cortex-X925"
            "0xd87" -> "Cortex-A725"
            "0xd80" -> "Cortex-A520"
            "0xd81" -> "Cortex-A720"
            "0xd82" -> "Cortex-X4"
            "0xd4e" -> "Cortex-X3"
            "0xd49" -> "Cortex-A715"
            "0xd48" -> "Cortex-X2"
            "0xd47" -> "Cortex-A710"
            "0xd46" -> "Cortex-A510"
            "0xd44" -> "Cortex-X1"
            "0xd41" -> "Cortex-A78"
            "0xd0d" -> "Cortex-A77"
            "0xd0b" -> "Cortex-A76"
            "0xd0a" -> "Cortex-A75"
            "0xd09" -> "Cortex-A73"
            "0xd08" -> "Cortex-A72"
            "0xd07" -> "Cortex-A57"
            "0xd05" -> "Cortex-A55"
            "0xd03" -> "Cortex-A53"
            "0x001", "0x002" -> "Oryon"
            "0x804", "0x805" -> "Kryo 585"
            "0x802", "0x803" -> "Kryo 485"
            "0x800", "0x801" -> "Kryo 385"
            else -> ""
        }
    }

    override fun onResume() {
        super.onResume()
        auroraBgAnimator = ThemeUtils.applyBackground(binding.thermalMonitorRoot, existingAnimator = auroraBgAnimator)
        ThermalTelemetryHub.register(this, this)
    }

    override fun onPause() {
        super.onPause()
        ThermalTelemetryHub.unregister(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ThermalTelemetryHub.unregister(this)
        try {
            auroraBgAnimator?.cancel()
            auroraBgAnimator = null
        } catch (_: Throwable) {}
        try {
            bgExecutor.shutdown()
        } catch (_: Throwable) {}
    }

    private fun refreshCpuCharts() {
        if (realtimeSamples.isEmpty()) return
        binding.chartCpuRealtime.setDataPoints(realtimeSamples.map { it.cpuTempC })
        for (holder in coreViews) {
            val series = realtimeSamples.map { it.coreFreqs[holder.coreIndex]?.toFloat() ?: 0f }
            holder.chart.setDataPoints(series)
        }
    }

    private fun refreshGpuCharts() {
        if (realtimeSamples.isEmpty()) return
        binding.chartGpuTempRealtime.setDataPoints(realtimeSamples.map { it.gpuTempC })
        binding.chartGpuFreqRealtime.setDataPoints(realtimeSamples.map { it.gpuFreqMHz.toFloat() })
        binding.chartGpuUsageRealtime.setDataPoints(realtimeSamples.map { it.gpuUsagePercent.toFloat() })
    }

    private fun refreshBatteryCharts() {
        if (realtimeSamples.isEmpty()) return
        binding.chartBatteryRealtime.setDataPoints(realtimeSamples.map { it.batTempC })
        binding.chartBatteryHistory.setDataPoints(realtimeSamples.map { it.batTempC })
        binding.chartBatteryLevelHistory.setDataPoints(realtimeSamples.map { it.batPercent.toFloat() })
    }

    override fun onThermalDataUpdate(data: SharedThermalData) {
        if (isFinishing || isDestroyed) return

        binding.batteryGraphicView.setBatteryPercent(data.batPercent, data.isCharging)
        val tempFormatted = String.format(Locale.US, "%.1f", data.batTempC).replace(".", ",")
        binding.tvBatteryTempLive.text = "${tempFormatted}°C"
        binding.tvBatteryHealthStatus.text = data.batHealthStr
        binding.tvBatteryVoltageLive.text = "${String.format(Locale.US, "%.2f", data.batVoltageV)} V · ${data.batCurrentMA} mA"
        binding.tvBatteryChartCurrentTemp.text = "Hiện tại: ${tempFormatted}°C"

        val cpuTempInt = data.cpuTempC.toInt()
        binding.cpuChipGraphicView.setCpuTemperature(cpuTempInt)
        binding.tvCpuChartCurrentTemp.text = "Hiện tại: ${cpuTempInt}°C"
        for (holder in coreViews) {
            val curMHz = data.coreFreqs[holder.coreIndex] ?: 1000
            holder.tvFreq.text = "Xung nhịp: ${curMHz}Mhz"
        }

        val gpuTempInt = data.gpuTempC.toInt()
        binding.gpuChipGraphicView.setGpuData(gpuTempInt, data.gpuUsagePercent)
        binding.tvGpuModelName.text = data.gpuModelName
        binding.tvGpuClockLive.text = "${data.gpuFreqMHz}Mhz"
        binding.tvGpuClockRange.text = "Tối thiểu: ${data.gpuMinFreqMHz}Mhz - Tối đa: ${data.gpuMaxFreqMHz}Mhz"
        binding.tvGpuTempLive.text = "${gpuTempInt}°C"
        binding.tvGpuUsageLive.text = "${data.gpuUsagePercent}%"

        binding.tvGpuChartCurrentTemp.text = "Hiện tại: ${gpuTempInt}°C"
        binding.tvGpuChartCurrentFreq.text = "Hiện tại: ${data.gpuFreqMHz}Mhz (Tối thiểu: ${data.gpuMinFreqMHz}Mhz - Tối đa: ${data.gpuMaxFreqMHz}Mhz)"
        binding.tvGpuChartCurrentUsage.text = "Hiện tại: ${data.gpuUsagePercent}%"

        val hasShizuku = data.isShizukuActive || ShizukuUtils.hasShizukuPermission()
        if (hasShizuku) {
            binding.tvFpsEngineModeBadge.text = "● Shizuku Chuẩn 100%"
            binding.tvFpsEngineModeBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvFpsEngineDescription.text = "Đã kích hoạt đọc tầng đồ họa SurfaceFlinger: bắt trọn từng khung hình Render của Game và drop FPS."
            binding.btnConnectShizuku.visibility = View.GONE
        } else {
            binding.tvFpsEngineModeBadge.text = "○ Chưa cấp Shizuku"
            binding.tvFpsEngineModeBadge.setTextColor(Color.parseColor("#F59E0B"))
            binding.tvFpsEngineDescription.text = "Đang đo tần số quét hiển thị màn hình (Refresh Rate). Hãy cấp quyền Shizuku để đo Render FPS thực tế trong Game!"
            binding.btnConnectShizuku.visibility = View.VISIBLE
        }

        val wasRec = lastObservedRecording ?: data.isRecording
        lastObservedRecording = data.isRecording
        updateRecordingButtonState(data.isRecording)

        if (wasRec && !data.isRecording) {

            val latest = ThermalSessionManager.getSavedSessions().firstOrNull()
            selectedSessionId = latest?.id
            renderSessionChips()
            if (activeTab == TAB_FPS) {
                updateSessionChartsAndStats()
                binding.scrollThermalSessions.smoothScrollTo(0, 0)
            }
        } else if (!wasRec && data.isRecording) {

            selectedSessionId = null
            renderSessionChips()
            if (activeTab == TAB_FPS) {
                updateSessionChartsAndStats()
            }
        }

        val nowSec = (realtimeSamples.size + 1).toLong()
        val curSample = ThermalSample(
            timestampMs = System.currentTimeMillis(),
            elapsedSec = nowSec,
            fps = data.fps,
            powerWatts = data.powerWatts,
            cpuTempC = data.cpuTempC,
            cpuUsagePercent = data.cpuUsagePercent,
            gpuTempC = data.gpuTempC,
            gpuUsagePercent = data.gpuUsagePercent,
            batTempC = data.batTempC,
            batPercent = data.batPercent,
            coreFreqs = HashMap(data.coreFreqs),
            gpuFreqMHz = data.gpuFreqMHz,
            frameCount = data.frameCount,
            jankCount = data.jankCount,
            bigJankCount = data.bigJankCount,
            avgFrameTimeMs = data.avgFrameTimeMs,
            maxFrameTimeMs = data.maxFrameTimeMs
        )
        realtimeSamples.add(curSample)
        if (realtimeSamples.size > 180) {
            realtimeSamples.removeAt(0)
        }

        when (activeTab) {
            TAB_BATTERY -> refreshBatteryCharts()
            TAB_CPU -> refreshCpuCharts()
            TAB_GPU -> refreshGpuCharts()
            TAB_FPS -> {
                if (!isUserScrolling) {
                    updateSessionChartsAndStats()
                }
            }
        }
    }
}

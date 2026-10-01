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

    // Dữ liệu lịch sử tích lũy cho biểu đồ theo bản ghi
    private val batteryHistoryPoints = mutableListOf<Float>()
    private val batteryLevelHistoryPoints = mutableListOf<Float>()

    companion object {
        private const val TAB_BATTERY = 0
        private const val TAB_CPU = 1
        private const val TAB_GPU = 2
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

        // Bật Edge-to-Edge tràn viền toàn màn hình trong suốt tuyệt đối
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        binding = ActivityThermalMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Gắn hiệu ứng chuyển động dải màu Aurora sống động
        auroraBgAnimator = ThemeUtils.attachAuroraBackground(binding.thermalMonitorRoot)

        ViewCompat.setOnApplyWindowInsetsListener(binding.thermalMonitorRoot) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.topThermalHeader.updatePadding(top = (12 * resources.displayMetrics.density).toInt() + statusBars.top)
            binding.scrollThermalContent.updatePadding(bottom = navBars.bottom + (32 * resources.displayMetrics.density).toInt())
            insets
        }

        initHeaderAndTabs()
        initCharts()
        setupCpuCoreCards()
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
            Triple(binding.tabGpu, binding.layoutTabGpuContent, TAB_GPU)
        )

        for ((tabView, _, index) in tabs) {
            ViewAnimationExtensions.applySpringTouch(tabView)
            tabView.setOnClickListener {
                switchTab(index, tabs, animated = true)
            }
        }

        binding.scrollThermalTabs.post {
            switchTab(TAB_BATTERY, tabs, animated = false)
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

        // Animate fluid jelly pill
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
            // Đã có quyền -> Bật ngay Cửa Sổ Nổi (Floating HUD)
            startFloatingThermalHUD()
        } else {
            // Chưa có quyền -> Hiển thị popup hướng dẫn cấp quyền
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
            Toast.makeText(this, "✅ Đã kích hoạt Cửa Sổ Nổi giám sát nhiệt độ!", Toast.LENGTH_SHORT).show()
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
                val r2 = ShizukuUtils.execShizukuCommand("appops set $packageName 10021 allow") // MIUI Background Popup
                val r3 = ShizukuUtils.execShizukuCommand("appops set $packageName 10022 allow") // MIUI Lockscreen

                mainHandler.post {
                    if (r1.exitCode == 0) {
                        Toast.makeText(this@ThermalMonitorActivity, "✅ Đã tự động cấp quyền Cửa Sổ Nổi thành công qua Shizuku!", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        startFloatingThermalHUD()
                    } else {
                        Toast.makeText(this@ThermalMonitorActivity, "⚠️ Không thể cấp quyền tự động: ${r1.stderr}", Toast.LENGTH_SHORT).show()
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
        // Biểu đồ Pin Realtime: 0 - 100°C
        binding.chartBatteryRealtime.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 45,
            isCpu = false,
            showLabels = true
        )

        // Biểu đồ Pin Bản Ghi: 0 - 100°C
        binding.chartBatteryHistory.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 45,
            isCpu = false,
            showLabels = true
        )

        // Biểu đồ Mức Pin: 0 - 100%
        binding.chartBatteryLevelHistory.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 45,
            isCpu = false,
            showLabels = true
        )

        // Biểu đồ CPU Realtime: 0 - 100°C
        binding.chartCpuRealtime.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 45,
            isCpu = true,
            showLabels = true
        )

        // Biểu đồ GPU Nhiệt độ Realtime: 0 - 100°C
        binding.chartGpuTempRealtime.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 45,
            isCpu = false,
            showLabels = true,
            customColor = Color.parseColor("#2979FF")
        )

        // Biểu đồ GPU Xung nhịp Realtime: 0 - 1200MHz
        binding.chartGpuFreqRealtime.setChartConfig(
            min = 0f,
            max = 1200f,
            capacity = 45,
            isCpu = false,
            showLabels = true,
            customColor = Color.parseColor("#00E5FF")
        )

        // Biểu đồ GPU Mức sử dụng Realtime: 0 - 100%
        binding.chartGpuUsageRealtime.setChartConfig(
            min = 0f,
            max = 100f,
            capacity = 45,
            isCpu = false,
            showLabels = true,
            customColor = Color.parseColor("#F59E0B")
        )
    }

    private fun setupCpuCoreCards() {
        val container = binding.containerCpuCoresGrid
        container.removeAllViews()
        coreViews.clear()

        val mainInfo = DeviceInfoUtils.getMainDeviceInfo(this)
        val socName = mainInfo.socName
        val coreCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

        // Đặt thông tin tổng quan CPU
        binding.tvCpuChipName.text = socName
        binding.tvCpuSpeedRange.text = mainInfo.cpuFreqRange
        binding.tvCpuCoreCount.text = "$coreCount"

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
                capacity = 30,
                isCpu = true,
                showLabels = false
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

        // 1. Quét trực tiếp mã định danh phần cứng ARM CPU Part từ /proc/cpuinfo
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

        // 2. Tra cứu cơ sở dữ liệu SoC chi tiết theo từng dòng chip
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

    override fun onThermalDataUpdate(data: SharedThermalData) {
        if (isFinishing || isDestroyed) return

        // 1. Cập nhật Pin
        binding.batteryGraphicView.setBatteryPercent(data.batPercent, data.isCharging)
        val tempFormatted = String.format(Locale.US, "%.1f", data.batTempC).replace(".", ",")
        binding.tvBatteryTempLive.text = "${tempFormatted}°C"
        binding.tvBatteryHealthStatus.text = data.batHealthStr
        binding.tvBatteryVoltageLive.text = "${String.format(Locale.US, "%.2f", data.batVoltageV)} V · ${data.batCurrentMA} mA"
        binding.tvBatteryChartCurrentTemp.text = "Hiện tại: ${tempFormatted}°C"
        binding.chartBatteryRealtime.addDataPoint(data.batTempC)

        // Tích lũy biểu đồ bản ghi
        if (batteryHistoryPoints.size < 40) {
            batteryHistoryPoints.add(data.batTempC)
            batteryLevelHistoryPoints.add(data.batPercent.toFloat())
            binding.chartBatteryHistory.setDataPoints(batteryHistoryPoints)
            binding.chartBatteryLevelHistory.setDataPoints(batteryLevelHistoryPoints)
        } else {
            binding.chartBatteryHistory.addDataPoint(data.batTempC)
            binding.chartBatteryLevelHistory.addDataPoint(data.batPercent.toFloat())
        }

        // 2. Cập nhật CPU
        val cpuTempInt = data.cpuTempC.toInt()
        binding.cpuChipGraphicView.setCpuTemperature(cpuTempInt)
        binding.tvCpuChartCurrentTemp.text = "Hiện tại: ${cpuTempInt}°C"
        binding.chartCpuRealtime.addDataPoint(data.cpuTempC)

        for (holder in coreViews) {
            val curMHz = data.coreFreqs[holder.coreIndex] ?: 1000
            holder.tvFreq.text = "Xung nhịp: ${curMHz}Mhz"
            holder.chart.addDataPoint(curMHz.toFloat())
        }

        // 3. Cập nhật GPU
        val gpuTempInt = data.gpuTempC.toInt()
        binding.gpuChipGraphicView.setGpuData(gpuTempInt, data.gpuUsagePercent)
        binding.tvGpuModelName.text = data.gpuModelName
        binding.tvGpuClockLive.text = "${data.gpuFreqMHz}Mhz"
        binding.tvGpuTempLive.text = "${gpuTempInt}°C"
        binding.tvGpuUsageLive.text = "${data.gpuUsagePercent}%"

        binding.tvGpuChartCurrentTemp.text = "Hiện tại: ${gpuTempInt}°C"
        binding.tvGpuChartCurrentFreq.text = "Hiện tại: ${data.gpuFreqMHz}Mhz"
        binding.tvGpuChartCurrentUsage.text = "Hiện tại: ${data.gpuUsagePercent}%"

        binding.chartGpuTempRealtime.addDataPoint(data.gpuTempC)
        binding.chartGpuFreqRealtime.addDataPoint(data.gpuFreqMHz.toFloat())
        binding.chartGpuUsageRealtime.addDataPoint(data.gpuUsagePercent.toFloat())
    }
}

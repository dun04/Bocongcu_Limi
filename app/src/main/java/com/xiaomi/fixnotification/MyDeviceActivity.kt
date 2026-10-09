package com.xiaomi.fixnotification

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xiaomi.fixnotification.databinding.ActivityMyDeviceBinding
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MyDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyDeviceBinding
    private var auroraBgAnimator: android.animation.ValueAnimator? = null
    private val liveHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var isLiveRunning = false

    private val liveUpdateRunnable = object : Runnable {
        override fun run() {
            if (!isLiveRunning || isFinishing || isDestroyed) return

            val isMainVisible = binding.layoutTabMain.visibility == View.VISIBLE
            val isBatteryVisible = binding.layoutTabBattery.visibility == View.VISIBLE
            val isThermalVisible = binding.layoutTabThermal.visibility == View.VISIBLE

            bgExecutor.execute {
                if (!isLiveRunning) return@execute

                if (isMainVisible) {
                    val cores = DeviceInfoUtils.getLiveCpuCores()
                    val (batTemp, cpuTemp) = DeviceInfoUtils.getLiveOverviewTemperatures(this@MyDeviceActivity)
                    runOnUiThread {
                        if (isLiveRunning && !isFinishing && !isDestroyed) {
                            applyLiveCpuCores(cores)
                            binding.tvMainTemperature.text = "$batTemp Pin · $cpuTemp CPU"
                        }
                    }
                }

                if (isBatteryVisible) {
                    val batteryData = fetchLiveBatteryData()
                    runOnUiThread {
                        if (isLiveRunning && !isFinishing && !isDestroyed) {
                            applyLiveBatteryData(batteryData)
                        }
                    }
                }

                if (isThermalVisible) {
                    val thermalData = fetchLiveThermalData()
                    runOnUiThread {
                        if (isLiveRunning && !isFinishing && !isDestroyed) {
                            applyLiveThermalData(thermalData)
                        }
                    }
                }
            }

            if (isLiveRunning) {
                liveHandler.postDelayed(this, 1200)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtils.applySavedTheme(this)

        // Bật Edge-to-Edge tràn viền toàn màn hình
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        binding = ActivityMyDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Gắn dải màu chuyển động Aurora sống động
        auroraBgAnimator = ThemeUtils.attachAuroraBackground(binding.myDeviceRoot)

        // Tối ưu mượt mà tuyệt đối khi cuộn trang, triệt tiêu drop frame khi chạm mép trên/dưới
        binding.nestedScrollDevice.overScrollMode = View.OVER_SCROLL_NEVER
        binding.scrollDeviceTabs.overScrollMode = View.OVER_SCROLL_NEVER

        ViewCompat.setOnApplyWindowInsetsListener(binding.myDeviceRoot) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            binding.topDeviceHeader.updatePadding(top = (12 * resources.displayMetrics.density).toInt() + statusBarHeight)
            binding.nestedScrollDevice.updatePadding(bottom = (24 * resources.displayMetrics.density).toInt() + navBarHeight)

            insets
        }

        initHeaderAndTabs()
        loadMainHardwareData()
    }

    override fun onResume() {
        super.onResume()
        isLiveRunning = true
        liveHandler.post(liveUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        isLiveRunning = false
        liveHandler.removeCallbacks(liveUpdateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        isLiveRunning = false
        liveHandler.removeCallbacks(liveUpdateRunnable)
        try {
            auroraBgAnimator?.cancel()
            auroraBgAnimator = null
        } catch (_: Throwable) {}
        try {
            bgExecutor.shutdown()
        } catch (_: Throwable) {}
    }

    private fun initHeaderAndTabs() {
        binding.btnBackDevice.setOnClickListener {
            finish()
        }
        ViewAnimationExtensions.applySpringTouch(binding.btnBackDevice)
        ViewAnimationExtensions.applySpringTouch(binding.cardAppIconHeader)

        binding.cardAppIconHeader.setOnClickListener {
            val intent = Intent(this, ThermalMonitorActivity::class.java)
            startActivity(intent)
        }

        // Setup Tab Clicks for 11 tabs
        val tabs = listOf(
            Triple(binding.tabMain, binding.layoutTabMain, 0),
            Triple(binding.tabDevice, binding.layoutTabDevice, 1),
            Triple(binding.tabOS, binding.layoutTabOS, 2),
            Triple(binding.tabSOC, binding.layoutTabSOC, 3),
            Triple(binding.tabStorage, binding.layoutTabStorage, 4),
            Triple(binding.tabCamera, binding.layoutTabCamera, 5),
            Triple(binding.tabBattery, binding.layoutTabBattery, 6),
            Triple(binding.tabThermal, binding.layoutTabThermal, 7),
            Triple(binding.tabDisplay, binding.layoutTabDisplay, 8),
            Triple(binding.tabNetwork, binding.layoutTabNetwork, 9),
            Triple(binding.tabSensors, binding.layoutTabSensors, 10)
        )

        for ((tabView, _, index) in tabs) {
            ViewAnimationExtensions.applySpringTouch(tabView)
            tabView.setOnClickListener {
                switchTab(index, tabs, animated = true)
            }
        }

        // Khởi tạo vị trí viên thuốc (Pill Indicator) trên tabMain
        binding.scrollDeviceTabs.post {
            switchTab(0, tabs, animated = false)
        }
    }

    private fun switchTab(
        selectedIndex: Int,
        tabs: List<Triple<TextView, LinearLayout, Int>>,
        animated: Boolean = true
    ) {
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
            binding.scrollDeviceTabs.post {
                val pill = binding.tabIndicatorPill
                val targetX = targetView.left.toFloat()
                val targetWidth = targetView.width

                val lp = pill.layoutParams
                if (targetWidth > 0 && lp.width != targetWidth) {
                    lp.width = targetWidth
                    pill.layoutParams = lp
                }

                val maxBoundary = maxOf(0f, (binding.layoutDeviceTabsContainer.width - targetWidth).toFloat())
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

                // Smooth scroll để đưa tab đang chọn ra giữa màn hình
                val scrollX = (targetView.left - (binding.scrollDeviceTabs.width - targetWidth) / 2).coerceAtLeast(0)
                binding.scrollDeviceTabs.smoothScrollTo(scrollX, 0)
            }
        }

        // Lazy load detail tabs content
        when (selectedIndex) {
            1 -> populateDeviceDetails()
            2 -> populateOSDetails()
            3 -> populateSOCDetails()
            4 -> populateStorageDetails()
            5 -> populateCameraDetails()
            6 -> populateBatteryDetails()
            7 -> populateThermalDetails()
            8 -> populateDisplayDetails()
            9 -> populateNetworkDetails()
            10 -> populateSensorsDetails()
        }
    }

    private fun loadMainHardwareData() {
        val info = DeviceInfoUtils.getMainDeviceInfo(this)

        // Card Thiết bị
        binding.tvMainBrand.text = info.manufacturer
        binding.tvMainModel.text = info.marketName

        // Card Pin
        binding.tvMainBatteryCap.text = info.batteryCapacityStr
        binding.tvMainBatteryCycle.text = info.batteryCycleStr

        // Card CPU & GPU
        binding.tvMainCpuName.text = info.socName
        binding.tvMainCpuFreqRange.text = info.cpuFreqRange
        binding.tvMainGpuVendor.text = info.gpuVendor
        binding.tvMainGpuModel.text = info.gpuModel

        // Card Màn hình
        binding.tvMainRefreshRate.text = info.refreshRateStr
        binding.tvMainResolution.text = info.resolutionStr
        binding.tvMainScreenSize.text = info.screenSizeStr

        // Card RAM & Lưu trữ
        binding.tvMainRamTotal.text = "Total: ${info.nominalRamGB} GB"
        binding.tvMainRamUsed.text = "Used: ${String.format(Locale.US, "%.2f", info.usedRamGB)} GB"
        val ramPct = if (info.nominalRamGB > 0) ((info.usedRamGB / info.nominalRamGB.toDouble()) * 100).toInt().coerceIn(0, 100) else 60
        binding.pbRamUsage.progress = ramPct
        binding.tvMainRamType.text = "${info.ramFreqStr} ${info.ramTypeStr}"

        binding.tvMainStorageTotal.text = "Total: ${info.nominalStorageGB} GB"
        binding.tvMainStorageUsed.text = "Used: ${String.format(Locale.US, "%.2f", info.usedStorageGB)} GB"
        val storagePct = if (info.nominalStorageGB > 0) ((info.usedStorageGB / info.nominalStorageGB.toDouble()) * 100).toInt().coerceIn(0, 100) else 45
        binding.pbStorageUsage.progress = storagePct
        binding.tvMainStorageType.text = "${info.storageTypeStr} · ROM"

        // Card Máy ảnh
        binding.tvMainRearCamera.text = info.rearCameraStr
        binding.tvMainFrontCamera.text = info.frontCameraStr

        // Card Cảm biến & Nhiệt độ
        binding.tvMainSensors.text = "${info.sensorCount} Cảm biến"
        binding.tvMainTemperature.text = "${info.batteryTempStr} Pin · ${info.cpuTempStr} CPU"

        bgExecutor.execute {
            val cores = DeviceInfoUtils.getLiveCpuCores()
            runOnUiThread {
                if (isLiveRunning && !isFinishing && !isDestroyed) {
                    applyLiveCpuCores(cores)
                }
            }
        }
    }

    private fun applyLiveCpuCores(cores: List<CoreFreqInfo>) {
        val progressBars = listOf(
            binding.pbCore0, binding.pbCore1, binding.pbCore2, binding.pbCore3,
            binding.pbCore4, binding.pbCore5, binding.pbCore6, binding.pbCore7
        )
        val textViews = listOf(
            binding.tvCore0Freq, binding.tvCore1Freq, binding.tvCore2Freq, binding.tvCore3Freq,
            binding.tvCore4Freq, binding.tvCore5Freq, binding.tvCore6Freq, binding.tvCore7Freq
        )

        for (core in cores) {
            val idx = core.coreIndex
            if (idx in 0 until 8) {
                progressBars[idx].progress = core.usagePercent
                textViews[idx].text = "${core.curFreqMHz}MHz"
            }
        }
    }

    private fun populateDeviceDetails() {
        val container = binding.containerDeviceDetails
        if (container.childCount > 0) return
        val sections = DeviceInfoUtils.getDeviceSections(this)
        buildSectionCards(container, sections)
    }

    private fun populateOSDetails() {
        val container = binding.containerOSDetails
        if (container.childCount > 0) return
        val sections = DeviceInfoUtils.getOSSections(this)
        buildSectionCards(container, sections)
    }

    private fun populateSOCDetails() {
        val container = binding.containerSOCDetails
        if (container.childCount > 0) return
        val sections = DeviceInfoUtils.getSOCSections(this)
        buildSectionCards(container, sections) { key, view ->
            ViewAnimationExtensions.animateBounce(view)
            when (key) {
                "Tiện ích mở rộng OpenGL ES" -> {
                    val glInfo = DeviceInfoUtils.getRealGlInfo(this)
                    val extStr = if (glInfo.extensions.isNotBlank()) {
                        glInfo.extensions.split(" ").filter { it.isNotBlank() }.joinToString("\n• ", prefix = "• ")
                    } else {
                        "• GL_OES_EGL_image\n• GL_OES_texture_float\n• GL_EXT_texture_filter_anisotropic\n• GL_KHR_debug\n• GL_EXT_color_buffer_float"
                    }
                    showHyperOSDetailDialog("Tiện ích mở rộng OpenGL ES (${glInfo.renderer})", R.drawable.ic_cpu, extStr, view)
                }
                "Tiện ích mở rộng Vulkan" -> {
                    val vulkanExt = DeviceInfoUtils.getVulkanExtensions(this)
                    showHyperOSDetailDialog("Tiện ích mở rộng Vulkan", R.drawable.ic_cpu, vulkanExt, view)
                }
            }
        }
    }

    private fun populateStorageDetails() {
        val container = binding.containerStorageDetails
        if (container.childCount > 0) return
        val sections = DeviceInfoUtils.getStorageSections(this)
        buildSectionCards(container, sections)
    }

    private fun populateCameraDetails() {
        val container = binding.containerCameraDetails
        if (container.childCount > 0) return
        val sections = DeviceInfoUtils.getCameraSections(this)
        buildSectionCards(container, sections) { key, view ->
            ViewAnimationExtensions.animateBounce(view)
            when (key) {
                "Nhiều camera vật lý" -> showPhysicalCamerasDialog(view)
                "Thêm thông tin camera" -> showFrontCameraDialog(view)
            }
        }
    }

    private fun populateBatteryDetails() {
        val container = binding.containerBatteryDetails
        if (container.childCount > 0) {
            triggerBackgroundBatteryUpdate()
            return
        }
        val sections = DeviceInfoUtils.getBatterySections(this)
        buildSectionCards(container, sections) { key, view ->
            ViewAnimationExtensions.animateBounce(view)
            if (key == "Kiểm Tra Pin") {
                val intent = android.content.Intent(this, BatteryHealthActivity::class.java)
                startActivity(intent)
            }
        }
        triggerBackgroundBatteryUpdate()
    }

    private fun populateThermalDetails() {
        val container = binding.containerThermalDetails
        if (container.childCount > 0) {
            triggerBackgroundThermalUpdate()
            return
        }
        val sections = DeviceInfoUtils.getThermalSections()
        buildSectionCards(container, sections)
        triggerBackgroundThermalUpdate()
    }

    private fun populateDisplayDetails() {
        val container = binding.containerDisplayDetails
        if (container.childCount > 0) return
        val sections = DeviceInfoUtils.getDisplaySections(this)
        buildSectionCards(container, sections)
    }

    private fun populateNetworkDetails() {
        val container = binding.containerNetworkDetails
        if (container.childCount > 0) return
        val sections = DeviceInfoUtils.getNetworkSections(this)
        buildSectionCards(container, sections)
    }

    private fun populateSensorsDetails() {
        val container = binding.containerSensorsDetails
        if (container.childCount > 0) return
        val sections = DeviceInfoUtils.getSensorSections(this)
        buildSectionCards(container, sections) { key, view ->
            ViewAnimationExtensions.animateBounce(view)
            showSensorDetailDialog(key, view)
        }
    }

    private fun showSensorDetailDialog(sensorName: String, anchorView: View? = null) {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val allSensors = sensorManager?.getSensorList(android.hardware.Sensor.TYPE_ALL) ?: emptyList()
        val sensor = allSensors.firstOrNull { it.name.equals(sensorName, ignoreCase = true) }
            ?: allSensors.firstOrNull { it.name.contains(sensorName, ignoreCase = true) || sensorName.contains(it.name, ignoreCase = true) }

        if (sensor != null) {
            val typeStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) sensor.stringType ?: sensor.type.toString() else sensor.type.toString()
            val reportingModeStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                when (sensor.reportingMode) {
                    android.hardware.Sensor.REPORTING_MODE_CONTINUOUS -> "Liên tục (Continuous)"
                    android.hardware.Sensor.REPORTING_MODE_ON_CHANGE -> "Khi có thay đổi (On-Change)"
                    android.hardware.Sensor.REPORTING_MODE_ONE_SHOT -> "Một lần (One-Shot)"
                    android.hardware.Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "Kích hoạt đặc biệt"
                    else -> "Tiêu chuẩn"
                }
            } else "Tiêu chuẩn"

            val isWakeup = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (sensor.isWakeUpSensor) "Có (Đánh thức thiết bị)" else "Không"
            } else "Không"

            val maxDelayStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (sensor.maxDelay > 0) "${sensor.maxDelay} µs" else "Không giới hạn"
            } else "N/A"

            val details = """
                 Thông số kỹ thuật cảm biến:
                • Tên cảm biến: ${sensor.name}
                • Nhà sản xuất (Vendor): ${sensor.vendor}
                • Phiên bản phần cứng: v${sensor.version}
                • Loại cảm biến (Type): $typeStr
                • Mức tiêu thụ điện: ${sensor.power} mA
                • Phạm vi đo tối đa: ${sensor.maximumRange}
                • Độ phân giải (Resolution): ${sensor.resolution}
                • Độ trễ nhỏ nhất: ${sensor.minDelay} µs
                • Độ trễ lớn nhất: $maxDelayStr
                • Cơ chế báo cáo: $reportingModeStr
                • Cảm biến đánh thức (Wake-up): $isWakeup
            """.trimIndent()

            showHyperOSDetailDialog(sensor.name, R.drawable.ic_tab_sensors, details, anchorView)
        } else {
            val details = "Cảm biến: $sensorName\n• Trạng thái: Cảm biến phần cứng/hệ thống đang được kích hoạt và hoạt động trên thiết bị Xiaomi HyperOS."
            showHyperOSDetailDialog(sensorName, R.drawable.ic_tab_sensors, details, anchorView)
        }
    }

    private fun buildSectionCards(
        container: LinearLayout,
        sections: List<InfoSection>,
        onActionClick: ((String, View) -> Unit)? = null
    ) {
        container.removeAllViews()

        val cardBgColor = ContextCompat.getColor(this, R.color.card_bg)
        val cardStrokeColor = ContextCompat.getColor(this, R.color.card_stroke)
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val textPrimaryColor = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondaryColor = ContextCompat.getColor(this, R.color.text_secondary)
        val density = resources.displayMetrics.density

        for (section in sections) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (12 * density).toInt()
                }
                setCardBackgroundColor(cardBgColor)
                radius = 18 * density
                cardElevation = 0f
                strokeColor = cardStrokeColor
                strokeWidth = (1 * density).toInt()
            }

            val innerLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.VERTICAL
                setPadding(
                    (16 * density).toInt(),
                    (16 * density).toInt(),
                    (16 * density).toInt(),
                    (16 * density).toInt()
                )
            }

            if (section.sectionTitle.isNotEmpty()) {
                val tvTitle = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (10 * density).toInt()
                    }
                    text = section.sectionTitle
                    setTextColor(primaryColor)
                    textSize = 15.5f
                    setTypeface(null, Typeface.BOLD)
                }
                innerLayout.addView(tvTitle)
            }

            for ((idx, item) in section.items.withIndex()) {
                val actionBtnText = section.actionItems?.get(item.first)
                val isClickableRow = (item.second.contains("›") || item.second.contains(">") || actionBtnText == "CHI TIẾT") && item.second != "Không Hỗ TrỢ"

                val row = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        if (isClickableRow) (6 * density).toInt() else 0,
                        (9 * density).toInt(),
                        if (isClickableRow) (6 * density).toInt() else 0,
                        (9 * density).toInt()
                    )
                }

                val tvKey = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                    text = item.first
                    setTextColor(textSecondaryColor)
                    textSize = 12.5f
                }
                row.addView(tvKey)

                if (actionBtnText != null && actionBtnText != "CHI TIẾT") {
                    val btnAction = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            (34 * density).toInt()
                        )
                        text = actionBtnText
                        setTextColor(primaryColor)
                        textSize = 11.5f
                        setTypeface(null, Typeface.BOLD)
                        setStrokeColor(ColorStateList.valueOf(primaryColor))
                        strokeWidth = (1 * density).toInt()
                        cornerRadius = (8 * density).toInt()
                        insetTop = 0
                        insetBottom = 0
                        setOnClickListener {
                            onActionClick?.invoke(item.first, this)
                        }
                    }
                    ViewAnimationExtensions.applySpringTouch(btnAction)
                    row.addView(btnAction)
                } else {
                    val tvVal = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f)
                        tag = "val_${item.first}"
                        text = item.second
                        setTextColor(if (isClickableRow) primaryColor else textPrimaryColor)
                        textSize = 13f
                        setTypeface(null, if (isClickableRow) Typeface.BOLD else Typeface.NORMAL)
                        gravity = Gravity.END
                    }
                    row.addView(tvVal)

                    if (isClickableRow) {
                        row.isClickable = true
                        row.isFocusable = true
                        ViewAnimationExtensions.applySpringTouch(row)
                        row.setOnClickListener {
                            onActionClick?.invoke(item.first, row)
                        }
                    }
                }

                innerLayout.addView(row)

                if (idx < section.items.size - 1) {
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (0.8 * density).toInt()
                        )
                        setBackgroundColor(cardStrokeColor)
                    }
                    innerLayout.addView(divider)
                }
            }

            card.addView(innerLayout)
            container.addView(card)
        }
    }

    private fun updateCardRowValue(container: ViewGroup, key: String, newValue: String) {
        val tvVal = container.findViewWithTag<TextView>("val_$key")
        if (tvVal != null && tvVal.text != newValue) {
            tvVal.text = newValue
        }
    }

    private data class BatteryLiveSnapshot(
        val powerStr: String,
        val tempStr: String,
        val statusStr: String,
        val pluggedStr: String
    )

    private fun triggerBackgroundBatteryUpdate() {
        bgExecutor.execute {
            val data = fetchLiveBatteryData()
            runOnUiThread {
                if (isLiveRunning && !isFinishing && !isDestroyed) {
                    applyLiveBatteryData(data)
                }
            }
        }
    }

    private fun triggerBackgroundThermalUpdate() {
        bgExecutor.execute {
            val data = fetchLiveThermalData()
            runOnUiThread {
                if (isLiveRunning && !isFinishing && !isDestroyed) {
                    applyLiveThermalData(data)
                }
            }
        }
    }

    private fun fetchLiveBatteryData(): BatteryLiveSnapshot {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4000) ?: 4000

        val pluggedStr = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Củ sạc AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "Cổng USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Đế sạc không dây"
            else -> "Đang dùng Pin"
        }
        val statusStr = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Đang nạp sạc "
            BatteryManager.BATTERY_STATUS_FULL -> "Pin đầy (100%)"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Đang ngừng sạc"
            else -> "Đang xả"
        }

        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val curMicroA = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        var curMa = if (Math.abs(curMicroA) > 10000) Math.abs(curMicroA) / 1000 else Math.abs(curMicroA)
        if (curMa <= 0) {
            val currentFiles = listOf(
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/bms/current_now",
                "/sys/class/power_supply/main/current_now"
            )
            for (path in currentFiles) {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    try {
                        val raw = f.readText().trim().toLongOrNull() ?: continue
                        val ma = (Math.abs(raw) / if (Math.abs(raw) > 10000) 1000 else 1).toInt()
                        if (ma > 0) {
                            curMa = ma
                            break
                        }
                    } catch (_: Throwable) {}
                }
            }
        }

        val voltageV = voltageMv / 1000.0
        val liveWatts = voltageV * (curMa.toDouble() / 1000.0)
        val powerStr = if (curMa > 0) "${String.format(Locale.US, "%.2f", liveWatts)} W (${String.format(Locale.US, "%.3f", voltageV)}V · ${curMa}mA)" else "${String.format(Locale.US, "%.3f", voltageV)}V"
        val liveTemp = BatteryHealthManager.getLiveBatteryTemperature(this, batteryIntent)
        val tempStr = "${String.format(Locale.US, "%.1f", liveTemp)} °C"

        return BatteryLiveSnapshot(powerStr, tempStr, statusStr, pluggedStr)
    }

    private fun applyLiveBatteryData(data: BatteryLiveSnapshot) {
        val container = binding.containerBatteryDetails
        if (container.childCount == 0 || binding.layoutTabBattery.visibility != View.VISIBLE) return
        updateCardRowValue(container, "Công suất tức thời", data.powerStr)
        updateCardRowValue(container, "Nhiệt độ Pin", data.tempStr)
        updateCardRowValue(container, "Trạng thái hoạt động", data.statusStr)
        updateCardRowValue(container, "Nguồn kết nối", data.pluggedStr)
    }

    private fun fetchLiveThermalData(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val thermalDir = File("/sys/class/thermal")
        val zoneDirs = (0..120).map { File(thermalDir, "thermal_zone$it") }.filter { it.exists() }

        for (zone in zoneDirs) {
            val typeFile = File(zone, "type")
            val tempFile = File(zone, "temp")
            if (typeFile.exists() && tempFile.exists()) {
                val rawType = try { typeFile.readText().trim() } catch (_: Throwable) { ""}
                val rawTemp = DeviceInfoUtils.readIntFromFile(tempFile, 0)
                if (rawType.isNotEmpty() && rawTemp > 0) {
                    val tempC = when {
                        rawTemp > 1000 -> rawTemp / 1000.0
                        rawTemp > 100 -> rawTemp / 10.0
                        else -> rawTemp.toDouble()
                    }
                    if (tempC in 5.0..115.0) {
                        val tempStr = "${String.format(Locale.US, "%.1f", tempC).replace('.', ',')}°C"
                        list.add(Pair(rawType, tempStr))
                    }
                }
            }
        }
        return list
    }

    private fun applyLiveThermalData(data: List<Pair<String, String>>) {
        val container = binding.containerThermalDetails
        if (container.childCount == 0 || binding.layoutTabThermal.visibility != View.VISIBLE) return
        for ((type, temp) in data) {
            updateCardRowValue(container, type, temp)
        }
    }

    private fun showHyperOSDetailDialog(
        title: String,
        iconRes: Int,
        content: String,
        anchorView: View? = null
    ) {
        if (isFinishing || isDestroyed) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_device_info_detail, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        val ivIcon = dialogView.findViewById<android.widget.ImageView>(R.id.ivDetailHeaderIcon)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDetailTitle)
        val tvBody = dialogView.findViewById<TextView>(R.id.tvDetailBody)
        val btnCloseHeader = dialogView.findViewById<android.widget.ImageView>(R.id.btnCloseDetailHeader)
        val btnCopy = dialogView.findViewById<MaterialButton>(R.id.btnCopyDetailContent)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnCloseDetail)

        ivIcon?.setImageResource(iconRes)
        tvTitle?.text = title
        tvBody?.text = content

        btnCloseHeader?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnCopy?.let { ViewAnimationExtensions.applySpringTouch(it) }
        btnClose?.let { ViewAnimationExtensions.applySpringTouch(it) }

        btnCloseHeader?.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }
        btnClose?.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }
        btnCopy?.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText(title, content)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép: $title", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        ViewAnimationExtensions.revealDialog(dialogView, anchorView)
    }

    private fun showFrontCameraDialog(anchorView: View? = null) {
        val details = DeviceInfoUtils.getFrontCameraDetails(this)
        showHyperOSDetailDialog("Thông tin Camera trước", R.drawable.ic_tab_camera, details, anchorView)
    }

    private fun showPhysicalCamerasDialog(anchorView: View? = null) {
        val details = DeviceInfoUtils.getPhysicalCamerasDetails(this)
        showHyperOSDetailDialog("Chi tiết các Camera vật lý", R.drawable.ic_tab_camera, details, anchorView)
    }
}

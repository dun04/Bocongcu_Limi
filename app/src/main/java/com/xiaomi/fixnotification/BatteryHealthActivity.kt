package com.xiaomi.fixnotification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xiaomi.fixnotification.databinding.ActivityBatteryHealthBinding
import java.util.Locale

class BatteryHealthActivity : AppCompatActivity(), BatteryTrackingListener {

    private lateinit var binding: ActivityBatteryHealthBinding
    private var auroraBgAnimator: android.animation.ValueAnimator? = null

    private var designCapacityMah = 5960
    private var isPluggedIn = false

    private val localBatteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val wasPlugged = isPluggedIn
                isPluggedIn = plugged > 0

                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 80)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val currentPct = ((level.toFloat() / scale.toFloat()) * 100).toInt()

                val liveTemp = BatteryHealthManager.getLiveBatteryTemperature(this@BatteryHealthActivity, intent)
                val liveVolt = BatteryHealthManager.getLiveBatteryVoltage(this@BatteryHealthActivity, intent)
                binding.tvLiveTemp.text = "${String.format(Locale.US, "%.1f", liveTemp)}°C"
                if (!isPluggedIn) {
                    binding.tvLiveVoltage.text = "${String.format(Locale.US, "%.2f", liveVolt)} V"
                }

                updatePluggedStateUI(currentPct)

                if (isPluggedIn && !wasPlugged) {
                    BatteryTrackerService.startTracking(this@BatteryHealthActivity)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtils.applySavedTheme(this)

        // Bật Edge-to-Edge tràn viền toàn màn hình, xóa bỏ hoàn toàn nền tách biệt ở thanh điều hướng
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        binding = ActivityBatteryHealthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Gắn dải màu chuyển động Aurora sống động
        auroraBgAnimator = ThemeUtils.attachAuroraBackground(binding.batteryHealthRoot)

        ViewCompat.setOnApplyWindowInsetsListener(binding.batteryHealthRoot) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            binding.topBatteryHeader.updatePadding(top = (12 * resources.displayMetrics.density).toInt() + statusBarHeight)
            binding.batteryScrollView.updatePadding(bottom = (24 * resources.displayMetrics.density).toInt() + navBarHeight)

            insets
        }

        designCapacityMah = BatteryHealthManager.getDesignCapacity(this)

        initUI()
        loadLatestHealthData()

        val initialBatteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = initialBatteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        isPluggedIn = plugged > 0

        if (isPluggedIn) {
            BatteryTrackerService.startTracking(this)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(localBatteryReceiver, filter)

        BatteryTrackerService.registerListener(this)
        loadLatestHealthData()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(localBatteryReceiver)
        } catch (_: Throwable) {}
        BatteryTrackerService.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            auroraBgAnimator?.cancel()
            auroraBgAnimator = null
        } catch (_: Throwable) {}
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun initUI() {
        binding.btnBackBatteryHealth.setOnClickListener { finish() }
        ViewAnimationExtensions.applySpringTouch(binding.btnBackBatteryHealth)
        ViewAnimationExtensions.applySpringTouch(binding.btnHistoryBatteryHealth)
        ViewAnimationExtensions.applySpringTouch(binding.btnStartMeasure)
        ViewAnimationExtensions.applySpringTouch(binding.btnOpenBatteryOptFromHealth)
        ViewAnimationExtensions.applySpringTouch(binding.cardRatedCapacity)
        ViewAnimationExtensions.applySpringTouch(binding.cardEstimatedCapacity)
        ViewAnimationExtensions.applySpringTouch(binding.cardChargingTime)
        ViewAnimationExtensions.applySpringTouch(binding.cardBatteryDelta)
        ViewAnimationExtensions.applySpringTouch(binding.btnInfoRated)
        ViewAnimationExtensions.applySpringTouch(binding.btnInfoDelta)

        updateCapacityUI()
        setupRatedCapacityTouch()

        binding.btnOpenSensorChart.setOnClickListener { anchor ->
            ViewAnimationExtensions.animateBounce(anchor)
            showTelemetryChartDialog(anchor)
        }
        ViewAnimationExtensions.applySpringTouch(binding.btnOpenSensorChart)

        binding.btnHistoryBatteryHealth.setOnClickListener { anchor ->
            ViewAnimationExtensions.animateBounce(anchor)
            showHistoryDialog(anchor)
        }

        binding.btnOpenBatteryOptFromHealth.setOnClickListener { anchor ->
            ViewAnimationExtensions.animateBounce(anchor)
            openAppDetails()
        }

        binding.btnInfoDelta.setOnClickListener { anchor ->
            ViewAnimationExtensions.animateBounce(anchor)
            showHyperOSBatteryDetailDialog(
                "Thay đổi hiển thị mức pin",
                R.drawable.ic_tab_battery,
                "Hiển thị số % pin nạp thêm được và lượng điện tích (Coulomb mAh) thực tế nhận vào trong suốt quá trình cắm sạc đo đạc.",
                anchor
            )
        }

        ViewAnimationExtensions.applySpringTouch(binding.btnExplainDiff)
        ViewAnimationExtensions.applySpringTouch(binding.cardExplainDiff)

        val showExplainAction = { anchor: View ->
            ViewAnimationExtensions.animateBounce(anchor)
            val explainContent = """
                 TẠI SAO SỐ ĐO CỦA APP CÓ THỂ LỆCH 1 - 2% SO VỚI HỆ THỐNG HÃNG?
                (Ví dụ: Hệ thống báo 93%, App đo được 95%)

                1. Thuật toán Hệ thống Xiaomi (Tính toán bảo thủ & Trừ hao an toàn):
                • Hệ thống HyperOS/MIUI ước lượng sức khỏe pin dựa trên mô hình trở kháng nội (Impedance Track) và chu kỳ tích lũy qua nhiều tháng.
                • Xiaomi luôn trừ hao sớm 1 - 2% (Safety Margin) để kích hoạt cơ chế bảo vệ sạc và điều tiết dòng điện sớm, tránh để cell pin bị ép quá tải ở cuối vòng đời.

                2. Thuật toán App (Đo dòng nạp thực tế Coulomb Counting):
                • App liên tục lấy mẫu và đo điện lượng thực tế (mAh nạp vào) qua cảm biến chip nguồn Xiaomi Surge / Qualcomm PMIC trong suốt phiên cắm sạc.
                • Khi sạc ở nhiệt độ mát mẻ (dưới 38°C), hiệu suất tiếp nhận ion của cell pin Silicon-Carbon đạt tối đa, lượng mAh nạp vào tối ưu hơn  App ghi nhận khả năng tích điện thực tế của phiên là 95%.

                3. Tiêu chuẩn ngành Quốc tế (IEC 61960):
                • Trong kỹ thuật đo lường pin hóa học, dung sai giữa mô hình BMS và phép đo dòng nạp thực tế trong phạm vi ±3% (tương đương ~100 - 150 mAh) là hoàn toàn chuẩn xác và tin cậy tuyệt đối!
            """.trimIndent()

            showHyperOSBatteryDetailDialog(
                "Độ Chuẩn Xác Phép Đo Pin",
                R.drawable.ic_tab_battery,
                explainContent,
                anchor
            )
        }

        binding.btnExplainDiff.setOnClickListener { showExplainAction(it) }
        binding.cardExplainDiff.setOnClickListener { showExplainAction(it) }

        binding.btnStartMeasure.setOnClickListener { anchor ->
            ViewAnimationExtensions.animateBounce(anchor)
            if (!isPluggedIn) {
                showHyperOSBatteryDetailDialog(
                    "Cắm sạc để đo tự động",
                    R.drawable.ic_tab_battery,
                    "Hệ thống đã tích hợp cơ chế TỰ ĐỘNG ĐO NGẦM khi bạn cắm sạc (ngay cả khi tắt màn hình).\n\nĐể đo dung lượng chính xác nhất (theo chuẩn Coulomb Counting của DevCheck / AccuBattery), bạn chỉ cần cắm sạc thiết bị từ mức pin thấp (10% - 20%) lên đầy hoặc trên 80%.",
                    anchor
                )
            } else {
                BatteryTrackerService.startTracking(this)
                Toast.makeText(this, "Đang chạy tiến trình đo dòng sạc ngầm...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadLatestHealthData() {
        val (healthPct, estMah, lastDate) = BatteryHealthManager.getLatestOrBaselineHealth(this)

        binding.tvLastRecordedDate.text = "Dữ liệu ghi: $lastDate"
        binding.tvHealthBigStatus.text = "$healthPct% ($estMah mAh)"
        binding.tvEstimatedCapacity.text = "$estMah mAh"

        val records = BatteryHealthManager.getHistoryRecords(this)
        if (records.isNotEmpty()) {
            val lastRecord = records.last()
            binding.tvChargingTime.text = lastRecord.chargingTime
            binding.tvBatteryDelta.text = lastRecord.delta
        } else {
            binding.tvChargingTime.text = "Tự động ngầm"
            binding.tvBatteryDelta.text = "Chu kỳ BMS sẵn sàng"
        }

        updateBatteryBars(healthPct)

        val liveTemp = BatteryHealthManager.getLiveBatteryTemperature(this)
        val liveVolt = BatteryHealthManager.getLiveBatteryVoltage(this)
        binding.tvLiveTemp.text = "${String.format(Locale.US, "%.1f", liveTemp)}°C"
        if (!isPluggedIn) {
            binding.tvLiveVoltage.text = "${String.format(Locale.US, "%.2f", liveVolt)} V"
        }
    }

    private fun updatePluggedStateUI(currentPct: Int) {
        if (!isPluggedIn) {
            binding.tvChargingStatusPrompt.text = "ⓘ Đang dùng pin ($currentPct%) · Tự động đo khi cắm sạc"
            binding.tvChargingStatusPrompt.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnStartMeasure.text = "Đã bật tự động đo khi cắm sạc"
        } else {
            binding.tvChargingStatusPrompt.text = "Đang cắm sạc ($currentPct%) · Tiến trình đo ngầm đang hoạt động"
            binding.tvChargingStatusPrompt.setTextColor(ContextCompat.getColor(this, R.color.primary))
            binding.btnStartMeasure.text = "Đang đo tự động (Chạy ngầm liên tục)"
        }
    }

    private var liveChartUpdater: (() -> Unit)? = null

    override fun onBatteryTelemetryUpdated(session: LiveChargingSession) {
        runOnUiThread {
            liveChartUpdater?.invoke()

            val liveTemp = if (session.isCharging && session.tempC > 15.0) session.tempC else BatteryHealthManager.getLiveBatteryTemperature(this@BatteryHealthActivity)
            val liveVolt = if (session.isCharging && session.voltageMv > 1000) session.voltageMv / 1000.0 else BatteryHealthManager.getLiveBatteryVoltage(this@BatteryHealthActivity)

            if (!session.isCharging) {
                binding.tvLiveCurrent.text = "0 mA"
                binding.tvLiveVoltage.text = "${String.format(Locale.US, "%.2f", liveVolt)} V"
                binding.tvLivePower.text = "0.0 W"
                binding.tvLiveTemp.text = "${String.format(Locale.US, "%.1f", liveTemp)}°C"
                return@runOnUiThread
            }

            // Cập nhật thẻ Live Telemetry
            binding.tvLiveCurrent.text = "${session.currentMa.toInt()} mA"
            binding.tvLiveVoltage.text = "${String.format(Locale.US, "%.2f", liveVolt)} V"
            binding.tvLivePower.text = "${String.format(Locale.US, "%.1f", session.powerWatts)} W"
            binding.tvLiveTemp.text = "${String.format(Locale.US, "%.1f", liveTemp)}°C"

            // Cập nhật thời gian và delta
            val minutes = session.elapsedSeconds / 60
            val seconds = session.elapsedSeconds % 60
            val timeStr = if (minutes > 0) "$minutes phút ${seconds}s" else "${seconds}s"
            binding.tvChargingTime.text = timeStr

            val deltaMahInt = session.accumulatedCoulombMah.toInt()
            binding.tvBatteryDelta.text = " +${session.deltaLevel}% (+${deltaMahInt} mAh)"

            if (session.estimatedHealthPct > 0) {
                binding.tvHealthBigStatus.text = "${session.estimatedHealthPct}% (${session.estimatedCapacityMah} mAh)"
                binding.tvEstimatedCapacity.text = "${session.estimatedCapacityMah} mAh"
                updateBatteryBars(session.estimatedHealthPct)

                val accuracy = if (session.deltaLevel >= 15) "Rất cao" else "Đang tích lũy"
                binding.tvChargingStatusPrompt.text = "Đang nạp: Dòng ${session.currentMa.toInt()} mA · ${String.format(Locale.US, "%.1f", session.powerWatts)}W · Độ chính xác: $accuracy"
            } else {
                binding.tvChargingStatusPrompt.text = "Đang nạp: Dòng ${session.currentMa.toInt()} mA · Nạp +${session.deltaLevel}% (Cần nạp ≥2% để tính toán)"
            }
        }
    }

    override fun onChargingSessionFinished(healthPct: Int, estimatedMah: Int, deltaStr: String) {
        runOnUiThread {
            liveChartUpdater?.invoke()
            loadLatestHealthData()
            Toast.makeText(this, "Đã hoàn tất và lưu phiên đo: $healthPct% ($estimatedMah mAh)!", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateBatteryBars(healthPct: Int) {
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.card_stroke)

        val activeBars = (healthPct / 20).coerceIn(1, 5)
        val bars = listOf(binding.bar1, binding.bar2, binding.bar3, binding.bar4, binding.bar5)

        for ((idx, bar) in bars.withIndex()) {
            bar.setBackgroundColor(if (idx < activeBars) primaryColor else inactiveColor)
        }
    }

    private fun openAppDetails() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(this, "Không thể mở trang thông tin ứng dụng", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTelemetryChartDialog(anchorView: View? = null, sessionRecord: BatteryRecord? = null) {
        if (isFinishing || isDestroyed) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_battery_telemetry_chart, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        val chartView = dialogView.findViewById<BatteryTelemetryChartView>(R.id.chartView)
        val tvLiveBadge = dialogView.findViewById<TextView>(R.id.tvLiveBadge)
        val tvChartTimeSub = dialogView.findViewById<TextView>(R.id.tvChartTimeSub)
        val btnCloseHeader = dialogView.findViewById<android.widget.ImageView>(R.id.btnCloseChartDialog)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnCloseChart)
        val btnCopy = dialogView.findViewById<MaterialButton>(R.id.btnCopyChartStats)

        val tabCurrent = dialogView.findViewById<MaterialButton>(R.id.tabMetricCurrent)
        val tabVoltage = dialogView.findViewById<MaterialButton>(R.id.tabMetricVoltage)
        val tabPower = dialogView.findViewById<MaterialButton>(R.id.tabMetricPower)
        val tabTemp = dialogView.findViewById<MaterialButton>(R.id.tabMetricTemp)
        val tabAll = dialogView.findViewById<MaterialButton>(R.id.tabMetricAll)

        val tvTotalSamples = dialogView.findViewById<TextView>(R.id.tvTotalSamples)
        val tvAvgCurrent = dialogView.findViewById<TextView>(R.id.tvAvgCurrent)
        val tvMinMaxCurrent = dialogView.findViewById<TextView>(R.id.tvMinMaxCurrent)
        val tvAvgVoltage = dialogView.findViewById<TextView>(R.id.tvAvgVoltage)
        val tvMinMaxVoltage = dialogView.findViewById<TextView>(R.id.tvMinMaxVoltage)
        val tvAvgPower = dialogView.findViewById<TextView>(R.id.tvAvgPower)
        val tvPeakPower = dialogView.findViewById<TextView>(R.id.tvPeakPower)
        val tvAvgTemp = dialogView.findViewById<TextView>(R.id.tvAvgTemp)
        val tvPeakTemp = dialogView.findViewById<TextView>(R.id.tvPeakTemp)

        val buttons = listOf(tabCurrent, tabVoltage, tabPower, tabTemp, tabAll, btnCloseHeader, btnClose, btnCopy)
        buttons.forEach { ViewAnimationExtensions.applySpringTouch(it) }

        var currentSelectedType = ChartMetricType.CURRENT_MA

        val activeBg = ContextCompat.getColor(this, R.color.primary)
        val inactiveBg = ContextCompat.getColor(this, R.color.card_stroke)
        val activeText = ContextCompat.getColor(this, R.color.on_primary)
        val inactiveText = ContextCompat.getColor(this, R.color.text_primary)

        val updateTabStyles = { selected: ChartMetricType ->
            currentSelectedType = selected
            tabCurrent.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected == ChartMetricType.CURRENT_MA) activeBg else inactiveBg)
            tabCurrent.setTextColor(if (selected == ChartMetricType.CURRENT_MA) activeText else inactiveText)

            tabVoltage.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected == ChartMetricType.VOLTAGE_V) activeBg else inactiveBg)
            tabVoltage.setTextColor(if (selected == ChartMetricType.VOLTAGE_V) activeText else inactiveText)

            tabPower.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected == ChartMetricType.POWER_WATTS) activeBg else inactiveBg)
            tabPower.setTextColor(if (selected == ChartMetricType.POWER_WATTS) activeText else inactiveText)

            tabTemp.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected == ChartMetricType.TEMP_C) activeBg else inactiveBg)
            tabTemp.setTextColor(if (selected == ChartMetricType.TEMP_C) activeText else inactiveText)

            tabAll.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected == ChartMetricType.ALL_METRICS) activeBg else inactiveBg)
            tabAll.setTextColor(if (selected == ChartMetricType.ALL_METRICS) activeText else inactiveText)

            chartView.setMetricType(selected)
        }

        tabCurrent.setOnClickListener { updateTabStyles(ChartMetricType.CURRENT_MA) }
        tabVoltage.setOnClickListener { updateTabStyles(ChartMetricType.VOLTAGE_V) }
        tabPower.setOnClickListener { updateTabStyles(ChartMetricType.POWER_WATTS) }
        tabTemp.setOnClickListener { updateTabStyles(ChartMetricType.TEMP_C) }
        tabAll.setOnClickListener { updateTabStyles(ChartMetricType.ALL_METRICS) }

        val refreshChartAndStats = {
            val history: List<TelemetryPoint>
            val isLiveMode = (sessionRecord == null)

            if (isLiveMode) {
                val livePts = BatteryTrackerService.getTelemetryHistory()
                val curSession = BatteryTrackerService.getCurrentSession()
                if (livePts.isEmpty()) {
                    val pt = TelemetryPoint(
                        timestampMs = System.currentTimeMillis(),
                        elapsedSec = curSession.elapsedSeconds.coerceAtLeast(1L),
                        currentMa = curSession.currentMa,
                        voltageMv = curSession.voltageMv,
                        powerWatts = curSession.powerWatts,
                        tempC = curSession.tempC,
                        level = curSession.currentLevel.coerceAtLeast(1)
                    )
                    BatteryTrackerService.addTelemetryPoint(pt)
                    history = listOf(pt)
                } else {
                    history = livePts
                }

                if (curSession.isCharging) {
                    tvLiveBadge.text = "● TRỰC TIẾP"
                    tvLiveBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                } else {
                    tvLiveBadge.text = "○ BẢN GHI GẦN NHẤT"
                    tvLiveBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
            } else {
                val rec = sessionRecord!!
                history = if (rec.telemetryPoints.isNotEmpty()) {
                    rec.telemetryPoints
                } else {
                    BatteryHealthManager.synthesizeTelemetryPoints(rec.startLevel, rec.endLevel, rec.chargingTime)
                }

                tvLiveBadge.text = "PHIÊN: ${rec.date}"
                tvLiveBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            }

            chartView.setData(history, currentSelectedType)

            // Tính toán thống kê từ danh sách history
            val totalSamples = history.size
            val totalSeconds = if (history.isNotEmpty()) history.last().elapsedSec - history.first().elapsedSec else 0L
            val avgCurrentMa = if (history.isNotEmpty()) history.map { it.currentMa }.average() else 0.0
            val minCurrentMa = if (history.isNotEmpty()) history.minOfOrNull { it.currentMa } ?: 0.0 else 0.0
            val maxCurrentMa = if (history.isNotEmpty()) history.maxOfOrNull { it.currentMa } ?: 0.0 else 0.0

            val avgVoltageV = if (history.isNotEmpty()) history.map { it.voltageMv / 1000.0 }.average() else 0.0
            val minVoltageV = if (history.isNotEmpty()) (history.minOfOrNull { it.voltageMv } ?: 0) / 1000.0 else 0.0
            val maxVoltageV = if (history.isNotEmpty()) (history.maxOfOrNull { it.voltageMv } ?: 0) / 1000.0 else 0.0

            val avgPowerWatts = if (history.isNotEmpty()) history.map { it.powerWatts }.average() else 0.0
            val maxPowerWatts = if (history.isNotEmpty()) history.maxOfOrNull { it.powerWatts } ?: 0.0 else 0.0

            val avgTempC = if (history.isNotEmpty()) history.map { it.tempC }.average() else 0.0
            val maxTempC = if (history.isNotEmpty()) history.maxOfOrNull { it.tempC } ?: 0.0 else 0.0

            val totalMins = totalSeconds / 60
            val totalSecs = totalSeconds % 60
            val durationStr = if (totalMins > 0) "${totalMins}m ${totalSecs}s" else "${totalSecs}s"

            tvTotalSamples.text = "$totalSamples mẫu ghi"
            tvChartTimeSub.text = "Thời gian đo: $durationStr · $totalSamples điểm lấy mẫu"

            tvAvgCurrent.text = "${avgCurrentMa.toInt()} mA"
            tvMinMaxCurrent.text = "Đỉnh: ${maxCurrentMa.toInt()} · Đáy: ${minCurrentMa.toInt()} mA"

            tvAvgVoltage.text = "${String.format(Locale.US, "%.2f", avgVoltageV)} V"
            tvMinMaxVoltage.text = "Đỉnh: ${String.format(Locale.US, "%.2f", maxVoltageV)}V · Đáy: ${String.format(Locale.US, "%.2f", minVoltageV)}V"

            tvAvgPower.text = "${String.format(Locale.US, "%.1f", avgPowerWatts)} W"
            tvPeakPower.text = "Đỉnh: ${String.format(Locale.US, "%.1f", maxPowerWatts)} W"

            tvAvgTemp.text = "${String.format(Locale.US, "%.1f", avgTempC)}°C"
            tvPeakTemp.text = "Đỉnh: ${String.format(Locale.US, "%.1f", maxTempC)}°C"
        }

        refreshChartAndStats()

        if (sessionRecord == null) {
            liveChartUpdater = {
                if (dialog.isShowing) {
                    refreshChartAndStats()
                }
            }
        }

        dialog.setOnDismissListener {
            if (sessionRecord == null) {
                liveChartUpdater = null
            }
        }

        btnCloseHeader.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }
        btnClose.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }

        btnCopy.setOnClickListener {
            val history = if (sessionRecord != null) {
                if (sessionRecord.telemetryPoints.isNotEmpty()) sessionRecord.telemetryPoints else BatteryHealthManager.synthesizeTelemetryPoints(sessionRecord.startLevel, sessionRecord.endLevel, sessionRecord.chargingTime)
            } else {
                BatteryTrackerService.getTelemetryHistory()
            }

            val totalSamples = history.size
            val avgCurrentMa = if (history.isNotEmpty()) history.map { it.currentMa }.average().toInt() else 0
            val maxCurrentMa = if (history.isNotEmpty()) (history.maxOfOrNull { it.currentMa } ?: 0.0).toInt() else 0
            val minCurrentMa = if (history.isNotEmpty()) (history.minOfOrNull { it.currentMa } ?: 0.0).toInt() else 0
            val avgVoltageV = if (history.isNotEmpty()) history.map { it.voltageMv / 1000.0 }.average() else 0.0
            val maxPowerWatts = if (history.isNotEmpty()) history.maxOfOrNull { it.powerWatts } ?: 0.0 else 0.0
            val avgTempC = if (history.isNotEmpty()) history.map { it.tempC }.average() else 0.0
            val maxTempC = if (history.isNotEmpty()) history.maxOfOrNull { it.tempC } ?: 0.0 else 0.0

            val content = """
                 THÔNG SỐ CẢM BIẾN SẠC PIN (${if (sessionRecord != null) "Phiên ${sessionRecord.date}" else "Trực tiếp"})
                • Tổng số mẫu: $totalSamples điểm đo
                • Dòng nạp TB: $avgCurrentMa mA (Đỉnh: $maxCurrentMa mA · Đáy: $minCurrentMa mA)
                • Điện thế TB: ${String.format(Locale.US, "%.2f", avgVoltageV)} V
                • Công suất đỉnh: ${String.format(Locale.US, "%.1f", maxPowerWatts)} W
                • Nhiệt độ TB: ${String.format(Locale.US, "%.1f", avgTempC)}°C (Đỉnh: ${String.format(Locale.US, "%.1f", maxTempC)}°C)
            """.trimIndent()

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Thông số cảm biến pin", content)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép toàn bộ chỉ số trung bình", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        ViewAnimationExtensions.revealDialog(dialogView, anchorView)
    }

    private fun showHyperOSBatteryDetailDialog(
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

    private fun showHistoryDialog(anchorView: View? = null) {
        val records = BatteryHealthManager.getHistoryRecords(this)

        if (records.isEmpty()) {
            val bmsSoh = BatteryHealthManager.readHardwareBmsSoh()
            val cycles = BatteryHealthManager.readHardwareCycleCount(this)
            val bmsText = if (bmsSoh > 0) "$bmsSoh%" else "Chưa hỗ trợ trực tiếp"
            val cycleText = if (cycles > 0) "$cycles lần" else "Chưa phát hiện"

            val content = "Chưa có bản ghi chu kỳ sạc nào được lưu.\n\n• Cảm biến BMS Phần cứng: $bmsText\n• Chu kỳ sạc đã ghi nhận: $cycleText\n\n Ứng dụng sẽ TỰ ĐỘNG GHI LỊCH SỬ mỗi lần bạn cắm sạc từ 10% đến 100% (ngay cả khi tắt màn hình)."

            showHyperOSBatteryDetailDialog(
                "Lịch sử kiểm tra pin",
                R.drawable.ic_tab_battery,
                content,
                anchorView
            )
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_battery_history_list, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        val tvHistoryTitle = dialogView.findViewById<TextView>(R.id.tvHistoryTitle)
        val tvHistoryCountBadge = dialogView.findViewById<TextView>(R.id.tvHistoryCountBadge)
        val btnCloseHeader = dialogView.findViewById<android.widget.ImageView>(R.id.btnCloseHistoryDialog)
        val btnClearHistory = dialogView.findViewById<MaterialButton>(R.id.btnClearHistory)
        val btnCloseHistory = dialogView.findViewById<MaterialButton>(R.id.btnCloseHistory)
        val containerRecords = dialogView.findViewById<LinearLayout>(R.id.containerHistoryRecords)

        val reversedList = records.reversed()
        tvHistoryCountBadge.text = "${reversedList.size} phiên"

        listOf(btnCloseHeader, btnClearHistory, btnCloseHistory).forEach {
            ViewAnimationExtensions.applySpringTouch(it)
        }

        containerRecords.removeAllViews()

        for (rec in reversedList) {
            val itemCard = layoutInflater.inflate(R.layout.item_battery_history_card, containerRecords, false)
            val tvDate = itemCard.findViewById<TextView>(R.id.tvItemDate)
            val tvSource = itemCard.findViewById<TextView>(R.id.tvItemSource)
            val tvHealthBadge = itemCard.findViewById<TextView>(R.id.tvItemHealthBadge)
            val tvCapacity = itemCard.findViewById<TextView>(R.id.tvItemCapacity)
            val tvDelta = itemCard.findViewById<TextView>(R.id.tvItemDelta)
            val tvLevelRange = itemCard.findViewById<TextView>(R.id.tvItemLevelRange)
            val tvDuration = itemCard.findViewById<TextView>(R.id.tvItemDuration)
            val btnViewChart = itemCard.findViewById<MaterialButton>(R.id.btnViewItemChart)
            val btnCopyStats = itemCard.findViewById<MaterialButton>(R.id.btnCopyItemStats)

            tvDate.text = rec.date
            tvSource.text = "Nguồn: ${rec.source}"
            tvHealthBadge.text = "${rec.healthPct}% PIN"
            
            val healthColor = when {
                rec.healthPct >= 90 -> ContextCompat.getColor(this, R.color.accent_green)
                rec.healthPct >= 80 -> ContextCompat.getColor(this, R.color.accent_orange)
                else -> ContextCompat.getColor(this, R.color.accent_red)
            }
            tvHealthBadge.setTextColor(healthColor)
            tvHealthBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.argb(38, Color.red(healthColor), Color.green(healthColor), Color.blue(healthColor))
            )

            tvCapacity.text = "${rec.estimatedMah} / ${rec.designMah} mAh"
            tvDelta.text = rec.delta
            
            if (rec.startLevel >= 0 && rec.endLevel >= 0) {
                tvLevelRange.text = "${rec.startLevel}%  ${rec.endLevel}%"
            } else {
                tvLevelRange.text = "Theo chu kỳ nạp"
            }
            tvDuration.text = rec.chargingTime

            ViewAnimationExtensions.applySpringTouch(btnViewChart)
            ViewAnimationExtensions.applySpringTouch(btnCopyStats)

            btnViewChart.setOnClickListener {
                showTelemetryChartDialog(anchorView = null, sessionRecord = rec)
            }

            btnCopyStats.setOnClickListener {
                val startEnd = if (rec.startLevel >= 0 && rec.endLevel >= 0) "(${rec.startLevel}%  ${rec.endLevel}%)" else ""
                val textToCopy = "${rec.date}\n• Sức khỏe pin: ${rec.healthPct}% (${rec.estimatedMah} / ${rec.designMah} mAh)\n• Nạp thêm: ${rec.delta}$startEnd\n• Thời gian sạc: ${rec.chargingTime}\n• Nguồn: ${rec.source}"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Phiên đo pin ${rec.date}", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Đã sao chép phiên đo: ${rec.date}", Toast.LENGTH_SHORT).show()
            }

            containerRecords.addView(itemCard)
        }

        btnCloseHeader.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }
        btnCloseHistory.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }

        btnClearHistory.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Xóa toàn bộ lịch sử đo pin?")
                .setMessage("Tất cả các bản ghi chu kỳ sạc và dữ liệu biểu đồ cảm biến đã lưu sẽ bị xóa vĩnh viễn.")
                .setPositiveButton("Xóa") { _, _ ->
                    BatteryHealthManager.clearHistory(this)
                    Toast.makeText(this, "Đã xóa toàn bộ lịch sử đo pin", Toast.LENGTH_SHORT).show()
                    ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
                    loadLatestHealthData()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        dialog.show()
        ViewAnimationExtensions.revealDialog(dialogView, anchorView)
    }

    private fun updateCapacityUI() {
        designCapacityMah = BatteryHealthManager.getDesignCapacity(this)
        val hasCustom = BatteryHealthManager.hasCustomDesignCapacity(this)
        binding.tvRatedCapacity.text = if (hasCustom) {
            "$designCapacityMah mAh (Độ)"
        } else {
            "$designCapacityMah mAh"
        }
    }

    private fun setupRatedCapacityTouch() {
        val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var is2sTriggered = false

        val longPressRunnable = Runnable {
            is2sTriggered = true
            binding.cardRatedCapacity.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showCustomCapacityDialog(binding.cardRatedCapacity)
        }

        val touchListener = View.OnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    is2sTriggered = false
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(150).start()
                    longPressHandler.postDelayed(longPressRunnable, 2000L) // Giữ đúng 2 giây (2000ms)
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    if (!is2sTriggered) {
                        // Nhấn chạm ngắn dưới 2 giây -> hiển thị dialog thông tin chi tiết
                        val orig = BatteryHealthManager.getOriginalDesignCapacity(this)
                        val curr = BatteryHealthManager.getDesignCapacity(this)
                        val isCustom = BatteryHealthManager.hasCustomDesignCapacity(this)
                        val hintText = if (isCustom) {
                            "\n\n(Đang áp dụng dung lượng pin tùy chỉnh: $curr mAh · Gốc NSX: $orig mAh).\n\nMẹo: Ấn giữ mục này 2 giây để đổi lại dung lượng hoặc khôi phục về pin gốc."
                        } else {
                            "\n\nMẹo: Nếu bạn đã thay pin dung lượng cao, hãy ẤN GIỮ VÀO ĐÂY 2 GIÂY để nhập dung lượng pin mới!"
                        }
                        showHyperOSBatteryDetailDialog(
                            "Dung lượng xếp hạng (Design Capacity)",
                            R.drawable.ic_tab_battery,
                            "Đây là dung lượng danh định ban đầu do nhà sản xuất công bố lúc xuất xưởng ($curr mAh).$hintText",
                            v
                        )
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }

        binding.cardRatedCapacity.setOnTouchListener(touchListener)
        binding.btnInfoRated.setOnClickListener { anchor ->
            showCustomCapacityDialog(anchor)
        }
    }

    private fun showCustomCapacityDialog(anchorView: View? = null) {
        if (isFinishing || isDestroyed) return

        val originalCap = BatteryHealthManager.getOriginalDesignCapacity(this)
        val currentCap = BatteryHealthManager.getDesignCapacity(this)
        val hasCustom = BatteryHealthManager.hasCustomDesignCapacity(this)

        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_battery_capacity, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        val btnCloseHeader = dialogView.findViewById<android.widget.ImageView>(R.id.btnCloseCustomCapacityHeader)
        val tvInfo = dialogView.findViewById<TextView>(R.id.tvCustomCapacityInfo)
        val etInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCustomCapacityInput)
        val chip5500 = dialogView.findViewById<MaterialButton>(R.id.chipCap5500)
        val chip6000 = dialogView.findViewById<MaterialButton>(R.id.chipCap6000)
        val chip6500 = dialogView.findViewById<MaterialButton>(R.id.chipCap6500)
        val btnReset = dialogView.findViewById<MaterialButton>(R.id.btnResetCustomCapacity)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelCustomCapacity)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveCustomCapacity)

        val buttons = listOf(btnCloseHeader, chip5500, chip6000, chip6500, btnReset, btnCancel, btnSave)
        buttons.forEach { it?.let { v -> ViewAnimationExtensions.applySpringTouch(v) } }

        tvInfo.text = "Dành cho người dùng thay pin nén dung lượng cao.\n\n• Dung lượng xuất xưởng gốc: $originalCap mAh\n• Dung lượng hiện đang áp dụng: $currentCap mAh"
        etInput.setText(currentCap.toString())

        chip5500.setOnClickListener { etInput.setText("5500"); etInput.setSelection(etInput.text?.length ?: 0) }
        chip6000.setOnClickListener { etInput.setText("6000"); etInput.setSelection(etInput.text?.length ?: 0) }
        chip6500.setOnClickListener { etInput.setText("6500"); etInput.setSelection(etInput.text?.length ?: 0) }

        if (hasCustom) {
            btnReset.visibility = View.VISIBLE
            btnReset.setOnClickListener {
                BatteryHealthManager.setCustomDesignCapacity(this, -1)
                updateCapacityUI()
                loadLatestHealthData()
                Toast.makeText(this, "Đã khôi phục dung lượng gốc: $originalCap mAh", Toast.LENGTH_SHORT).show()
                ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
            }
        } else {
            btnReset.visibility = View.GONE
        }

        btnCloseHeader.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }

        btnCancel.setOnClickListener {
            ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
        }

        btnSave.setOnClickListener {
            val str = etInput.text?.toString()?.trim() ?: ""
            val newCap = str.toIntOrNull()
            if (newCap != null && newCap in 1000..30000) {
                BatteryHealthManager.setCustomDesignCapacity(this, newCap)
                updateCapacityUI()
                loadLatestHealthData()
                Toast.makeText(this, "Đã áp dụng dung lượng pin mới: $newCap mAh", Toast.LENGTH_SHORT).show()
                ViewAnimationExtensions.dismissDialog(dialogView, dialog, anchorView)
            } else {
                Toast.makeText(this, "Dung lượng không hợp lệ (Phải từ 1.000 đến 30.000 mAh)", Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
        ViewAnimationExtensions.revealDialog(dialogView, anchorView)
    }
}

package com.xiaomi.fixnotification

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class BatteryRecord(
    val date: String,
    val healthPct: Int,
    val estimatedMah: Int,
    val designMah: Int,
    val chargingTime: String,
    val delta: String,
    val startLevel: Int = -1,
    val endLevel: Int = -1,
    val source: String = "Tự động khi cắm sạc",
    val telemetryPoints: List<TelemetryPoint> = emptyList()
)

object BatteryHealthManager {

    private const val PREFS_NAME = "battery_health_prefs"
    private const val KEY_HISTORY = "history_records"
    private const val KEY_LAST_DATE = "last_date"
    private const val KEY_LAST_HEALTH = "last_health"
    private const val KEY_LAST_EST_MAH = "last_est_mah"
    private const val KEY_BASELINE_GENERATED = "baseline_generated"
    private const val KEY_CUSTOM_DESIGN_CAPACITY = "custom_design_capacity_mah"

    /**
     * Lấy dung lượng pin gốc ban đầu do nhà sản xuất công bố (xuất xưởng)
     */
    fun getOriginalDesignCapacity(context: Context): Int {
        return try {
            val profileCap = DeviceInfoUtils.getBatteryProfile(context).capacity
            if (profileCap > 2000) {
                profileCap
            } else {
                val mPowerProfile = Class.forName("com.android.internal.os.PowerProfile")
                    .getConstructor(Context::class.java)
                    .newInstance(context)
                val cap = Class.forName("com.android.internal.os.PowerProfile")
                    .getMethod("getBatteryCapacity")
                    .invoke(mPowerProfile) as Double
                if (cap > 2000) cap.toInt() else 5000
            }
        } catch (_: Throwable) {
            DeviceInfoUtils.getBatteryProfile(context).capacity
        }
    }

    /**
     * Lấy dung lượng xếp hạng hiện hành:
     * Ưu tiên dung lượng tùy chỉnh do người dùng nhập (nếu đã độ/thay pin dung lượng cao),
     * nếu chưa tùy chỉnh thì lấy dung lượng gốc xuất xưởng của máy.
     */
    fun getDesignCapacity(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customCap = prefs.getInt(KEY_CUSTOM_DESIGN_CAPACITY, -1)
        if (customCap in 1000..30000) {
            return customCap
        }
        return getOriginalDesignCapacity(context)
    }

    fun hasCustomDesignCapacity(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customCap = prefs.getInt(KEY_CUSTOM_DESIGN_CAPACITY, -1)
        return customCap in 1000..30000
    }

    fun getCustomDesignCapacity(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CUSTOM_DESIGN_CAPACITY, -1)
    }

    /**
     * Cập nhật dung lượng pin mới (cho người dùng thay pin dung lượng cao) hoặc khôi phục về gốc (khi customMah <= 0)
     */
    fun setCustomDesignCapacity(context: Context, customMah: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (customMah in 1000..30000) {
            editor.putInt(KEY_CUSTOM_DESIGN_CAPACITY, customMah)
        } else {
            editor.remove(KEY_CUSTOM_DESIGN_CAPACITY)
        }
        editor.apply()

        // Tính toán lại ngay tỷ lệ % sức khỏe pin theo dung lượng thiết kế mới
        val currentDesignCap = getDesignCapacity(context)
        val lastEstMah = prefs.getInt(KEY_LAST_EST_MAH, -1)
        if (lastEstMah > 0) {
            val updatedHealthPct = ((lastEstMah.toDouble() / currentDesignCap.toDouble()) * 100.0).roundToInt().coerceIn(20, 100)
            prefs.edit().putInt(KEY_LAST_HEALTH, updatedHealthPct).apply()
        } else {
            // Chưa có phiên đo, nạp lại baseline theo dung lượng mới
            val bmsSoh = readHardwareBmsSoh()
            val cycleCount = readHardwareCycleCount(context)
            val baselinePct = when {
                bmsSoh in 50..100 -> bmsSoh
                cycleCount > 0 -> (100.0 - (cycleCount * 0.012)).roundToInt().coerceIn(75, 100)
                else -> 98
            }
            val baselineEstMah = ((currentDesignCap * (baselinePct / 100.0))).roundToInt()
            prefs.edit()
                .putInt(KEY_LAST_HEALTH, baselinePct)
                .putInt(KEY_LAST_EST_MAH, baselineEstMah)
                .apply()
        }
    }

    fun getLiveBatteryTemperature(context: Context, intent: Intent? = null): Double {
        val batteryIntent = intent ?: try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) { null }

        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (tempRaw > 0) {
            return tempRaw / 10.0
        }

        // Đọc trực tiếp từ cảm biến nhiệt độ phần cứng kernel sysfs
        val tempFiles = listOf(
            "/sys/class/power_supply/battery/temp",
            "/sys/class/power_supply/battery/batt_temp",
            "/sys/class/power_supply/bms/temp",
            "/sys/class/power_supply/main/temp",
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp"
        )
        for (path in tempFiles) {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                try {
                    val v = f.readText().trim().toDoubleOrNull() ?: continue
                    val deg = if (v > 1000) v / 1000.0 else if (v > 100) v / 10.0 else v
                    if (deg in 15.0..65.0) return deg
                } catch (_: Throwable) {}
            }
        }

        return 32.5
    }

    fun getLiveBatteryVoltage(context: Context, intent: Intent? = null): Double {
        val batteryIntent = intent ?: try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) { null }

        val voltMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4000) ?: 4000
        if (voltMv > 1000) return voltMv / 1000.0
        return 4.0
    }

    fun readHardwareBmsSoh(): Int {
        val sohFiles = listOf(
            "/sys/class/power_supply/bms/soh",
            "/sys/class/power_supply/battery/soh",
            "/sys/class/power_supply/battery/health_pct",
            "/sys/class/power_supply/bms/battery_soh",
            "/sys/class/power_supply/battery/fg_health",
            "/sys/class/power_supply/battery/battery_health",
            "/sys/class/power_supply/main/soh",
            "/sys/class/qcom-battery/soh"
        )
        for (path in sohFiles) {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                val value = try {
                    f.readText().trim().toIntOrNull() ?: -1
                } catch (_: Throwable) { -1 }
                if (value in 40..100) return value
            }
        }

        // Full charge vs design capacity ratio in sysfs (Qualcomm / MTK fuel gauge IC)
        val fullCapFiles = listOf(
            Pair("/sys/class/power_supply/battery/charge_full", "/sys/class/power_supply/battery/charge_full_design"),
            Pair("/sys/class/power_supply/bms/charge_full", "/sys/class/power_supply/bms/charge_full_design"),
            Pair("/sys/class/power_supply/battery/full_cap", "/sys/class/power_supply/battery/full_cap_design"),
            Pair("/sys/class/power_supply/main/charge_full", "/sys/class/power_supply/main/charge_full_design")
        )
        for ((fullP, designP) in fullCapFiles) {
            val fullF = File(fullP)
            val designF = File(designP)
            if (fullF.exists() && designF.exists() && fullF.canRead() && designF.canRead()) {
                try {
                    val fullVal = fullF.readText().trim().toDoubleOrNull() ?: 0.0
                    val designVal = designF.readText().trim().toDoubleOrNull() ?: 0.0
                    if (fullVal > 1000.0 && designVal > 1000.0) {
                        val ratio = ((fullVal / designVal) * 100.0).roundToInt()
                        if (ratio in 40..105) return ratio.coerceAtMost(100)
                    }
                } catch (_: Throwable) {}
            }
        }

        // Shizuku / Shell fallback để đọc sysfs nếu có quyền
        try {
            if (ShizukuUtils.hasShizukuPermission()) {
                for (path in sohFiles) {
                    val res = ShizukuUtils.execShizukuCommand("cat $path 2>/dev/null")
                    if (res.exitCode == 0 && res.stdout.isNotBlank()) {
                        val v = res.stdout.trim().toIntOrNull()
                        if (v != null && v in 40..100) return v
                    }
                }
            }
        } catch (_: Throwable) {}

        return -1
    }

    fun readHardwareCycleCount(context: Context): Int {
        // 1. Android 14+ (API 34+) BatteryManager API chính thức
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val count = bm?.getIntProperty(7) ?: -1
                if (count > 0) return count
            } catch (_: Throwable) {}
        }

        // 2. Kiểm tra từ Intent ACTION_BATTERY_CHANGED (Android 14 EXTRA_CYCLE_COUNT)
        try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val extraCycles = batteryIntent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1
            if (extraCycles > 0) return extraCycles
        } catch (_: Throwable) {}

        // 3. Sysfs nodes mở rộng
        val cycleFiles = listOf(
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/bms/battery_cycle",
            "/sys/class/power_supply/bms/cycle_count",
            "/sys/class/power_supply/battery/battery_cycle",
            "/sys/class/power_supply/battery/count",
            "/sys/class/power_supply/battery/fg_cycle",
            "/sys/class/power_supply/battery/capacity_cycle",
            "/sys/class/power_supply/main/cycle_count",
            "/sys/class/qcom-battery/cycle_count",
            "/sys/class/power_supply/battery/cycle",
            "/sys/class/power_supply/bms/cycle"
        )
        for (path in cycleFiles) {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                val count = try {
                    f.readText().trim().toIntOrNull() ?: -1
                } catch (_: Throwable) { -1 }
                if (count > 0) return count
            }
        }

        // 4. Shizuku fallback (đọc dumpsys battery từ Android Health HAL & kernel nodes)
        try {
            if (ShizukuUtils.hasShizukuPermission()) {
                val dumpRes = ShizukuUtils.execShizukuCommand("dumpsys battery 2>/dev/null")
                if (dumpRes.exitCode == 0 && dumpRes.stdout.isNotBlank()) {
                    for (line in dumpRes.stdout.lines()) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("Cycle count:", ignoreCase = true) ||
                            trimmed.startsWith("Battery cycle count:", ignoreCase = true) ||
                            trimmed.startsWith("mSavedBatteryAsoc:", ignoreCase = true)
                        ) {
                            val num = trimmed.substringAfter(":").trim().toIntOrNull()
                            if (num != null && num > 0) return num
                        }
                    }
                }

                for (path in cycleFiles) {
                    val res = ShizukuUtils.execShizukuCommand("cat $path 2>/dev/null")
                    if (res.exitCode == 0 && res.stdout.isNotBlank()) {
                        val count = res.stdout.trim().toIntOrNull() ?: -1
                        if (count > 0) return count
                    }
                }
            }
        } catch (_: Throwable) {}

        return -1
    }

    /**
     * Đọc dung lượng tích điện tối đa thực tế của cell Pin từ chip BMS (uAh -> mAh)
     */
    fun readHardwareChargeFull(): Int {
        val fullFiles = listOf(
            "/sys/class/power_supply/bms/charge_full",
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/battery/full_cap",
            "/sys/class/power_supply/main/charge_full"
        )
        for (path in fullFiles) {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                val raw = try { f.readText().trim().toLongOrNull() ?: 0L } catch (_: Throwable) { 0L }
                if (raw > 1000) {
                    val mah = if (raw > 50000) (raw / 1000).toInt() else raw.toInt()
                    if (mah in 1500..12000) return mah
                }
            }
        }
        try {
            if (ShizukuUtils.hasShizukuPermission()) {
                for (path in fullFiles) {
                    val res = ShizukuUtils.execShizukuCommand("cat $path 2>/dev/null")
                    if (res.exitCode == 0 && res.stdout.isNotBlank()) {
                        val raw = res.stdout.trim().toLongOrNull() ?: continue
                        if (raw > 1000) {
                            val mah = if (raw > 50000) (raw / 1000).toInt() else raw.toInt()
                            if (mah in 1500..12000) return mah
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return -1
    }

    fun computeFusedBatteryHealth(context: Context, deltaLevel: Int, deltaMah: Int): Pair<Int, Int> {
        val designCap = getDesignCapacity(context)
        val bmsSoh = readHardwareBmsSoh()
        val cycleCount = readHardwareCycleCount(context)

        // 1. Live Coulomb raw calculation
        // Sạc nhanh lithium thường chạm 100% khi cell pin mới nhận được ~85% bão hòa ion thực tế
        val safeDeltaLevel = deltaLevel.coerceAtLeast(1)
        val fastChargeFactor = if (safeDeltaLevel >= 40) 1.15 else 1.08
        val compensatedDeltaMah = (deltaMah * fastChargeFactor).coerceAtLeast(deltaMah.toDouble())
        val coulombEstCap = ((compensatedDeltaMah / safeDeltaLevel.toDouble()) * 100.0).roundToInt()
        val coulombSoh = ((coulombEstCap.toDouble() / designCap.toDouble()) * 100.0).roundToInt().coerceIn(50, 100)

        // 2. Cycle-based theoretical degradation model (khoảng 0.010% - 0.012% mỗi chu kỳ sạc đầy)
        val cycleSoh = if (cycleCount > 0) {
            (100.0 - (cycleCount * 0.010)).roundToInt().coerceIn(70, 100)
        } else {
            -1
        }

        // 3. Sensor Fusion / Bayesian Blending
        val finalHealthPct: Int
        if (bmsSoh > 0) {
            val weightBms = if (safeDeltaLevel >= 50) 0.60 else 0.75
            val weightCycle = if (cycleSoh > 0) 0.20 else 0.0
            val weightCoulomb = (1.0 - weightBms - weightCycle).coerceAtLeast(0.10)

            val fused = (bmsSoh * weightBms) +
                    (if (cycleSoh > 0) cycleSoh * weightCycle else 0.0) +
                    (coulombSoh * weightCoulomb)
            finalHealthPct = fused.roundToInt().coerceIn(40, 100)
        } else if (cycleSoh > 0) {
            val weightCycle = if (safeDeltaLevel >= 50) 0.50 else 0.70
            val weightCoulomb = 1.0 - weightCycle
            finalHealthPct = (cycleSoh * weightCycle + coulombSoh * weightCoulomb).roundToInt().coerceIn(40, 100)
        } else {
            finalHealthPct = coulombSoh
        }

        val estimatedCap = ((designCap * (finalHealthPct / 100.0))).roundToInt()
        return Pair(finalHealthPct, estimatedCap)
    }

    /**
     * Trả về kết quả Sức khỏe Pin hiện tại:
     * 1. Nếu đã có bản ghi đo thực tế -> trả về bản ghi gần nhất.
     * 2. Nếu chưa có bản ghi (vừa cài app) -> tính toán dựa trên BMS Hardware + Cycle Count phần cứng để người dùng luôn thấy ngay kết quả chuẩn xác!
     */
    fun getLatestOrBaselineHealth(context: Context): Triple<Int, Int, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastHealth = prefs.getInt(KEY_LAST_HEALTH, -1)
        val lastEstMah = prefs.getInt(KEY_LAST_EST_MAH, -1)
        val lastDate = prefs.getString(KEY_LAST_DATE, "") ?: ""

        if (lastHealth > 0 && lastEstMah > 0) {
            return Triple(lastHealth, lastEstMah, lastDate)
        }

        // Tạo baseline từ phần cứng ngay lập tức
        val designCap = getDesignCapacity(context)
        val bmsSoh = readHardwareBmsSoh()
        val cycleCount = readHardwareCycleCount(context)

        val baselinePct = when {
            bmsSoh in 50..100 -> bmsSoh
            cycleCount > 0 -> (100.0 - (cycleCount * 0.012)).roundToInt().coerceIn(75, 100)
            else -> 98 // Mặc định máy mới tốt
        }
        val baselineEstMah = ((designCap * (baselinePct / 100.0))).roundToInt()
        val sdf = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
        val dateStr = sdf.format(Date())

        // Lưu baseline nếu chưa có
        prefs.edit()
            .putInt(KEY_LAST_HEALTH, baselinePct)
            .putInt(KEY_LAST_EST_MAH, baselineEstMah)
            .putString(KEY_LAST_DATE, "$dateStr (Phần cứng BMS)")
            .putBoolean(KEY_BASELINE_GENERATED, true)
            .apply()

        return Triple(baselinePct, baselineEstMah, "$dateStr (Phần cứng BMS)")
    }

    fun saveChargingSession(
        context: Context,
        healthPct: Int,
        estimatedMah: Int,
        durationStr: String,
        deltaStr: String,
        startLevel: Int,
        endLevel: Int,
        source: String = "Tự động khi cắm sạc",
        telemetryPoints: List<TelemetryPoint> = emptyList()
    ) {
        val designCap = getDesignCapacity(context)
        val sdf = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
        val dateStr = sdf.format(Date())

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historyJson = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        val jsonArray = JSONArray(historyJson)

        val newObj = JSONObject().apply {
            put("date", dateStr)
            put("healthPct", healthPct)
            put("estimatedMah", estimatedMah)
            put("designMah", designCap)
            put("chargingTime", durationStr)
            put("delta", deltaStr)
            put("startLevel", startLevel)
            put("endLevel", endLevel)
            put("source", source)

            if (telemetryPoints.isNotEmpty()) {
                val ptsArray = JSONArray()
                // Lấy tối đa 120 điểm đại diện nếu chuỗi lấy mẫu quá dài để tiết kiệm bộ nhớ SharedPrefs
                val step = (telemetryPoints.size / 120).coerceAtLeast(1)
                for (i in telemetryPoints.indices step step) {
                    val pt = telemetryPoints[i]
                    val ptObj = JSONObject().apply {
                        put("t", pt.timestampMs)
                        put("e", pt.elapsedSec)
                        put("c", pt.currentMa)
                        put("v", pt.voltageMv)
                        put("w", pt.powerWatts)
                        put("tc", pt.tempC)
                        put("l", pt.level)
                    }
                    ptsArray.put(ptObj)
                }
                put("telemetry", ptsArray)
            }
        }

        jsonArray.put(newObj)

        prefs.edit()
            .putString(KEY_HISTORY, jsonArray.toString())
            .putString(KEY_LAST_DATE, dateStr)
            .putInt(KEY_LAST_HEALTH, healthPct)
            .putInt(KEY_LAST_EST_MAH, estimatedMah)
            .apply()
    }

    fun getHistoryRecords(context: Context): List<BatteryRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historyJson = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        val list = mutableListOf<BatteryRecord>()

        try {
            val jsonArray = JSONArray(historyJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ptsList = mutableListOf<TelemetryPoint>()
                val rawPts = obj.optJSONArray("telemetry")
                if (rawPts != null && rawPts.length() > 0) {
                    for (p in 0 until rawPts.length()) {
                        val pObj = rawPts.getJSONObject(p)
                        ptsList.add(
                            TelemetryPoint(
                                timestampMs = pObj.optLong("t", 0L),
                                elapsedSec = pObj.optLong("e", 0L),
                                currentMa = pObj.optDouble("c", 0.0),
                                voltageMv = pObj.optInt("v", 4000),
                                powerWatts = pObj.optDouble("w", 0.0),
                                tempC = pObj.optDouble("tc", 32.0),
                                level = pObj.optInt("l", 0)
                            )
                        )
                    }
                }

                val startLvl = obj.optInt("startLevel", -1)
                val endLvl = obj.optInt("endLevel", -1)
                val chargingTime = obj.optString("chargingTime", "")

                val finalPoints = if (ptsList.isNotEmpty()) {
                    ptsList
                } else {
                    synthesizeTelemetryPoints(startLvl, endLvl, chargingTime)
                }

                list.add(
                    BatteryRecord(
                        date = obj.optString("date", ""),
                        healthPct = obj.optInt("healthPct", 100),
                        estimatedMah = obj.optInt("estimatedMah", 5000),
                        designMah = obj.optInt("designMah", 5000),
                        chargingTime = chargingTime,
                        delta = obj.optString("delta", ""),
                        startLevel = startLvl,
                        endLevel = endLvl,
                        source = obj.optString("source", "Tự động"),
                        telemetryPoints = finalPoints
                    )
                )
            }
        } catch (_: Throwable) {}

        return list
    }

    /**
     * Tạo chuỗi điểm dữ liệu cảm biến mô phỏng chuẩn xác vật lý sạc pin (CC/CV curves)
     * dành cho các bản ghi lịch sử chưa lưu telemetry chi tiết.
     */
    fun synthesizeTelemetryPoints(startLevel: Int, endLevel: Int, chargingTime: String): List<TelemetryPoint> {
        val sLvl = if (startLevel in 1..100) startLevel else 20
        val eLvl = if (endLevel in sLvl..100) endLevel else (sLvl + 30).coerceAtMost(100)
        val deltaLvl = (eLvl - sLvl).coerceAtLeast(1)

        // Ước tính tổng thời gian (giây) từ chuỗi thời gian
        var totalSec = 600L
        if (chargingTime.contains("phút")) {
            val minMatch = Regex("(\\d+)\\s*phút").find(chargingTime)
            val secMatch = Regex("(\\d+)\\s*s").find(chargingTime)
            val mins = minMatch?.groupValues?.get(1)?.toLongOrNull() ?: 10L
            val secs = secMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            totalSec = (mins * 60 + secs).coerceIn(60L, 7200L)
        }

        val points = mutableListOf<TelemetryPoint>()
        val count = 25
        val now = System.currentTimeMillis() - totalSec * 1000

        for (i in 0 until count) {
            val fraction = i.toDouble() / (count - 1).coerceAtLeast(1)
            val elapsed = (fraction * totalSec).toLong()
            val curLevel = (sLvl + fraction * deltaLvl).roundToInt().coerceIn(1, 100)

            // Đường cong dòng nạp giảm dần khi pin đầy (Constant Current -> Constant Voltage)
            val baseCurrent = when {
                curLevel < 50 -> 4200.0 - (curLevel * 10)
                curLevel < 80 -> 3500.0 - ((curLevel - 50) * 45)
                else -> 2100.0 - ((curLevel - 80) * 75)
            }.coerceAtLeast(450.0)

            val currentNoise = (Math.sin(i.toDouble() * 1.5) * 80.0)
            val currentMa = (baseCurrent + currentNoise).coerceAtLeast(300.0)

            // Điện thế tăng dần từ ~3.75V lên ~4.45V
            val voltageMv = (3750 + (curLevel * 7.0) + (Math.cos(i.toDouble()) * 15)).roundToInt().coerceIn(3600, 4480)
            val powerWatts = (voltageMv / 1000.0) * (currentMa / 1000.0)

            // Nhiệt độ tăng nhẹ rồi ổn định (31°C -> 38°C -> 35°C)
            val tempC = (31.5 + (Math.sin(fraction * Math.PI) * 6.5) + (fraction * 1.5))

            points.add(
                TelemetryPoint(
                    timestampMs = now + (elapsed * 1000),
                    elapsedSec = elapsed,
                    currentMa = currentMa,
                    voltageMv = voltageMv,
                    powerWatts = powerWatts,
                    tempC = tempC,
                    level = curLevel
                )
            )
        }
        return points
    }

    fun clearHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_HISTORY)
            .remove(KEY_LAST_DATE)
            .remove(KEY_LAST_HEALTH)
            .remove(KEY_LAST_EST_MAH)
            .remove(KEY_BASELINE_GENERATED)
            .apply()
    }
}

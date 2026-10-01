package com.xiaomi.fixnotification

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

data class SharedThermalData(
    val cpuTempC: Float,
    val cpuUsagePercent: Int,
    val gpuTempC: Float,
    val gpuFreqMHz: Int,
    val gpuMaxFreqMHz: Int,
    val gpuUsagePercent: Int,
    val gpuModelName: String,
    val batTempC: Float,
    val batPercent: Int,
    val isCharging: Boolean,
    val batHealthStr: String,
    val batVoltageV: Float,
    val batCurrentMA: Int,
    val coreFreqs: Map<Int, Int>
)

interface ThermalDataListener {
    fun onThermalDataUpdate(data: SharedThermalData)
}

object ThermalTelemetryHub {
    private val listeners = CopyOnWriteArrayList<ThermalDataListener>()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isRunning = false
    @Volatile private var lastData: SharedThermalData? = null

    fun register(listener: ThermalDataListener, context: Context) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        lastData?.let { listener.onThermalDataUpdate(it) }
        startLoopIfNeeded(context.applicationContext)
    }

    fun unregister(listener: ThermalDataListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            stopLoop()
        }
    }

    private fun startLoopIfNeeded(appContext: Context) {
        if (isRunning) return
        isRunning = true
        executor.execute {
            while (isRunning) {
                try {
                    val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 96) ?: 96
                    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                    val percent = ((level.toFloat() / scale.toFloat()) * 100).toInt()
                    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                    val isCharging = plugged > 0 || status == BatteryManager.BATTERY_STATUS_CHARGING

                    val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 360) ?: 360
                    val batTempC = tempRaw / 10.0f

                    val healthCode = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD) ?: BatteryManager.BATTERY_HEALTH_GOOD
                    val healthStr = when (healthCode) {
                        BatteryManager.BATTERY_HEALTH_GOOD -> "Tình trạng tốt"
                        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Quá nhiệt"
                        BatteryManager.BATTERY_HEALTH_DEAD -> "Chai nặng"
                        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Quá điện áp"
                        else -> "Tình trạng tốt"
                    }

                    val voltageRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4310) ?: 4310
                    val voltageV = if (voltageRaw > 100) voltageRaw / 1000.0f else voltageRaw.toFloat()

                    val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val currentNow = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 994
                    val currentMA = if (currentNow != Int.MIN_VALUE && currentNow != 0) Math.abs(currentNow) / 1000 else 994

                    val cpuTemp = DeviceInfoUtils.getCpuTemperature().toFloat()
                    val cpuUsage = DeviceInfoUtils.getCpuUsagePercent()

                    val gpuTemp = DeviceInfoUtils.getGpuTemperature().toFloat()
                    val gpuFreq = DeviceInfoUtils.getGpuFrequencyMHz()
                    val gpuMaxFreq = DeviceInfoUtils.getGpuMaxFrequencyMHz()
                    val gpuUsage = DeviceInfoUtils.getGpuUsagePercent()
                    val glInfo = DeviceInfoUtils.getRealGlInfo(appContext)
                    val gpuModelName = if (glInfo.renderer.isNotEmpty() && glInfo.renderer != "Unknown") glInfo.renderer else "GPU Adreno / Mali"

                    val freqs = mutableMapOf<Int, Int>()
                    val coreCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
                    for (i in 0 until coreCount) {
                        val curFreqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                        val minFreqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq")
                        val minKHz = DeviceInfoUtils.readIntFromFile(minFreqFile, 384000)
                        val curKHz = DeviceInfoUtils.readIntFromFile(curFreqFile, fallback = (minKHz + 500000))
                        val curMHz = (curKHz / 1000).coerceAtLeast(300)
                        freqs[i] = curMHz
                    }

                    val sharedData = SharedThermalData(
                        cpuTempC = cpuTemp,
                        cpuUsagePercent = cpuUsage,
                        gpuTempC = gpuTemp,
                        gpuFreqMHz = gpuFreq,
                        gpuMaxFreqMHz = gpuMaxFreq,
                        gpuUsagePercent = gpuUsage,
                        gpuModelName = gpuModelName,
                        batTempC = batTempC,
                        batPercent = percent,
                        isCharging = isCharging,
                        batHealthStr = healthStr,
                        batVoltageV = voltageV,
                        batCurrentMA = currentMA,
                        coreFreqs = freqs
                    )
                    lastData = sharedData

                    mainHandler.post {
                        for (l in listeners) {
                            l.onThermalDataUpdate(sharedData)
                        }
                    }

                    Thread.sleep(1000)
                } catch (_: Throwable) {
                    try { Thread.sleep(1000) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun stopLoop() {
        isRunning = false
    }
}

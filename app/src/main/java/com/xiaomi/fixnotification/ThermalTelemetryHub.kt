package com.xiaomi.fixnotification

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class SharedThermalData(
    val cpuTempC: Float,
    val cpuUsagePercent: Int,
    val gpuTempC: Float,
    val gpuFreqMHz: Int,
    val gpuMinFreqMHz: Int = 220,
    val gpuMaxFreqMHz: Int,
    val gpuUsagePercent: Int,
    val gpuModelName: String,
    val batTempC: Float,
    val batPercent: Int,
    val isCharging: Boolean,
    val batHealthStr: String,
    val batVoltageV: Float,
    val batCurrentMA: Int,
    val coreFreqs: Map<Int, Int>,
    val fps: Float = 60f,
    val powerWatts: Float = 0f,
    val isRecording: Boolean = false,
    val isShizukuActive: Boolean = false,

    val frameCount: Int = 0,
    val jankCount: Int = 0,
    val bigJankCount: Int = 0,
    val avgFrameTimeMs: Float = 0f,
    val maxFrameTimeMs: Float = 0f
)

interface ThermalDataListener {
    fun onThermalDataUpdate(data: SharedThermalData)
}

object ThermalTelemetryHub {
    private val listeners = CopyOnWriteArrayList<ThermalDataListener>()
    private val executor = Executors.newSingleThreadExecutor()
    private val shizukuExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isRunning = false
    @Volatile private var isShizukuWorkerRunning = false
    @Volatile private var lastData: SharedThermalData? = null
    @Volatile private var latestGameFps: Float? = null
    @Volatile private var latestHardwareFps: Float? = null

    @Volatile private var currentFps: Float = 60f
    private var frameCount = 0
    private var lastFpsSampleTime = System.currentTimeMillis()
    private var isFpsTrackerRunning = false

    @Volatile var isRecording: Boolean = false
        private set
    val recordedFpsPoints = CopyOnWriteArrayList<Float>()
    val recordedPowerPoints = CopyOnWriteArrayList<Float>()

    fun toggleRecording(context: Context? = null): Boolean {
        isRecording = !isRecording
        if (isRecording) {
            ThermalSessionManager.startSession()

            lastData?.let {
                recordedFpsPoints.add(it.fps)
                recordedPowerPoints.add(it.powerWatts)
                ThermalSessionManager.addSample(it)
            }
        } else {
            ThermalSessionManager.stopSession(context)
        }
        return isRecording
    }

    fun clearRecordedData() {
        recordedFpsPoints.clear()
        recordedPowerPoints.clear()
    }

    fun register(listener: ThermalDataListener, context: Context) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        lastData?.let { listener.onThermalDataUpdate(it) }
        startFpsTrackerIfNeeded()
        startLoopIfNeeded(context.applicationContext)
    }

    fun unregister(listener: ThermalDataListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            stopLoop()
            stopFpsTracker()
        }
    }

    private fun startFpsTrackerIfNeeded() {
        if (isFpsTrackerRunning) return
        isFpsTrackerRunning = true
        mainHandler.post {
            frameCount = 0
            lastFpsSampleTime = System.currentTimeMillis()
            scheduleNextFrameCallback()
        }
    }

    private fun stopFpsTracker() {
        isFpsTrackerRunning = false
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isFpsTrackerRunning) return
            frameCount++
            val now = System.currentTimeMillis()
            val elapsed = now - lastFpsSampleTime
            if (elapsed >= 1000) {
                val measured = (frameCount * 1000f / elapsed).coerceIn(10f, 240f)
                val rounded = Math.round(measured * 10f) / 10f
                currentFps = rounded
                frameCount = 0
                lastFpsSampleTime = now
            }
            if (isFpsTrackerRunning) {
                try {
                    Choreographer.getInstance().postFrameCallback(this)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun scheduleNextFrameCallback() {
        try {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } catch (_: Throwable) {}
    }

    private var lastMaxTimestamp: Long = 0L
    private var lastValidGameFps: Float? = null
    private var cachedForegroundPkg: String? = null
    private var lastForegroundCheckTime: Long = 0L
    private var cachedGameLayer: String? = null
    private var lastLayerResolveTimeMs: Long = 0L

    private fun getOrDetectForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        if (now - lastForegroundCheckTime < 2500L && cachedForegroundPkg != null) {
            return cachedForegroundPkg
        }
        lastForegroundCheckTime = now
        cachedForegroundPkg = detectForegroundPackage()
        return cachedForegroundPkg
    }

    private fun detectForegroundPackage(): String? {

        try {
            val cmd = ShizukuUtils.execShizukuCommandArgs(arrayOf("dumpsys", "activity", "top"))
            if (cmd.stdout.isNotBlank()) {
                val lines = cmd.stdout.lines().take(60)
                for (line in lines) {
                    val trimmed = line.trim()
                    val match = Regex("(?:ACTIVITY|TASK)(?:\\s+[0-9]+:)?\\s*([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)").find(trimmed)
                    if (match != null) {
                        val pkg = match.groupValues[1].trim()
                        if (isTargetGameOrApp(pkg)) {
                            return pkg
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        try {
            val cmd = ShizukuUtils.execShizukuCommand("dumpsys window | grep -E 'mCurrentFocus|topResumedActivity|mFocusedApp'")
            if (cmd.stdout.isNotBlank()) {
                for (line in cmd.stdout.lines()) {
                    val match = Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)[/#]").find(line)
                        ?: Regex("u0\\s+([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)").find(line)
                    if (match != null) {
                        val pkg = match.groupValues[1].trim()
                        if (isTargetGameOrApp(pkg)) {
                            return pkg
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun isTargetGameOrApp(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == "android") return false
        val systemBlacklist = listOf(
            "com.android.systemui", "com.miui.home", "com.xiaomi.fixnotification",
            "com.mi.android.globallauncher", "com.google.android.inputmethod.latin",
            "com.android.settings", "com.google.android.gms", "com.miui.freeform",
            "com.miui.securitycenter", "com.miui.securityadd", "com.miui.securitycore",
            "com.miui.powerkeeper", "com.xiaomi.joyose", "com.miui.touchassistant",
            "com.miui.voiceassist", "com.xiaomi.gamecenter"
        )
        return systemBlacklist.none { pkg.equals(it, ignoreCase = true) }
    }

    private fun parseLatencyTimestamps(layer: String): List<Long>? {
        if (layer.isBlank()) return null
        try {
            val res = ShizukuUtils.execShizukuCommandArgs(arrayOf("dumpsys", "SurfaceFlinger", "--latency", layer))
            if (res.exitCode != 0 || res.stdout.isBlank()) return null
            val lines = res.stdout.trim().lines()
            if (lines.size < 3) return null

            val validTimestamps = ArrayList<Long>(128)
            val PENDING_FENCE = Long.MAX_VALUE
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val col0 = parts[0].toLongOrNull() ?: 0L
                    val col1 = parts[1].toLongOrNull() ?: 0L
                    val col2 = if (parts.size >= 3) parts[2].toLongOrNull() ?: 0L else 0L

                    val t = when {
                        col1 in 1000000L..(PENDING_FENCE - 1) -> col1
                        col0 in 1000000L..(PENDING_FENCE - 1) -> col0
                        col2 in 1000000L..(PENDING_FENCE - 1) -> col2
                        else -> 0L
                    }
                    if (t > 0L) {
                        validTimestamps.add(t)
                    }
                }
            }

            if (validTimestamps.size >= 2) {
                validTimestamps.sort()
                val dedup = ArrayList<Long>(validTimestamps.size)
                for (ts in validTimestamps) {
                    if (dedup.isEmpty() || ts != dedup.last()) {
                        dedup.add(ts)
                    }
                }
                if (dedup.size >= 2) {
                    return dedup
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun parseGfxinfoTimestamps(targetPkg: String): List<Long>? {
        try {
            val res = ShizukuUtils.execShizukuCommandArgs(arrayOf("dumpsys", "gfxinfo", targetPkg, "framestats"))
            if (res.stdout.isBlank()) return null
            val lines = res.stdout.lines()
            val profileIdx = lines.indexOfFirst { it.startsWith("---PROFILEDATA---") }
            if (profileIdx == -1 || profileIdx + 2 >= lines.size) return null

            val timestamps = ArrayList<Long>(128)
            for (i in (profileIdx + 2) until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty() || line.startsWith("---")) break
                val parts = line.split(",")
                if (parts.size >= 14) {
                    val flags = parts[0].toLongOrNull() ?: 0L
                    if (flags != 0L) continue
                    val intendedVsync = parts[1].toLongOrNull() ?: 0L
                    val vsync = parts[2].toLongOrNull() ?: 0L
                    val frameCompleted = parts[13].toLongOrNull() ?: 0L
                    val t = when {
                        frameCompleted in 1000000L..9223372000000000000L -> frameCompleted
                        vsync in 1000000L..9223372000000000000L -> vsync
                        intendedVsync in 1000000L..9223372000000000000L -> intendedVsync
                        else -> 0L
                    }
                    if (t > 0L && (timestamps.isEmpty() || t != timestamps.last())) {
                        timestamps.add(t)
                    }
                }
            }
            if (timestamps.size >= 2) {
                timestamps.sort()
                return timestamps
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun resolveActiveGameLayer(targetPkg: String?): String? {
        val now = System.currentTimeMillis()
        if (cachedGameLayer != null && (now - lastLayerResolveTimeMs) < 2000L) {
            val ts = parseLatencyTimestamps(cachedGameLayer!!)
            if (ts != null && ts.size >= 2) {
                return cachedGameLayer
            }
            cachedGameLayer = null
        }

        try {
            val listRes = ShizukuUtils.execShizukuCommandArgs(arrayOf("dumpsys", "SurfaceFlinger", "--list"))
            if (listRes.stdout.isBlank()) return cachedGameLayer

            val allLayers = listRes.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val lastPart = if (targetPkg != null && targetPkg.contains(".")) targetPkg.substringAfterLast(".") else (targetPkg ?: "")

            val gameLayers = if (!targetPkg.isNullOrBlank()) {
                allLayers.filter { line ->
                    (line.contains(targetPkg, ignoreCase = true) ||
                     (lastPart.length >= 3 && line.contains(lastPart, ignoreCase = true)) ||
                     (targetPkg.contains("kgvn") && (line.contains("sgame", ignoreCase = true) || line.contains("tencent", ignoreCase = true)))) &&
                    !line.contains("Background for", ignoreCase = true)
                }
            } else emptyList()

            val candidates = if (gameLayers.isNotEmpty()) gameLayers else {
                allLayers.filter { line ->
                    (line.contains("SurfaceView", ignoreCase = true) || line.contains("BLAST", ignoreCase = true)) &&
                    !line.contains("Background for", ignoreCase = true) &&
                    !line.contains("systemui", ignoreCase = true) &&
                    !line.contains("miui.home", ignoreCase = true) &&
                    !line.contains("launcher", ignoreCase = true) &&
                    !line.contains("fixnotification", ignoreCase = true) &&
                    !line.contains("inputmethod", ignoreCase = true)
                }
            }

            val prioritized = candidates.sortedWith(
                compareByDescending<String> { it.contains("SurfaceView", ignoreCase = true) }
                    .thenByDescending { it.contains("BLAST", ignoreCase = true) || it.endsWith("#0") || it.contains("Activity", ignoreCase = true) }
            )

            var bestLayer: String? = null
            var newestTs = 0L
            for (layer in prioritized.take(8)) {
                val testTimestamps = parseLatencyTimestamps(layer)
                if (testTimestamps != null && testTimestamps.size >= 2) {
                    val last = testTimestamps.last()
                    if (last > newestTs) {
                        newestTs = last
                        bestLayer = layer
                    }
                }
            }

            if (bestLayer != null) {
                cachedGameLayer = bestLayer
                lastLayerResolveTimeMs = now
                return bestLayer
            }
        } catch (_: Throwable) {}

        return cachedGameLayer
    }

    private fun calculateFpsFromTimestamps(timestamps: List<Long>): Float? {
        if (timestamps.size < 2) return null
        val newest = timestamps.last()

        if (lastMaxTimestamp > 0L && newest > lastMaxTimestamp) {
            val newFrames = timestamps.filter { it > lastMaxTimestamp }
            val deltaNs = newest - lastMaxTimestamp
            if (newFrames.isNotEmpty() && deltaNs in 200_000_000L..2_500_000_000L) {
                val exactFps = (newFrames.size.toDouble() / (deltaNs / 1_000_000_000.0)).toFloat()
                lastMaxTimestamp = newest
                if (exactFps in 10f..240f) {
                    val rounded = (Math.round(exactFps * 10f) / 10f).coerceIn(10f, 240f)
                    lastValidGameFps = rounded
                    return rounded
                }
            }
        }

        val windowStart = newest - 1_000_000_000L
        val window = timestamps.filter { it >= windowStart }
        val calculatedFps = if (window.size >= 2) {
            val spanSec = (window.last() - window.first()) / 1_000_000_000.0
            if (spanSec in 0.4..1.4) {
                ((window.size - 1) / spanSec).toFloat()
            } else {
                window.size.toFloat()
            }
        } else {
            window.size.toFloat()
        }

        lastMaxTimestamp = newest
        if (calculatedFps in 10f..240f) {
            val rounded = (Math.round(calculatedFps * 10f) / 10f).coerceIn(10f, 240f)
            lastValidGameFps = rounded
            return rounded
        }
        return null
    }

    private var isTimeStatsEnabled: Boolean = false

    private fun ensureTimeStatsEnabled() {
        if (!isTimeStatsEnabled && ShizukuUtils.hasShizukuPermission()) {
            try {
                ShizukuUtils.execShizukuCommandArgs(arrayOf("dumpsys", "SurfaceFlinger", "--timestats", "-enable"))
                isTimeStatsEnabled = true
            } catch (_: Throwable) {}
        }
    }

    private data class TimeStatsLayer(
        val layerName: String,
        val packageName: String,
        val totalFrames: Long,
        val averageFps: Float?,
        val renderRate: Int?
    )

    private var lastTrackedLayer: String? = null
    private var lastTrackedTotalFrames: Long = -1L
    private var lastTrackedTimeNs: Long = 0L

    private fun parseTimeStatsLayers(stdout: String): List<TimeStatsLayer> {
        if (stdout.isBlank()) return emptyList()

        val result = ArrayList<TimeStatsLayer>()
        var currentLayer = ""
        var currentPkg = ""
        var currentTotalFrames = -1L
        var currentAvgFps: Float? = null
        var currentRenderRate: Int? = null

        fun commitCurrent() {
            if (currentLayer.isNotEmpty() || currentPkg.isNotEmpty()) {
                if (currentTotalFrames >= 0L) {
                    result.add(
                        TimeStatsLayer(
                            layerName = currentLayer,
                            packageName = currentPkg,
                            totalFrames = currentTotalFrames,
                            averageFps = currentAvgFps,
                            renderRate = currentRenderRate
                        )
                    )
                }
            }
            currentLayer = ""
            currentPkg = ""
            currentTotalFrames = -1L
            currentAvgFps = null
            currentRenderRate = null
        }

        for (line in stdout.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Layer [") || trimmed.startsWith("layerName = ")) {
                commitCurrent()
                currentLayer = if (trimmed.startsWith("Layer [")) {
                    trimmed.removePrefix("Layer [").removeSuffix("]").trim()
                } else {
                    trimmed.substringAfter("layerName = ").trim()
                }
            } else if (trimmed.startsWith("packageName = ")) {
                currentPkg = trimmed.substringAfter("packageName = ").trim()
            } else if (trimmed.startsWith("totalFrames = ")) {
                currentTotalFrames = trimmed.substringAfter("totalFrames = ").trim().toLongOrNull() ?: -1L
            } else if (trimmed.startsWith("averageFPS = ")) {
                currentAvgFps = trimmed.substringAfter("averageFPS = ").trim().toFloatOrNull()
            } else if (trimmed.startsWith("renderRate = ")) {
                currentRenderRate = Regex("(\\d+)\\s*fps").find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
            }
        }
        commitCurrent()
        return result
    }

    private fun readGameFpsViaShizuku(targetPkg: String?): Float? {
        if (!ShizukuUtils.hasShizukuPermission()) return null

        try {

            if (!targetPkg.isNullOrBlank()) {
                val resolvedLayer = resolveActiveGameLayer(targetPkg)
                if (!resolvedLayer.isNullOrBlank()) {
                    val sfTimestamps = parseLatencyTimestamps(resolvedLayer)
                    if (sfTimestamps != null && sfTimestamps.size >= 2) {
                        val fps = calculateFpsFromTimestamps(sfTimestamps)
                        if (fps != null && fps in 10f..240f) {
                            lastValidGameFps = fps
                            return fps
                        }
                    }
                }
            }

            ensureTimeStatsEnabled()
            val dumpRes = ShizukuUtils.execShizukuCommandArgs(arrayOf("dumpsys", "SurfaceFlinger", "--timestats", "-dump"))
            if (dumpRes.exitCode == 0 && dumpRes.stdout.isNotBlank()) {
                val layers = parseTimeStatsLayers(dumpRes.stdout)
                if (layers.isNotEmpty()) {
                    val matchedLayers = if (!targetPkg.isNullOrBlank()) {
                        val lastPart = if (targetPkg.contains(".")) targetPkg.substringAfterLast(".") else targetPkg
                        layers.filter {
                            (it.packageName.isNotEmpty() && (it.packageName.contains(targetPkg, ignoreCase = true) || it.packageName.contains(lastPart, ignoreCase = true))) ||
                            (it.layerName.isNotEmpty() && (it.layerName.contains(targetPkg, ignoreCase = true) || it.layerName.contains(lastPart, ignoreCase = true) || it.layerName.contains("SurfaceView", ignoreCase = true)))
                        }
                    } else emptyList()

                    val candidates = if (matchedLayers.isNotEmpty()) matchedLayers else {
                        layers.filter {
                            it.totalFrames > 0L &&
                            !it.layerName.contains("systemui", ignoreCase = true) &&
                            !it.layerName.contains("miui.home", ignoreCase = true) &&
                            !it.layerName.contains("fixnotification", ignoreCase = true)
                        }
                    }

                    if (candidates.isNotEmpty()) {
                        val best = candidates.sortedWith(
                            compareByDescending<TimeStatsLayer> { it.layerName.contains("SurfaceView", ignoreCase = true) }
                                .thenByDescending { it.renderRate != null }
                                .thenByDescending { it.totalFrames }
                        ).first()

                        val nowNs = System.nanoTime()
                        val sameLayer = (lastTrackedLayer != null && lastTrackedLayer == best.layerName)

                        if (sameLayer && lastTrackedTotalFrames >= 0L) {
                            val deltaFrames = best.totalFrames - lastTrackedTotalFrames
                            val deltaSec = (nowNs - lastTrackedTimeNs) / 1_000_000_000.0

                            if (deltaSec in 0.25..3.0 && deltaFrames > 0L) {
                                val exactFps = (deltaFrames / deltaSec).toFloat()
                                lastTrackedLayer = best.layerName
                                lastTrackedTotalFrames = best.totalFrames
                                lastTrackedTimeNs = nowNs

                                val rounded = (Math.round(exactFps * 10f) / 10f).coerceIn(10f, 240f)
                                lastValidGameFps = rounded
                                return rounded
                            }
                        }

                        lastTrackedLayer = best.layerName
                        lastTrackedTotalFrames = best.totalFrames
                        lastTrackedTimeNs = nowNs
                    }
                }
            }

            if (!targetPkg.isNullOrBlank()) {
                val gfxTimestamps = parseGfxinfoTimestamps(targetPkg)
                if (gfxTimestamps != null && gfxTimestamps.size >= 2) {
                    val fps = calculateFpsFromTimestamps(gfxTimestamps)
                    if (fps != null && fps in 10f..240f) {
                        lastValidGameFps = fps
                        return fps
                    }
                }
            }
        } catch (_: Throwable) {
            cachedGameLayer = null
            lastMaxTimestamp = 0L
        }

        return null
    }

    private fun resetGameFpsTracking() {
        latestGameFps = null
        latestHardwareFps = null
        lastValidGameFps = null
        lastMaxTimestamp = 0L
        cachedGameLayer = null
        lastTrackedLayer = null
        lastTrackedTotalFrames = -1L
        lastTrackedTimeNs = 0L
    }

    private var cachedSysfsFpsFile: File? = null
    private var lastSysfsScanTime: Long = 0L

    private fun readSysfsFps(): Float? {
        val now = System.currentTimeMillis()
        val cached = cachedSysfsFpsFile
        if (cached != null && cached.exists() && cached.canRead()) {
            try {
                val text = cached.readText().trim()
                val match = Regex("([0-9]+(?:\\.[0-9]+)?)").find(text)
                val fpsVal = match?.groupValues?.get(1)?.toFloatOrNull()
                if (fpsVal != null && fpsVal in 10f..240f) return fpsVal
            } catch (_: Throwable) {
                cachedSysfsFpsFile = null
            }
        }

        if (now - lastSysfsScanTime < 5000L && cachedSysfsFpsFile == null) return null
        lastSysfsScanTime = now

        val fpsFiles = listOf(
            File("/sys/class/mi_display/disp-DSI-0/fps"),
            File("/sys/class/mi_display/disp-DSI-1/fps"),
            File("/sys/class/drm/card0-DSI-1/measured_fps"),
            File("/sys/class/drm/card0-DSI-0/measured_fps"),
            File("/sys/class/drm/card0/sde-crtc-0/measured_fps"),
            File("/sys/class/drm/card0/sde-crtc-1/measured_fps"),
            File("/sys/class/drm/sde-crtc-0/measured_fps"),
            File("/sys/devices/platform/soc/soc:qcom,dsi-display-primary/measured_fps"),
            File("/sys/class/drm/card0/device/fps"),
            File("/sys/class/thermal/thermal_message/fps"),
            File("/sys/class/touch/touch_dev/fps"),
            File("/sys/devices/virtual/graphics/fb0/measured_fps"),
            File("/sys/class/graphics/fb0/measured_fps"),
            File("/sys/class/graphics/fb0/fps")
        )
        for (f in fpsFiles) {
            if (f.exists() && f.canRead()) {
                try {
                    val text = f.readText().trim()
                    val match = Regex("([0-9]+(?:\\.[0-9]+)?)").find(text)
                    val fpsVal = match?.groupValues?.get(1)?.toFloatOrNull()
                    if (fpsVal != null && fpsVal in 10f..240f) {
                        cachedSysfsFpsFile = f
                        return fpsVal
                    }
                } catch (_: Throwable) {}
            }
        }

        return null
    }

    private fun calculatePowerWatts(voltageV: Float, currentMA: Int): Float {

        val powerFiles = listOf(
            File("/sys/class/power_supply/battery/power_now"),
            File("/sys/class/power_supply/bms/power_now")
        )
        for (f in powerFiles) {
            if (f.exists() && f.canRead()) {
                try {
                    val raw = f.readText().trim().toLongOrNull()
                    if (raw != null && raw > 0) {
                        val w = raw / 1_000_000f
                        if (w in 0.1f..65f) {
                            return Math.round(w * 10f) / 10f
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        if (voltageV > 0 && currentMA > 0) {
            val w = voltageV * (currentMA / 1000f)
            return (Math.round(w * 10f) / 10f).coerceIn(0.1f, 50f)
        }

        return 3.5f
    }

    private fun startShizukuWorkerIfNeeded() {
        if (isShizukuWorkerRunning) return
        isShizukuWorkerRunning = true
        shizukuExecutor.execute {
            while (isRunning && isShizukuWorkerRunning) {
                try {
                    if (ShizukuUtils.hasShizukuPermission()) {
                        val foregroundPkg = getOrDetectForegroundPackage()
                        val isGame = foregroundPkg != null && isTargetGameOrApp(foregroundPkg)
                        if (isGame) {
                            FrameJankTracker.setTarget(foregroundPkg)
                            val fps = FrameJankTracker.getCurrentFps()
                            if (fps != null && fps > 0f) {
                                latestGameFps = fps
                                lastValidGameFps = fps
                            }
                        } else {
                            FrameJankTracker.setTarget(null)
                            latestGameFps = null
                            lastValidGameFps = null
                            resetGameFpsTracking()
                        }
                        readHardwareStatsViaShizuku()
                    }
                    Thread.sleep(700L)
                } catch (_: Throwable) {
                    try { Thread.sleep(700L) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun readHardwareStatsViaShizuku() {
        try {
            val cmd = ShizukuUtils.execShizukuCommand(
                "head -n 1 /proc/stat; cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null; cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null; cat /sys/module/ged/parameters/gpu_loading 2>/dev/null; cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null; cat /sys/module/ged/parameters/gpu_freq 2>/dev/null; echo HW_FPS:$(cat /sys/class/mi_display/disp-DSI-0/fps 2>/dev/null)$(cat /sys/class/drm/card0/sde-crtc-0/measured_fps 2>/dev/null)$(cat /sys/class/drm/card0-DSI-0/measured_fps 2>/dev/null)$(cat /sys/class/thermal/thermal_message/fps 2>/dev/null)"
            )
            if (cmd.exitCode == 0 && cmd.stdout.isNotBlank()) {
                val lines = cmd.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
                for (line in lines) {
                    if (line.startsWith("HW_FPS:")) {
                        val text = line.removePrefix("HW_FPS:").trim()
                        val match = Regex("([0-9]+(?:\\.[0-9]+)?)").find(text)
                        val fps = match?.groupValues?.get(1)?.toFloatOrNull()
                        if (fps != null && fps in 15f..240f) {
                            latestHardwareFps = Math.round(fps * 10f) / 10f
                        }
                    } else if (line.startsWith("cpu ")) {
                        val parsedCpu = DeviceInfoUtils.parseProcStatLine(line)
                        if (parsedCpu != null) {
                            DeviceInfoUtils.externalCpuUsage = parsedCpu
                        }
                    } else if (line.contains(" ")) {
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            val busy = parts[0].toLongOrNull()
                            val total = parts[1].toLongOrNull()
                            if (busy != null && total != null) {

                                DeviceInfoUtils.externalGpuUsage =
                                    if (total > 0) ((busy * 100f) / total).roundToInt().coerceIn(0, 100) else 0
                            }
                        }
                    } else {
                        val num = line.toLongOrNull()
                        if (num != null) {
                            if (num in 0..100) {
                                DeviceInfoUtils.externalGpuUsage = num.toInt()
                            } else if (num > 1000) {
                                val mhz = when {
                                    num > 10000000 -> (num / 1000000).toInt()
                                    num > 10000 -> (num / 1000).toInt()
                                    else -> num.toInt()
                                }
                                DeviceInfoUtils.externalGpuFreqMHz = mhz
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun startLoopIfNeeded(appContext: Context) {
        if (isRunning) return
        isRunning = true
        startShizukuWorkerIfNeeded()
        FrameJankTracker.start()
        executor.execute {
            while (isRunning) {
                try {
                    val loopStart = System.currentTimeMillis()
                    val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100

                    val capacityProp = (appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
                    val percent = when {
                        capacityProp in 0..100 -> capacityProp
                        level >= 0 && scale > 0 -> ((level * 100 + scale / 2) / scale).coerceIn(0, 100)
                        else -> 0
                    }
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
                    val gpuMinFreq = DeviceInfoUtils.getGpuMinFrequencyMHz()
                    val gpuMaxFreq = DeviceInfoUtils.getGpuMaxFrequencyMHz()
                    val gpuUsage = DeviceInfoUtils.getGpuUsagePercent()
                    val glInfo = DeviceInfoUtils.getRealGlInfo(appContext)
                    val gpuModelName = if (glInfo.renderer.isNotEmpty() && glInfo.renderer != "Unknown") glInfo.renderer else "GPU Adreno / Mali"

                    val freqs = DeviceInfoUtils.getCpuCoreFrequencies()

                    val frameSnap = FrameJankTracker.drain()

                    val trackerFps = FrameJankTracker.getCurrentFps() ?: if (frameSnap.fps > 0f) frameSnap.fps else null
                    if (trackerFps != null && trackerFps in 5f..240f) {
                        lastValidGameFps = trackerFps
                    }
                    val isShizukuActive = ShizukuUtils.hasShizukuPermission()
                    val cachedGame = if (isShizukuActive) lastValidGameFps else null

                    val isGame = cachedForegroundPkg != null && isTargetGameOrApp(cachedForegroundPkg!!)
                    val finalFps: Float = when {

                        isGame && trackerFps != null && trackerFps in 5f..240f -> trackerFps
                        isGame && cachedGame != null && cachedGame in 5f..240f -> cachedGame

                        isGame -> lastData?.fps ?: 60f

                        else -> {
                            val sysfsFps = readSysfsFps()
                            val hwFps = latestHardwareFps
                            when {
                                sysfsFps != null && sysfsFps > 0f -> sysfsFps
                                hwFps != null && hwFps > 0f -> hwFps
                                currentFps > 0f -> currentFps
                                else -> 60f
                            }
                        }
                    }

                    val powerWatts = calculatePowerWatts(voltageV, currentMA)

                    val sharedData = SharedThermalData(
                        cpuTempC = cpuTemp,
                        cpuUsagePercent = cpuUsage,
                        gpuTempC = gpuTemp,
                        gpuFreqMHz = gpuFreq,
                        gpuMinFreqMHz = gpuMinFreq,
                        gpuMaxFreqMHz = gpuMaxFreq,
                        gpuUsagePercent = gpuUsage,
                        gpuModelName = gpuModelName,
                        batTempC = batTempC,
                        batPercent = percent,
                        isCharging = isCharging,
                        batHealthStr = healthStr,
                        batVoltageV = voltageV,
                        batCurrentMA = currentMA,
                        coreFreqs = freqs,
                        fps = finalFps,
                        powerWatts = powerWatts,
                        isRecording = isRecording,
                        isShizukuActive = isShizukuActive,
                        frameCount = frameSnap.frames,
                        jankCount = frameSnap.jank,
                        bigJankCount = frameSnap.bigJank,
                        avgFrameTimeMs = frameSnap.avgFrameTimeMs,
                        maxFrameTimeMs = frameSnap.maxFrameTimeMs
                    )
                    lastData = sharedData

                    if (isRecording) {
                        if (recordedFpsPoints.size >= 300) {
                            recordedFpsPoints.removeAt(0)
                        }
                        recordedFpsPoints.add(finalFps)

                        if (recordedPowerPoints.size >= 300) {
                            recordedPowerPoints.removeAt(0)
                        }
                        recordedPowerPoints.add(powerWatts)

                        ThermalSessionManager.addSample(sharedData)
                    }

                    mainHandler.post {
                        for (l in listeners) {
                            l.onThermalDataUpdate(sharedData)
                        }
                    }

                    val loopElapsed = System.currentTimeMillis() - loopStart
                    val targetInterval = 1000L
                    val sleepMs = (targetInterval - loopElapsed).coerceAtLeast(50L)
                    Thread.sleep(sleepMs)
                } catch (_: Throwable) {
                    try { Thread.sleep(1000L) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun stopLoop() {
        isRunning = false
        isShizukuWorkerRunning = false
        FrameJankTracker.stop()
        DeviceInfoUtils.externalCpuUsage = -1
        DeviceInfoUtils.externalGpuUsage = -1
        DeviceInfoUtils.externalGpuFreqMHz = -1
    }
}

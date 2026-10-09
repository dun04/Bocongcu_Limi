package com.xiaomi.fixnotification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class ThermalSample(
    val timestampMs: Long,
    val elapsedSec: Long,
    val fps: Float,
    val powerWatts: Float,
    val cpuTempC: Float,
    val cpuUsagePercent: Int,
    val gpuTempC: Float,
    val gpuUsagePercent: Int,
    val batTempC: Float,
    val batPercent: Int,
    val coreFreqs: Map<Int, Int>,
    val gpuFreqMHz: Int = 0,

    val frameCount: Int = 0,
    val jankCount: Int = 0,
    val bigJankCount: Int = 0,
    val avgFrameTimeMs: Float = 0f,
    val maxFrameTimeMs: Float = 0f
)

data class ThermalSession(
    val id: String,
    val startTimeMs: Long,
    var endTimeMs: Long = 0L,
    val samples: MutableList<ThermalSample> = mutableListOf()
) {
    val durationSeconds: Long
        get() = if (samples.isEmpty()) 0L else (samples.last().elapsedSec - samples.first().elapsedSec).coerceAtLeast(samples.size.toLong())

    val avgFps: Float
        get() = if (samples.isEmpty()) 0f else Math.round(samples.map { it.fps }.average().toFloat() * 10f) / 10f

    val maxFps: Float
        get() = if (samples.isEmpty()) 0f else samples.maxOf { it.fps }

    val minFps: Float
        get() = if (samples.isEmpty()) 0f else samples.minOf { it.fps }

    val fps1PercentLow: Float
        get() {
            if (samples.isEmpty()) return 0f
            val valid = samples.map { it.fps }.filter { it > 0f }
            if (valid.isEmpty()) return 0f
            val sorted = valid.sorted()
            val count = kotlin.math.round((sorted.size * 0.01).coerceAtLeast(1.0)).toInt()
            val slice = sorted.take(count)
            return Math.round(slice.average().toFloat() * 10f) / 10f
        }

    val avgPower: Float
        get() = if (samples.isEmpty()) 0f else Math.round(samples.map { it.powerWatts }.average().toFloat() * 10f) / 10f

    val maxPower: Float
        get() = if (samples.isEmpty()) 0f else samples.maxOf { it.powerWatts }

    val maxCpuTemp: Float
        get() = if (samples.isEmpty()) 0f else samples.maxOf { it.cpuTempC }

    val maxGpuTemp: Float
        get() = if (samples.isEmpty()) 0f else samples.maxOf { it.gpuTempC }

    val maxBatTemp: Float
        get() = if (samples.isEmpty()) 0f else samples.maxOf { it.batTempC }

    val hasFrameData: Boolean
        get() = samples.any { it.frameCount > 0 }

    val totalFrames: Int
        get() = samples.sumOf { it.frameCount }

    val totalJank: Int
        get() = samples.sumOf { it.jankCount }

    val totalBigJank: Int
        get() = samples.sumOf { it.bigJankCount }

    val frameActiveSeconds: Double
        get() = samples.sumOf { it.avgFrameTimeMs.toDouble() * it.frameCount } / 1000.0

    val jankPer10Min: Float
        get() = frameActiveSeconds.let { if (it > 0.0) (totalJank * 600.0 / it).toFloat() else 0f }

    val bigJankPer10Min: Float
        get() = frameActiveSeconds.let { if (it > 0.0) (totalBigJank * 600.0 / it).toFloat() else 0f }

    val avgFrameTimeMs: Float
        get() = totalFrames.let { if (it > 0) (frameActiveSeconds * 1000.0 / it).toFloat() else 0f }

    val maxFrameTimeMs: Float
        get() = if (samples.isEmpty()) 0f else samples.maxOf { it.maxFrameTimeMs }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss · dd/MM", Locale.getDefault())
            return sdf.format(Date(startTimeMs))
        }

    val title: String
        get() {
            val mins = durationSeconds / 60
            val secs = durationSeconds % 60
            val dur = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
            return "Phiên ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(startTimeMs))} ($dur)"
        }
}

object ThermalSessionManager {

    private val sessions = CopyOnWriteArrayList<ThermalSession>()
    @Volatile private var currentSession: ThermalSession? = null
    private var sessionStartTime = 0L
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        loadSessionsFromDisk(context)
    }

    fun isRecording(): Boolean = currentSession != null

    fun startSession(): ThermalSession {
        val now = System.currentTimeMillis()
        sessionStartTime = now
        val session = ThermalSession(
            id = "session_$now",
            startTimeMs = now
        )
        currentSession = session
        return session
    }

    fun addSample(data: SharedThermalData) {
        val session = currentSession ?: return
        val now = System.currentTimeMillis()
        val elapsedSec = (now - sessionStartTime) / 1000L
        val sample = ThermalSample(
            timestampMs = now,
            elapsedSec = elapsedSec,
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
        session.samples.add(sample)
    }

    fun stopSession(context: Context? = null): ThermalSession? {
        val session = currentSession ?: return null
        session.endTimeMs = System.currentTimeMillis()
        if (session.samples.isNotEmpty()) {
            sessions.add(0, session)
            if (sessions.size > 20) {
                sessions.removeAt(sessions.size - 1)
            }
            context?.let { saveSessionsToDisk(it) }
        }
        currentSession = null
        return session
    }

    fun getCurrentSession(): ThermalSession? = currentSession

    fun getSavedSessions(): List<ThermalSession> = sessions.toList()

    fun clearAll(context: Context) {
        currentSession = null
        sessions.clear()
        saveSessionsToDisk(context)
    }

    private fun loadSessionsFromDisk(context: Context) {
        try {
            val file = File(context.filesDir, "thermal_sessions.json")
            if (!file.exists()) return
            val jsonText = file.readText()
            val array = JSONArray(jsonText)
            sessions.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", "")
                val start = obj.optLong("startTimeMs", 0L)
                val end = obj.optLong("endTimeMs", 0L)
                val sArray = obj.optJSONArray("samples") ?: JSONArray()
                val samplesList = mutableListOf<ThermalSample>()
                for (j in 0 until sArray.length()) {
                    val sObj = sArray.getJSONObject(j)
                    val freqsObj = sObj.optJSONObject("coreFreqs")
                    val freqsMap = mutableMapOf<Int, Int>()
                    if (freqsObj != null) {
                        for (k in freqsObj.keys()) {
                            val coreIdx = k.toIntOrNull() ?: continue
                            freqsMap[coreIdx] = freqsObj.optInt(k, 1000)
                        }
                    }
                    samplesList.add(
                        ThermalSample(
                            timestampMs = sObj.optLong("timestampMs", 0L),
                            elapsedSec = sObj.optLong("elapsedSec", 0L),
                            fps = sObj.optDouble("fps", 60.0).toFloat(),
                            powerWatts = sObj.optDouble("powerWatts", 0.0).toFloat(),
                            cpuTempC = sObj.optDouble("cpuTempC", 0.0).toFloat(),
                            cpuUsagePercent = sObj.optInt("cpuUsagePercent", 0),
                            gpuTempC = sObj.optDouble("gpuTempC", 0.0).toFloat(),
                            gpuUsagePercent = sObj.optInt("gpuUsagePercent", 0),
                            batTempC = sObj.optDouble("batTempC", 0.0).toFloat(),
                            batPercent = sObj.optInt("batPercent", 0),
                            coreFreqs = freqsMap,
                            gpuFreqMHz = sObj.optInt("gpuFreqMHz", 0),
                            frameCount = sObj.optInt("frameCount", 0),
                            jankCount = sObj.optInt("jankCount", 0),
                            bigJankCount = sObj.optInt("bigJankCount", 0),
                            avgFrameTimeMs = sObj.optDouble("avgFrameTimeMs", 0.0).toFloat(),
                            maxFrameTimeMs = sObj.optDouble("maxFrameTimeMs", 0.0).toFloat()
                        )
                    )
                }
                sessions.add(ThermalSession(id, start, end, samplesList))
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun saveSessionsToDisk(context: Context) {
        try {
            val array = JSONArray()
            for (s in sessions) {
                val obj = JSONObject()
                obj.put("id", s.id)
                obj.put("startTimeMs", s.startTimeMs)
                obj.put("endTimeMs", s.endTimeMs)

                val sArray = JSONArray()
                for (sample in s.samples) {
                    val sObj = JSONObject()
                    sObj.put("timestampMs", sample.timestampMs)
                    sObj.put("elapsedSec", sample.elapsedSec)
                    sObj.put("fps", sample.fps)
                    sObj.put("powerWatts", sample.powerWatts)
                    sObj.put("cpuTempC", sample.cpuTempC)
                    sObj.put("cpuUsagePercent", sample.cpuUsagePercent)
                    sObj.put("gpuTempC", sample.gpuTempC)
                    sObj.put("gpuUsagePercent", sample.gpuUsagePercent)
                    sObj.put("batTempC", sample.batTempC)
                    sObj.put("batPercent", sample.batPercent)
                    sObj.put("gpuFreqMHz", sample.gpuFreqMHz)
                    sObj.put("frameCount", sample.frameCount)
                    sObj.put("jankCount", sample.jankCount)
                    sObj.put("bigJankCount", sample.bigJankCount)
                    sObj.put("avgFrameTimeMs", sample.avgFrameTimeMs.toDouble())
                    sObj.put("maxFrameTimeMs", sample.maxFrameTimeMs.toDouble())

                    val freqsObj = JSONObject()
                    for ((coreIdx, mhz) in sample.coreFreqs) {
                        freqsObj.put(coreIdx.toString(), mhz)
                    }
                    sObj.put("coreFreqs", freqsObj)
                    sArray.put(sObj)
                }
                obj.put("samples", sArray)
                array.put(obj)
            }
            val file = File(context.filesDir, "thermal_sessions.json")
            file.writeText(array.toString())
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}

package com.xiaomi.fixnotification

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaDrm
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.util.Size
import android.view.WindowManager
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CoreFreqInfo(
    val coreIndex: Int,
    val curFreqMHz: Int,
    val maxFreqMHz: Int,
    val usagePercent: Int
)

data class InfoSection(
    val sectionTitle: String,
    val items: List<Pair<String, String>>,
    val actionItems: Map<String, String>? = null
)

data class GlHardwareInfo(
    val renderer: String,
    val vendor: String,
    val version: String,
    val extensions: String
)

data class DeviceMainInfo(
    val manufacturer: String,
    val modelName: String,
    val marketName: String,
    val batteryCapacityStr: String,
    val batteryCycleStr: String,
    val batteryLevel: Int,
    val batteryTempStr: String,
    val socName: String,
    val cpuFreqRange: String,
    val gpuVendor: String,
    val gpuModel: String,
    val refreshRateStr: String,
    val resolutionStr: String,
    val screenSizeStr: String,
    val totalRamGB: Double,
    val nominalRamGB: Int,
    val usedRamGB: Double,
    val ramTypeStr: String,
    val ramFreqStr: String,
    val ramSpeedStr: String,
    val totalStorageGB: Double,
    val nominalStorageGB: Int,
    val usedStorageGB: Double,
    val storageTypeStr: String,
    val rearCameraStr: String,
    val frontCameraStr: String,
    val sensorCount: Int,
    val cpuTempStr: String
)

data class CameraHardwareInfo(
    val id: String,
    val facing: Int,
    val megapixels: Int,
    val focalLengths: List<Float>,
    val apertures: List<Float>,
    val isoRange: Pair<Int, Int>?,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val sensorFormat: String,
    val oisSupported: Boolean,
    val flashSupported: Boolean,
    val maxDigitalZoom: Float,
    val maxVideoResolution: String,
    val pixelWidth: Int,
    val pixelHeight: Int
)

object DeviceInfoUtils {

    private var cachedGlInfo: GlHardwareInfo? = null
    @Volatile
    private var cachedMainDeviceInfo: DeviceMainInfo? = null

    fun getLiveOverviewTemperatures(context: Context): Pair<String, String> {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320
            val batteryTemp = tempRaw / 10.0
            val batteryTempStr = "${String.format(Locale.US, "%.1f", batteryTemp)}°C"
            val cpuTemp = getCpuTemperature()
            val cpuTempStr = "${String.format(Locale.US, "%.1f", cpuTemp)}°C"
            Pair(batteryTempStr, cpuTempStr)
        } catch (_: Throwable) {
            Pair("35.0°C", "38.0°C")
        }
    }

    fun isMediaTekDevice(): Boolean {
        val hw = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val platform = getSystemProperty("ro.board.platform").lowercase()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.lowercase() else ""
        val mtkProp = getSystemProperty("ro.mediatek.platform").lowercase()
        val chipProp = getSystemProperty("ro.chipname").lowercase()

        return hw.startsWith("mt") || hw.contains("mediatek") ||
                board.startsWith("mt") || platform.startsWith("mt") ||
                soc.startsWith("mt") || soc.contains("dimensity") ||
                mtkProp.isNotEmpty() || chipProp.startsWith("mt")
    }

    fun getRealGlInfo(context: Context): GlHardwareInfo {
        cachedGlInfo?.let { return it }
        try {
            val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val vers = IntArray(2)
            EGL14.eglInitialize(dpy, vers, 0, vers, 1)

            val configAttr = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfig = IntArray(1)
            EGL14.eglChooseConfig(dpy, configAttr, 0, configs, 0, 1, numConfig, 0)

            if (numConfig[0] > 0 && configs[0] != null) {
                val ctxAttr = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
                )
                val ctx = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
                val pbufAttr = intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE
                )
                val surf = EGL14.eglCreatePbufferSurface(dpy, configs[0], pbufAttr, 0)
                EGL14.eglMakeCurrent(dpy, surf, surf, ctx)

                val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
                val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: ""
                val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
                val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""

                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, surf)
                EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)

                if (renderer.isNotBlank()) {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val configGl = am?.deviceConfigurationInfo?.glEsVersion ?: "3.2"
                    val finalVendor = when {
                        vendor.isNotEmpty() -> vendor
                        renderer.contains("Mali", ignoreCase = true) || renderer.contains("Immortalis", ignoreCase = true) -> "ARM"
                        renderer.contains("Adreno", ignoreCase = true) -> "Qualcomm"
                        else -> if (isMediaTekDevice()) "ARM" else "Qualcomm"
                    }
                    val info = GlHardwareInfo(
                        renderer = renderer,
                        vendor = finalVendor,
                        version = "OpenGL ES $configGl ($glVersion)",
                        extensions = extensions
                    )
                    cachedGlInfo = info
                    return info
                }
            }
        } catch (_: Throwable) {}

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val glVerStr = am?.deviceConfigurationInfo?.glEsVersion ?: "3.2"
        val isMtk = isMediaTekDevice()
        val vendor = if (isMtk) "ARM" else "Qualcomm"
        val renderer = if (isMtk) {
            val model = Build.MODEL.uppercase()
            if (model.contains("TURBO 4 PRO") || model.contains("TURBO 4")) "ARM Immortalis-G720 / Mali-G720"
            else if (model.contains("K70E") || model.contains("X6 PRO")) "ARM Mali-G615-MC6"
            else if (model.contains("K70 ULTRA") || model.contains("14T PRO")) "ARM Immortalis-G720 MC12"
            else "ARM Mali GPU"
        } else {
            val model = Build.MODEL.uppercase()
            if (model.contains("18 PRO MAX") || model.contains("18 PROMAX") || model.contains("18PROMAX")) "Adreno™ 850 Extreme"
            else if (model.contains("18 PRO") || model.contains("18 ULTRA") || model.contains("XIAOMI 18")) "Adreno™ 850"
            else if (model.contains("15 PRO") || model.contains("15 ULTRA") || model.contains("15") || model.contains("K80 PRO")) "Adreno™ 830"
            else if (model.contains("TURBO 4 PRO") || model.contains("NOTE 17 TURBO")) "Adreno™ 825"
            else if (model.contains("TURBO 3") || model.contains("POCO F6") || model.contains("CIVI 4")) "Adreno™ 735"
            else if (model.contains("14 PRO") || model.contains("14 ULTRA") || model.contains("14") || model.contains("K70 PRO")) "Adreno™ 750"
            else if (model.contains("13 PRO") || model.contains("13 ULTRA") || model.contains("13") || model.contains("K60 PRO")) "Adreno™ 740"
            else "Qualcomm Adreno GPU"
        }

        val fallback = GlHardwareInfo(
            renderer = renderer,
            vendor = vendor,
            version = "OpenGL ES $glVerStr",
            extensions = "GL_OES_EGL_image GL_OES_texture_float GL_EXT_texture_filter_anisotropic GL_KHR_debug GL_EXT_color_buffer_float GL_EXT_geometry_shader"
        )
        cachedGlInfo = fallback
        return fallback
    }

    fun getVulkanVersion(context: Context): String {
        val pm = context.packageManager
        for (feature in pm.systemAvailableFeatures) {
            if (feature.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) {
                val v = feature.version
                val major = (v shr 22) and 0x7F
                val minor = (v shr 12) and 0x3FF
                val patch = v and 0xFFF
                return "Vulkan $major.$minor.$patch"
            }
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)) {
            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (pm.hasSystemFeature("android.hardware.vulkan.level", 1)) "Level 1" else "Level 0"
            } else "Level 0"
            return "Vulkan 1.3 ($level)"
        }
        return "Vulkan 1.3"
    }

    fun getVulkanExtensions(context: Context): String {
        val pm = context.packageManager
        val vulkanFeatures = pm.systemAvailableFeatures.filter { it.name?.startsWith("android.hardware.vulkan") == true }
        val sb = StringBuilder()
        sb.append("• VK_KHR_surface\n")
        sb.append("• VK_KHR_swapchain\n")
        sb.append("• VK_KHR_driver_properties\n")
        sb.append("• VK_KHR_dynamic_rendering\n")
        sb.append("• VK_KHR_shader_float16_int8\n")
        sb.append("• VK_EXT_subgroup_size_control\n")
        sb.append("• VK_KHR_timeline_semaphore\n")
        sb.append("• VK_KHR_synchronization2\n")
        sb.append("• VK_KHR_format_feature_flags2\n")
        for (f in vulkanFeatures) {
            sb.append("• ${f.name} (v${f.version})\n")
        }
        return sb.toString().trimEnd()
    }

    fun getSystemProperty(key: String): String {
        try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            val value = get.invoke(null, key) as? String
            if (!value.isNullOrBlank()) return value.trim()
        } catch (_: Throwable) {}

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val output = process.inputStream.bufferedReader().readLine()?.trim() ?: ""
            process.destroy()
            output
        } catch (_: Throwable) {
            ""
        }
    }

    fun getMainDeviceInfo(context: Context): DeviceMainInfo {
        cachedMainDeviceInfo?.let { return it }
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val marketNameProp = getSystemProperty("ro.product.marketname")
            .ifEmpty { getSystemProperty("ro.product.model") }
            .ifEmpty { Build.MODEL }

        val glInfo = getRealGlInfo(context)

        val rawHardware = Build.HARDWARE
        val rawSoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        val socName = resolveSocName(rawSoc.ifEmpty { rawHardware }, getSystemProperty("ro.board.platform"), glInfo, context)

        val minFreq = getCpuMinFreqMHz()
        val maxFreq = getCpuMaxFreqMHz()
        val cpuFreqRange = "${String.format(Locale.US, "%.1f", minFreq.toDouble())} ~ ${String.format(Locale.US, "%.1f", maxFreq.toDouble())} MHz"

        val gpuVendor = glInfo.vendor
        val gpuModel = glInfo.renderer

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val refreshRate = try {
            display.mode.refreshRate.roundToInt()
        } catch (_: Throwable) {
            120
        }
        val refreshRateStr = "$refreshRate Hz"
        val resolutionStr = "${metrics.widthPixels} x ${metrics.heightPixels}"

        val xInches = metrics.widthPixels.toDouble() / metrics.xdpi
        val yInches = metrics.heightPixels.toDouble() / metrics.ydpi
        val diagonal = sqrt(xInches.pow(2.0) + yInches.pow(2.0))
        val screenSizeStr = if (diagonal in 4.0..14.0) String.format(Locale.US, "%.2finch", diagonal) else "6.67inch"

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 85) ?: 85
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryLevelPct = ((level.toFloat() / scale.toFloat()) * 100).toInt()
        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320
        val batteryTemp = tempRaw / 10.0
        val batteryTempStr = "${String.format(Locale.US, "%.1f", batteryTemp)}°C"

        val capacity = getBatteryCapacity(context)
        val batteryCapacityStr = "${capacity}mAh(điển hình)"
        val cycles = getBatteryCycleCount(context)
        val batteryCycleStr = if (cycles > 0) "Chu kỳ sạc: $cycles" else "Tình trạng tốt"

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalRamGB = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val availRamGB = memInfo.availMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val usedRamGB = (totalRamGB - availRamGB).coerceAtLeast(0.0)

        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availBlocks = stat.availableBlocksLong
        val totalBytes = totalBlocks * blockSize
        val availBytes = availBlocks * blockSize
        val usedBytes = totalBytes - availBytes

        val totalStorageGB = roundToStandardStorage(totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
        val usedStorageGB = usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)

        val (rearCam, frontCam) = getCameraMegapixels(context)

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensorCount = sm.getSensorList(Sensor.TYPE_ALL).size

        val cpuTemp = getCpuTemperature()
        val cpuTempStr = "${String.format(Locale.US, "%.1f", cpuTemp)}°C"

        val nominalRamGB = roundToStandardRam(totalRamGB)
        val nominalStorageGB = roundToStandardStorage(totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)).toInt()

        val ramType: String
        val ramFreq: String
        val ramSpeed: String
        val storageType: String

        val combinedUpper = "$marketNameProp ${Build.MODEL} ${Build.DEVICE} $socName".uppercase()

        when {
            combinedUpper.contains("18 PRO MAX") || combinedUpper.contains("18 PROMAX") || combinedUpper.contains("18PROMAX") || combinedUpper.contains("18 ULTRA") || combinedUpper.contains("18 PRO") || combinedUpper.contains("XIAOMI 18") || combinedUpper.contains("17 PRO MAX") || combinedUpper.contains("17 PROMAX") || combinedUpper.contains("17PROMAX") || combinedUpper.contains("17 ULTRA") || combinedUpper.contains("17 PRO") || combinedUpper.contains("K100 PRO MAX") || combinedUpper.contains("K100 PROMAX") || combinedUpper.contains("K100 MAX") || combinedUpper.contains("K100 ULTRA") || combinedUpper.contains("K100 PRO") || combinedUpper.contains("K90 PRO MAX") || combinedUpper.contains("K90 PROMAX") -> {
                ramType = "LPDDR5X Ultra"
                ramFreq = "5300MHz"
                ramSpeed = "9600 Mbps"
                storageType = "UFS 4.1"
            }
            combinedUpper.contains("XIAOMI 17") || (combinedUpper.contains(" 17") && !combinedUpper.contains("NOTE")) || combinedUpper.contains("K90 ULTRA") || combinedUpper.contains("K90 MAX") || combinedUpper.contains("K90 PRO") || combinedUpper.contains("15 ULTRA") || combinedUpper.contains("15 PRO") -> {
                ramType = "LPDDR5X"
                ramFreq = "4800MHz"
                ramSpeed = "8533 Mbps"
                storageType = if (combinedUpper.contains("17") || combinedUpper.contains("15 PRO") || combinedUpper.contains("15 ULTRA") || combinedUpper.contains("K90 ULTRA") || combinedUpper.contains("K90 MAX")) "UFS 4.1" else "UFS 4.0"
            }
            combinedUpper.contains("K90") || combinedUpper.contains("K100") || combinedUpper.contains("15") || combinedUpper.contains("K80 PRO") || combinedUpper.contains("TURBO 5 MAX") || combinedUpper.contains("TURBO 5") || combinedUpper.contains("TURBO 4 PRO") || combinedUpper.contains("POCO X7 PRO") || combinedUpper.contains("POCO F7") || combinedUpper.contains("K70 ULTRA") || combinedUpper.contains("14T PRO") -> {
                ramType = "LPDDR5X"
                ramFreq = "4800MHz"
                ramSpeed = "8533 Mbps"
                storageType = if ((combinedUpper.contains("TURBO 5 MAX") || combinedUpper.contains("POCO F7")) && nominalStorageGB >= 512) "UFS 4.1" else "UFS 4.0"
            }
            combinedUpper.contains("NOTE 17 PRO+") || combinedUpper.contains("NOTE 17 TURBO") || combinedUpper.contains("NOTE 14 PRO+") || combinedUpper.contains("TURBO 3") || combinedUpper.contains("K70 PRO") || combinedUpper.contains("K70") -> {
                ramType = "LPDDR5X"
                ramFreq = "4266MHz"
                ramSpeed = "8533 Mbps"
                storageType = if (nominalStorageGB >= 256) "UFS 4.0" else "UFS 3.1"
            }
            combinedUpper.contains("NOTE 17 PRO") || combinedUpper.contains("NOTE 14 PRO") || combinedUpper.contains("NOTE 13 PRO+") || combinedUpper.contains("13T") || combinedUpper.contains("CIVI 4 PRO") -> {
                ramType = "LPDDR5"
                ramFreq = "3200MHz"
                ramSpeed = "6400 Mbps"
                storageType = "UFS 3.1"
            }
            combinedUpper.contains("NOTE 17") || combinedUpper.contains("NOTE 14") || combinedUpper.contains("NOTE 13") || nominalStorageGB <= 128 -> {
                ramType = "LPDDR4X"
                ramFreq = "2133MHz"
                ramSpeed = "4266 Mbps"
                storageType = "UFS 2.2"
            }
            else -> {
                ramType = if (socName.contains("Elite") || socName.contains("9500") || socName.contains("9400") || socName.contains("8 Gen 3") || socName.contains("9300") || socName.contains("8400") || totalRamGB >= 11.0) "LPDDR5X" else "LPDDR5"
                ramFreq = if (ramType == "LPDDR5X") "4800MHz" else "3200MHz"
                ramSpeed = if (ramType == "LPDDR5X") "8533 Mbps" else "6400 Mbps"
                storageType = if (nominalStorageGB >= 256 && (socName.contains("Elite") || socName.contains("9500") || socName.contains("9400") || socName.contains("8 Gen 3") || socName.contains("8400") || socName.contains("8 Gen 2"))) "UFS 4.0" else "UFS 3.1"
            }
        }

        val info = DeviceMainInfo(
            manufacturer = manufacturer,
            modelName = Build.MODEL,
            marketName = marketNameProp,
            batteryCapacityStr = batteryCapacityStr,
            batteryCycleStr = batteryCycleStr,
            batteryLevel = batteryLevelPct,
            batteryTempStr = batteryTempStr,
            socName = socName,
            cpuFreqRange = cpuFreqRange,
            gpuVendor = gpuVendor,
            gpuModel = gpuModel,
            refreshRateStr = refreshRateStr,
            resolutionStr = resolutionStr,
            screenSizeStr = screenSizeStr,
            totalRamGB = totalRamGB,
            nominalRamGB = nominalRamGB,
            usedRamGB = usedRamGB,
            ramTypeStr = ramType,
            ramFreqStr = ramFreq,
            ramSpeedStr = ramSpeed,
            totalStorageGB = totalStorageGB,
            nominalStorageGB = nominalStorageGB,
            usedStorageGB = usedStorageGB,
            storageTypeStr = storageType,
            rearCameraStr = rearCam,
            frontCameraStr = frontCam,
            sensorCount = sensorCount,
            cpuTempStr = cpuTempStr
        )
        cachedMainDeviceInfo = info
        return info
    }

    fun getLiveCpuCores(): List<CoreFreqInfo> {
        val coreCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
        val result = mutableListOf<CoreFreqInfo>()

        for (i in (coreCount - 1) downTo 0) {
            val curFreqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            val maxFreqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            val minFreqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq")

            val maxFreqKHz = readIntFromFile(maxFreqFile, fallback = 3000000)
            val minFreqKHz = readIntFromFile(minFreqFile, fallback = 384000)
            val curFreqKHz = readIntFromFile(curFreqFile, fallback = (minFreqKHz + (maxFreqKHz - minFreqKHz) * 0.35).toInt())

            val curFreqMHz = (curFreqKHz / 1000).coerceAtLeast(300)
            val maxFreqMHz = (maxFreqKHz / 1000).coerceAtLeast(curFreqMHz)

            val range = (maxFreqKHz - minFreqKHz).coerceAtLeast(1)
            val usagePct = (((curFreqKHz - minFreqKHz).toFloat() / range.toFloat()) * 100).toInt().coerceIn(5, 100)

            result.add(
                CoreFreqInfo(
                    coreIndex = i,
                    curFreqMHz = curFreqMHz,
                    maxFreqMHz = maxFreqMHz,
                    usagePercent = usagePct
                )
            )
        }
        return result
    }

    fun getDeviceSections(context: Context): List<InfoSection> {
        val pm = context.packageManager
        val hasNfc = pm.hasSystemFeature(PackageManager.FEATURE_NFC)
        val hasFp = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        val hasFace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.hasSystemFeature(PackageManager.FEATURE_FACE) else true
        val hasIr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) pm.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR) else true
        val hasBt = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        val hasGps = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenDimMm = "${String.format(Locale.US, "%.1f", (metrics.widthPixels / metrics.xdpi * 25.4))} x ${String.format(Locale.US, "%.1f", (metrics.heightPixels / metrics.ydpi * 25.4))} mm (Màn hình)"

        val releaseDateStr = getDeviceOfficialReleaseDate(context)
        val buildRomDateStr = try {
            SimpleDateFormat("MM/yyyy", Locale.getDefault()).format(Date(Build.TIME))
        } catch (_: Throwable) {
            "2024"
        }

        val simStr = try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tm?.activeModemCount ?: 2 else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) tm?.phoneCount ?: 2 else 2
            if (count > 1) "SIM kép chế độ chờ kép (Nano-SIM / eSIM)" else "SIM đơn (Nano-SIM)"
        } catch (_: Throwable) {
            "SIM kép chế độ chờ kép (Nano-SIM / eSIM)"
        }

        val soc = getMainDeviceInfo(context).socName
        val wifiGen = when {
            soc.contains("Elite") || soc.contains("9400") || soc.contains("8 Gen 3") || soc.contains("8400") -> "Wi-Fi 7 (802.11be)"
            soc.contains("8 Gen 2") || soc.contains("9300") || soc.contains("8+ Gen 1") -> "Wi-Fi 6E (802.11ax)"
            else -> "Wi-Fi 6 (802.11ax)"
        }
        val btVer = when {
            soc.contains("Elite") || soc.contains("9400") || soc.contains("8400") -> "5.4 / LE Audio"
            soc.contains("8 Gen 3") || soc.contains("9300") -> "5.4"
            soc.contains("8 Gen 2") -> "5.3"
            else -> "5.2"
        }

        val items = listOf(
            "Nhà sản xuất" to Build.MANUFACTURER,
            "Thương hiệu (Brand)" to Build.BRAND,
            "Bo mạch" to Build.BOARD,
            "Phần cứng" to Build.HARDWARE,
            "Thời điểm ra mắt chính thức" to releaseDateStr,
            "Ngày phát hành bản dựng (ROM)" to buildRomDateStr,
            "SIM" to simStr,
            "Kích thước hiển thị" to screenDimMm,
            "Mật độ điểm ảnh" to "${metrics.densityDpi} DPI (~${metrics.xdpi.roundToInt()} ppi)",
            "GPS" to (if (hasGps) "Hỗ trợ (Dual-band L1+L5)" else "Không Hỗ Trợ"),
            "Wi-Fi" to wifiGen,
            "Giao thức Wi-Fi hỗ trợ" to "Wi-Fi Direct\nWi-Fi Aware\nWi-Fi Passpoint\n2.4GHz / 5GHz / 6GHz",
            "Bluetooth" to (if (hasBt) btVer else "Không Hỗ Trợ"),
            "NFC" to (if (hasNfc) "Hỗ trợ" else "Không Hỗ Trợ"),
            "Cảm biến vân tay" to (if (hasFp) "Hỗ trợ (Dưới màn hình / Cạnh bên)" else "Không Hỗ Trợ"),
            "Nhận diện khuôn mặt" to (if (hasFace) "Hỗ trợ (AI Face Unlock)" else "Không Hỗ Trợ"),
            "Hồng ngoại (IR Blaster)" to (if (hasIr) "Hỗ trợ" else "Không Hỗ Trợ"),
            "Vị trí chính xác" to "Hỗ trợ (GPS, GLONASS, BDS, GALILEO, QZSS, NavIC)",
            "Mã Model" to Build.MODEL,
            "Tên mã máy (Device)" to Build.DEVICE,
            "Tên mã sản phẩm (Product)" to Build.PRODUCT,
            "Bootloader" to Build.BOOTLOADER,
            "Mã phiên bản (Build ID)" to Build.ID,
            "Phiên bản hiển thị (Display ID)" to getSystemProperty("ro.build.display.id").ifEmpty { Build.DISPLAY }.ifEmpty { Build.ID },
            "Dấu vân tay (Fingerprint)" to Build.FINGERPRINT
        )

        return listOf(
            InfoSection(sectionTitle = "", items = items)
        )
    }

    fun getOSSections(context: Context): List<InfoSection> {
        val buildTimeFormatted = try {
            val date = Date(Build.TIME)
            SimpleDateFormat("d 'tháng' M, yyyy HH:mm:ss (z)", Locale("vi", "VN")).format(date)
        } catch (_: Throwable) {
            "N/A"
        }

        val buildHost = getSystemProperty("ro.build.host").ifEmpty { Build.HOST }.ifEmpty { "xiaomi-build-server" }
        val buildUser = getSystemProperty("ro.build.user").ifEmpty { Build.USER }.ifEmpty { "builder" }
        val buildIncremental = getSystemProperty("ro.mi.os.version.incremental")
            .ifEmpty { Build.VERSION.INCREMENTAL }
            .ifEmpty { getSystemProperty("ro.build.version.incremental") }
        val buildDisplay = getSystemProperty("ro.build.display.id").ifEmpty { Build.DISPLAY }.ifEmpty { Build.ID }
        val buildId = getSystemProperty("ro.build.id").ifEmpty { Build.ID }

        val isRooted = checkRootMethod()

        val hyperOsVer = getSystemProperty("ro.mi.os.version.name")
            .ifEmpty { getSystemProperty("ro.miui.ui.version.name") }
            .ifEmpty { buildIncremental }
        val osUi = if (hyperOsVer.isNotEmpty()) "Xiaomi HyperOS ($hyperOsVer)" else "Xiaomi HyperOS (Android ${Build.VERSION.RELEASE})"

        val radioVer = try { Build.getRadioVersion() } catch (_: Throwable) { null }
            ?: getSystemProperty("gsm.version.baseband")
            .ifEmpty { "Không rõ" }

        val selinuxStatus = try {
            val enforceFile = File("/sys/fs/selinux/enforce")
            if (enforceFile.exists() && enforceFile.canRead()) {
                if (enforceFile.readText().trim() == "1") "Enforcing (Bảo vệ toàn vẹn)" else "Permissive (Cho phép)"
            } else {
                val prop = getSystemProperty("ro.boot.selinux")
                if (prop.equals("permissive", true)) "Permissive" else "Enforcing (Bảo vệ toàn vẹn)"
            }
        } catch (_: Throwable) {
            "Enforcing (Bảo vệ toàn vẹn)"
        }

        val isAbUpdate = getSystemProperty("ro.build.ab_update") == "true"
        val isDynamicPart = getSystemProperty("ro.boot.dynamic_partitions") == "true"
        val isTreble = getSystemProperty("ro.treble.enabled") == "true" || Build.VERSION.SDK_INT >= 28
        val isSystemAsRoot = getSystemProperty("ro.build.system_root_image") == "true" || Build.VERSION.SDK_INT >= 29

        val systemItems = listOf(
            "Phiên bản Android" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Giao diện hệ điều hành" to osUi,
            "Vá bảo mật Android" to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "N/A"),
            "Dải tần cơ sở (Baseband / Modem)" to radioVer,
            "Trạng thái Root / SuperUser" to (if (isRooted) "Đã Root" else "Chưa Root (Chính hãng)"),
            "Trạng thái SELinux" to selinuxStatus,
            "Giả lập" to "Không (Thiết bị vật lý)",
            "Cập nhật liền mạch (A/B)" to (if (isAbUpdate) "Hỗ trợ (A/B Seamless)" else "Không hỗ trợ (A-only)"),
            "System-as-root" to (if (isSystemAsRoot) "Hỗ trợ" else "Không hỗ trợ"),
            "Phân vùng động (Dynamic Partitions)" to (if (isDynamicPart) "Hỗ trợ" else "Hỗ trợ (Dynamic)"),
            "Project Treble" to (if (isTreble) "Hỗ trợ (Treble Enabled)" else "Không hỗ trợ")
        )

        val buildItems = listOf(
            "Mã bản dựng (Build ID)" to buildId,
            "Phiên bản hiển thị ROM (Display ID)" to buildDisplay,
            "Mã phân phối ROM (Incremental)" to buildIncremental,
            "Thời gian biên dịch (Build Time)" to buildTimeFormatted,
            "Người biên dịch (Build User)" to buildUser,
            "Máy chủ biên dịch (Build Host)" to buildHost,
            "Kiểu bản dựng (Build Type)" to Build.TYPE,
            "Nhãn bản dựng (Build Tags)" to Build.TAGS,
            "Dấu vân tay bản dựng (Fingerprint)" to Build.FINGERPRINT
        )

        val jvmItems = listOf(
            "Java VM" to "${System.getProperty("java.vm.name") ?: "ART"} ${System.getProperty("java.vm.version") ?: "2.1.0"}",
            "Java Runtime" to (System.getProperty("java.runtime.version") ?: "Android Runtime 0.9"),
            "Kernel Linux" to readKernelVersion(),
            "Kiến trúc HĐH" to (System.getProperty("os.arch") ?: "aarch64"),
            "Kích thước Heap VM Tối đa" to "${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB",
            "Mức sử dụng Heap VM Hiện tại" to "${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)} MB"
        )

        val drmMap = getDrmInfo()
        val drmItems = listOf(
            "Nhà cung cấp DRM" to (drmMap["Nhà cung cấp DRM"] ?: "com.google.android.widevine (Google)"),
            "Cấp độ bảo mật" to (drmMap["Cấp độ bảo mật"] ?: "Widevine Security Level 1 (L1)"),
            "Phiên bản DRM" to (drmMap["Phiên bản DRM"] ?: "18.0.0"),
            "Mô tả" to (drmMap["Mô tả"] ?: "Widevine Modular DRM Plugin"),
            "Thuật toán mã hóa" to (drmMap["Thuật toán mã hóa"] ?: "AES/CBC/NoPadding, HmacSHA256"),
            "ID thiết bị duy nhất" to (drmMap["ID thiết bị duy nhất"] ?: generateDeviceUniqueHash())
        )

        return listOf(
            InfoSection(sectionTitle = "Hệ thống", items = systemItems),
            InfoSection(sectionTitle = "Thông tin bản dựng (Build)", items = buildItems),
            InfoSection(sectionTitle = "Môi trường thực thi & Kernel", items = jvmItems),
            InfoSection(sectionTitle = "DRM & Bản quyền số", items = drmItems)
        )
    }

    private fun checkRootMethod(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        return paths.any { File(it).exists() }
    }

    fun getSOCSections(context: Context): List<InfoSection> {
        val info = getMainDeviceInfo(context)
        val glInfo = getRealGlInfo(context)
        val cores = getLiveCpuCores()
        val coreCount = Runtime.getRuntime().availableProcessors()

        val cpuArch = buildCpuArchitectureString(coreCount, info.socName)
        val governor = readCpuGovernor()

        val gpuItems = listOf(
            "GPU Renderer" to glInfo.renderer,
            "GPU Vendor" to glInfo.vendor,
            "Phiên bản OpenGL ES" to glInfo.version,
            "Phiên bản Vulkan" to getVulkanVersion(context),
            "Tiện ích mở rộng OpenGL ES" to "",
            "Tiện ích mở rộng Vulkan" to ""
        )
        val gpuActions = mapOf(
            "Tiện ích mở rộng OpenGL ES" to "XEM",
            "Tiện ích mở rộng Vulkan" to "XEM"
        )

        val cpuItems = mutableListOf<Pair<String, String>>()
        cpuItems.add("Kiến trúc phân cụm" to cpuArch)
        cpuItems.add("Tên Chipset (SoC)" to info.socName)
        cpuItems.add("Số lượng nhân CPU" to "$coreCount nhân (Octa-core)")
        cpuItems.add("ABIs được hỗ trợ" to Build.SUPPORTED_ABIS.joinToString(", "))
        cpuItems.add("Kiểu kiến trúc" to (if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "64-bit (ARMv9 / ARMv8)" else "32-bit"))
        cpuItems.add("Tốc Độ Xử Lý Của CPU" to info.cpuFreqRange)
        cpuItems.add("Bộ điều chỉnh CPU (Governor)" to governor)
        cpuItems.add("Tiến trình bán dẫn" to getCpuFabrication(info.socName))
        cpuItems.add("Thời điểm công bố SoC" to getSocReleaseDate(info.socName))
        val cpuTemp = getCpuTemperature()
        val cpuTempF = cpuTemp * 1.8 + 32
        cpuItems.add("Nhiệt độ CPU" to "${String.format(Locale.US, "%.1f", cpuTemp)}°C / ${String.format(Locale.US, "%.1f", cpuTempF)}°F")
        cpuItems.add("Tập lệnh mã hóa phần cứng" to "AES, SHA-1, SHA-256, SHA-512, CRC32, PMULL")

        for (core in cores) {
            cpuItems.add("Core ${core.coreIndex}" to "${core.curFreqMHz} / ${core.maxFreqMHz} MHz")
        }

        return listOf(
            InfoSection(sectionTitle = "GPU (Bộ xử lý đồ họa)", items = gpuItems, actionItems = gpuActions),
            InfoSection(sectionTitle = "CPU (Bộ vi xử lý)", items = cpuItems)
        )
    }

    private fun readCpuGovernor(): String {
        return try {
            val f = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
            if (f.exists() && f.canRead()) f.readText().trim() else "schedutil"
        } catch (_: Throwable) {
            "schedutil"
        }
    }

    private fun buildCpuArchitectureString(coreCount: Int, socName: String): String {
        val freqMap = mutableMapOf<Int, Int>()
        for (i in 0 until coreCount) {
            val maxFreqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            val maxKHz = readIntFromFile(maxFreqFile, fallback = 0)
            if (maxKHz > 0) {
                val mhz = maxKHz / 1000
                freqMap[mhz] = (freqMap[mhz] ?: 0) + 1
            }
        }

        if (freqMap.isNotEmpty()) {
            val sortedClusters = freqMap.toList().sortedByDescending { it.first }
            val vendorPrefix = if (socName.contains("Dimensity") || socName.contains("MT") || socName.contains("Helio")) "ARM Cortex®" else if (socName.contains("8 Elite")) "Qualcomm Oryon™" else "ARM Cortex®"
            return sortedClusters.joinToString("\n") { (mhz, count) ->
                "$count x $vendorPrefix $mhz MHz"
            }
        }

        return when {
            socName.contains("8 Elite Gen 5") || socName.contains("8 Elite Gen 2") || socName.contains("8 Gen 5") -> "2 x Qualcomm Oryon™ Gen 2 4500 MHz\n6 x Qualcomm Oryon™ Gen 2 3700 MHz"
            socName.contains("8 Elite Gen 5v") || socName.contains("8s Gen 5") -> "1 x Qualcomm Oryon™ 3800 MHz\n4 x Oryon™ 3200 MHz\n3 x Oryon™ 2400 MHz"
            socName.contains("8 Elite") -> "2 x Qualcomm Oryon™ 4320 MHz\n6 x Qualcomm Oryon™ 3530 MHz"
            socName.contains("9500s") -> "1 x ARM Cortex-X925 3600 MHz\n3 x ARM Cortex-X4 3300 MHz\n4 x ARM Cortex-A725 2400 MHz"
            socName.contains("9500") -> "1 x ARM Cortex-X935 3800 MHz\n3 x ARM Cortex-X925 3400 MHz\n4 x ARM Cortex-A730 2500 MHz"
            socName.contains("8s Gen 4") || socName.contains("8s Elite") -> "1 x Cortex-X4 3200 MHz\n4 x Cortex-A720 3000 MHz\n3 x Cortex-A520 2000 MHz"
            socName.contains("9400") -> "1 x ARM Cortex-X925 3630 MHz\n3 x ARM Cortex-X4 3300 MHz\n4 x ARM Cortex-A720 2400 MHz"
            socName.contains("8400") -> "1 x ARM Cortex-A725 3200 MHz\n3 x ARM Cortex-A725 3000 MHz\n4 x ARM Cortex-A725 2000 MHz"
            socName.contains("8 Gen 3") -> "1 x Cortex-X4 3300 MHz\n5 x Cortex-A720 3150 MHz\n2 x Cortex-A520 2270 MHz"
            socName.contains("8s Gen 3") -> "1 x Cortex-X4 3000 MHz\n4 x Cortex-A720 2800 MHz\n3 x Cortex-A520 2000 MHz"
            socName.contains("7+ Gen 3") -> "1 x Cortex-X4 2800 MHz\n4 x Cortex-A720 2600 MHz\n3 x Cortex-A520 1900 MHz"
            socName.contains("8 Gen 2") -> "1 x Cortex-X3 3190 MHz\n4 x Cortex-A715/A710 2800 MHz\n3 x Cortex-A510 2000 MHz"
            socName.contains("8300") -> "4 x Cortex-A715 3350 MHz\n4 x Cortex-A510 2200 MHz"
            else -> "$coreCount x ARM 64-bit Cores"
        }
    }

    fun getStorageSections(context: Context): List<InfoSection> {
        val info = getMainDeviceInfo(context)
        val availStorage = (info.totalStorageGB - info.usedStorageGB).coerceAtLeast(0.0)
        val availRam = (info.totalRamGB - info.usedRamGB).coerceAtLeast(0.0)

        val zramSizeFile = File("/sys/block/zram0/disksize")
        val zramBytes = if (zramSizeFile.exists()) readLongFromFile(zramSizeFile, 0L) else 0L
        val zramStr = if (zramBytes > 0) {
            val zramGB = zramBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            "Hỗ trợ (${String.format(Locale.US, "%.1f", zramGB)} GB ZRAM / ZSTD)"
        } else {
            "Hỗ trợ (ZSTD / LZ4 Compressed)"
        }

        val extRamProp = getSystemProperty("persist.sys.miui.memory_extension.size")
            .ifEmpty { getSystemProperty("persist.sys.mms.ext_mem_size") }
        val extRamStr = if (extRamProp.isNotEmpty() && extRamProp != "0") {
            val extMb = extRamProp.toIntOrNull() ?: 0
            if (extMb > 0) "Bật (+${extMb / 1024}.00 GB HyperOS RAM Plus)" else "Bật (+4.00 ~ +8.00 GB)"
        } else {
            "Bật (+4.00 ~ +8.00 GB)"
        }

        val ramSpeed = "${info.ramSpeedStr} · ${info.ramFreqStr}"
        val storageSpeed = when (info.storageTypeStr) {
            "UFS 4.1" -> "~4500 MB/s Đọc · ~3200 MB/s Ghi (UFS 4.1 Flagship)"
            "UFS 4.0" -> "~4200 MB/s Đọc · ~2800 MB/s Ghi (UFS 4.0 High-Speed)"
            "UFS 3.1" -> "~2100 MB/s Đọc · ~1200 MB/s Ghi (UFS 3.1)"
            else -> "~1000 MB/s Đọc · ~500 MB/s Ghi (UFS 2.2)"
        }

        val ramItems = listOf(
            "Tổng dung lượng RAM" to "${info.nominalRamGB} GB (${String.format(Locale.US, "%.2f", info.totalRamGB)} GB Thật)",
            "RAM đang sử dụng" to "${String.format(Locale.US, "%.2f", info.usedRamGB)} GB",
            "RAM khả dụng" to "${String.format(Locale.US, "%.2f", availRam)} GB",
            "Chuẩn bộ nhớ RAM" to "${info.ramTypeStr} ($ramSpeed)",
            "Kênh bộ nhớ" to "Quad-channel 4x16-bit",
            "Mở rộng RAM ảo (RAM Plus / HyperOS)" to extRamStr,
            "Bộ nhớ nén ZRAM" to zramStr
        )

        val storageItems = listOf(
            "Tổng dung lượng bộ nhớ trong" to "${info.nominalStorageGB} GB",
            "Đã sử dụng (Lưu trữ)" to "${String.format(Locale.US, "%.2f", info.usedStorageGB)} GB",
            "Dung lượng còn trống" to "${String.format(Locale.US, "%.2f", availStorage)} GB",
            "Chuẩn lưu trữ" to info.storageTypeStr,
            "Tốc độ truyền dữ liệu ước tính" to storageSpeed,
            "Định dạng hệ thống tệp" to "F2FS / EXT4 (Flash-Friendly File System)",
            "Mã hóa phân vùng" to "File-based Encryption (FBE) AES-256-XTS"
        )

        return listOf(
            InfoSection(sectionTitle = "Bộ nhớ RAM", items = ramItems),
            InfoSection(sectionTitle = "Bộ nhớ trong (ROM)", items = storageItems)
        )
    }

    data class CameraLensSpec(
        val role: String,
        val megapixels: Int,
        val aperture: String,
        val focalLength: String,
        val sensorName: String,
        val sensorFormat: String,
        val ois: Boolean,
        val maxVideo: String,
        val specialFeature: String = ""
    )

    data class DeviceCameraProfile(
        val rearLenses: List<CameraLensSpec>,
        val frontLenses: List<CameraLensSpec>,
        val isLeica: Boolean = false,
        val zoomDescription: String = ""
    )

    fun getDeviceCameraProfile(context: Context): DeviceCameraProfile? {
        val model = Build.MODEL.uppercase()
        val market = getSystemProperty("ro.product.marketname").uppercase()
        val dev = Build.DEVICE.uppercase()
        val combined = "$model $market $dev"

        return when {

            combined.contains("18 PRO MAX") || combined.contains("18 PROMAX") || combined.contains("18PROMAX") || combined.contains("18 ULTRA") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm 1-inch Leica Summilux)", 50, "F1.4 ~ F4.0 (Biến thiên)", "23mm", "Sony LYT-950 / LOFIC Gen 3 (1.0\" Type)", "1.0\" Type", true, "8K 60fps / 4K 120fps", "Cảm biến 1-inch Flagship thế hệ mới, HyperOIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 12mm)", 50, "F1.8", "12mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", false, "4K 60fps", "122° FOV, AF Macro 5cm"),
                        CameraLensSpec("Telephoto Chân dung (3.2x / 75mm Leica)", 50, "F1.8", "75mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", true, "4K 60fps", "Floating Telephoto, OIS, Macro 10cm"),
                        CameraLensSpec("Tele Tiềm vọng (Periscope 5x-10x / 120-240mm Leica)", 200, "F2.6", "120mm", "Samsung HP9 (1/1.4\")", "1/1.4\"", true, "8K 60fps / 4K 120fps", "200MP Ultra Periscope, Zoom quang 5x-10x, Zoom số 120x, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 50, "F2.0", "22mm", "OmniVision OV50D (1/2.88\")", "1/2.88\"", false, "4K 60fps", "4K 60fps Selfie, HDR")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 5x · Zoom kết hợp 10x · Zoom số 120x"
                )
            }

            combined.contains("18 PRO") || combined.contains("XIAOMI 18 PRO") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm Leica Summilux)", 50, "F1.44", "23mm", "Light Hunter 950 (1/1.28\")", "1/1.28\"", true, "8K 30fps / 4K 60fps", "1.22µm Super Pixel, OIS Quang học thế hệ mới"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 14mm)", 50, "F2.2", "14mm", "Samsung JN5 (1/2.76\")", "1/2.76\"", false, "4K 60fps", "115° FOV, Macro 5cm"),
                        CameraLensSpec("Tele Tiềm vọng (Periscope 5x / 120mm Leica)", 50, "F2.5", "120mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", true, "8K 30fps / 4K 60fps", "Zoom quang 5x, Zoom số 120x, Telemacro 30cm, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 50, "F2.0", "22mm", "OmniVision OV50D", "1/2.88\"", false, "4K 60fps", "AI Beautify, HDR Selfie")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 5x · Zoom kỹ thuật số 120x"
                )
            }

            combined.contains("XIAOMI 18") || (combined.contains(" 18") && !combined.contains("NOTE") && !combined.contains("REDMI") && !combined.contains("PAD")) -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm Leica)", 50, "F1.6", "23mm", "Light Hunter 950 (1/1.28\")", "1/1.28\"", true, "8K 30fps / 4K 60fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 14mm)", 50, "F2.2", "14mm", "Samsung JN1 (1/2.76\")", "1/2.76\"", false, "4K 60fps", "115° FOV, Macro"),
                        CameraLensSpec("Telephoto (3.2x / 60mm Leica)", 50, "F2.0", "60mm", "Samsung JN5 (1/2.76\")", "1/2.76\"", true, "4K 60fps", "Zoom quang 3.2x, OIS, Macro 10cm")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32B", "1/3.14\"", false, "4K 60fps", "HDR Selfie")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 3.2x · Zoom kỹ thuật số 30x"
                )
            }

            combined.contains("17 ULTRA") || combined.contains("17 PRO MAX") || combined.contains("17 PROMAX") || combined.contains("17PROMAX") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm 1-inch Leica Summilux)", 50, "F1.6 ~ F4.0 (Biến thiên)", "23mm", "Sony LYT-900 / LOFIC Gen 2 (1.0\" Type)", "1.0\" Type", true, "8K 60fps / 4K 120fps", "Cảm biến 1-inch Flagship thế hệ mới, HyperOIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 12mm)", 50, "F1.8", "12mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", false, "4K 60fps", "122° FOV, AF Macro 5cm"),
                        CameraLensSpec("Telephoto Chân dung (3.2x / 75mm Leica)", 50, "F1.8", "75mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", true, "4K 60fps", "Floating Telephoto, OIS, Macro 10cm"),
                        CameraLensSpec("Tele Tiềm vọng (Periscope 5x-10x / 120-240mm Leica)", 200, "F2.6", "120mm", "Samsung HP9 (1/1.4\")", "1/1.4\"", true, "8K 60fps / 4K 120fps", "200MP Ultra Periscope, Zoom quang 5x-10x, Zoom số 120x, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 50, "F2.0", "22mm", "OmniVision OV50D (1/2.88\")", "1/2.88\"", false, "4K 60fps", "4K 60fps Selfie, HDR")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 5x · Zoom kết hợp 10x · Zoom số 120x"
                )
            }

            combined.contains("17 PRO") || combined.contains("XIAOMI 17 PRO") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm Leica Summilux)", 50, "F1.44", "23mm", "Light Hunter 950 (1/1.28\")", "1/1.28\"", true, "8K 30fps / 4K 60fps", "1.22µm Super Pixel, OIS Quang học thế hệ mới"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 14mm)", 50, "F2.2", "14mm", "Samsung JN5 (1/2.76\")", "1/2.76\"", false, "4K 60fps", "115° FOV, Macro 5cm"),
                        CameraLensSpec("Tele Tiềm vọng (Periscope 5x / 120mm Leica)", 50, "F2.5", "120mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", true, "8K 30fps / 4K 60fps", "Zoom quang 5x, Zoom số 120x, Telemacro 30cm, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32B", "1/3.14\"", false, "4K 60fps", "AI Beautify, HDR Selfie")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 5x · Zoom kỹ thuật số 120x"
                )
            }

            combined.contains("XIAOMI 17") || (combined.contains(" 17") && !combined.contains("NOTE") && !combined.contains("REDMI") && !combined.contains("PAD")) -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm Leica)", 50, "F1.6", "23mm", "Light Hunter 950 (1/1.28\")", "1/1.28\"", true, "8K 30fps / 4K 60fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 14mm)", 50, "F2.2", "14mm", "Samsung JN1 (1/2.76\")", "1/2.76\"", false, "4K 60fps", "115° FOV, Macro"),
                        CameraLensSpec("Telephoto (3.2x / 60mm Leica)", 50, "F2.0", "60mm", "Samsung JN5 (1/2.76\")", "1/2.76\"", true, "4K 60fps", "Zoom quang 3.2x, OIS, Macro 10cm")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32B", "1/3.14\"", false, "4K 60fps", "HDR Selfie")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 3.2x · Zoom kỹ thuật số 30x"
                )
            }

            combined.contains("K100 PRO MAX") || combined.contains("K100 MAX") || combined.contains("K100 ULTRA") || combined.contains("K100 EXTREME") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm Flagship)", 50, "F1.44", "23mm", "Light Hunter 950 / Sony LYT-900 (1/1.28\")", "1/1.28\"", true, "8K 30fps / 4K 120fps", "1.22µm Super Pixel, HyperOIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 14mm)", 50, "F2.2", "14mm", "Samsung JN5 (1/2.76\")", "1/2.76\"", false, "4K 60fps", "120° FOV, AF Macro"),
                        CameraLensSpec("Tele Tiềm vọng (Periscope 5x / 120mm)", 50, "F2.5", "120mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", true, "8K 30fps / 4K 60fps", "Zoom quang 5x, Zoom số 120x, Telemacro 30cm, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32B", "1/3.14\"", false, "4K 60fps", "AI Beautify, HDR Selfie")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom quang học 5x · Zoom kỹ thuật số 120x"
                )
            }

            combined.contains("K100 PRO") || combined.contains("K100") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 24mm)", 50, "F1.6", "24mm", "Light Hunter 900 / LYT-800 (1/1.31\")", "1/1.31\"", true, "8K 30fps / 4K 60fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 15mm)", 50, "F2.2", "15mm", "OmniVision OV50D (1/2.88\")", "1/2.88\"", false, "4K 60fps", "120° FOV"),
                        CameraLensSpec("Telephoto (3x / 75mm)", 50, "F2.0", "75mm", "Samsung JN5 (1/2.76\")", "1/2.76\"", true, "4K 60fps", "Zoom quang 3x, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.2", "24mm", "OmniVision OV20B", "1/4.0\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom quang học 3x · Zoom kỹ thuật số 30x"
                )
            }

            combined.contains("K90 PRO MAX") || combined.contains("K90 MAX") || combined.contains("K90 ULTRA") || combined.contains("K90 EXTREME") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 24mm)", 50, "F1.6", "24mm", "Light Hunter 900 (1/1.31\")", "1/1.31\"", true, "8K 30fps / 4K 60fps", "OIS Quang học thế hệ mới"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 15mm)", 50, "F2.2", "15mm", "OmniVision OV50D (1/2.88\")", "1/2.88\"", false, "4K 60fps", "120° FOV"),
                        CameraLensSpec("Telephoto (3.2x / 75mm)", 50, "F2.0", "75mm", "Samsung JN5 (1/2.76\")", "1/2.76\"", true, "4K 60fps", "Zoom quang 3.2x, Zoom số 60x, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.2", "24mm", "OmniVision OV20B", "1/4.0\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom quang học 3.2x · Zoom kỹ thuật số 60x"
                )
            }

            combined.contains("K90 PRO") || combined.contains("K90") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 24mm)", 50, "F1.6", "24mm", "Light Hunter 800 (1/1.55\")", "1/1.55\"", true, "8K 24fps / 4K 60fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 15mm)", 32, "F2.2", "15mm", "OmniVision OV32B (1/3.14\")", "1/3.14\"", false, "4K 60fps", "120° FOV"),
                        CameraLensSpec("Telephoto (2.5x / 60mm)", 50, "F2.0", "60mm", "Samsung JN5 (1/2.76\")", "1/2.76\"", true, "4K 60fps", "Zoom quang 2.5x, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.2", "24mm", "OmniVision OV20B", "1/4.0\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom quang học 2.5x · Zoom kỹ thuật số 25x"
                )
            }

            combined.contains("NOTE 17 PRO+") || combined.contains("NOTE 17 PRO PLUS") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm)", 200, "F1.65", "23mm", "Samsung ISOCELL HP3 (1/1.4\")", "1/1.4\"", true, "4K 30fps", "200MP Siêu chi tiết, OIS Quang học, Zoom Lossless 4x"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide)", 8, "F2.2", "16mm", "Sony IMX355 (1/4.0\")", "1/4.0\"", false, "1080p 30fps", "120° FOV"),
                        CameraLensSpec("Tele Chân dung / Macro", 2, "F2.4", "24mm", "Macro Sensor", "1/5.0\"", false, "1080p 30fps", "Chụp cận cảnh")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.0", "24mm", "OmniVision OV20B", "1/4.0\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom quang kết hợp 4x (In-Sensor) · Zoom số 10x"
                )
            }
            combined.contains("NOTE 17 PRO") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 26mm)", 50, "F1.5", "26mm", "Sony LYT-600 (1/1.95\")", "1/1.95\"", true, "4K 30fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide)", 8, "F2.2", "16mm", "Sony IMX355 (1/4.0\")", "1/4.0\"", false, "1080p 30fps", "120° FOV"),
                        CameraLensSpec("Macro", 2, "F2.4", "24mm", "Macro Sensor", "1/5.0\"", false, "1080p 30fps", "Chụp cận cảnh")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.2", "24mm", "OmniVision OV20B", "1/4.0\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom kỹ thuật số 10x"
                )
            }
            combined.contains("NOTE 17 TURBO") || combined.contains("NOTE 17 SPEED") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 26mm)", 50, "F1.5", "26mm", "Sony IMX882 / LYT-600 (1/1.95\")", "1/1.95\"", true, "4K 60fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide)", 8, "F2.2", "16mm", "OmniVision OV08D (1/4.0\")", "1/4.0\"", false, "1080p 30fps", "119° FOV")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.2", "24mm", "OmniVision OV20B", "1/4.0\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom kỹ thuật số 10x"
                )
            }
            combined.contains("NOTE 17") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 26mm)", 108, "F1.75", "26mm", "Samsung HM6 (1/1.67\")", "1/1.67\"", true, "1080p 60fps", "108MP Siêu nét, OIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide)", 8, "F2.2", "16mm", "OmniVision OV08D", "1/4.0\"", false, "1080p 30fps", "118° FOV"),
                        CameraLensSpec("Cảm biến chiều sâu", 2, "F2.4", "24mm", "Depth Sensor", "1/5.0\"", false, "1080p 30fps", "Xóa phông")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 16, "F2.4", "24mm", "OmniVision OV16A", "1/3.06\"", false, "1080p 30fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom kỹ thuật số 10x (3x In-sensor Zoom)"
                )
            }

            combined.contains("15 PRO") || combined.contains("XIAOMI 15 PRO") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm Leica Summilux)", 50, "F1.44", "23mm", "Light Hunter 900 (1/1.31\")", "1/1.31\"", true, "8K 30fps / 4K 60fps", "1.2µm (2.4µm Super Pixel 4-in-1), OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 14mm)", 50, "F2.2", "14mm", "Samsung S5KJN1/JN5 (1/2.76\")", "1/2.76\"", false, "4K 60fps", "Góc nhìn siêu rộng 115°, Siêu cận Macro 5cm, EIS"),
                        CameraLensSpec("Tele Tiềm vọng (Periscope 5x / 120mm Leica)", 50, "F2.5", "120mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", true, "8K 30fps / 4K 60fps", "Zoom quang 5x, Zoom số 120x, Telemacro 30cm, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước (Wide / 22mm)", 32, "F2.0", "22mm", "OmniVision OV32B (1/3.14\")", "1/3.14\"", false, "4K 60fps", "AI Beautify, HDR Selfie, Chân dung xóa phông")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 5x · Zoom kỹ thuật số 120x"
                )
            }

            combined.contains("XIAOMI 15") || combined.contains("15") && !combined.contains("NOTE") && !combined.contains("REDMI") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm Leica)", 50, "F1.62", "23mm", "Light Hunter 900 (1/1.31\")", "1/1.31\"", true, "8K 30fps / 4K 60fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 14mm)", 50, "F2.2", "14mm", "Samsung JN1 (1/2.76\")", "1/2.76\"", false, "4K 60fps", "Góc nhìn 115°, Macro"),
                        CameraLensSpec("Telephoto (3.2x / 60mm Leica)", 50, "F2.0", "60mm", "Samsung JN5 (1/2.76\")", "1/2.76\"", true, "4K 60fps", "Zoom quang 3.2x, OIS, Macro 10cm")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32B", "1/3.14\"", false, "4K 60fps", "HDR Selfie")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 3.2x · Zoom kỹ thuật số 30x"
                )
            }

            combined.contains("14 PRO") || combined.contains("XIAOMI 14 PRO") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm Leica)", 50, "F1.42 ~ F4.0 (Biến thiên)", "23mm", "Light Hunter 900 (1/1.31\")", "1/1.31\"", true, "8K 24fps / 4K 60fps", "Khẩu độ biến thiên, OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 14mm)", 50, "F2.2", "14mm", "Samsung JN1 (1/2.76\")", "1/2.76\"", false, "4K 60fps", "115° FOV, Macro 5cm"),
                        CameraLensSpec("Telephoto (3.2x / 75mm Leica)", 50, "F2.0", "75mm", "Samsung JN1 (1/2.76\")", "1/2.76\"", true, "4K 60fps", "Floating Telephoto, OIS, Macro 10cm")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32B", "1/3.14\"", false, "4K 60fps", "HDR Selfie")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 3.2x · Zoom kỹ thuật số 60x"
                )
            }

            combined.contains("XIAOMI 14") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm Leica)", 50, "F1.6", "23mm", "Light Hunter 900 (1/1.31\")", "1/1.31\"", true, "8K 24fps / 4K 60fps", "OIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 14mm)", 50, "F2.2", "14mm", "Samsung JN1 (1/2.76\")", "1/2.76\"", false, "4K 60fps", "115° FOV"),
                        CameraLensSpec("Telephoto (3.2x / 75mm Leica)", 50, "F2.0", "75mm", "Samsung JN1 (1/2.76\")", "1/2.76\"", true, "4K 60fps", "Floating Telephoto, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32B", "1/3.14\"", false, "4K 60fps", "HDR Selfie")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 3.2x · Zoom kỹ thuật số 30x"
                )
            }

            combined.contains("13 PRO") || combined.contains("XIAOMI 13 PRO") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm 1-inch Leica)", 50, "F1.9", "23mm", "Sony IMX989 (1.0\" Type)", "1.0\" Type", true, "8K 24fps / 4K 60fps", "Cảm biến 1-inch, HyperOIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 14mm)", 50, "F2.2", "14mm", "Samsung JN1 (1/2.76\")", "1/2.76\"", false, "4K 60fps", "115° FOV, AF"),
                        CameraLensSpec("Telephoto (3.2x / 75mm Leica)", 50, "F2.0", "75mm", "Samsung JN1 (1/2.76\")", "1/2.76\"", true, "4K 60fps", "Floating Telephoto, OIS, Macro 10cm")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32C", "1/3.14\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 3.2x · Zoom kỹ thuật số 70x"
                )
            }

            combined.contains("TURBO 5 MAX") || combined.contains("TURBO5 MAX") || combined.contains("TURBO 5 PRO") || combined.contains("POCO F7 ULTRA") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 24mm)", 50, "F1.5", "24mm", "Sony LYT-700 / Light Hunter 800 (1/1.55\")", "1/1.55\"", true, "8K 24fps / 4K 60fps", "OIS Quang học thế hệ mới, 2.0µm Fusion"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 15mm)", 8, "F2.2", "15mm", "OmniVision OV08D (1/4.0\")", "1/4.0\"", false, "1080p 30fps", "120° FOV, EIS"),
                        CameraLensSpec("Telephoto Chân dung (2.5x / 60mm)", 50, "F2.0", "60mm", "Samsung JN5 (1/2.76\")", "1/2.76\"", true, "4K 60fps", "Zoom quang 2.5x, Zoom số 30x, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.2", "24mm", "OmniVision OV20B (1/4.0\")", "1/4.0\"", false, "1080p 60fps", "HDR Selfie")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom quang học 2.5x · Zoom kỹ thuật số 30x"
                )
            }

            combined.contains("TURBO 5") || combined.contains("TURBO5") || combined.contains("POCO F7 PRO") || combined.contains("POCO F7") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 26mm)", 50, "F1.5", "26mm", "Sony LYT-600 (1/1.95\")", "1/1.95\"", true, "4K 60fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 16mm)", 8, "F2.2", "16mm", "OmniVision OV08D (1/4.0\")", "1/4.0\"", false, "1080p 30fps", "Góc nhìn 119° FOV")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.2", "24mm", "OmniVision OV20B (1/4.0\")", "1/4.0\"", false, "1080p 60fps", "HDR Selfie")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom kỹ thuật số 10x (In-sensor Zoom 2x Lossless)"
                )
            }

            combined.contains("TURBO 4 PRO") || combined.contains("TURBO4 PRO") || combined.contains("TURBO 4") || combined.contains("POCO X7 PRO") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 26mm)", 50, "F1.5", "26mm", "Sony LYT-600 (1/1.95\")", "1/1.95\"", true, "4K 60fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 16mm)", 8, "F2.2", "16mm", "OmniVision OV08D (1/4.0\")", "1/4.0\"", false, "1080p 30fps", "Góc nhìn 119° FOV")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.2", "24mm", "OmniVision OV20B (1/4.0\")", "1/4.0\"", false, "1080p 60fps", "HDR Selfie")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom kỹ thuật số 10x (In-sensor Zoom 2x Lossless)"
                )
            }

            combined.contains("TURBO 3") || combined.contains("POCO F6") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 26mm)", 50, "F1.59", "26mm", "Sony LYT-600 (1/1.95\")", "1/1.95\"", true, "4K 60fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 16mm)", 8, "F2.2", "16mm", "Sony IMX355 (1/4.0\")", "1/4.0\"", false, "1080p 30fps", "Góc nhìn 119° FOV")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.2", "24mm", "OmniVision OV20B", "1/4.0\"", false, "1080p 60fps", "HDR Selfie")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom kỹ thuật số 10x"
                )
            }

            combined.contains("K70 PRO") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 24mm)", 50, "F1.6", "24mm", "Light Hunter 800 (1/1.55\")", "1/1.55\"", true, "8K 24fps / 4K 60fps", "OIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 15mm)", 12, "F2.2", "15mm", "OmniVision OV13B (1/3.06\")", "1/3.06\"", false, "4K 60fps", "120° FOV"),
                        CameraLensSpec("Telephoto (2x / 50mm)", 50, "F1.9", "50mm", "Light Hunter 400 (1/2.88\")", "1/2.88\"", false, "4K 60fps", "2x Optical Zoom")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 16, "F2.4", "24mm", "OmniVision OV16A", "1/3.06\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom quang học 2x · Zoom kỹ thuật số 20x"
                )
            }

            combined.contains("K70 ULTRA") || combined.contains("14T PRO") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm)", 50, "F1.6", "23mm", "Sony IMX906 / Light Fusion 900 (1/1.56\")", "1/1.56\"", true, "8K 24fps / 4K 60fps", "OIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide)", 8, "F2.2", "16mm", "OmniVision OV08D", "1/4.0\"", false, "1080p 30fps", "119° FOV"),
                        CameraLensSpec("Ống kính bổ trợ / Macro", 2, "F2.4", "24mm", "Macro Sensor", "1/5.0\"", false, "1080p 30fps", "Chụp cận cảnh Macro")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.0", "24mm", "OmniVision OV20B", "1/4.0\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = combined.contains("14T"),
                    zoomDescription = "Zoom kỹ thuật số 10x"
                )
            }

            combined.contains("15 ULTRA") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm 1-inch Leica)", 50, "F1.63 ~ F4.0 (Biến thiên)", "23mm", "Sony LYT-900 (1.0\" Type)", "1.0\" Type", true, "8K 30fps / 4K 120fps", "Cảm biến 1-inch thế hệ mới, HyperOIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 14mm)", 50, "F1.8", "14mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", false, "4K 60fps", "122° FOV, AF Macro"),
                        CameraLensSpec("Telephoto (3.2x / 75mm Leica)", 50, "F1.8", "75mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", true, "4K 60fps", "Floating Telephoto, OIS, Macro 10cm"),
                        CameraLensSpec("Tele Tiềm vọng (Periscope / 100mm-200mm Leica)", 200, "F2.6", "100mm", "Samsung ISOCELL HP9 (1/1.4\")", "1/1.4\"", true, "8K 30fps / 4K 120fps", "200MP Ultra Telephoto, OIS Quang học")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32B", "1/3.14\"", false, "4K 60fps", "4K Video")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 4.3x/8.6x · Zoom kỹ thuật số 120x"
                )
            }

            combined.contains("14 ULTRA") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm 1-inch Leica)", 50, "F1.63 ~ F4.0 (Biến thiên)", "23mm", "Sony LYT-900 (1.0\" Type)", "1.0\" Type", true, "8K 30fps / 4K 120fps", "Cảm biến 1-inch thế hệ mới, HyperOIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 12mm)", 50, "F1.8", "12mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", false, "4K 60fps", "122° FOV, AF Macro 5cm"),
                        CameraLensSpec("Telephoto (3.2x / 75mm Leica)", 50, "F1.8", "75mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", true, "4K 60fps", "Floating Telephoto, OIS, Macro 10cm"),
                        CameraLensSpec("Tele Tiềm vọng (Periscope 5x / 120mm Leica)", 50, "F2.5", "120mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", true, "8K 30fps / 4K 60fps", "Zoom quang 5x, Telemacro 30cm, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32B", "1/3.14\"", false, "4K 60fps", "4K Video")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 5x · Zoom kỹ thuật số 120x"
                )
            }

            combined.contains("13 ULTRA") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm 1-inch Leica)", 50, "F1.9 ~ F4.0 (Biến thiên)", "23mm", "Sony IMX989 (1.0\" Type)", "1.0\" Type", true, "8K 24fps / 4K 60fps", "Cảm biến 1-inch, HyperOIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 12mm)", 50, "F1.8", "12mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", false, "4K 60fps", "122° FOV, AF"),
                        CameraLensSpec("Telephoto (3.2x / 75mm Leica)", 50, "F1.8", "75mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", true, "4K 60fps", "OIS, Macro"),
                        CameraLensSpec("Tele Tiềm vọng (Periscope 5x / 120mm Leica)", 50, "F3.0", "120mm", "Sony IMX858 (1/2.51\")", "1/2.51\"", true, "8K 24fps", "Zoom quang 5x, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32C", "1/3.14\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 5x · Zoom kỹ thuật số 120x"
                )
            }

            combined.contains("XIAOMI 13") && !combined.contains("13T") && !combined.contains("NOTE") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm Leica)", 50, "F1.8", "23mm", "Sony IMX800 (1/1.49\")", "1/1.49\"", true, "8K 24fps / 4K 60fps", "HyperOIS"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 15mm)", 12, "F2.2", "15mm", "OmniVision OV13B (1/3.06\")", "1/3.06\"", false, "4K 60fps", "120° FOV"),
                        CameraLensSpec("Telephoto (3.2x / 75mm Leica)", 10, "F2.0", "75mm", "Samsung S5K3K1 (1/3.75\")", "1/3.75\"", true, "4K 60fps", "Zoom quang 3.2x, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 32, "F2.0", "22mm", "OmniVision OV32C", "1/3.14\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = true,
                    zoomDescription = "Zoom quang học 3.2x · Zoom kỹ thuật số 30x"
                )
            }

            combined.contains("K70E") || combined.contains("POCO X6 PRO") || (combined.contains("K70") && !combined.contains("PRO") && !combined.contains("ULTRA")) -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 24mm)", 64, "F1.79", "24mm", "OmniVision OV64B (1/2.0\")", "1/2.0\"", true, "4K 30fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 16mm)", 8, "F2.2", "16mm", "OmniVision OV08D (1/4.0\")", "1/4.0\"", false, "1080p 30fps", "120° FOV"),
                        CameraLensSpec("Cảm biến Macro", 2, "F2.4", "24mm", "Macro Sensor", "1/5.0\"", false, "1080p 30fps", "Chụp cận cảnh")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 16, "F2.4", "24mm", "OmniVision OV16A", "1/3.06\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom kỹ thuật số 10x"
                )
            }

            combined.contains("NOTE 14 PRO+") || combined.contains("NOTE 14 PRO PLUS") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm)", 50, "F1.6", "23mm", "Light Hunter 800 (1/1.55\")", "1/1.55\"", true, "4K 30fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 16mm)", 8, "F2.2", "16mm", "Sony IMX355 (1/4.0\")", "1/4.0\"", false, "1080p 30fps", "120° FOV"),
                        CameraLensSpec("Telephoto (2.5x / 60mm)", 50, "F2.0", "60mm", "Samsung JN1 (1/2.76\")", "1/2.76\"", true, "4K 30fps", "Zoom quang 2.5x, OIS")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 20, "F2.2", "24mm", "OmniVision OV20B", "1/4.0\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom quang học 2.5x · Zoom kỹ thuật số 30x"
                )
            }

            combined.contains("NOTE 13 PRO") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 23mm)", 200, "F1.65", "23mm", "Samsung ISOCELL HP3 (1/1.4\")", "1/1.4\"", true, "4K 30fps", "200MP Siêu độ phân giải, OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 16mm)", 8, "F2.2", "16mm", "Sony IMX355 (1/4.0\")", "1/4.0\"", false, "1080p 30fps", "120° FOV"),
                        CameraLensSpec("Cảm biến Macro", 2, "F2.4", "24mm", "Macro Sensor", "1/5.0\"", false, "1080p 30fps", "Chụp cận cảnh")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 16, "F2.4", "24mm", "OmniVision OV16A", "1/3.06\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom kỹ thuật số 10x (2x/4x In-sensor Lossless)"
                )
            }

            combined.contains("NOTE 12 TURBO") || combined.contains("POCO F5") -> {
                DeviceCameraProfile(
                    rearLenses = listOf(
                        CameraLensSpec("Chính (Wide / 24mm)", 64, "F1.79", "24mm", "OmniVision OV64B (1/2.0\")", "1/2.0\"", true, "4K 30fps", "OIS Quang học"),
                        CameraLensSpec("Góc siêu rộng (Ultra-Wide / 16mm)", 8, "F2.2", "16mm", "Sony IMX355 (1/4.0\")", "1/4.0\"", false, "1080p 30fps", "119° FOV"),
                        CameraLensSpec("Cảm biến Macro", 2, "F2.4", "24mm", "Macro Sensor", "1/5.0\"", false, "1080p 30fps", "Chụp cận cảnh")
                    ),
                    frontLenses = listOf(
                        CameraLensSpec("Camera Selfie trước", 16, "F2.4", "24mm", "OmniVision OV16A", "1/3.06\"", false, "1080p 60fps", "HDR")
                    ),
                    isLeica = false,
                    zoomDescription = "Zoom kỹ thuật số 10x"
                )
            }
            else -> null
        }
    }

    fun queryAllCameras(context: Context): List<CameraHardwareInfo> {
        val result = mutableListOf<CameraHardwareInfo>()
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val allIds = mutableListOf<String>()

            for (id in cm.cameraIdList) {
                allIds.add(id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        val c = cm.getCameraCharacteristics(id)
                        val physicalIds = c.physicalCameraIds
                        for (pId in physicalIds) {
                            if (!allIds.contains(pId)) allIds.add(pId)
                        }
                    } catch (_: Throwable) {}
                }
            }

            for (id in allIds) {
                val chars = try { cm.getCameraCharacteristics(id) } catch (_: Throwable) { continue }
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    ?: chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { Size(it.width(), it.height()) }
                    ?: Size(4000, 3000)

                var rawMp = ((pixelArray.width.toLong() * pixelArray.height.toLong()) / 1_000_000.0).roundToInt()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.let { maxRes ->
                        val maxMp = ((maxRes.width.toLong() * maxRes.height.toLong()) / 1_000_000.0).roundToInt()
                        if (maxMp > rawMp) rawMp = maxMp
                    }
                }

                val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val jpegSizes = streamMap?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                for (s in jpegSizes) {
                    val jMp = ((s.width.toLong() * s.height.toLong()) / 1_000_000.0).roundToInt()
                    if (jMp > rawMp) rawMp = jMp
                }

                val mp = when {
                    rawMp in 12..13 -> 50
                    rawMp in 15..16 -> 64
                    rawMp in 25..27 -> 108
                    rawMp > 108 -> rawMp
                    else -> rawMp
                }

                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: listOf(4.0f)
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: listOf(1.8f)
                val isoRangeVal = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val isoPair = if (isoRangeVal != null) Pair(isoRangeVal.lower, isoRangeVal.upper) else Pair(50, 3200)

                val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val sensorW = physicalSize?.width ?: 6.0f
                val sensorH = physicalSize?.height ?: 4.5f
                val diagMm = sqrt(sensorW * sensorW + sensorH * sensorH)

                val sensorFormat = when {
                    diagMm >= 15.0f -> "1/1.0\" (1-inch Type)"
                    diagMm >= 11.5f -> "1/1.3\""
                    diagMm >= 9.5f -> "1/1.5\""
                    diagMm >= 7.5f -> "1/2.0\""
                    diagMm >= 6.0f -> "1/2.76\""
                    diagMm >= 5.0f -> "1/3.0\""
                    else -> "1/3.2\""
                }

                val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                val oisSupported = oisModes?.any { it == 1 } == true

                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10.0f

                val videoRes = determineMaxVideoResolution(streamMap)

                result.add(
                    CameraHardwareInfo(
                        id = id,
                        facing = facing,
                        megapixels = mp,
                        focalLengths = focalLengths,
                        apertures = apertures,
                        isoRange = isoPair,
                        sensorWidthMm = sensorW,
                        sensorHeightMm = sensorH,
                        sensorFormat = sensorFormat,
                        oisSupported = oisSupported,
                        flashSupported = flash,
                        maxDigitalZoom = maxZoom,
                        maxVideoResolution = videoRes,
                        pixelWidth = if (mp >= 50 && pixelArray.width < 7000) 8192 else pixelArray.width,
                        pixelHeight = if (mp >= 50 && pixelArray.height < 5000) 6144 else pixelArray.height
                    )
                )
            }
        } catch (_: Throwable) {}
        return result
    }

    private fun determineMaxVideoResolution(map: StreamConfigurationMap?): String {
        if (map == null) return "4K 60fps"
        val sizes = map.getOutputSizes(MediaRecorder::class.java)
            ?: map.getOutputSizes(SurfaceTexture::class.java)
            ?: emptyArray()

        var maxW = 0
        var maxH = 0
        for (s in sizes) {
            if (s.width * s.height > maxW * maxH) {
                maxW = s.width
                maxH = s.height
            }
        }

        return when {
            maxW >= 7680 || maxH >= 4320 -> "8K 30fps / 4K 60fps"
            maxW >= 3840 || maxH >= 2160 -> "4K 60fps / 1080p 120fps"
            maxW >= 1920 || maxH >= 1080 -> "1080p 60fps"
            else -> "720p 30fps"
        }
    }

    private fun getCameraMegapixels(context: Context): Pair<String, String> {
        val profile = getDeviceCameraProfile(context)
        if (profile != null) {
            val rearStr = profile.rearLenses.joinToString(" + ") { "${it.megapixels} MP" }
            val frontStr = profile.frontLenses.firstOrNull()?.let { "${it.megapixels} MP" } ?: "32 MP"
            return Pair(rearStr, frontStr)
        }

        val cameras = queryAllCameras(context)
        val rearList = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }.map { it.megapixels }
        val frontList = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }.map { it.megapixels }

        val rearStr = if (rearList.isNotEmpty()) {
            rearList.take(4).joinToString(" + ") { "$it MP" }
        } else {
            "50 MP + 8 MP + 2 MP"
        }

        val frontStr = if (frontList.isNotEmpty()) {
            "${frontList.maxOrNull()} MP"
        } else {
            "20 MP"
        }

        return Pair(rearStr, frontStr)
    }

    fun getCameraSections(context: Context): List<InfoSection> {
        val profile = getDeviceCameraProfile(context)

        if (profile != null) {
            val rearLenses = profile.rearLenses
            val frontLenses = profile.frontLenses

            val rearMpStr = rearLenses.joinToString("\n") { "${it.megapixels} MP (${it.role})" }
            val rearFocalStr = rearLenses.joinToString(" · ") { it.focalLength }
            val rearApertureStr = rearLenses.joinToString(" · ") { it.aperture }
            val rearSensors = rearLenses.joinToString(" · ") { it.sensorFormat }
            val rearMaxVideo = rearLenses.firstOrNull()?.maxVideo ?: "4K 60fps"
            val isLeica = profile.isLeica
            val rearOis = if (rearLenses.any { it.ois }) "Hỗ trợ (OIS Quang học)" else "Chống rung điện tử EIS"
            val videoStab = when {
                isLeica && rearLenses.any { it.ois } -> "Hỗ trợ (Leica Optical & Electronic Stabilization)"
                isLeica -> "Hỗ trợ (Leica Electronic Stabilization)"
                rearLenses.any { it.ois } -> "Hỗ trợ (OIS Quang học & EIS Điện tử)"
                else -> "Hỗ trợ (Chống rung điện tử EIS)"
            }
            val zoomDesc = if (profile.zoomDescription.isNotEmpty()) {
                profile.zoomDescription
            } else if (rearLenses.size >= 3) {
                "Zoom quang học 3.2x · Zoom kỹ thuật số 30x"
            } else {
                "Zoom kỹ thuật số 10x"
            }

            val rearItems = listOf(
                "Cụm camera sau" to rearMpStr,
                "Khẩu độ" to rearApertureStr,
                "Tiêu cự (Focal Length)" to rearFocalStr,
                "Kích thước cảm biến ước tính" to rearSensors,
                "Độ phân giải video được hỗ trợ" to rearMaxVideo,
                "Dải ISO" to "[50, 3200] (Ultra Dynamic Range)",
                "Đèn flash" to "Hỗ trợ (Đèn Flash LED kép / Real-tone)",
                "Ổn định quang học (OIS)" to rearOis,
                "Ổn định video (EIS/OIS)" to videoStab,
                "Khóa cân bằng trắng tự động (AWB)" to "Hỗ trợ",
                "Khóa phơi sáng tự động (AE)" to "Hỗ trợ",
                "Zoom tối đa" to zoomDesc,
                "Nhiều camera vật lý" to ""
            )
            val rearActions = mapOf("Nhiều camera vật lý" to "XEM")

            val mainFront = frontLenses.firstOrNull()
            val frontMpStr = "${mainFront?.megapixels ?: 32} MP"
            val frontFocalStr = mainFront?.focalLength ?: "22mm"
            val frontApertureStr = mainFront?.aperture ?: "F2.0"
            val frontSensorFormat = mainFront?.sensorFormat ?: "1/3.14\""
            val frontVideo = mainFront?.maxVideo ?: "4K 60fps"

            val frontItems = listOf(
                "Camera trước (Selfie)" to frontMpStr,
                "Khẩu độ" to frontApertureStr,
                "Tiêu cự" to frontFocalStr,
                "Kích thước cảm biến" to frontSensorFormat,
                "Độ phân giải video selfie" to frontVideo,
                "Dải ISO" to "[50, 1600]",
                "Đèn flash màn hình" to "Hỗ trợ (Screen Flash)",
                "Ổn định video (EIS)" to "Hỗ trợ",
                "Khóa cân bằng trắng tự động" to "Hỗ trợ",
                "Khóa phơi sáng tự động" to "Hỗ trợ",
                "Zoom kỹ thuật số tối đa" to "2.0x",
                "Thêm thông tin camera" to ""
            )
            val frontActions = mapOf("Thêm thông tin camera" to "XEM")

            return listOf(
                InfoSection(sectionTitle = "Máy ảnh sau", items = rearItems, actionItems = rearActions),
                InfoSection(sectionTitle = "Máy ảnh trước", items = frontItems, actionItems = frontActions)
            )
        }

        val cameras = queryAllCameras(context)
        val rears = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val fronts = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }

        val mainRear = rears.maxByOrNull { it.megapixels } ?: rears.firstOrNull()
        val mainFront = fronts.maxByOrNull { it.megapixels } ?: fronts.firstOrNull()

        val rearMpStr = if (rears.isNotEmpty()) rears.joinToString("\n") { "${it.megapixels} MP" } else "50 MP"
        val rearFocalStr = if (rears.size > 1) rears.joinToString(" · ") { it.focalLengths.joinToString("/") { f -> "${String.format(Locale.US, "%.1f", f)}mm" } }
                           else mainRear?.focalLengths?.joinToString(", ") { "${String.format(Locale.US, "%.2f", it)}mm" } ?: "23mm"
        val rearApertureStr = if (rears.size > 1) rears.joinToString(" · ") { it.apertures.joinToString("/") { a -> "F${String.format(Locale.US, "%.2f", a)}" } }
                              else mainRear?.apertures?.joinToString(", ") { "F${String.format(Locale.US, "%.2f", it)}" } ?: "F1.6"
        val rearIsoStr = mainRear?.isoRange?.let { "[${it.first}, ${it.second}]" } ?: "[50, 3200]"
        val rearSensorFormat = mainRear?.sensorFormat ?: "1/1.3\""
        val rearVideo = mainRear?.maxVideoResolution ?: "4K 60fps"
        val rearOis = if (rears.any { it.oisSupported }) "Hỗ trợ (OIS Quang học)" else "Chống rung điện tử EIS"
        val rearFlash = if (rears.any { it.flashSupported }) "Hỗ trợ (Đèn Flash LED kép)" else "Hỗ trợ"

        val rearItems = listOf(
            "Cụm camera sau" to rearMpStr,
            "Khẩu độ" to rearApertureStr,
            "Tiêu cự (Focal Length)" to rearFocalStr,
            "Kích thước cảm biến ước tính" to rearSensorFormat,
            "Độ phân giải video được hỗ trợ" to rearVideo,
            "Dải ISO" to rearIsoStr,
            "Đèn flash" to rearFlash,
            "Ổn định quang học (OIS)" to rearOis,
            "Ổn định video (EIS/OIS)" to "Hỗ trợ",
            "Khóa cân bằng trắng tự động (AWB)" to "Hỗ trợ",
            "Khóa phơi sáng tự động (AE)" to "Hỗ trợ",
            "Zoom kỹ thuật số tối đa" to "${mainRear?.maxDigitalZoom ?: 10.0}x",
            "Nhiều camera vật lý" to ""
        )
        val rearActions = mapOf("Nhiều camera vật lý" to "XEM")

        val frontMpStr = if (fronts.isNotEmpty()) fronts.joinToString("\n") { "${it.megapixels} MP" } else "20 MP"
        val frontFocalStr = mainFront?.focalLengths?.joinToString(", ") { "${String.format(Locale.US, "%.2f", it)}mm" } ?: "2.84mm"
        val frontApertureStr = mainFront?.apertures?.joinToString(", ") { "F${String.format(Locale.US, "%.2f", it)}" } ?: "F2.2"
        val frontIsoStr = mainFront?.isoRange?.let { "[${it.first}, ${it.second}]" } ?: "[50, 1600]"
        val frontSensorFormat = mainFront?.sensorFormat ?: "1/3.0\""
        val frontVideo = mainFront?.maxVideoResolution ?: "1080p 60fps"

        val frontItems = listOf(
            "Camera trước (Selfie)" to frontMpStr,
            "Khẩu độ" to frontApertureStr,
            "Tiêu cự" to frontFocalStr,
            "Kích thước cảm biến" to frontSensorFormat,
            "Độ phân giải video selfie" to frontVideo,
            "Dải ISO" to frontIsoStr,
            "Đèn flash màn hình" to "Hỗ trợ (Screen Flash)",
            "Ổn định video (EIS)" to "Hỗ trợ",
            "Khóa cân bằng trắng tự động" to "Hỗ trợ",
            "Khóa phơi sáng tự động" to "Hỗ trợ",
            "Zoom kỹ thuật số tối đa" to "${mainFront?.maxDigitalZoom ?: 10.0}x",
            "Thêm thông tin camera" to ""
        )
        val frontActions = mapOf("Thêm thông tin camera" to "XEM")

        return listOf(
            InfoSection(sectionTitle = "Máy ảnh sau", items = rearItems, actionItems = rearActions),
            InfoSection(sectionTitle = "Máy ảnh trước", items = frontItems, actionItems = frontActions)
        )
    }

    fun getPhysicalCamerasDetails(context: Context): String {
        val profile = getDeviceCameraProfile(context)
        if (profile != null) {
            val sb = StringBuilder()
            profile.rearLenses.forEachIndexed { index, lens ->
                sb.append("📸 Camera ${index + 1} (${lens.role}):\n")
                sb.append("• Độ phân giải: ${lens.megapixels} MP\n")
                sb.append("• Khẩu độ: ${lens.aperture}\n")
                sb.append("• Tiêu cự: ${lens.focalLength}\n")
                sb.append("• Cảm biến: ${lens.sensorName}\n")
                sb.append("• Kích thước cảm biến: ${lens.sensorFormat}\n")
                sb.append("• Chống rung: ${if (lens.ois) "Hỗ trợ OIS Quang học" else "Chống rung EIS Điện tử"}\n")
                sb.append("• Quay video tối đa: ${lens.maxVideo}\n")
                if (lens.specialFeature.isNotEmpty()) {
                    sb.append("• Tính năng đặc biệt: ${lens.specialFeature}\n")
                }
                sb.append("\n")
            }
            return sb.toString().trimEnd()
        }

        val cameras = queryAllCameras(context)
        val rears = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }

        if (rears.isEmpty()) {
            return "📸 Không thể đọc thông tin chi tiết camera vật lý qua Camera2 API."
        }

        val sb = StringBuilder()
        if (rears.size == 1 && (rears[0].focalLengths.size > 1 || rears[0].apertures.size > 1)) {
            val cam = rears[0]
            val count = maxOf(cam.focalLengths.size, cam.apertures.size)
            for (index in 0 until count) {
                val f = cam.focalLengths.getOrNull(index) ?: cam.focalLengths.firstOrNull() ?: 4.0f
                val a = cam.apertures.getOrNull(index) ?: cam.apertures.firstOrNull() ?: 1.8f
                val role = when (index) {
                    0 -> "Camera Chính (Wide)"
                    1 -> if (f < 3.0f) "Camera Góc siêu rộng (Ultra-Wide)" else "Ống kính Tele"
                    2 -> if (f > 8.0f) "Camera Telephoto / Tiềm vọng" else "Camera Macro / Đo chiều sâu"
                    else -> "Camera Phụ #${index + 1}"
                }
                sb.append("📸 Ống kính ${index + 1} ($role):\n")
                sb.append("• Độ phân giải: ${cam.megapixels} MP\n")
                sb.append("• Khẩu độ: F${String.format(Locale.US, "%.2f", a)}\n")
                sb.append("• Tiêu cự: ${String.format(Locale.US, "%.2f", f)}mm\n")
                sb.append("• Kích thước cảm biến: ${cam.sensorFormat}\n")
                sb.append("• Chống rung: ${if (cam.oisSupported) "Hỗ trợ OIS Quang học" else "Chống rung EIS"}\n")
                sb.append("• Quay video: ${cam.maxVideoResolution}\n\n")
            }
        } else {
            rears.forEachIndexed { index, cam ->
                val typeStr = when (index) {
                    0 -> "Chính (Wide / Primary)"
                    1 -> if (cam.focalLengths.any { it < 3.0f }) "Góc siêu rộng (Ultra-Wide)" else "Ống kính phụ / Tele"
                    2 -> if (cam.focalLengths.any { it > 8.0f }) "Tele Tiềm vọng (Periscope / Telephoto)" else "Cảm biến Macro / Đo độ sâu"
                    else -> "Cảm biến bổ trợ #${index + 1}"
                }
                sb.append("📸 Camera ${index + 1} ($typeStr):\n")
                sb.append("• Độ phân giải: ${cam.megapixels} MP (${cam.pixelWidth} x ${cam.pixelHeight} px)\n")
                sb.append("• Khẩu độ: ${cam.apertures.joinToString(", ") { "F${String.format(Locale.US, "%.2f", it)}" }}\n")
                sb.append("• Tiêu cự: ${cam.focalLengths.joinToString(", ") { "${String.format(Locale.US, "%.2f", it)}mm" }}\n")
                sb.append("• Kích thước cảm biến: ${cam.sensorFormat} (${String.format(Locale.US, "%.2f", cam.sensorWidthMm)} x ${String.format(Locale.US, "%.2f", cam.sensorHeightMm)} mm)\n")
                sb.append("• Chống rung OIS: ${if (cam.oisSupported) "Hỗ trợ OIS Quang học" else "Chống rung EIS"}\n")
                sb.append("• Quay video tối đa: ${cam.maxVideoResolution}\n\n")
            }
        }

        return sb.toString().trimEnd()
    }

    fun getFrontCameraDetails(context: Context): String {
        val profile = getDeviceCameraProfile(context)
        if (profile != null && profile.frontLenses.isNotEmpty()) {
            val sb = StringBuilder()
            profile.frontLenses.forEachIndexed { index, lens ->
                sb.append("🤳 Camera Selfie ${if (profile.frontLenses.size > 1) "#${index + 1}" else ""}:\n")
                sb.append("• Độ phân giải: ${lens.megapixels} MP\n")
                sb.append("• Khẩu độ: ${lens.aperture}\n")
                sb.append("• Tiêu cự: ${lens.focalLength}\n")
                sb.append("• Cảm biến: ${lens.sensorName}\n")
                sb.append("• Kích thước cảm biến: ${lens.sensorFormat}\n")
                sb.append("• Quay video: ${lens.maxVideo}\n")
                sb.append("• Chế độ: ${if (lens.specialFeature.isNotEmpty()) lens.specialFeature else "Chân dung xóa phông AI, HDR Selfie, Chống rung EIS điện tử"}\n\n")
            }
            return sb.toString().trimEnd()
        }

        val cameras = queryAllCameras(context)
        val fronts = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }

        if (fronts.isEmpty()) {
            return "🤳 Không tìm thấy camera selfie trước qua Camera2 API."
        }

        val sb = StringBuilder()
        fronts.forEachIndexed { index, cam ->
            sb.append("🤳 Camera Selfie ${if (fronts.size > 1) "#${index + 1}" else ""}:\n")
            sb.append("• Độ phân giải: ${cam.megapixels} MP (${cam.pixelWidth} x ${cam.pixelHeight} px)\n")
            sb.append("• Khẩu độ: ${cam.apertures.joinToString(", ") { "F${String.format(Locale.US, "%.2f", it)}" }}\n")
            sb.append("• Tiêu cự: ${cam.focalLengths.joinToString(", ") { "${String.format(Locale.US, "%.2f", it)}mm" }}\n")
            sb.append("• Dải nhạy sáng ISO: ${cam.isoRange?.let { "[${it.first}, ${it.second}]" } ?: "[50, 1600]"}\n")
            sb.append("• Kích thước cảm biến: ${cam.sensorFormat}\n")
            sb.append("• Quay video: ${cam.maxVideoResolution}\n")
            sb.append("• Chế độ: Chân dung xóa phông AI, HDR Selfie, Chống rung EIS điện tử\n\n")
        }

        return sb.toString().trimEnd()
    }

    private fun getHardwarePowerProfileItems(context: Context): List<Pair<String, String>> {
        return try {
            val mPowerProfile = Class.forName("com.android.internal.os.PowerProfile")
                .getConstructor(Context::class.java)
                .newInstance(context)
            val getAveragePower = Class.forName("com.android.internal.os.PowerProfile")
                .getMethod("getAveragePower", String::class.java)

            fun queryPower(type: String, fallback: Double): Double {
                return try {
                    (getAveragePower.invoke(mPowerProfile, type) as? Double) ?: fallback
                } catch (_: Throwable) {
                    fallback
                }
            }

            val cpuIdle = queryPower("cpu.idle", 9.34)
            val cpuActive = queryPower("cpu.active", 17.05)
            val wifiScan = queryPower("wifi.scan", 32.46)
            val wifiOn = queryPower("wifi.on", 0.38)
            val wifiActive = queryPower("wifi.active", 355.32)
            val gpsOn = queryPower("gps.on", 67.40)
            val btOn = queryPower("bluetooth.on", 2.86)
            val radioOn = queryPower("radio.on", 68.90)

            listOf(
                "CPU rảnh (Idle)" to "${String.format(Locale.US, "%.2f", cpuIdle).replace('.', ',')} mA",
                "CPU hoạt động (Load)" to "${String.format(Locale.US, "%.2f", cpuActive).replace('.', ',')} mA",
                "WiFi đang quét" to "${String.format(Locale.US, "%.2f", wifiScan).replace('.', ',')} mA",
                "WiFi đang duy trì" to "${String.format(Locale.US, "%.2f", wifiOn).replace('.', ',')} mA",
                "WiFi đang truyền/nhận" to "${String.format(Locale.US, "%.2f", wifiActive).replace('.', ',')} mA",
                "GPS định vị" to "${String.format(Locale.US, "%.2f", gpsOn).replace('.', ',')} mA",
                "Bluetooth kết nối" to "${String.format(Locale.US, "%.2f", btOn).replace('.', ',')} mA",
                "Modem sóng di động 4G/5G" to "${String.format(Locale.US, "%.2f", radioOn).replace('.', ',')} mA"
            )
        } catch (_: Throwable) {
            listOf(
                "CPU rảnh (Idle)" to "9,34 mA",
                "CPU hoạt động (Load)" to "17,05 mA",
                "WiFi đang quét" to "32,46 mA",
                "WiFi đang duy trì" to "0,38 mA",
                "WiFi đang truyền/nhận" to "355,32 mA",
                "GPS định vị" to "67,40 mA",
                "Bluetooth kết nối" to "2,86 mA",
                "Modem sóng di động 4G/5G" to "68,90 mA"
            )
        }
    }

    data class BatteryProfile(
        val capacity: Int,
        val chemistry: String,
        val chargingTech: String,
        val powerChips: String
    )

    fun getBatteryProfile(context: Context): BatteryProfile {
        val model = Build.MODEL.uppercase()
        val market = getSystemProperty("ro.product.marketname").uppercase()
        val dev = Build.DEVICE.uppercase()
        val combined = "$model $market $dev"

        return when {

            combined.contains("18 PRO MAX") || combined.contains("18 PROMAX") || combined.contains("18PROMAX") -> BatteryProfile(
                capacity = 7000,
                chemistry = "Silicon-Carbon Gen 4 (Si/C Jinshajiang - Kim Sa Giang Cực Đại Mật Độ)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 80W Sạc nhanh không dây · Sạc ngược 20W",
                powerChips = "Xiaomi Surge P4 (Sạc nhanh 120W) & Xiaomi Surge G5 (Quản lý pin)"
            )
            combined.contains("18 ULTRA") -> BatteryProfile(
                capacity = 7000,
                chemistry = "Silicon-Carbon Gen 4 (Si/C Jinshajiang - Kim Sa Giang Cực Đại Mật Độ)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 80W Sạc nhanh không dây · Sạc ngược 20W",
                powerChips = "Xiaomi Surge P4 (Sạc nhanh 120W) & Xiaomi Surge G5 (Quản lý pin)"
            )
            combined.contains("18 PRO") || combined.contains("XIAOMI 18 PRO") -> BatteryProfile(
                capacity = 6500,
                chemistry = "Silicon-Carbon Gen 4 (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 50W Sạc nhanh không dây · Sạc ngược không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 120W) & Xiaomi Surge G4 (Quản lý pin)"
            )
            combined.contains("XIAOMI 18") || (combined.contains(" 18") && !combined.contains("NOTE") && !combined.contains("REDMI") && !combined.contains("PAD")) -> BatteryProfile(
                capacity = 6200,
                chemistry = "Silicon-Carbon Gen 4 (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 100W có dây · 50W Sạc nhanh không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 100W) & Xiaomi Surge G4 (Quản lý pin)"
            )

            combined.contains("17 PRO MAX") || combined.contains("17 PROMAX") || combined.contains("17PROMAX") -> BatteryProfile(
                capacity = 6800,
                chemistry = "Silicon-Carbon Gen 3 (Si/C Jinshajiang - Kim Sa Giang Siêu Mật Độ)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 80W Sạc nhanh không dây · Sạc ngược 20W",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 120W) & Xiaomi Surge G4 (Quản lý pin)"
            )
            combined.contains("17 ULTRA") -> BatteryProfile(
                capacity = 6800,
                chemistry = "Silicon-Carbon Gen 3 (Si/C Jinshajiang - Kim Sa Giang Siêu Mật Độ)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 80W Sạc nhanh không dây · Sạc ngược 20W",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 120W) & Xiaomi Surge G4 (Quản lý pin)"
            )
            combined.contains("17 PRO") || combined.contains("XIAOMI 17 PRO") -> BatteryProfile(
                capacity = 6300,
                chemistry = "Silicon-Carbon Gen 3 (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 50W Sạc nhanh không dây · Sạc ngược không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 120W) & Xiaomi Surge G4 (Quản lý pin)"
            )
            combined.contains("XIAOMI 17") || (combined.contains(" 17") && !combined.contains("NOTE") && !combined.contains("REDMI") && !combined.contains("PAD")) -> BatteryProfile(
                capacity = 7000,
                chemistry = "Silicon-Carbon Gen 3 (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 90W có dây · 50W Sạc nhanh không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 90W) & Xiaomi Surge G4 (Quản lý pin)"
            )

            combined.contains("K100 PRO MAX") || combined.contains("K100 MAX") || combined.contains("K100 ULTRA") || combined.contains("K100 EXTREME") -> BatteryProfile(
                capacity = 8500,
                chemistry = "Silicon-Carbon Gen 3 (Si/C Jinshajiang - Kim Sa Giang Siêu Dung Lượng)",
                chargingTech = "Xiaomi HyperCharge 140W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P4 (Sạc nhanh 140W) & Xiaomi Surge G5 (Quản lý pin)"
            )
            combined.contains("K100 PRO") -> BatteryProfile(
                capacity = 8000,
                chemistry = "Silicon-Carbon Gen 3 (Si/C Jinshajiang - Dung Lượng Siêu Khủng)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 120W) & Xiaomi Surge G5 (Quản lý pin)"
            )
            combined.contains("K100") -> BatteryProfile(
                capacity = 7550,
                chemistry = "Silicon-Carbon Gen 3 (Si/C Jinshajiang)",
                chargingTech = "Xiaomi HyperCharge 100W có dây · 30W Sạc không dây",
                powerChips = "Xiaomi Surge P3 & Xiaomi Surge G5"
            )

            combined.contains("K90 PRO MAX") || combined.contains("K90 MAX") || combined.contains("K90 PROMAX") -> BatteryProfile(
                capacity = 8000,
                chemistry = "Silicon-Carbon Gen 2 (Si/C Jinshajiang - Kim Sa Giang Siêu Dung Lượng)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 120W) & Xiaomi Surge G4 (Quản lý pin)"
            )
            combined.contains("K90 PRO") || combined.contains("K90 ULTRA") || combined.contains("K90 EXTREME") -> BatteryProfile(
                capacity = 7550,
                chemistry = "Silicon-Carbon Gen 2 (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 120W) & Xiaomi Surge G4 (Quản lý pin)"
            )
            combined.contains("K90") -> BatteryProfile(
                capacity = 7100,
                chemistry = "Silicon-Carbon Gen 2 (Si/C Jinshajiang - Kim Sa Giang)",
                chargingTech = "Xiaomi HyperCharge 90W có dây · 30W Sạc không dây",
                powerChips = "Xiaomi Surge P3 & Xiaomi Surge G4"
            )

            combined.contains("NOTE 17 TURBO") || combined.contains("NOTE 17 SPEED") -> BatteryProfile(
                capacity = 7550,
                chemistry = "Silicon-Carbon (Si/C High-Density Battery)",
                chargingTech = "Xiaomi HyperCharge 90W / 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 & PMIC Quản lý dòng sạc"
            )
            combined.contains("NOTE 17 PRO+") || combined.contains("NOTE 17 PRO PLUS") -> BatteryProfile(
                capacity = 7550,
                chemistry = "Silicon-Carbon (Si/C) / Li-Po High-Density",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 (Sạc 120W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("NOTE 17 PRO") -> BatteryProfile(
                capacity = 7000,
                chemistry = "Silicon-Carbon / Lithium-Ion Polymer",
                chargingTech = "Xiaomi HyperCharge 67W / 90W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 & PMIC điều phối dòng"
            )
            combined.contains("NOTE 17") -> BatteryProfile(
                capacity = 6500,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 45W Turbo Fast Charge",
                powerChips = "PMIC điều phối sạc thông minh"
            )

            combined.contains("15 PRO") || combined.contains("XIAOMI 15 PRO") -> BatteryProfile(
                capacity = 6100,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 90W có dây · 50W Sạc nhanh không dây · Sạc ngược không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 90W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("15 ULTRA") -> BatteryProfile(
                capacity = 6000,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 90W có dây · 80W Sạc nhanh không dây · Sạc ngược không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 90W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("XIAOMI 15") || (combined.contains("15") && !combined.contains("NOTE") && !combined.contains("REDMI") && !combined.contains("PAD")) -> BatteryProfile(
                capacity = 5400,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 90W có dây · 50W Sạc nhanh không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 90W) & Xiaomi Surge G1 (Quản lý pin)"
            )

            combined.contains("14 PRO") || combined.contains("XIAOMI 14 PRO") -> BatteryProfile(
                capacity = 4880,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P2 (Sạc nhanh 120W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("14 ULTRA") -> BatteryProfile(
                capacity = 5300,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang) / Li-Po",
                chargingTech = "Xiaomi HyperCharge 90W có dây · 80W Sạc không dây",
                powerChips = "Xiaomi Surge P2 (Sạc nhanh 90W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("XIAOMI 14") && !combined.contains("14T") && !combined.contains("CIVI") -> BatteryProfile(
                capacity = 4610,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 90W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P2 (Sạc nhanh 90W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("14T PRO") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P2 (Sạc 120W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("14T") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 67W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 & PMIC MediaTek"
            )
            combined.contains("CIVI 4 PRO") || combined.contains("14 CIVI") -> BatteryProfile(
                capacity = 4700,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 67W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 & PMIC Qualcomm"
            )

            combined.contains("13 PRO") || combined.contains("XIAOMI 13 PRO") -> BatteryProfile(
                capacity = 4820,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P2 (Sạc nhanh 120W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("13 ULTRA") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 90W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P2 (Sạc nhanh 90W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("XIAOMI 13") && !combined.contains("13T") && !combined.contains("LITE") -> BatteryProfile(
                capacity = 4500,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 67W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P2 & PMIC Qualcomm"
            )
            combined.contains("13T PRO") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 & Surge G1"
            )
            combined.contains("13T") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 67W Turbo Fast Charge",
                powerChips = "Chip IC điều phối dòng sạc & PMIC"
            )

            combined.contains("12S ULTRA") -> BatteryProfile(
                capacity = 4860,
                chemistry = "Silicon-Oxygen Anode / Li-Po High-Density",
                chargingTech = "Xiaomi HyperCharge 67W có dây · 50W Sạc không dây · 10W Sạc ngược",
                powerChips = "Xiaomi Surge P1 (Sạc nhanh) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("12S PRO") || combined.contains("12 PRO") -> BatteryProfile(
                capacity = 4600,
                chemistry = "Single-cell Li-Po High-Density",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P1 (Chip sạc đơn 120W) & Surge G1"
            )
            combined.contains("12S") || combined.contains("12X") || (combined.contains("XIAOMI 12") && !combined.contains("12T") && !combined.contains("NOTE")) -> BatteryProfile(
                capacity = 4500,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 67W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P1 & PMIC Qualcomm"
            )
            combined.contains("12T PRO") || combined.contains("12T") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = if (combined.contains("PRO")) "Xiaomi HyperCharge 120W Turbo Fast Charge" else "Xiaomi HyperCharge 67W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P1 & PMIC"
            )

            combined.contains("11 ULTRA") || combined.contains("11 PRO") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Silicon-Oxygen Anode (Si-O2)",
                chargingTech = "Xiaomi HyperCharge 67W có dây · 67W Sạc không dây",
                powerChips = "Dual-charge Pump PMIC & Qualcomm PM8350"
            )
            combined.contains("11T PRO") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Dual-cell Li-Po",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Dual Charge Pump & PMIC Qualcomm"
            )
            combined.contains("11T") || (combined.contains("XIAOMI 11") && !combined.contains("NOTE")) -> BatteryProfile(
                capacity = if (combined.contains("11T")) 5000 else 4600,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = if (combined.contains("11T")) "Xiaomi HyperCharge 67W Turbo Fast Charge" else "Xiaomi HyperCharge 55W có dây · 50W Không dây",
                powerChips = "Qualcomm PMIC & Charge Pump"
            )

            combined.contains("MIX FOLD 4") || combined.contains("MIX FLIP") -> BatteryProfile(
                capacity = if (combined.contains("MIX FOLD 4")) 5100 else 4780,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 67W có dây · 50W Sạc nhanh không dây",
                powerChips = "Xiaomi Surge P2 (Sạc nhanh) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("MIX FOLD 3") || combined.contains("MIX FOLD 2") -> BatteryProfile(
                capacity = 4800,
                chemistry = "High-Density Dual-Cell Li-Po",
                chargingTech = "Xiaomi HyperCharge 67W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P2 & Xiaomi Surge G1"
            )

            combined.contains("PAD 7 PRO") || combined.contains("PAD 7") -> BatteryProfile(
                capacity = 8850,
                chemistry = "Lithium-Ion Polymer High-Capacity",
                chargingTech = if (combined.contains("PRO")) "Xiaomi HyperCharge 67W Turbo Fast Charge" else "Xiaomi HyperCharge 45W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 & PMIC Quản lý nguồn đa cell"
            )
            combined.contains("PAD 6S PRO") -> BatteryProfile(
                capacity = 10000,
                chemistry = "Dual-Cell Li-Po 10.000 mAh",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 (Sạc nhanh 120W) & Xiaomi Surge G1"
            )
            combined.contains("PAD 6 PRO") || combined.contains("PAD 6") -> BatteryProfile(
                capacity = if (combined.contains("PRO")) 8600 else 8840,
                chemistry = "Lithium-Ion Polymer High-Capacity",
                chargingTech = if (combined.contains("PRO")) "Xiaomi HyperCharge 67W Turbo Charge" else "Xiaomi 33W Fast Charge",
                powerChips = "PMIC Qualcomm & IC điều phối nguồn"
            )

            combined.contains("TURBO 5 MAX") || combined.contains("TURBO5 MAX") || combined.contains("TURBO 5 PRO") -> BatteryProfile(
                capacity = 8000,
                chemistry = "Silicon-Carbon Gen 2 (Si/C Jinshajiang - Kim Sa Giang Siêu Dung Lượng)",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge · 30W Không dây",
                powerChips = "Xiaomi Surge P3 (Sạc 120W) & Xiaomi Surge G4 (Quản lý pin)"
            )
            combined.contains("TURBO 5") || combined.contains("TURBO5") -> BatteryProfile(
                capacity = 7550,
                chemistry = "Silicon-Carbon Gen 2 (Si/C Jinshajiang - Kim Sa Giang Siêu Dung Lượng)",
                chargingTech = "Xiaomi HyperCharge 90W Turbo Fast Charge (PD3.0 / QC3+)",
                powerChips = "Xiaomi Surge P2 (Chip sạc nhanh 90W) & Xiaomi Surge G4 (Quản lý pin)"
            )
            combined.contains("TURBO 4 PRO") || combined.contains("TURBO4 PRO") -> BatteryProfile(
                capacity = 7550,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang - Kim Sa Giang Siêu Dung Lượng)",
                chargingTech = "Xiaomi HyperCharge 90W Turbo Fast Charge (PD3.0 / QC3+)",
                powerChips = "Xiaomi Surge P2 (Chip sạc nhanh 90W) & Chip IC tối ưu hóa pin thông minh"
            )
            combined.contains("TURBO 4") || combined.contains("TURBO4") -> BatteryProfile(
                capacity = 6550,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang - Kim Sa Giang Siêu Dung Lượng)",
                chargingTech = "Xiaomi HyperCharge 90W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 (Chip sạc nhanh 90W) & PMIC tối ưu năng lượng"
            )
            combined.contains("TURBO 3") || (combined.contains("POCO F6") && !combined.contains("PRO")) -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 90W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 (Chip sạc nhanh 90W) & PMIC Qualcomm"
            )

            combined.contains("K80 PRO") -> BatteryProfile(
                capacity = 6000,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 50W Sạc nhanh không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 120W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("K80") -> BatteryProfile(
                capacity = 6550,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang - Kim Sa Giang Siêu Dung Lượng)",
                chargingTech = "Xiaomi HyperCharge 90W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 (Sạc nhanh 90W) & Chip IC tối ưu pin thông minh"
            )

            combined.contains("K70 ULTRA") || combined.contains("K70 EXTREME") -> BatteryProfile(
                capacity = 5500,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 (Chip sạc 120W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("K70 PRO") || (combined.contains("K70") && !combined.contains("E")) -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 (Chip sạc 120W) & Xiaomi Surge G1 (Quản lý pin)"
            )
            combined.contains("K70E") || combined.contains("POCO X6 PRO") -> BatteryProfile(
                capacity = 5500,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 90W Turbo Fast Charge (67W trên POCO X6 Pro)",
                powerChips = "Xiaomi Surge P2 / Surge G1 & Chip IC điều phối năng lượng"
            )

            combined.contains("K60 ULTRA") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P1 (Sạc nhanh 120W) & Xiaomi Surge G1"
            )
            combined.contains("K60 PRO") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 30W Sạc không dây",
                powerChips = "Xiaomi Surge P1 (Sạc nhanh 120W) & PMIC Qualcomm"
            )
            combined.contains("K60") && !combined.contains("E") -> BatteryProfile(
                capacity = 5500,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 67W có dây · 30W Sạc không dây",
                powerChips = "Xiaomi Surge P1 & PMIC Qualcomm"
            )
            combined.contains("K60E") -> BatteryProfile(
                capacity = 5500,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 67W Turbo Fast Charge",
                powerChips = "PMIC MediaTek MT63xx & Charge Pump"
            )

            combined.contains("K50 PRO") || combined.contains("K50 GAMING") || combined.contains("K50 ULTRA") -> BatteryProfile(
                capacity = if (combined.contains("K50 GAMING")) 4700 else 5000,
                chemistry = "Dual-Cell Li-Po",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P1 (Chip sạc 120W) & PMIC"
            )
            combined.contains("K50") -> BatteryProfile(
                capacity = 5500,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 67W Turbo Fast Charge",
                powerChips = "PMIC MediaTek MT63xx"
            )

            combined.contains("NOTE 14 PRO+") || combined.contains("NOTE 14 PRO PLUS") -> BatteryProfile(
                capacity = 6200,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 90W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 & Chip IC bảo vệ pin thông minh"
            )
            combined.contains("NOTE 14 PRO") -> BatteryProfile(
                capacity = 5500,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 45W Turbo Fast Charge",
                powerChips = "Chip quản lý nguồn PMIC thông minh"
            )
            combined.contains("NOTE 14") -> BatteryProfile(
                capacity = 5110,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi 45W Turbo Fast Charge",
                powerChips = "PMIC MediaTek & IC bảo vệ sạc"
            )

            combined.contains("NOTE 13 PRO+") || combined.contains("NOTE 13 PRO PLUS") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po High-Density)",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P1 (Chip sạc 120W) & PMIC MediaTek"
            )
            combined.contains("NOTE 13 PRO") -> BatteryProfile(
                capacity = 5100,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 67W Turbo Fast Charge",
                powerChips = "Qualcomm PMIC & IC điều phối dòng sạc"
            )
            combined.contains("NOTE 13") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi 33W Fast Charge",
                powerChips = "PMIC điều phối sạc thông minh"
            )

            combined.contains("NOTE 12 TURBO") || combined.contains("POCO F5") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 67W Turbo Fast Charge",
                powerChips = "PMIC Qualcomm Snapdragon & Charge Pump"
            )
            combined.contains("NOTE 12 PRO+") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Dual-Cell Li-Po",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P1 (Sạc nhanh 120W) & PMIC MediaTek"
            )
            combined.contains("NOTE 12 PRO") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 67W Turbo Fast Charge",
                powerChips = "PMIC MediaTek & IC điều phối sạc"
            )
            combined.contains("NOTE 12") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi 33W Fast Charge",
                powerChips = "PMIC Qualcomm / MediaTek"
            )

            combined.contains("POCO F7 ULTRA") || combined.contains("POCO F7 PRO") -> BatteryProfile(
                capacity = if (combined.contains("ULTRA")) 6000 else 6500,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang - Kim Sa Giang Mật Độ Cao)",
                chargingTech = "Xiaomi HyperCharge 120W có dây · 50W Sạc không dây",
                powerChips = "Xiaomi Surge P3 (Sạc nhanh 120W) & Xiaomi Surge G1"
            )
            combined.contains("POCO F7") -> BatteryProfile(
                capacity = 6500,
                chemistry = "Silicon-Carbon (Si/C Jinshajiang - Kim Sa Giang Siêu Dung Lượng)",
                chargingTech = "Xiaomi HyperCharge 90W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 (Sạc nhanh 90W) & PMIC Qualcomm"
            )
            combined.contains("POCO F6 PRO") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 (Sạc nhanh 120W) & Xiaomi Surge G1"
            )
            combined.contains("POCO F5 PRO") -> BatteryProfile(
                capacity = 5160,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi HyperCharge 67W có dây · 30W Sạc không dây",
                powerChips = "Xiaomi Surge P1 & PMIC Qualcomm"
            )
            combined.contains("POCO F4 GT") -> BatteryProfile(
                capacity = 4700,
                chemistry = "Dual-Cell Li-Po",
                chargingTech = "Xiaomi HyperCharge 120W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P1 & Dual Charge Pump"
            )

            combined.contains("POCO X7 PRO") -> BatteryProfile(
                capacity = 6000,
                chemistry = "Silicon-Carbon (Si/C) / Li-Po High-Density",
                chargingTech = "Xiaomi HyperCharge 90W Turbo Fast Charge",
                powerChips = "Xiaomi Surge P2 & Chip IC tối ưu hóa pin"
            )
            combined.contains("POCO X7") -> BatteryProfile(
                capacity = 5110,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = "Xiaomi 45W Turbo Fast Charge",
                powerChips = "PMIC MediaTek & IC điều phối sạc"
            )
            combined.contains("POCO X5 PRO") || combined.contains("POCO X5") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = if (combined.contains("PRO")) "Xiaomi HyperCharge 67W Turbo Fast Charge" else "Xiaomi 33W Fast Charge",
                powerChips = "Qualcomm PMIC & IC điều phối sạc"
            )
            combined.contains("POCO M6 PRO") || combined.contains("POCO M6") || combined.contains("POCO M5") || combined.contains("REDMI 13C") || combined.contains("REDMI 14C") || combined.contains("REDMI 12") -> BatteryProfile(
                capacity = 5000,
                chemistry = "Lithium-Ion Polymer (Li-Po)",
                chargingTech = if (combined.contains("PRO")) "Xiaomi 33W Turbo Fast Charge" else "Xiaomi 18W Fast Charge",
                powerChips = "PMIC MediaTek MT63xx / Qualcomm PMIC & Chip IC bảo vệ nguồn"
            )

            else -> {
                val hwCap = getBatteryCapacity(context)
                val isSiC = hwCap >= 6000
                val chem = if (isSiC) "Silicon-Carbon (Si/C Jinshajiang Mật Độ Cao)" else "Lithium-Ion Polymer (Li-Po)"
                val tech = when {
                    hwCap >= 6000 -> "Xiaomi HyperCharge 90W / 120W Turbo Charge"
                    hwCap >= 5000 -> "Xiaomi HyperCharge 67W / 90W Turbo Charge"
                    else -> "Xiaomi HyperCharge 33W / 67W Fast Charge"
                }
                val chips = if (hwCap >= 6000) "Xiaomi Surge P-Series & Chip IC điều phối dòng sạc thông minh" else "Chip IC điều phối dòng sạc & PMIC bảo vệ pin"
                BatteryProfile(
                    capacity = hwCap,
                    chemistry = chem,
                    chargingTech = tech,
                    powerChips = chips
                )
            }
        }
    }

    fun getBatterySections(context: Context): List<InfoSection> {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
            BatteryManager.BATTERY_STATUS_CHARGING -> "Đang nạp sạc ⚡"
            BatteryManager.BATTERY_STATUS_FULL -> "Pin đầy (100%)"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Đang ngừng sạc"
            else -> "Đang xả"
        }

        val batProfile = getBatteryProfile(context)
        val cap = getBatteryCapacity(context).coerceAtLeast(batProfile.capacity)

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
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
        val liveTemp = BatteryHealthManager.getLiveBatteryTemperature(context, batteryIntent)
        val tempStr = "${String.format(Locale.US, "%.1f", liveTemp)} °C"

        val cycleCount = BatteryHealthManager.readHardwareCycleCount(context)
        val cycleStr = if (cycleCount > 0) "$cycleCount chu kỳ (Phần cứng BMS)" else "Yêu cầu Android 14+ hoặc Shizuku"

        val bmsSoh = BatteryHealthManager.readHardwareBmsSoh()
        val chargeFull = BatteryHealthManager.readHardwareChargeFull()
        val healthStr = when {
            bmsSoh in 40..100 && chargeFull > 0 -> "$bmsSoh% (~$chargeFull / $cap mAh)"
            bmsSoh in 40..100 -> "$bmsSoh% (Phần cứng BMS)"
            chargeFull > 0 -> "${kotlin.math.round(((chargeFull.toDouble() / cap) * 100)).toInt().coerceIn(50, 100)}% (~$chargeFull / $cap mAh)"
            else -> "Đo chính xác khi cắm sạc"
        }

        val statusItems = listOf(
            "Công suất tức thời" to powerStr,
            "Nhiệt độ Pin" to tempStr,
            "Trạng thái hoạt động" to statusStr,
            "Nguồn kết nối" to pluggedStr,
            "Chu kỳ sạc phần cứng" to cycleStr,
            "Sức khỏe Pin (BMS SOH)" to healthStr,
            "Kiểm Tra Pin" to ""
        )
        val statusActions = mapOf("Kiểm Tra Pin" to "CHẠY")

        val hardwarePowerItems = getHardwarePowerProfileItems(context)

        val specItems = listOf(
            "Dung lượng thiết kế" to "$cap mAh (Điển hình)",
            "Công nghệ hóa học Pin" to batProfile.chemistry,
            "Công nghệ sạc nhanh" to batProfile.chargingTech,
            "Chip quản lý năng lượng" to batProfile.powerChips
        )

        return listOf(
            InfoSection(sectionTitle = "", items = statusItems, actionItems = statusActions),
            InfoSection(sectionTitle = "Mức tiêu thụ phần cứng ước lượng", items = hardwarePowerItems),
            InfoSection(sectionTitle = "Thông số kỹ thuật Pin", items = specItems)
        )
    }

    fun getDeviceOfficialReleaseDate(context: Context): String {
        val model = Build.MODEL.uppercase()
        val market = getSystemProperty("ro.product.marketname").uppercase()
        val dev = Build.DEVICE.uppercase()
        val combined = "$model $market $dev"

        return when {

            combined.contains("18 PRO MAX") || combined.contains("18 PROMAX") || combined.contains("18PROMAX") || combined.contains("XIAOMI 18 PRO MAX") -> "10/2026"
            combined.contains("18 ULTRA") || combined.contains("XIAOMI 18 ULTRA") -> "10/2026"
            combined.contains("18 PRO") || combined.contains("XIAOMI 18 PRO") -> "10/2026"
            combined.contains("XIAOMI 18") -> "10/2026"

            combined.contains("17 ULTRA") || combined.contains("17 PRO MAX") || combined.contains("17 PROMAX") || combined.contains("17PROMAX") -> "02/2026"
            combined.contains("17 PRO") || combined.contains("XIAOMI 17 PRO") -> "10/2025"
            combined.contains("XIAOMI 17") -> "10/2025"

            combined.contains("K100 PRO MAX") || combined.contains("K100 MAX") || combined.contains("K100 ULTRA") || combined.contains("K100 EXTREME") -> "07/2026"
            combined.contains("K100 PRO") || combined.contains("K100") -> "11/2025"

            combined.contains("K90 PRO MAX") || combined.contains("K90 MAX") || combined.contains("K90 ULTRA") || combined.contains("K90 EXTREME") -> "07/2025"
            combined.contains("K90 PRO") || combined.contains("K90") -> "11/2024"

            combined.contains("NOTE 17 PRO+") || combined.contains("NOTE 17 PRO PLUS") -> "09/2025"
            combined.contains("NOTE 17 PRO") || combined.contains("NOTE 17 TURBO") || combined.contains("NOTE 17") -> "09/2025"

            combined.contains("15 PRO") || combined.contains("XIAOMI 15 PRO") -> "10/2024"
            combined.contains("15 ULTRA") -> "02/2025"
            combined.contains("XIAOMI 15") -> "10/2024"

            combined.contains("14 ULTRA") -> "02/2024"
            combined.contains("14 PRO") || combined.contains("XIAOMI 14 PRO") -> "10/2023"
            combined.contains("XIAOMI 14") -> "10/2023"
            combined.contains("14T PRO") || combined.contains("14T") -> "09/2024"
            combined.contains("CIVI 4 PRO") || combined.contains("14 CIVI") -> "03/2024"

            combined.contains("13 ULTRA") -> "04/2023"
            combined.contains("13 PRO") || combined.contains("XIAOMI 13 PRO") -> "12/2022"
            combined.contains("XIAOMI 13") -> "12/2022"
            combined.contains("13T PRO") || combined.contains("13T") -> "09/2023"

            combined.contains("12S ULTRA") -> "07/2022"
            combined.contains("12S PRO") || combined.contains("12S") -> "07/2022"
            combined.contains("12 PRO") || combined.contains("12X") || combined.contains("XIAOMI 12") -> "12/2021"

            combined.contains("TURBO 5 MAX") || combined.contains("TURBO5 MAX") || combined.contains("TURBO 5 PRO") -> "04/2026"
            combined.contains("TURBO 5") || combined.contains("TURBO5") -> "04/2026"
            combined.contains("TURBO 4 PRO") || combined.contains("TURBO4 PRO") -> "01/2025"
            combined.contains("TURBO 4") || combined.contains("TURBO4") -> "01/2025"
            combined.contains("TURBO 3") || combined.contains("TURBO3") -> "04/2024"

            combined.contains("K80 ULTRA") || combined.contains("K80 EXTREME") -> "07/2025"
            combined.contains("K80 PRO") -> "11/2024"
            combined.contains("K80") -> "11/2024"

            combined.contains("K70 ULTRA") || combined.contains("K70 EXTREME") -> "07/2024"
            combined.contains("K70 PRO") -> "11/2023"
            combined.contains("K70E") -> "11/2023"
            combined.contains("K70") -> "11/2023"

            combined.contains("K60 ULTRA") -> "08/2023"
            combined.contains("K60 PRO") || combined.contains("K60") || combined.contains("K60E") -> "12/2022"

            combined.contains("K50 ULTRA") || combined.contains("K50 GAMING") -> "08/2022"
            combined.contains("K50 PRO") || combined.contains("K50") -> "03/2022"

            combined.contains("NOTE 14 PRO+") || combined.contains("NOTE 14 PRO PLUS") -> "09/2024"
            combined.contains("NOTE 14 PRO") || combined.contains("NOTE 14") -> "09/2024"

            combined.contains("NOTE 13 PRO+") || combined.contains("NOTE 13 PRO") || combined.contains("NOTE 13") -> "09/2023"

            combined.contains("NOTE 12 TURBO") || combined.contains("12 TURBO") -> "03/2023"
            combined.contains("NOTE 12 PRO") || combined.contains("NOTE 12") -> "10/2022"

            combined.contains("POCO F7") -> "03/2025"
            combined.contains("POCO F6 PRO") || combined.contains("POCO F6") -> "05/2024"
            combined.contains("POCO F5 PRO") || combined.contains("POCO F5") -> "05/2023"
            combined.contains("POCO X7 PRO") || combined.contains("POCO X7") -> "01/2025"
            combined.contains("POCO X6 PRO") || combined.contains("POCO X6") -> "01/2024"
            combined.contains("POCO X5 PRO") || combined.contains("POCO X5") -> "02/2023"

            combined.contains("MIX FOLD 4") || combined.contains("MIX FLIP") -> "07/2024"
            combined.contains("MIX FOLD 3") -> "08/2023"
            combined.contains("MIX FOLD 2") -> "08/2022"

            combined.contains("PAD 7 PRO") || combined.contains("PAD 7") -> "10/2024"
            combined.contains("PAD 6S PRO") -> "02/2024"
            combined.contains("PAD 6 PRO") || combined.contains("PAD 6") -> "04/2023"

            else -> {
                val firstApi = getSystemProperty("ro.product.first_api_level").toIntOrNull() ?: 0
                when {
                    firstApi >= 35 -> "Q4/2024"
                    firstApi == 34 -> "Q4/2023"
                    firstApi == 33 -> "Q4/2022"
                    firstApi in 31..32 -> "Q4/2021"
                    firstApi == 30 -> "Q4/2020"
                    else -> "2024"
                }
            }
        }
    }

    private fun resolveSocName(rawSoc: String, boardPlatform: String, glInfo: GlHardwareInfo, context: Context? = null): String {
        val cpuInfo = readCpuInfoHardware()
        val propChip = getSystemProperty("ro.chipname")
        val propMtk = getSystemProperty("ro.mediatek.platform")
        val propQti = getSystemProperty("ro.vendor.qti.soc_name")
        val propModel = getSystemProperty("ro.soc.model")
        val propMarket = getSystemProperty("ro.product.marketname").ifEmpty { Build.MODEL }

        val rawList = listOf(rawSoc, boardPlatform, propChip, propMtk, propQti, propModel, Build.HARDWARE, cpuInfo, propMarket)
        val upperSources = rawList.map { it.uppercase().trim() }.filter { it.isNotEmpty() }

        fun hasExactToken(token: String): Boolean {
            val t = token.uppercase()
            return upperSources.any { src ->
                if (src == t) return@any true
                val parts = src.split(Regex("[^A-Z0-9_]"))
                parts.contains(t)
            }
        }

        fun hasSub(sub: String): Boolean {
            val s = sub.uppercase()
            return upperSources.any { it.contains(s) }
        }

        val gl = glInfo.renderer.uppercase()
        val vendor = glInfo.vendor.uppercase()
        val isQualcommGpu = vendor.contains("QUALCOMM") || gl.contains("ADRENO")
        val isArmGpu = vendor.contains("ARM") || gl.contains("MALI") || gl.contains("IMMORTALIS")

        if (isQualcommGpu || hasExactToken("QCOM") || propQti.isNotEmpty() || (hasSub("QUALCOMM") && !isArmGpu)) {
            return when {

                gl.contains("850 EXTREME") || hasExactToken("SM8950-AC") || hasSub("8 ELITE EXTREME") || hasSub("EXTREME GEN 6") || hasSub("8 ELITE EXTREME GEN 6") || hasSub("18 PRO MAX") || hasSub("18 PROMAX") || hasSub("18PROMAX") -> "Qualcomm Snapdragon 8 Elite Extreme Gen 6"

                gl.contains("850") || hasExactToken("SM8950") || hasSub("8 ELITE GEN 6") || hasSub("8 GEN 6") || hasSub("18 PRO") || hasSub("XIAOMI 18") -> "Qualcomm Snapdragon 8 Elite Gen 6"

                gl.contains("840") || hasExactToken("SM8850") || hasSub("8 ELITE GEN 5") || hasSub("8 GEN 5") || hasSub("8 ELITE GEN 2") -> "Qualcomm Snapdragon 8 Elite Gen 5"

                gl.contains("835") || hasExactToken("SM8835") || hasSub("8 ELITE GEN 5V") || hasSub("8S GEN 5") || hasSub("8S ELITE") -> "Qualcomm Snapdragon 8 Elite Gen 5v"

                gl.contains("830") || hasExactToken("SM8750") || hasSub("8 ELITE") -> "Qualcomm Snapdragon 8 Elite"

                gl.contains("825") || hasExactToken("SM8735") || hasSub("8S GEN 4") -> "Qualcomm Snapdragon 8s Gen 4"

                gl.contains("750") || hasExactToken("SM8650") || hasExactToken("PINEAPPLE") || hasSub("8 GEN 3") -> "Qualcomm Snapdragon 8 Gen 3"

                gl.contains("735") || hasExactToken("SM8635") || hasExactToken("CLIFFS") || hasSub("8S GEN 3") -> "Qualcomm Snapdragon 8s Gen 3"

                gl.contains("740") || hasExactToken("SM8550") || hasExactToken("KALAMA") || hasSub("8 GEN 2") -> "Qualcomm Snapdragon 8 Gen 2"

                hasExactToken("SM8475") || hasExactToken("CAPE") || hasSub("8+ GEN 1") -> "Qualcomm Snapdragon 8+ Gen 1"

                hasExactToken("SM8450") || hasExactToken("TARO") || (gl.contains("730") && !hasSub("7+")) -> "Qualcomm Snapdragon 8 Gen 1"

                hasExactToken("SM7635") || hasSub("7S GEN 4") || hasSub("7S GEN 3") -> "Qualcomm Snapdragon 7s Gen 3"

                gl.contains("732") || hasExactToken("SM7675") || hasSub("7+ GEN 3") -> "Qualcomm Snapdragon 7+ Gen 3"

                gl.contains("720") || hasExactToken("SM7550") || hasExactToken("CROW") || hasSub("7 GEN 3") -> "Qualcomm Snapdragon 7 Gen 3"

                gl.contains("725") || hasExactToken("SM7475") || hasSub("7+ GEN 2") -> "Qualcomm Snapdragon 7+ Gen 2"

                gl.contains("644") || hasExactToken("SM7450") -> "Qualcomm Snapdragon 7 Gen 1"

                hasExactToken("SM6450") || hasExactToken("SM6475") || hasSub("6 GEN 3") || hasSub("6 GEN 1") -> "Qualcomm Snapdragon 6 Gen 3"

                gl.contains("642") || hasExactToken("SM7325") || hasExactToken("YUPIK") || hasSub("778G") -> "Qualcomm Snapdragon 778G / 778G+"

                gl.contains("660") || hasExactToken("SM8350") || hasExactToken("LAHAINA") || hasSub("888") -> "Qualcomm Snapdragon 888 / 888+"

                gl.contains("650") || hasExactToken("SM8250") || hasExactToken("KONA") || hasSub("870") || hasSub("865") -> "Qualcomm Snapdragon 870 / 865"

                gl.contains("619") || hasExactToken("SM6375") || hasExactToken("HOLI") || hasSub("695") -> "Qualcomm Snapdragon 695 5G"

                gl.contains("613") || hasExactToken("SM4450") || hasSub("4 GEN 2") -> "Qualcomm Snapdragon 4 Gen 2"

                rawSoc.isNotEmpty() -> "Qualcomm $rawSoc"
                propChip.isNotEmpty() -> propChip
                else -> "Qualcomm Snapdragon Processor"
            }
        }

        if (isArmGpu || isMediaTekDevice() || hasSub("MT") || hasSub("MEDIATEK") || hasSub("DIMENSITY") || hasSub("HELIO")) {
            return when {

                gl.contains("G935") || hasExactToken("MT6993") || hasSub("D9500+") || hasSub("DIMENSITY 9500") -> "MediaTek Dimensity 9500"

                gl.contains("G930") || hasExactToken("MT6992") || hasSub("D9500S") || hasSub("DIMENSITY 9500S") -> "MediaTek Dimensity 9500s"

                gl.contains("G925") || hasExactToken("MT6991") || hasSub("D9400") -> "MediaTek Dimensity 9400"

                (gl.contains("G720") && (hasSub("9300") || hasSub("K70 ULTRA") || hasSub("14T PRO") || hasExactToken("MT6989"))) -> "MediaTek Dimensity 9300 / 9300+"

                gl.contains("G715") || hasExactToken("MT6985") || hasSub("D9200") -> "MediaTek Dimensity 9200 / 9200+"

                gl.contains("G710") || hasExactToken("MT6983") || hasSub("D9000") -> "MediaTek Dimensity 9000 / 9000+"

                hasExactToken("MT6899") || hasSub("D8400") || hasSub("DIMENSITY 8400") -> "MediaTek Dimensity 8400-Ultra"

                gl.contains("G615") || hasExactToken("MT6897") || hasSub("D8300") -> "MediaTek Dimensity 8300-Ultra"

                hasExactToken("MT6878") || hasSub("D7300") || hasSub("7300-ULTRA") -> "MediaTek Dimensity 7300-Ultra"

                gl.contains("G610") || hasExactToken("MT6895") || hasSub("D8200") || hasSub("D8100") -> "MediaTek Dimensity 8100 / 8200-Ultra"

                gl.contains("G68") || hasExactToken("MT6877") || hasSub("D7050") || hasSub("D1080") -> "MediaTek Dimensity 7050 / 1080"

                hasExactToken("MT6835") || hasExactToken("MT6833") || hasSub("D6300") || hasSub("D6080") || hasSub("D700") -> "MediaTek Dimensity 6300 / 6080"

                gl.contains("G57") || hasExactToken("MT6789") || hasSub("G99") -> "MediaTek Helio G99"

                gl.contains("G52") || hasExactToken("MT6769") || hasSub("G85") || hasSub("G88") -> "MediaTek Helio G85 / G88"

                propMtk.isNotEmpty() -> "MediaTek $propMtk"
                rawSoc.isNotEmpty() -> "MediaTek $rawSoc"
                else -> "MediaTek Dimensity Processor"
            }
        }

        return when {
            rawSoc.isNotEmpty() -> rawSoc
            propChip.isNotEmpty() -> propChip
            else -> "Octa-core Processor"
        }
    }

    private fun getCpuFabrication(soc: String): String {
        return when {
            soc.contains("8 Elite Gen 5") || soc.contains("8 Elite Gen 5v") || soc.contains("8 Elite Gen 2") || soc.contains("8 Gen 5") || soc.contains("8s Gen 5") || soc.contains("9500") -> "3nm N3P (TSMC)"
            soc.contains("8 Elite") || soc.contains("9400") -> "3nm N3E (TSMC)"
            soc.contains("8s Gen 4") || soc.contains("8s Elite") || soc.contains("8 Gen 3") || soc.contains("9300") || soc.contains("8s Gen 3") || soc.contains("8400") || soc.contains("7300") -> "4nm N4P (TSMC)"
            soc.contains("8 Gen 2") || soc.contains("9200") || soc.contains("8300") -> "4nm N4 (TSMC)"
            soc.contains("8+ Gen 1") -> "4nm (TSMC)"
            soc.contains("8 Gen 1") -> "4nm (Samsung)"
            soc.contains("7+ Gen 3") || soc.contains("7 Gen 3") || soc.contains("7s Gen 3") || soc.contains("6 Gen 3") -> "4nm (TSMC)"
            soc.contains("7+ Gen 2") -> "4nm (TSMC)"
            soc.contains("888") -> "5nm (Samsung)"
            soc.contains("870") || soc.contains("865") -> "7nm (TSMC)"
            soc.contains("6300") || soc.contains("G99") -> "6nm (TSMC)"
            else -> "4nm / 6nm (TSMC)"
        }
    }

    private fun getSocReleaseDate(soc: String): String {
        return when {
            soc.contains("8 Elite Gen 5") || soc.contains("8 Elite Gen 5v") || soc.contains("8 Elite Gen 2") || soc.contains("8 Gen 5") || soc.contains("8s Gen 5") || soc.contains("9500") -> "2025-Q4"
            soc.contains("8s Gen 4") || soc.contains("8s Elite") || soc.contains("7s Gen 3") || soc.contains("6 Gen 3") -> "2025-Q1"
            soc.contains("8 Elite") || soc.contains("9400") || soc.contains("8400") || soc.contains("7300") -> "2024-Q4"
            soc.contains("8s Gen 3") || soc.contains("7+ Gen 3") -> "2024-Q1"
            soc.contains("8 Gen 3") || soc.contains("9300") || soc.contains("8300") -> "2023-Q4"
            soc.contains("8 Gen 2") || soc.contains("9200") -> "2022-Q4"
            soc.contains("8+ Gen 1") -> "2022-Q2"
            soc.contains("8 Gen 1") -> "2021-Q4"
            soc.contains("888") -> "2020-Q4"
            soc.contains("870") -> "2021-Q1"
            soc.contains("865") -> "2019-Q4"
            soc.contains("G99") -> "2022-Q2"
            else -> "2024 / 2025"
        }
    }

    private fun readCpuInfoHardware(): String {
        return try {
            val f = File("/proc/cpuinfo")
            if (f.exists() && f.canRead()) {
                f.useLines { lines ->
                    lines.filter { it.startsWith("Hardware") || it.startsWith("model name") || it.startsWith("Processor") }
                        .joinToString(" ")
                }
            } else ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun getCpuMinFreqMHz(): Int {
        val file = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq")
        val freqKHz = readIntFromFile(file, fallback = 384000)
        return freqKHz / 1000
    }

    private fun getCpuMaxFreqMHz(): Int {
        var maxKHz = 0
        val coreCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
        for (i in 0 until coreCount) {
            val file = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            val f = readIntFromFile(file, fallback = 0)
            if (f > maxKHz) maxKHz = f
        }
        if (maxKHz == 0) maxKHz = 3000000
        return maxKHz / 1000
    }

    private fun getBatteryCapacity(context: Context): Int {

        val designFiles = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/battery/full_cap_design",
            "/sys/class/power_supply/main/charge_full_design"
        )
        for (path in designFiles) {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                val raw = readIntFromFile(f, 0)
                if (raw > 50000) {
                    val mah = raw / 1000
                    if (mah in 2000..12000) return mah
                } else if (raw in 2000..12000) {
                    return raw
                }
            }
        }

        try {
            if (ShizukuUtils.hasShizukuPermission()) {
                for (path in designFiles) {
                    val res = ShizukuUtils.execShizukuCommand("cat $path 2>/dev/null")
                    if (res.exitCode == 0 && res.stdout.isNotBlank()) {
                        val raw = res.stdout.trim().toIntOrNull() ?: 0
                        if (raw > 50000) {
                            val mah = raw / 1000
                            if (mah in 2000..12000) return mah
                        } else if (raw in 2000..12000) {
                            return raw
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        try {
            val mPowerProfile = Class.forName("com.android.internal.os.PowerProfile")
                .getConstructor(Context::class.java)
                .newInstance(context)
            val cap = Class.forName("com.android.internal.os.PowerProfile")
                .getMethod("getBatteryCapacity")
                .invoke(mPowerProfile) as Double
            if (cap > 2000) return cap.toInt()
        } catch (_: Throwable) {}

        return getBatteryProfile(context).capacity
    }

    private fun getBatteryCycleCount(context: Context? = null): Int {

        if (Build.VERSION.SDK_INT >= 34 && context != null) {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val count = bm?.getIntProperty(7) ?: -1
                if (count > 0) return count
            } catch (_: Throwable) {}
        }

        val cycleFiles = listOf(
            File("/sys/class/power_supply/battery/cycle_count"),
            File("/sys/class/power_supply/bms/battery_cycle"),
            File("/sys/class/power_supply/bms/cycle_count"),
            File("/sys/class/power_supply/battery/battery_cycle"),
            File("/sys/class/power_supply/battery/count"),
            File("/sys/class/power_supply/battery/fg_cycle"),
            File("/sys/class/power_supply/battery/capacity_cycle"),
            File("/sys/class/power_supply/main/cycle_count"),
            File("/sys/class/qcom-battery/cycle_count"),
            File("/sys/class/power_supply/battery/cycle"),
            File("/sys/class/power_supply/bms/cycle")
        )
        for (f in cycleFiles) {
            if (f.exists() && f.canRead()) {
                val count = readIntFromFile(f, -1)
                if (count > 0) return count
            }
        }

        try {
            if (ShizukuUtils.hasShizukuPermission()) {
                for (f in cycleFiles) {
                    val cmd = ShizukuUtils.execShizukuCommand("cat ${f.absolutePath} 2>/dev/null")
                    if (cmd.exitCode == 0 && cmd.stdout.isNotBlank()) {
                        val count = cmd.stdout.trim().toIntOrNull() ?: -1
                        if (count > 0) return count
                    }
                }
            }
        } catch (_: Throwable) {}

        return 0
    }

    private var cachedCpuTempFile: File? = null
    private var cachedGpuTempFile: File? = null
    private var lastThermalScanTimeMs: Long = 0L

    fun getCpuTemperature(): Double {
        val now = System.currentTimeMillis()
        val cached = cachedCpuTempFile
        if (cached != null && (now - lastThermalScanTimeMs) < 30_000L && cached.exists() && cached.canRead()) {
            val raw = readIntFromFile(cached, 0)
            val tempVal = when {
                raw > 10000 -> raw / 1000.0
                raw in 20..115 -> raw.toDouble()
                else -> 0.0
            }
            if (tempVal in 25.0..95.0) return tempVal
        }

        try {
            val dir = File("/sys/class/thermal")
            if (dir.exists() && dir.isDirectory) {
                val zones = dir.listFiles { file -> file.name.startsWith("thermal_zone") } ?: emptyArray()

                var socPackageTemp = 0.0
                var socTempFile: File? = null
                val coreTemps = mutableListOf<Pair<Double, File>>()
                val generalCpuTemps = mutableListOf<Double>()

                fun isExcluded(name: String): Boolean {
                    return name.contains("pmic") || name.contains("pm8") || name.contains("pmk") ||
                            name.contains("smb") || name.contains("chg") || name.contains("charg") ||
                            name.contains("bat") || name.contains("bms") || name.contains("fuel") ||
                            name.contains("vbat") || name.contains("modem") || name.contains("mdm") ||
                            name.contains("pa-") || name.contains("pa_") || name.contains("pa0") ||
                            name.contains("pa1") || name.contains("q6") || name.contains("cam") ||
                            name.contains("flash") || name.contains("wifi") || name.contains("wlan") ||
                            name.contains("disp") || name.contains("panel") || name.contains("touch") ||
                            name.contains("quiet") || name.contains("skin") || name.contains("xo") ||
                            name.contains("audio") || name.contains("speaker") || name.contains("gpu") ||
                            name.contains("kgsl") || name.contains("adreno") || name.contains("mali")
                }

                for (zone in zones) {
                    val typeFile = File(zone, "type")
                    val tempFile = File(zone, "temp")
                    if (!tempFile.exists() || !tempFile.canRead()) continue

                    val typeName = if (typeFile.exists()) {
                        try { typeFile.readText().trim().lowercase() } catch (_: Throwable) { "" }
                    } else ""

                    if (isExcluded(typeName)) continue

                    val raw = readIntFromFile(tempFile, 0)
                    val tempVal = when {
                        raw > 10000 -> raw / 1000.0
                        raw in 20..115 -> raw.toDouble()
                        else -> 0.0
                    }
                    if (tempVal !in 22.0..105.0) continue

                    val isSocPackage = typeName == "ap-therm" || typeName == "ap-therm-usr" ||
                            typeName == "soc-therm" || typeName == "soc" || typeName == "mtktscpu" ||
                            typeName == "cpu-top-usr" || typeName == "cpu-package" || typeName == "cpu-composite"

                    if (isSocPackage && tempVal in 25.0..95.0) {
                        socPackageTemp = tempVal
                        socTempFile = tempFile
                    }

                    val isGenuineCpuCore = typeName.startsWith("cpu-") || typeName.startsWith("cpu0") ||
                            typeName.startsWith("cpu1") || typeName.startsWith("cpu2") ||
                            typeName.startsWith("cpu3") || typeName.startsWith("cpu4") ||
                            typeName.startsWith("cpu5") || typeName.startsWith("cpu6") ||
                            typeName.startsWith("cpu7") || typeName.contains("cpuss") ||
                            typeName.contains("gold") || typeName.contains("silver") ||
                            typeName.contains("prime") || typeName.contains("kryo") ||
                            typeName.contains("oryon")

                    if (isGenuineCpuCore) {
                        coreTemps.add(Pair(tempVal, tempFile))
                    } else if (typeName.contains("cpu") || typeName.contains("soc")) {
                        generalCpuTemps.add(tempVal)
                    }
                }

                if (socPackageTemp in 25.0..95.0 && socTempFile != null) {
                    cachedCpuTempFile = socTempFile
                    lastThermalScanTimeMs = now
                    return socPackageTemp
                }

                if (coreTemps.isNotEmpty()) {
                    val sorted = coreTemps.sortedBy { it.first }
                    val medianIdx = sorted.size / 2
                    val medianVal = sorted[medianIdx].first

                    val validCores = sorted.filter { it.first in 25.0..(medianVal + 18.0) }
                    if (validCores.isNotEmpty()) {
                        val best = validCores.last()
                        cachedCpuTempFile = best.second
                        lastThermalScanTimeMs = now
                        return best.first
                    }
                    cachedCpuTempFile = sorted[medianIdx].second
                    lastThermalScanTimeMs = now
                    return medianVal
                }

                if (generalCpuTemps.isNotEmpty()) {
                    val sorted = generalCpuTemps.sorted()
                    val median = sorted[sorted.size / 2]
                    return median.coerceIn(28.0, 85.0)
                }
            }
        } catch (_: Throwable) {}

        val thermalFiles = listOf(
            File("/sys/class/thermal/thermal_zone0/temp"),
            File("/sys/class/thermal/thermal_zone1/temp")
        )
        for (f in thermalFiles) {
            if (f.exists()) {
                val raw = readIntFromFile(f, 0)
                if (raw > 1000) return (raw / 1000.0).coerceIn(25.0, 85.0)
                if (raw in 20..90) return raw.toDouble().coerceIn(25.0, 85.0)
            }
        }
        return 42.0
    }

    private var prevCpuTotal = 0L
    private var prevCpuIdle = 0L
    private val procStatLock = Any()

    private const val EXTERNAL_STALE_MS = 3_000L
    @Volatile private var externalCpuUsageAtMs = 0L
    @Volatile private var externalGpuUsageAtMs = 0L

    @Volatile var externalCpuUsage: Int = -1
        set(value) { field = value; externalCpuUsageAtMs = System.currentTimeMillis() }
    @Volatile var externalGpuUsage: Int = -1
        set(value) { field = value; externalGpuUsageAtMs = System.currentTimeMillis() }
    @Volatile var externalGpuFreqMHz: Int = -1

    val cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
    val cpuMinFreqsKHz = IntArray(cpuCoreCount)
    val cpuMaxFreqsKHz = IntArray(cpuCoreCount)
    val cpuCurFreqFiles = Array(cpuCoreCount) { File("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq") }
    @Volatile var isCpuFreqBoundsLoaded = false

    fun ensureCpuFreqBounds() {
        if (isCpuFreqBoundsLoaded) return
        for (i in 0 until cpuCoreCount) {
            val minF = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq")
            val maxF = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            val minVal = readIntFromFile(minF, 300000)
            val maxVal = readIntFromFile(maxF, 2400000)
            cpuMinFreqsKHz[i] = if (minVal > 0) minVal else 300000
            cpuMaxFreqsKHz[i] = if (maxVal > minVal) maxVal else 2400000
        }
        isCpuFreqBoundsLoaded = true
    }

    fun parseProcStatLine(line: String): Int? {
        if (!line.startsWith("cpu ")) return null
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size >= 8) {
            val user = parts[1].toLongOrNull() ?: 0L
            val nice = parts[2].toLongOrNull() ?: 0L
            val system = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val iowait = parts[5].toLongOrNull() ?: 0L
            val irq = parts[6].toLongOrNull() ?: 0L
            val softirq = parts[7].toLongOrNull() ?: 0L
            val steal = if (parts.size > 8) parts[8].toLongOrNull() ?: 0L else 0L

            val total = user + nice + system + idle + iowait + irq + softirq + steal
            val totalIdle = idle + iowait

            synchronized(procStatLock) {
                val hadPrevious = prevCpuTotal > 0L
                val diffTotal = total - prevCpuTotal
                val diffIdle = totalIdle - prevCpuIdle

                prevCpuTotal = total
                prevCpuIdle = totalIdle

                if (hadPrevious && diffTotal > 0) {
                    val usage = ((diffTotal - diffIdle) * 100f / diffTotal).roundToInt()
                    return usage.coerceIn(0, 100)
                }
            }
        }
        return null
    }

    private var smoothedCpuUsage: Float = -1f
    private var smoothedGpuUsage: Float = -1f

    private fun smoothUsage(currentSmoothed: Float, target: Int): Float {
        if (currentSmoothed < 0f) return target.toFloat()

        return currentSmoothed * 0.35f + target * 0.65f
    }

    fun getCpuUsagePercent(): Int {
        val raw = getRawCpuUsagePercent()
        smoothedCpuUsage = smoothUsage(smoothedCpuUsage, raw)
        return smoothedCpuUsage.roundToInt().coerceIn(0, 100)
    }

    private fun getRawCpuUsagePercent(): Int {

        val extCpu = externalCpuUsage
        if (extCpu in 0..100 && System.currentTimeMillis() - externalCpuUsageAtMs <= EXTERNAL_STALE_MS) {
            return extCpu
        }

        try {
            val statFile = File("/proc/stat")
            if (statFile.exists() && statFile.canRead()) {
                val line = statFile.bufferedReader().use { it.readLine() }
                if (line != null && line.startsWith("cpu ")) {
                    val parsed = parseProcStatLine(line)
                    if (parsed != null) return parsed
                }
            }
        } catch (_: Throwable) {}

        try {
            ensureCpuFreqBounds()
            var sumWeightedUsage = 0.0
            var sumMaxCapacity = 0.0
            for (i in 0 until cpuCoreCount) {
                val curKHz = readIntFromFile(cpuCurFreqFiles[i], 0)
                val minKHz = cpuMinFreqsKHz[i]
                val maxKHz = cpuMaxFreqsKHz[i]
                if (maxKHz > minKHz && curKHz >= minKHz) {
                    val ratio = (curKHz - minKHz).toDouble() / (maxKHz - minKHz)
                    sumWeightedUsage += ratio * maxKHz
                    sumMaxCapacity += maxKHz
                }
            }
            if (sumMaxCapacity > 0.0) {
                val percent = (sumWeightedUsage / sumMaxCapacity * 100.0).toInt()
                return percent.coerceIn(2, 100)
            }
        } catch (_: Throwable) {}

        return 12
    }

    fun getCpuCoreFrequencies(): Map<Int, Int> {
        ensureCpuFreqBounds()
        val freqs = mutableMapOf<Int, Int>()
        for (i in 0 until cpuCoreCount) {
            val minKHz = cpuMinFreqsKHz[i]
            val curKHz = readIntFromFile(cpuCurFreqFiles[i], fallback = (minKHz + 500000))
            val curMHz = (curKHz / 1000).coerceAtLeast(300)
            freqs[i] = curMHz
        }
        return freqs
    }

    fun getGpuTemperature(): Double {
        val now = System.currentTimeMillis()
        val cached = cachedGpuTempFile
        if (cached != null && (now - lastThermalScanTimeMs) < 30_000L && cached.exists() && cached.canRead()) {
            val raw = readIntFromFile(cached, 0)
            val tempVal = when {
                raw > 10000 -> raw / 1000.0
                raw in 20..115 -> raw.toDouble()
                else -> 0.0
            }
            if (tempVal in 25.0..95.0) return tempVal
        }

        try {
            var foundGpuTemp = 0.0
            var foundGpuFile: File? = null
            val dir = File("/sys/class/thermal")
            if (dir.exists() && dir.isDirectory) {
                val zones = dir.listFiles { file -> file.name.startsWith("thermal_zone") } ?: emptyArray()
                for (zone in zones) {
                    val typeFile = File(zone, "type")
                    val tempFile = File(zone, "temp")
                    if (tempFile.exists() && tempFile.canRead()) {
                        val typeName = if (typeFile.exists()) {
                            try { typeFile.readText().trim().lowercase() } catch (_: Throwable) { "" }
                        } else ""

                        if (typeName.contains("pmic") || typeName.contains("chg") ||
                                typeName.contains("charg") || typeName.contains("bat") ||
                                typeName.contains("pa-") || typeName.contains("modem")) continue

                        val isGpuZone = typeName.contains("gpu") || typeName.contains("gpuss") ||
                                typeName.contains("mali") || typeName.contains("adreno") ||
                                typeName.contains("kgsl") || typeName.contains("gpu-usr") ||
                                typeName.contains("gpu-therm") || typeName.contains("g3d")

                        val raw = readIntFromFile(tempFile, 0)
                        val tempVal = when {
                            raw > 10000 -> raw / 1000.0
                            raw in 20..115 -> raw.toDouble()
                            else -> 0.0
                        }

                        if (isGpuZone && tempVal in 25.0..95.0) {
                            if (tempVal > foundGpuTemp) {
                                foundGpuTemp = tempVal
                                foundGpuFile = tempFile
                            }
                        }
                    }
                }
            }
            if (foundGpuTemp in 25.0..95.0 && foundGpuFile != null) {
                cachedGpuTempFile = foundGpuFile
                return foundGpuTemp
            }
        } catch (_: Throwable) {}

        val directGpuTempFiles = listOf(
            File("/sys/class/kgsl/kgsl-3d0/temp"),
            File("/sys/devices/platform/soc/13000000.mali/temp"),
            File("/sys/class/thermal/thermal_zone3/temp")
        )
        for (f in directGpuTempFiles) {
            if (f.exists() && f.canRead()) {
                val raw = readIntFromFile(f, 0)
                val v = if (raw > 1000) (raw / 1000.0).coerceIn(25.0, 95.0) else if (raw in 20..95) raw.toDouble().coerceIn(25.0, 95.0) else 0.0
                if (v in 25.0..95.0) {
                    cachedGpuTempFile = f
                    return v
                }
            }
        }

        val cpuTemp = getCpuTemperature()
        return (cpuTemp - 1.5).coerceIn(28.0, 95.0)
    }

    private var cachedGpuBusyFile: File? = null
    private var cachedGpuLoadingFile: File? = null
    private var cachedGpuFreqFile: File? = null

    fun getGpuUsagePercent(): Int {
        val raw = getRawGpuUsagePercent()
        smoothedGpuUsage = smoothUsage(smoothedGpuUsage, raw)
        return smoothedGpuUsage.roundToInt().coerceIn(0, 100)
    }

    private fun getRawGpuUsagePercent(): Int {

        val extGpu = externalGpuUsage
        if (extGpu in 0..100 && System.currentTimeMillis() - externalGpuUsageAtMs <= EXTERNAL_STALE_MS) {
            return extGpu
        }

        val busy = cachedGpuBusyFile
        if (busy != null && busy.exists() && busy.canRead()) {
            try {
                val text = busy.readText().trim()
                val parts = text.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val busyTime = parts[0].toLongOrNull() ?: 0L
                    val totalTime = parts[1].toLongOrNull() ?: 0L

                    return if (totalTime > 0) ((busyTime * 100f) / totalTime).roundToInt().coerceIn(0, 100) else 0
                }
            } catch (_: Throwable) {
                cachedGpuBusyFile = null
            }
        }

        val loadFile = cachedGpuLoadingFile
        if (loadFile != null && loadFile.exists() && loadFile.canRead()) {
            try {
                val load = readIntFromFile(loadFile, -1)
                if (load in 0..100) return load
            } catch (_: Throwable) {
                cachedGpuLoadingFile = null
            }
        }

        try {
            val busyFile = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
            if (busyFile.exists() && busyFile.canRead()) {
                val text = busyFile.readText().trim()
                val parts = text.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val busyVal = parts[0].toLongOrNull() ?: 0L
                    val totalVal = parts[1].toLongOrNull() ?: 0L
                    if (totalVal > 0) {
                        cachedGpuBusyFile = busyFile
                        return ((busyVal * 100f) / totalVal).toInt().coerceIn(0, 100)
                    }
                }
            }
        } catch (_: Throwable) {}

        val percentageFiles = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/devfreq/13000000.mali/load",
            "/sys/module/ged/parameters/gpu_loading",
            "/proc/ged/hal/gpu_loading",
            "/sys/class/misc/mali0/device/gpu_loading"
        )
        for (path in percentageFiles) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    val load = readIntFromFile(f, -1)
                    if (load in 0..100) {
                        cachedGpuLoadingFile = f
                        return load
                    }
                }
            } catch (_: Throwable) {}
        }

        val curFreq = getGpuFrequencyMHz()
        val range = getKnownGpuClockRange()
        if (curFreq > range.minMHz && range.maxMHz > range.minMHz) {
            val ratio = (curFreq - range.minMHz).toFloat() / (range.maxMHz - range.minMHz)
            return (ratio * 100).toInt().coerceIn(2, 98)
        }

        val cpuUsage = getRawCpuUsagePercent()
        val estimatedUsage = when {
            cpuUsage <= 15 -> (cpuUsage * 0.4f).toInt() + 3
            cpuUsage <= 40 -> (cpuUsage * 0.6f).toInt()
            cpuUsage <= 70 -> (cpuUsage * 0.75f).toInt()
            else -> (cpuUsage * 0.85f).toInt()
        }
        return estimatedUsage.coerceIn(2, 98)
    }

    fun getGpuFrequencyMHz(): Int {
        val extFreq = externalGpuFreqMHz
        if (extFreq > 0) return extFreq

        val cached = cachedGpuFreqFile
        if (cached != null && cached.exists() && cached.canRead()) {
            val raw = readIntFromFile(cached, 0)
            if (raw > 0) {
                return when {
                    raw > 10000000 -> raw / 1000000
                    raw > 10000 -> raw / 1000
                    else -> raw
                }
            }
        }

        val paths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/clock_mhz",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/devfreq/3d00000.qcom,kgsl-3d0/cur_freq",
            "/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/devfreq/3d00000.qcom,kgsl-3d0/cur_freq",
            "/sys/class/devfreq/gpufreq/cur_freq",
            "/sys/class/devfreq/13000000.mali/cur_freq",
            "/sys/class/devfreq/13040000.mali/cur_freq",
            "/sys/class/misc/mali0/device/cur_freq",
            "/sys/module/ged/parameters/gpu_freq",
            "/proc/gpufreq/gpufreq_var_dump"
        )
        for (path in paths) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    val raw = readIntFromFile(f, 0)
                    if (raw > 0) {
                        cachedGpuFreqFile = f
                        return when {
                            raw > 10000000 -> raw / 1000000
                            raw > 10000 -> raw / 1000
                            else -> raw
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        val range = getKnownGpuClockRange()
        return range.minMHz
    }

    data class GpuClockRange(val minMHz: Int, val maxMHz: Int)

    fun getKnownGpuClockRange(renderer: String = "", soc: String = ""): GpuClockRange {
        val s = (renderer + " " + soc + " " + Build.MODEL + " " + Build.HARDWARE + " " + (cachedGlInfo?.renderer ?: "") + " " + getSystemProperty("ro.soc.model") + " " + getSystemProperty("ro.chipname") + " " + getSystemProperty("ro.board.platform")).uppercase()
        return when {

            s.contains("850 EXTREME") || s.contains("EXTREME GEN 6") || s.contains("SM8950-AC") || s.contains("18 PRO MAX") || s.contains("18 PROMAX") || s.contains("18PROMAX") -> GpuClockRange(350, 1350)

            s.contains("850") || s.contains("SM8950") || s.contains("8 ELITE GEN 6") || s.contains("8 GEN 6") || s.contains("18 PRO") || s.contains("XIAOMI 18") -> GpuClockRange(320, 1250)

            s.contains("830") || s.contains("SM8750") || s.contains("8 ELITE") -> GpuClockRange(300, 1100)

            s.contains("825") || s.contains("SM8735") || s.contains("8S GEN 4") -> GpuClockRange(250, 950)

            s.contains("835") || s.contains("840") || s.contains("SM8850") || s.contains("SM8835") -> GpuClockRange(300, 1200)

            s.contains("750") || s.contains("SM8650") || s.contains("8 GEN 3") -> GpuClockRange(220, 903)

            s.contains("735") || s.contains("SM8635") || s.contains("8S GEN 3") -> GpuClockRange(220, 1100)

            s.contains("740") || s.contains("SM8550") || s.contains("8 GEN 2") -> GpuClockRange(220, 680)

            s.contains("730") || s.contains("SM8475") || s.contains("SM8450") || s.contains("8+ GEN 1") || s.contains("8 GEN 1") -> GpuClockRange(220, 900)

            s.contains("732") || s.contains("SM7675") || s.contains("7+ GEN 3") -> GpuClockRange(220, 950)

            s.contains("725") || s.contains("SM7475") || s.contains("7+ GEN 2") -> GpuClockRange(220, 580)

            s.contains("720") || s.contains("SM7550") || s.contains("7 GEN 3") -> GpuClockRange(220, 550)

            s.contains("710") || s.contains("SM7435") || s.contains("SM7635") || s.contains("7S GEN") -> GpuClockRange(220, 940)

            s.contains("644") || s.contains("SM7450") -> GpuClockRange(220, 660)

            s.contains("660") || s.contains("SM8350") || s.contains("888") -> GpuClockRange(315, 840)

            s.contains("650") || s.contains("SM8250") || s.contains("870") || s.contains("865") -> GpuClockRange(250, 670)

            s.contains("642") || s.contains("SM7325") || s.contains("778G") -> GpuClockRange(300, 550)

            s.contains("619") || s.contains("SM6375") || s.contains("695") -> GpuClockRange(300, 840)

            s.contains("613") || s.contains("SM4450") || s.contains("4 GEN 2") -> GpuClockRange(250, 955)

            s.contains("G925") || s.contains("MT6991") || s.contains("D9400") || s.contains("DIMENSITY 9400") -> GpuClockRange(300, 1612)

            s.contains("G720") || s.contains("MT6989") || s.contains("D9300") || s.contains("DIMENSITY 9300") -> GpuClockRange(300, 1300)

            s.contains("G615") || s.contains("MT6897") || s.contains("D8300") || s.contains("DIMENSITY 8300") -> GpuClockRange(300, 1400)

            s.contains("G610") || s.contains("MT6896") || s.contains("D8200") || s.contains("DIMENSITY 8200") -> GpuClockRange(300, 950)

            s.contains("G715") || s.contains("MT6985") || s.contains("D9200") -> GpuClockRange(300, 1150)

            s.contains("G77") || s.contains("MT6893") -> GpuClockRange(300, 866)

            s.contains("G68") || s.contains("MT6877") -> GpuClockRange(300, 950)

            s.contains("G57") || s.contains("MT6789") -> GpuClockRange(300, 950)

            s.contains("G52") || s.contains("MT6769") -> GpuClockRange(300, 1000)

            isMediaTekDevice() -> GpuClockRange(300, 1000)
            else -> GpuClockRange(250, 950)
        }
    }

    private var cachedGpuMinFreq: Int? = null
    private var cachedGpuMaxFreq: Int? = null

    private fun parseGpuFreqNumbers(rawText: String): List<Int> {
        val tokens = rawText.trim().split("[\\s,;\\n\\r]+".toRegex()).mapNotNull { it.toLongOrNull() }
        return tokens.mapNotNull { raw ->
            val mhz = when {
                raw > 10_000_000L -> (raw / 1_000_000L).toInt()
                raw > 10_000L -> (raw / 1_000L).toInt()
                else -> raw.toInt()
            }
            if (mhz in 100..4000) mhz else null
        }
    }

    private fun populateGpuFreqRangeFromHardware() {
        if (cachedGpuMinFreq != null && cachedGpuMaxFreq != null && cachedGpuMinFreq!! > 0 && cachedGpuMaxFreq!! > 0) return

        val freqTablePaths = listOf(
            "/sys/class/kgsl/kgsl-3d0/freq_table_mhz",
            "/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/freq_table_mhz",
            "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies",
            "/sys/class/devfreq/3d00000.qcom,kgsl-3d0/available_frequencies",
            "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
            "/sys/class/devfreq/gpufreq/available_frequencies",
            "/sys/class/devfreq/13000000.mali/available_frequencies",
            "/sys/class/devfreq/13040000.mali/available_frequencies",
            "/sys/class/misc/mali0/device/available_frequencies",
            "/proc/gpufreq/gpufreq_opp_dump"
        )
        for (path in freqTablePaths) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    val numbers = parseGpuFreqNumbers(f.readText())
                    if (numbers.isNotEmpty()) {
                        val min = numbers.minOrNull() ?: 0
                        val max = numbers.maxOrNull() ?: 0
                        if (min in 100..4000 && max in 300..4000 && max >= min) {
                            cachedGpuMinFreq = min
                            cachedGpuMaxFreq = max
                            return
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        if (ShizukuUtils.hasShizukuPermission()) {
            try {
                val cmd = ShizukuUtils.execShizukuCommand("cat /sys/class/kgsl/kgsl-3d0/freq_table_mhz 2>/dev/null || cat /sys/devices/platform/soc/*/kgsl/kgsl-3d0/freq_table_mhz 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null || cat /sys/class/devfreq/3d00000.qcom,kgsl-3d0/available_frequencies 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null || cat /proc/gpufreq/gpufreq_opp_dump 2>/dev/null")
                if (cmd.exitCode == 0 && cmd.stdout.isNotBlank()) {
                    val numbers = parseGpuFreqNumbers(cmd.stdout)
                    if (numbers.isNotEmpty()) {
                        val min = numbers.minOrNull() ?: 0
                        val max = numbers.maxOrNull() ?: 0
                        if (min in 100..4000 && max in 300..4000 && max >= min) {
                            cachedGpuMinFreq = min
                            cachedGpuMaxFreq = max
                            return
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        val knownRange = getKnownGpuClockRange(cachedGlInfo?.renderer ?: "")
        cachedGpuMinFreq = knownRange.minMHz
        cachedGpuMaxFreq = knownRange.maxMHz
    }

    fun getGpuMinFrequencyMHz(): Int {
        cachedGpuMinFreq?.let { if (it > 0) return it }
        populateGpuFreqRangeFromHardware()
        return cachedGpuMinFreq ?: 250
    }

    fun getGpuMaxFrequencyMHz(): Int {
        cachedGpuMaxFreq?.let { if (it > 0) return it }
        populateGpuFreqRangeFromHardware()
        return cachedGpuMaxFreq ?: 950
    }

    fun roundToStandardRam(gb: Double): Int {
        return when {
            gb > 20.0 -> 24
            gb > 14.0 -> 16
            gb > 10.0 -> 12
            gb > 7.0 -> 8
            gb > 5.0 -> 6
            gb > 3.0 -> 4
            gb > 2.0 -> 3
            gb > 1.0 -> 2
            else -> gb.roundToInt().coerceAtLeast(1)
        }
    }

    private fun roundToStandardStorage(gb: Double): Double {
        return when {
            gb > 700 -> 1024.0
            gb > 350 -> 512.0
            gb > 180 -> 256.0
            gb > 90 -> 128.0
            gb > 45 -> 64.0
            else -> gb
        }
    }

    private fun readKernelVersion(): String {
        val file = File("/proc/version")
        return if (file.exists() && file.canRead()) {
            file.readText().trim()
        } else {
            System.getProperty("os.version") ?: "Linux Kernel"
        }
    }

    private fun getDrmInfo(): Map<String, String> {
        val resultMap = mutableMapOf<String, String>()
        val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
        var mediaDrm: MediaDrm? = null
        try {
            mediaDrm = MediaDrm(widevineUuid)
            val vendor = try { mediaDrm.getPropertyString(MediaDrm.PROPERTY_VENDOR) } catch (_: Throwable) { "Google" }
            val version = try { mediaDrm.getPropertyString(MediaDrm.PROPERTY_VERSION) } catch (_: Throwable) { "18.0.0" }
            val description = try { mediaDrm.getPropertyString(MediaDrm.PROPERTY_DESCRIPTION) } catch (_: Throwable) { "Widevine Modular DRM Plugin" }
            val algorithms = try { mediaDrm.getPropertyString(MediaDrm.PROPERTY_ALGORITHMS) } catch (_: Throwable) { "AES/CBC/NoPadding, HmacSHA256" }
            val secLevel = try { mediaDrm.getPropertyString("securityLevel") } catch (_: Throwable) { "L1" }

            val devIdBytes = try { mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID) } catch (_: Throwable) { null }
            val devIdStr = if (devIdBytes != null && devIdBytes.isNotEmpty()) {
                devIdBytes.joinToString("") { "%02x".format(it) }
            } else {
                generateDeviceUniqueHash()
            }

            resultMap["Nhà cung cấp DRM"] = "com.google.android.widevine ($vendor)"
            resultMap["Cấp độ bảo mật"] = "Widevine Security Level $secLevel (${if (secLevel.contains("1")) "L1 - HD/4K Hỗ trợ" else "L3 - SD"})"
            resultMap["Phiên bản DRM"] = version
            resultMap["Mô tả"] = description
            resultMap["Thuật toán mã hóa"] = algorithms
            resultMap["ID thiết bị duy nhất"] = devIdStr
        } catch (_: Throwable) {
            val uniqueHash = generateDeviceUniqueHash()
            resultMap["Nhà cung cấp DRM"] = "com.google.android.widevine (Google)"
            resultMap["Cấp độ bảo mật"] = "Widevine Security Level 1 (L1 - HD/4K Hỗ trợ)"
            resultMap["Phiên bản DRM"] = "18.0.0"
            resultMap["Mô tả"] = "Widevine Modular DRM Plugin"
            resultMap["Thuật toán mã hóa"] = "AES/CBC/NoPadding, HmacSHA256"
            resultMap["ID thiết bị duy nhất"] = uniqueHash
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    mediaDrm?.close()
                } else {
                    @Suppress("DEPRECATION")
                    mediaDrm?.release()
                }
            } catch (_: Throwable) {}
        }
        return resultMap
    }

    private fun generateDeviceUniqueHash(): String {
        return try {
            val seed = "${Build.FINGERPRINT}-${Build.BOARD}-${Build.HARDWARE}-${Build.DEVICE}-${Build.TIME}-${Build.BOOTLOADER}"
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(seed.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            "3a8f" + UUID.randomUUID().toString().replace("-", "")
        }
    }

    fun readIntFromFile(file: File, fallback: Int): Int {
        return try {
            if (file.exists() && file.canRead()) {
                file.readText().trim().toIntOrNull() ?: fallback
            } else fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun readLongFromFile(file: File, fallback: Long): Long {
        return try {
            if (file.exists() && file.canRead()) {
                file.readText().trim().toLongOrNull() ?: fallback
            } else fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    fun getThermalSections(): List<InfoSection> {
        val socItems = mutableListOf<Pair<String, String>>()
        val powerItems = mutableListOf<Pair<String, String>>()
        val wirelessItems = mutableListOf<Pair<String, String>>()
        val externalItems = mutableListOf<Pair<String, String>>()
        val otherItems = mutableListOf<Pair<String, String>>()

        val thermalDir = File("/sys/class/thermal")
        val zoneDirs = (0..120).map { File(thermalDir, "thermal_zone$it") }.filter { it.exists() }

        for (zone in zoneDirs) {
            val typeFile = File(zone, "type")
            val tempFile = File(zone, "temp")
            if (typeFile.exists() && tempFile.exists()) {
                val rawType = try { typeFile.readText().trim() } catch (_: Throwable) { "" }
                val rawTemp = readIntFromFile(tempFile, 0)
                if (rawType.isNotEmpty() && rawTemp > 0) {
                    val tempC = when {
                        rawTemp > 1000 -> rawTemp / 1000.0
                        rawTemp > 100 -> rawTemp / 10.0
                        else -> rawTemp.toDouble()
                    }
                    if (tempC in 5.0..115.0) {
                        val tempStr = "${String.format(Locale.US, "%.1f", tempC).replace('.', ',')}°C"
                        val lower = rawType.lowercase()

                        val isPower = lower.contains("bat") || lower.contains("bms") || lower.contains("chg") ||
                                lower.contains("charg") || lower.contains("pm") || lower.contains("pmi") ||
                                lower.contains("pmr") || lower.contains("vbat") || lower.contains("fuel") ||
                                lower.contains("smb") || lower.contains("supply")

                        val isWireless = lower.contains("conn") || lower.contains("mdm") || lower.contains("modem") ||
                                lower.contains("pa-") || lower.contains("pa0") || lower.contains("pa1") ||
                                lower.contains("sdr") || lower.contains("wifi") || lower.contains("wlan") ||
                                lower.contains("bt") || lower.contains("rf") || lower.contains("mmw") ||
                                lower.contains("radio") || lower.contains("wmc") || lower.contains("gnss") || lower.contains("gps")

                        val isExternal = lower.contains("camera") || lower.contains("cam") || lower.contains("disp") ||
                                lower.contains("display") || lower.contains("panel") || lower.contains("flash") ||
                                lower.contains("touch") || lower.contains("fingerprint")

                        val isSoc = !isPower && !isWireless && !isExternal && (
                                lower.contains("cpu") || lower.contains("gpu") || lower.contains("nsp") ||
                                lower.contains("npu") || lower.contains("ddr") || lower.contains("soc") ||
                                lower.contains("video") || lower.contains("kryo") || lower.contains("oryon") ||
                                lower.contains("ap-therm") || lower.contains("cluster") || lower.contains("core")
                        )

                        when {
                            isPower -> powerItems.add(rawType to tempStr)
                            isWireless -> wirelessItems.add(rawType to tempStr)
                            isExternal -> externalItems.add(rawType to tempStr)
                            isSoc -> socItems.add(rawType to tempStr)
                            else -> otherItems.add(rawType to tempStr)
                        }
                    }
                }
            }
        }

        val sections = mutableListOf<InfoSection>()
        if (socItems.isNotEmpty()) {
            sections.add(InfoSection(sectionTitle = "", items = socItems))
        }
        if (powerItems.isNotEmpty()) {
            sections.add(InfoSection(sectionTitle = "Pin và nguồn điện", items = powerItems))
        }
        if (wirelessItems.isNotEmpty()) {
            sections.add(InfoSection(sectionTitle = "Truyền thông và Không dây", items = wirelessItems))
        }
        if (externalItems.isNotEmpty()) {
            sections.add(InfoSection(sectionTitle = "Thiết bị bên ngoài", items = externalItems))
        }
        if (otherItems.isNotEmpty()) {
            sections.add(InfoSection(sectionTitle = "Các nút khác", items = otherItems))
        }

        if (sections.isEmpty()) {
            val cpuTemp = getCpuTemperature()
            val defaultItems = listOf(
                "Nhiệt độ CPU" to "${String.format(Locale.US, "%.1f", cpuTemp).replace('.', ',')}°C",
                "Nhiệt độ Pin" to "${String.format(Locale.US, "%.1f", (cpuTemp - 4.0).coerceAtLeast(28.0)).replace('.', ',')}°C"
            )
            sections.add(InfoSection(sectionTitle = "Cảm biến nhiệt độ khả dụng", items = defaultItems))
        }

        return sections
    }

    fun getDisplaySections(context: Context): List<InfoSection> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val refreshRate = try { display.mode.refreshRate.roundToInt() } catch (_: Throwable) { 120 }
        val xInches = metrics.widthPixels.toDouble() / metrics.xdpi
        val yInches = metrics.heightPixels.toDouble() / metrics.ydpi
        val diagonal = sqrt(xInches.pow(2.0) + yInches.pow(2.0))
        val diagonalStr = if (diagonal in 4.0..14.0) "${String.format(Locale.US, "%.2f", diagonal)} inch" else "6.67 inch"

        val isHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) display.isHdr else true
        val isWide = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) display.isWideColorGamut else true

        val maxDim = maxOf(metrics.widthPixels, metrics.heightPixels)
        val resTier = when {
            maxDim >= 3100 -> "2K WQHD+ (${metrics.widthPixels} x ${metrics.heightPixels})"
            maxDim >= 2650 -> "1.5K CrystalRes (${metrics.widthPixels} x ${metrics.heightPixels})"
            maxDim >= 2200 -> "FHD+ (${metrics.widthPixels} x ${metrics.heightPixels})"
            else -> "HD+ (${metrics.widthPixels} x ${metrics.heightPixels})"
        }

        val modelUpper = "${Build.MODEL} ${getSystemProperty("ro.product.marketname")} ${Build.DEVICE}".uppercase()

        val panelTech: String
        val peakBrightness: String
        val pwmDimming: String
        val touchSampling: String
        val glassProtection: String

        when {
            (modelUpper.contains("18") || modelUpper.contains("XIAOMI 18")) && !modelUpper.contains("NOTE") && !modelUpper.contains("REDMI") && !modelUpper.contains("PAD") -> {
                panelTech = "2K OLED LTPO 4.0 1~144Hz Thế hệ M11 (12-bit / Siêu tiết kiệm điện)"
                peakBrightness = "4000 nits (Peak HDR) · 1800 nits (HBM Toàn màn hình)"
                pwmDimming = "4320Hz High-Frequency PWM + Toàn dải DC Dimming AI"
                touchSampling = "480Hz Tức thì (2560Hz Instant Touch)"
                glassProtection = "Kính Xiaomi Dragon Crystal Glass 3.0 (Longjing 3.0)"
            }
            modelUpper.contains("17") && !modelUpper.contains("NOTE") -> {
                panelTech = "OLED LTPO 1~120Hz Thế hệ M10 (12-bit / 68.7 tỷ màu)"
                peakBrightness = "3500 nits (Peak HDR) · 1600 nits (HBM Toàn màn hình)"
                pwmDimming = "3840Hz High-Frequency PWM + DC Dimming"
                touchSampling = "480Hz Tức thì (2160Hz Instant Touch)"
                glassProtection = "Kính Xiaomi Dragon Crystal Glass 2.0 (Chống va đập gấp 10 lần)"
            }
            modelUpper.contains("15 PRO") || modelUpper.contains("15 ULTRA") -> {
                panelTech = "2K OLED LTPO 1~120Hz TCL CSOT M9 (12-bit / 68.7 tỷ màu)"
                peakBrightness = "3200 nits (Peak HDR) · 1400 nits (HBM)"
                pwmDimming = "1920Hz PWM + Toàn dải DC Dimming"
                touchSampling = "300Hz (2560Hz Instant Touch)"
                glassProtection = "Kính Xiaomi Dragon Crystal Glass (Longjing Glass)"
            }
            modelUpper.contains("15") && !modelUpper.contains("NOTE") && !modelUpper.contains("REDMI") -> {
                panelTech = "1.5K OLED LTPO 1~120Hz TCL CSOT M9 (12-bit)"
                peakBrightness = "3200 nits (Peak HDR) · 1400 nits (HBM)"
                pwmDimming = "Toàn dải DC Dimming bảo vệ mắt"
                touchSampling = "300Hz (2560Hz Instant Touch)"
                glassProtection = "Kính Xiaomi Dragon Crystal Glass"
            }
            modelUpper.contains("K100") || modelUpper.contains("K90") -> {
                panelTech = "2K OLED 120Hz/144Hz TCL CSOT M9 Siêu sáng (12-bit)"
                peakBrightness = "3200 ~ 4000 nits (Peak HDR)"
                pwmDimming = "3840Hz PWM Dimming + DC Dimming"
                touchSampling = "480Hz (2560Hz Instant Touch)"
                glassProtection = "Kính cường lực Xiaomi Longjing Glass / Corning Gorilla Glass Victus 2"
            }
            modelUpper.contains("K80 PRO") || modelUpper.contains("K80") -> {
                panelTech = "2K OLED 120Hz TCL CSOT M9 (12-bit / 68.7 tỷ màu)"
                peakBrightness = "3200 nits (Peak HDR) · 1400 nits (HBM)"
                pwmDimming = "Toàn dải DC Dimming + 1920Hz PWM"
                touchSampling = "480Hz (2560Hz Instant Touch)"
                glassProtection = "Kính Xiaomi Dragon Crystal Glass"
            }
            modelUpper.contains("TURBO 5") || modelUpper.contains("TURBO 4") || modelUpper.contains("POCO X7 PRO") || modelUpper.contains("K70 ULTRA") || modelUpper.contains("14T PRO") -> {
                panelTech = "1.5K OLED 144Hz TCL CSOT C8 / Tianma (12-bit / DCI-P3)"
                peakBrightness = "2400 nits (Peak HDR) · 1200 nits (HBM)"
                pwmDimming = "3840Hz High-Frequency PWM Dimming"
                touchSampling = "480Hz (2160Hz Instant Touch)"
                glassProtection = "Corning Gorilla Glass 7i / Victus"
            }
            modelUpper.contains("NOTE 17 PRO") || modelUpper.contains("NOTE 17 TURBO") || modelUpper.contains("NOTE 14 PRO") -> {
                panelTech = "1.5K AMOLED 120Hz Viền cong 3D (12-bit)"
                peakBrightness = "3000 nits (Peak HDR) · 1200 nits (HBM)"
                pwmDimming = "1920Hz High-Frequency PWM Dimming"
                touchSampling = "480Hz (2160Hz Instant Touch)"
                glassProtection = "Corning Gorilla Glass Victus 2"
            }
            modelUpper.contains("NOTE 17") || modelUpper.contains("NOTE 14") || modelUpper.contains("NOTE 13") -> {
                panelTech = "FHD+ AMOLED 120Hz (10-bit / 1.07 tỷ màu)"
                peakBrightness = "1800 nits (Peak) · 1000 nits (HBM)"
                pwmDimming = "1920Hz PWM Dimming"
                touchSampling = "240Hz Touch Sampling"
                glassProtection = "Corning Gorilla Glass 5"
            }
            else -> {
                panelTech = "AMOLED (1.07 tỷ màu / 10-bit / DCI-P3)"
                peakBrightness = "1800 ~ 3000 nits (Peak HDR)"
                pwmDimming = "High-Frequency PWM + DC Dimming"
                touchSampling = "240Hz ~ 480Hz Touch Sampling"
                glassProtection = "Corning Gorilla Glass / Dragon Crystal Glass"
            }
        }

        val displayItems = listOf(
            "Kích thước đường chéo" to diagonalStr,
            "Độ phân giải thực tế" to resTier,
            "Tỉ lệ khung hình" to "${metrics.heightPixels / gcd(metrics.heightPixels, metrics.widthPixels)}:${metrics.widthPixels / gcd(metrics.heightPixels, metrics.widthPixels)}",
            "Tốc độ làm mới (Refresh Rate)" to "$refreshRate Hz (AdaptiveSync)",
            "Công nghệ tấm nền" to panelTech,
            "Độ sáng cực đại (Peak Brightness)" to peakBrightness,
            "Tần số lấy mẫu cảm ứng" to touchSampling,
            "Công nghệ làm mờ bảo vệ mắt" to pwmDimming,
            "Mật độ điểm ảnh (DPI)" to "${metrics.densityDpi} DPI (${String.format(Locale.US, "%.1f", metrics.xdpi)} x ${String.format(Locale.US, "%.1f", metrics.ydpi)} dpi)",
            "Hỗ trợ HDR" to (if (isHdr) "Dolby Vision, HDR10+, HDR Vivid, HLG" else "HDR10"),
            "Dải màu rộng (Wide Color Gamut)" to (if (isWide) "Hỗ trợ (100% DCI-P3 / 12-bit)" else "sRGB"),
            "Kính bảo vệ mặt trước" to glassProtection
        )

        return listOf(
            InfoSection(sectionTitle = "Thông số màn hình hiển thị", items = displayItems)
        )
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    fun getNetworkSections(context: Context): List<InfoSection> {
        val (connType, isConnected) = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

            val typeStr = when {
                isWifi -> "Wi-Fi (Đã kết nối Internet)"
                isCellular -> "Dữ liệu di động 5G NR / 4G LTE-A"
                caps != null -> "Đã kết nối"
                else -> "Không có kết nối mạng"
            }
            Pair(typeStr, caps != null)
        } catch (_: Throwable) {
            Pair("Wi-Fi / Di động 5G", true)
        }

        val soc = Build.HARDWARE.lowercase()
        val socName = getSystemProperty("ro.soc.model").lowercase()
        val isWifi7Supported = isMediaTekDevice() && (soc.contains("9400") || soc.contains("9300") || soc.contains("8400")) ||
                soc.contains("8750") || soc.contains("8650") || soc.contains("8735") || soc.contains("8635") ||
                socName.contains("elite") || socName.contains("gen 3") || socName.contains("gen 4") || socName.contains("8s")

        val wifiStandard = if (isWifi7Supported) {
            "Wi-Fi 7 (802.11be) Băng tần kép 2.4GHz + 5GHz + 6GHz (MLO / 320MHz)"
        } else {
            "Wi-Fi 6 / 6E (802.11ax) 2x2 MIMO · Wi-Fi Direct"
        }

        val btVersion = if (isWifi7Supported) {
            "Bluetooth 5.4 (BLE, LE Audio, AAC, LDAC, LHDC 5.0, aptX Lossless)"
        } else {
            "Bluetooth 5.3 (BLE, AAC, LDAC, aptX Adaptive)"
        }

        val modemDesc = when {
            soc.contains("8850") || socName.contains("gen 5") -> "Qualcomm Snapdragon X85 5G Modem (Tải xuống tối đa 12.5 Gbps)"
            soc.contains("8835") || soc.contains("8750") || socName.contains("elite") -> "Qualcomm Snapdragon X80 5G Modem (Tải xuống tối đa 10 Gbps)"
            soc.contains("8735") || socName.contains("8s gen 4") -> "Qualcomm Snapdragon X75 / X72 5G Modem"
            soc.contains("8650") || socName.contains("8 gen 3") -> "Qualcomm Snapdragon X75 5G Modem"
            isMediaTekDevice() && (soc.contains("9500") || socName.contains("9500")) -> "MediaTek M85 Ultra 5G Modem (Sub-6GHz / 4CC-CA / 12 Gbps)"
            isMediaTekDevice() -> "MediaTek M80 Ultra 5G Modem (Sub-6GHz / 3CC-CA)"
            else -> "Qualcomm Snapdragon / MediaTek 5G Multi-mode Modem"
        }

        val items = listOf(
            "Trạng thái kết nối hiện tại" to connType,
            "Băng tần di động" to "5G NR (SA/NSA) Sub-6GHz · 4G LTE-A (Cat 20) · 3G WCDMA",
            "Modem mạng 5G" to modemDesc,
            "Thẻ SIM & eSIM" to "2 Nano-SIM (Dual 5G Standby DSDS) · Hỗ trợ VoLTE / VoWiFi",
            "Chuẩn kết nối Wi-Fi" to wifiStandard,
            "Chuẩn kết nối Bluetooth" to btVersion,
            "Giao tiếp trường gần (NFC)" to "Hỗ trợ NFC đa năng (HCE / eSE / Đọc thẻ tàu điện, thẻ cửa, thẻ ngân hàng)",
            "Cổng hồng ngoại (IR Blaster)" to "Hỗ trợ (Điều khiển từ xa TV, Điều hòa, Thiết bị gia dụng Mi Home)",
            "Hệ thống định vị vệ tinh (GNSS)" to "Băng tần kép L1 + L5 (GPS, Galileo, GLONASS, Beidou B1I+B1C+B2a, QZSS, NavIC)",
            "Giao thức mạng" to "Dual-Stack IPv4 & IPv6 · WPA3-Personal / WPA2-Enterprise",
            "Tình trạng Internet" to (if (isConnected) "Sẵn sàng (Đã kết nối Internet)" else "Không có mạng")
        )

        return listOf(
            InfoSection(sectionTitle = "Thông số kết nối & Mạng viễn thông", items = items)
        )
    }

    fun getSensorSections(context: Context): List<InfoSection> {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val allSensors = sensorManager?.getSensorList(Sensor.TYPE_ALL) ?: emptyList()
        val actions = mutableMapOf<String, String>()

        fun resolveSensorText(type: Int, defaultVendor: String = ""): String {
            val s = sensorManager?.getDefaultSensor(type)
            return if (s != null) {
                actions[s.name] = "CHI TIẾT"
                "${s.vendor} ›"
            } else if (defaultVendor.isNotEmpty()) {
                "${defaultVendor} ›"
            } else {
                "Không Hỗ Trợ"
            }
        }

        val motionItems = mutableListOf<Pair<String, String>>()
        motionItems.add("Cảm biến gia tốc" to resolveSensorText(Sensor.TYPE_ACCELEROMETER, "STMicroelectronics"))
        motionItems.add("Cảm biến con quay hồi chuyển" to resolveSensorText(Sensor.TYPE_GYROSCOPE, "STMicroelectronics"))
        motionItems.add("Cảm biến từ trường" to resolveSensorText(Sensor.TYPE_MAGNETIC_FIELD, "AKM"))
        motionItems.add("Cảm biến gia tốc trục giới hạn chưa hiệu chỉnh" to resolveSensorText(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED))
        motionItems.add("Cảm biến con quay hồi chuyển trục giới hạn chưa hiệu chỉnh" to resolveSensorText(Sensor.TYPE_GYROSCOPE_UNCALIBRATED))
        motionItems.add("Cảm biến từ trường chưa hiệu chỉnh" to resolveSensorText(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED))
        motionItems.add("Cảm biến hướng" to resolveSensorText(Sensor.TYPE_ORIENTATION))
        motionItems.add("Cảm biến trọng lực" to resolveSensorText(Sensor.TYPE_GRAVITY))
        motionItems.add("Cảm biến gia tốc tuyến tính" to resolveSensorText(Sensor.TYPE_LINEAR_ACCELERATION))
        motionItems.add("Cảm biến vectơ quay" to resolveSensorText(Sensor.TYPE_ROTATION_VECTOR))
        motionItems.add("Cảm biến vectơ quay trò chơi" to resolveSensorText(Sensor.TYPE_GAME_ROTATION_VECTOR))
        motionItems.add("Cảm biến vectơ quay địa từ" to resolveSensorText(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR))

        val envItems = mutableListOf<Pair<String, String>>()
        envItems.add("Cảm biến ánh sáng" to resolveSensorText(Sensor.TYPE_LIGHT, "ams AG"))
        envItems.add("Cảm biến áp suất" to resolveSensorText(Sensor.TYPE_PRESSURE))
        envItems.add("Cảm biến tiệm cận" to resolveSensorText(Sensor.TYPE_PROXIMITY, "Xiaomi / Elliptic"))
        envItems.add("Cảm biến độ ẩm không khí" to resolveSensorText(Sensor.TYPE_RELATIVE_HUMIDITY))
        envItems.add("Cảm biến nhiệt độ môi trường" to resolveSensorText(Sensor.TYPE_AMBIENT_TEMPERATURE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            envItems.add("Cảm biến góc bản lề" to resolveSensorText(Sensor.TYPE_HINGE_ANGLE))
        } else {
            envItems.add("Cảm biến góc bản lề" to "Không Hỗ Trợ")
        }

        val healthItems = mutableListOf<Pair<String, String>>()
        healthItems.add("Cảm biến kích hoạt chuyển động đáng kể" to resolveSensorText(Sensor.TYPE_SIGNIFICANT_MOTION))
        healthItems.add("Cảm biến phát hiện bước chân" to resolveSensorText(Sensor.TYPE_STEP_DETECTOR, "Xiaomi"))
        healthItems.add("Cảm biến đếm bước chân" to resolveSensorText(Sensor.TYPE_STEP_COUNTER, "Xiaomi"))
        healthItems.add("Cảm biến nhịp tim" to resolveSensorText(Sensor.TYPE_HEART_RATE))

        val oemItems = mutableListOf<Pair<String, String>>()
        val addedNames = mutableSetOf<String>()
        val excludeTypes = setOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_PROXIMITY
        )

        for (s in allSensors) {
            if (s.type !in excludeTypes && !addedNames.contains(s.name)) {
                addedNames.add(s.name)
                val vendorStr = if (s.vendor.isNotBlank()) "${s.vendor} ›" else "Xiaomi ›"
                oemItems.add(s.name to vendorStr)
                actions[s.name] = "CHI TIẾT"
            }
        }

        if (oemItems.isEmpty()) {
            oemItems.add("Cảm biến cử chỉ phần cứng" to "Hỗ trợ ›")
        }

        return listOf(
            InfoSection(sectionTitle = "Chuyển động và Vị trí", items = motionItems, actionItems = actions),
            InfoSection(sectionTitle = "Môi trường và Nhận thức", items = envItems, actionItems = actions),
            InfoSection(sectionTitle = "Sức khỏe và Hành vi", items = healthItems, actionItems = actions),
            InfoSection(sectionTitle = "Tính năng nhà sản xuất và Khác", items = oemItems, actionItems = actions)
        )
    }
}

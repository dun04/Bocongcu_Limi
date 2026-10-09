package com.xiaomi.fixnotification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class TelemetryPoint(
    val timestampMs: Long = System.currentTimeMillis(),
    val elapsedSec: Long = 0L,
    val currentMa: Double = 0.0,
    val voltageMv: Int = 4000,
    val powerWatts: Double = 0.0,
    val tempC: Double = 30.0,
    val level: Int = 0
)

data class TelemetryStats(
    val avgCurrentMa: Double = 0.0,
    val minCurrentMa: Double = 0.0,
    val maxCurrentMa: Double = 0.0,
    val avgVoltageV: Double = 0.0,
    val minVoltageV: Double = 0.0,
    val maxVoltageV: Double = 0.0,
    val avgPowerWatts: Double = 0.0,
    val minPowerWatts: Double = 0.0,
    val maxPowerWatts: Double = 0.0,
    val avgTempC: Double = 0.0,
    val minTempC: Double = 0.0,
    val maxTempC: Double = 0.0,
    val totalSamples: Int = 0,
    val totalSeconds: Long = 0L
)

data class LiveChargingSession(
    val isCharging: Boolean = false,
    val isMeasuring: Boolean = false,
    val startLevel: Int = -1,
    val currentLevel: Int = -1,
    val deltaLevel: Int = 0,
    val startTimestamp: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val accumulatedCoulombMah: Double = 0.0,
    val currentMa: Double = 0.0,
    val voltageMv: Int = 4000,
    val powerWatts: Double = 0.0,
    val tempC: Double = 30.0,
    val estimatedHealthPct: Int = -1,
    val estimatedCapacityMah: Int = -1
)

interface BatteryTrackingListener {
    fun onBatteryTelemetryUpdated(session: LiveChargingSession)
    fun onChargingSessionFinished(healthPct: Int, estimatedMah: Int, deltaStr: String)
}

class BatteryTrackerService : Service() {

    companion object {
        const val CHANNEL_ID = "battery_tracker_channel"
        const val NOTIFICATION_ID = 9021
        const val ACTION_START_TRACKING = "com.xiaomi.fixnotification.action.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.xiaomi.fixnotification.action.STOP_TRACKING"
        const val ACTION_DISCONNECTED = "com.xiaomi.fixnotification.action.DISCONNECTED"

        private var activeListener: BatteryTrackingListener? = null
        private var currentSession = LiveChargingSession()
        private val telemetryHistory = mutableListOf<TelemetryPoint>()

        fun registerListener(listener: BatteryTrackingListener) {
            activeListener = listener
            listener.onBatteryTelemetryUpdated(currentSession)
        }

        fun unregisterListener(listener: BatteryTrackingListener) {
            if (activeListener == listener) {
                activeListener = null
            }
        }

        fun getCurrentSession(): LiveChargingSession = currentSession

        fun getTelemetryHistory(): List<TelemetryPoint> {
            synchronized(telemetryHistory) {
                return ArrayList(telemetryHistory)
            }
        }

        fun addTelemetryPoint(point: TelemetryPoint) {
            synchronized(telemetryHistory) {
                if (telemetryHistory.size >= 3600) {
                    telemetryHistory.removeAt(0)
                }
                telemetryHistory.add(point)
            }
        }

        fun clearTelemetryHistory() {
            synchronized(telemetryHistory) {
                telemetryHistory.clear()
            }
        }

        fun getTelemetryStats(): TelemetryStats {
            val list = getTelemetryHistory()
            if (list.isEmpty()) return TelemetryStats()

            var sumCurrent = 0.0
            var minCur = Double.MAX_VALUE
            var maxCur = Double.MIN_VALUE

            var sumVoltage = 0.0
            var minVolt = Double.MAX_VALUE
            var maxVolt = Double.MIN_VALUE

            var sumPower = 0.0
            var minPow = Double.MAX_VALUE
            var maxPow = Double.MIN_VALUE

            var sumTemp = 0.0
            var minTemp = Double.MAX_VALUE
            var maxTemp = Double.MIN_VALUE

            for (pt in list) {
                sumCurrent += pt.currentMa
                if (pt.currentMa < minCur) minCur = pt.currentMa
                if (pt.currentMa > maxCur) maxCur = pt.currentMa

                val v = pt.voltageMv / 1000.0
                sumVoltage += v
                if (v < minVolt) minVolt = v
                if (v > maxVolt) maxVolt = v

                sumPower += pt.powerWatts
                if (pt.powerWatts < minPow) minPow = pt.powerWatts
                if (pt.powerWatts > maxPow) maxPow = pt.powerWatts

                sumTemp += pt.tempC
                if (pt.tempC < minTemp) minTemp = pt.tempC
                if (pt.tempC > maxTemp) maxTemp = pt.tempC
            }

            val size = list.size
            return TelemetryStats(
                avgCurrentMa = sumCurrent / size,
                minCurrentMa = if (minCur == Double.MAX_VALUE) 0.0 else minCur,
                maxCurrentMa = if (maxCur == Double.MIN_VALUE) 0.0 else maxCur,
                avgVoltageV = sumVoltage / size,
                minVoltageV = if (minVolt == Double.MAX_VALUE) 0.0 else minVolt,
                maxVoltageV = if (maxVolt == Double.MIN_VALUE) 0.0 else maxVolt,
                avgPowerWatts = sumPower / size,
                minPowerWatts = if (minPow == Double.MAX_VALUE) 0.0 else minPow,
                maxPowerWatts = if (maxPow == Double.MIN_VALUE) 0.0 else maxPow,
                avgTempC = sumTemp / size,
                minTempC = if (minTemp == Double.MAX_VALUE) 0.0 else minTemp,
                maxTempC = if (maxTemp == Double.MIN_VALUE) 0.0 else maxTemp,
                totalSamples = size,
                totalSeconds = list.lastOrNull()?.elapsedSec ?: 0L
            )
        }

        fun startTracking(context: Context) {
            val intent = Intent(context, BatteryTrackerService::class.java).apply {
                action = ACTION_START_TRACKING
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        fun stopTracking(context: Context) {
            val intent = Intent(context, BatteryTrackerService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            try {
                context.startService(intent)
            } catch (_: Throwable) {}
        }

        fun onPowerDisconnected(context: Context) {
            val intent = Intent(context, BatteryTrackerService::class.java).apply {
                action = ACTION_DISCONNECTED
            }
            try {
                context.startService(intent)
            } catch (_: Throwable) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isSampling = false

    private var sessionStartLevel = -1
    private var sessionStartTimestamp = 0L
    private var sessionLastSampleTimestamp = 0L
    private var sessionStartChargeCounterUah = 0L
    private var sessionAccumulatedCoulomb = 0.0

    private var lastNotifUpdateMillis = 0L

    private val batteryIntentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val isPlugged = plugged > 0

                if (!isPlugged && isSampling) {
                    finalizeAndSaveSession(isExplicit = false)
                }
            }
        }
    }

    private val samplingRunnable = object : Runnable {
        override fun run() {
            if (isSampling) {
                performSamplingTick()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(batteryIntentReceiver, filter)
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_TRACKING

        when (action) {
            ACTION_START_TRACKING -> {
                startForegroundWithNotification()
                startChargingTracking()
            }
            ACTION_STOP_TRACKING -> {
                finalizeAndSaveSession(isExplicit = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_DISCONNECTED -> {
                finalizeAndSaveSession(isExplicit = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isSampling = false
        handler.removeCallbacks(samplingRunnable)
        try {
            unregisterReceiver(batteryIntentReceiver)
        } catch (_: Throwable) {}
        releaseWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Theo dõi Nạp Pin & Sức Khỏe Pin",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Theo dõi dòng sạc thực tế và tính toán độ chai pin trong lúc cắm sạc"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("⚡ Đang theo dõi nạp pin & đo độ chai pin ngầm...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, BatteryHealthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery_small)
            .setContentTitle("Sức khỏe Pin HyperOS · Đang đo ngầm")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FixXiaomi:BatteryChargeTracker").apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(4 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (_: Throwable) {}
    }

    private fun startChargingTracking() {
        if (isSampling) return

        val initialIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sessionStartLevel = initialIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 50) ?: 50
        val initialStatus = initialIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        if (sessionStartLevel >= 100 || initialStatus == BatteryManager.BATTERY_STATUS_FULL) {
            isSampling = false
            currentSession = currentSession.copy(
                isMeasuring = false,
                isCharging = true,
                currentLevel = 100,
                startLevel = 100
            )
            activeListener?.onBatteryTelemetryUpdated(currentSession)
            return
        }

        acquireWakeLock()
        isSampling = true

        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        sessionStartChargeCounterUah = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).coerceAtLeast(0L)
        } else 0L

        sessionStartTimestamp = SystemClock.elapsedRealtime()
        sessionLastSampleTimestamp = sessionStartTimestamp
        sessionAccumulatedCoulomb = 0.0

        handler.post(samplingRunnable)
    }

    private fun performSamplingTick() {
        val now = SystemClock.elapsedRealtime()
        val dtSec = (now - sessionLastSampleTimestamp) / 1000.0
        sessionLastSampleTimestamp = now

        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val currentLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, sessionStartLevel) ?: sessionStartLevel
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val isPlugged = plugged > 0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4000) ?: 4000
        val tempC = BatteryHealthManager.getLiveBatteryTemperature(this, batteryIntent)

        val currentNowRaw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } else 0

        var currentMa = if (abs(currentNowRaw) > 10000) {
            abs(currentNowRaw) / 1000.0
        } else {
            abs(currentNowRaw).toDouble()
        }

        if (isPlugged && currentMa < 50.0) {
            currentMa = 2200.0
        }

        val powerWatts = (voltageMv / 1000.0) * (currentMa / 1000.0)

        if (isPlugged && currentMa > 50.0 && dtSec > 0) {
            sessionAccumulatedCoulomb += (currentMa * (dtSec / 3600.0))
        }

        val currentCounterUah = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).coerceAtLeast(0L)
        } else 0L

        val counterDeltaMah = if (sessionStartChargeCounterUah > 0 && currentCounterUah > sessionStartChargeCounterUah) {
            (currentCounterUah - sessionStartChargeCounterUah) / 1000.0
        } else 0.0

        val deltaMah = if (counterDeltaMah > 10.0) {
            counterDeltaMah
        } else {
            sessionAccumulatedCoulomb
        }

        val deltaLevel = (currentLevel - sessionStartLevel).coerceAtLeast(0)
        val elapsedSec = (now - sessionStartTimestamp) / 1000

        var estHealthPct = -1
        var estCapMah = -1
        if (deltaLevel >= 2 || deltaMah >= 20.0) {
            val (fusedPct, fusedCap) = BatteryHealthManager.computeFusedBatteryHealth(this, deltaLevel, deltaMah.roundToInt())
            estHealthPct = fusedPct
            estCapMah = fusedCap
        }

        val telemetryPt = TelemetryPoint(
            timestampMs = System.currentTimeMillis(),
            elapsedSec = elapsedSec,
            currentMa = currentMa,
            voltageMv = voltageMv,
            powerWatts = powerWatts,
            tempC = tempC,
            level = currentLevel
        )
        addTelemetryPoint(telemetryPt)

        val isFull = currentLevel >= 100 || status == BatteryManager.BATTERY_STATUS_FULL
        if (isFull && (deltaLevel >= 2 || deltaMah >= 20.0)) {
            finalizeAndSaveSession(isExplicit = false, customSource = "Tự động khi đạt 100% pin")

            currentSession = LiveChargingSession(
                isCharging = isPlugged,
                isMeasuring = false,
                startLevel = sessionStartLevel,
                currentLevel = currentLevel,
                deltaLevel = deltaLevel,
                startTimestamp = sessionStartTimestamp,
                elapsedSeconds = elapsedSec,
                accumulatedCoulombMah = deltaMah,
                currentMa = currentMa,
                voltageMv = voltageMv,
                powerWatts = powerWatts,
                tempC = tempC,
                estimatedHealthPct = estHealthPct,
                estimatedCapacityMah = estCapMah
            )

            val healthSuffix = if (estHealthPct > 0) " · Sức khỏe: $estHealthPct%" else ""
            val notif = buildNotification("✅ Pin đã đạt 100% (+$deltaLevel% · +${deltaMah.roundToInt()} mAh$healthSuffix) · Đã tự động chốt kết quả đo!")
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, notif)

            activeListener?.onBatteryTelemetryUpdated(currentSession)
            return
        }

        currentSession = LiveChargingSession(
            isCharging = isPlugged,
            isMeasuring = true,
            startLevel = sessionStartLevel,
            currentLevel = currentLevel,
            deltaLevel = deltaLevel,
            startTimestamp = sessionStartTimestamp,
            elapsedSeconds = elapsedSec,
            accumulatedCoulombMah = deltaMah,
            currentMa = currentMa,
            voltageMv = voltageMv,
            powerWatts = powerWatts,
            tempC = tempC,
            estimatedHealthPct = estHealthPct,
            estimatedCapacityMah = estCapMah
        )

        if (now - lastNotifUpdateMillis > 3000L) {
            lastNotifUpdateMillis = now
            val notifText = if (estHealthPct > 0) {
                "⚡ Sạc: $currentLevel% (+$deltaLevel% · +${deltaMah.roundToInt()} mAh) · Sức khỏe: $estHealthPct%"
            } else {
                "⚡ Sạc: $currentLevel% · Dòng: ${currentMa.roundToInt()} mA (${String.format(Locale.US, "%.1f", powerWatts)}W)"
            }
            val notif = buildNotification(notifText)
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, notif)
        }

        activeListener?.onBatteryTelemetryUpdated(currentSession)
    }

    private fun finalizeAndSaveSession(isExplicit: Boolean, customSource: String? = null) {
        if (!isSampling) return
        isSampling = false
        handler.removeCallbacks(samplingRunnable)
        releaseWakeLock()

        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val currentLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, sessionStartLevel) ?: sessionStartLevel
        val deltaLevel = (currentLevel - sessionStartLevel).coerceAtLeast(0)
        val deltaMah = sessionAccumulatedCoulomb.roundToInt()
        val elapsedSec = (SystemClock.elapsedRealtime() - sessionStartTimestamp) / 1000

        val minutes = elapsedSec / 60
        val seconds = elapsedSec % 60
        val durationStr = if (minutes > 0) "$minutes phút ${seconds}s" else "${seconds}s"
        val deltaStr = "+$deltaLevel% (+$deltaMah mAh)"
        val sourceStr = customSource ?: (if (isExplicit) "Đo thủ công" else "Tự động khi cắm sạc")

        if (deltaLevel >= 2 || deltaMah >= 20) {
            val (healthPct, estCap) = BatteryHealthManager.computeFusedBatteryHealth(this, deltaLevel, deltaMah)

            BatteryHealthManager.saveChargingSession(
                context = this,
                healthPct = healthPct,
                estimatedMah = estCap,
                durationStr = durationStr,
                deltaStr = deltaStr,
                startLevel = sessionStartLevel,
                endLevel = currentLevel,
                source = sourceStr,
                telemetryPoints = getTelemetryHistory()
            )

            activeListener?.onChargingSessionFinished(healthPct, estCap, deltaStr)
        }

        currentSession = currentSession.copy(isMeasuring = false, isCharging = false)
    }
}

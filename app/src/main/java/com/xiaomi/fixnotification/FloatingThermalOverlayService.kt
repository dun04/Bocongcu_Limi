package com.xiaomi.fixnotification

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class FloatingThermalOverlayService : Service(), ThermalDataListener {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var gaugeCpu: HudGaugeView? = null
    private var tvCpuTemp: TextView? = null
    private var gaugeGpu: HudGaugeView? = null
    private var tvGpuTemp: TextView? = null
    private var gaugeBat: HudGaugeView? = null
    private var tvBatTemp: TextView? = null

    private var lastTapTime = 0L
    private val tapHandler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_START = "ACTION_START_FLOATING_THERMAL"
        const val ACTION_STOP = "ACTION_STOP_FLOATING_THERMAL"
        private const val NOTIFICATION_ID = 2026
        private const val CHANNEL_ID = "channel_thermal_overlay"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        initFloatingWindow()
        ThermalTelemetryHub.register(this, this)

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cửa Sổ Nổi Giám Sát Nhiệt Độ",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị HUD nhiệt độ CPU, GPU và Pin nổi trên màn hình"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, ThermalMonitorActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingThermalOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Màn Hình Giám Sát Nhiệt Độ Đang Nổi")
            .setContentText("Chạm để mở toàn màn hình · Chạm đúp 2 lần để tắt HUD")
            .setSmallIcon(R.drawable.ic_popup_window)
            .setContentIntent(pendingOpen)
            .addAction(R.drawable.ic_close_white, "Đóng Cửa Sổ Nổi", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun initFloatingWindow() {
        if (floatingView != null) return

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_FixNotificationXiaomi)
            floatingView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_thermal_hud, null)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val density = resources.displayMetrics.density
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (resources.displayMetrics.widthPixels - (190 * density).toInt()).coerceAtLeast(20)
                y = (140 * density).toInt()
            }

            gaugeCpu = floatingView?.findViewById(R.id.gaugeFloatingCpu)
            tvCpuTemp = floatingView?.findViewById(R.id.tvFloatingCpuTemp)
            gaugeGpu = floatingView?.findViewById(R.id.gaugeFloatingGpu)
            tvGpuTemp = floatingView?.findViewById(R.id.tvFloatingGpuTemp)
            gaugeBat = floatingView?.findViewById(R.id.gaugeFloatingBat)
            tvBatTemp = floatingView?.findViewById(R.id.tvFloatingBatTemp)

            // Logic kéo thả siêu mượt 1:1 trực tiếp theo chuyển động tay (không trễ frame)
            val touchSlop = 4f * density

            floatingView?.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val p = params ?: return false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = p.x
                            initialY = p.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (isDragging || Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                                isDragging = true
                                val screenW = resources.displayMetrics.widthPixels
                                val screenH = resources.displayMetrics.heightPixels
                                val hudW = floatingView?.width?.takeIf { it > 0 } ?: (180 * density).toInt()
                                val hudH = floatingView?.height?.takeIf { it > 0 } ?: (45 * density).toInt()

                                val maxX = (screenW - hudW).coerceAtLeast(0)
                                val maxY = (screenH - hudH).coerceAtLeast(0)

                                p.x = (initialX + dx).toInt().coerceIn(0, maxX)
                                p.y = (initialY + dy).toInt().coerceIn(0, maxY)

                                try {
                                    windowManager?.updateViewLayout(floatingView, p)
                                } catch (_: Throwable) {}
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            // Xử lý chạm 2 lần liên tục (Double Tap) để tắt HUD hoặc 1 lần mở toàn màn hình
                            if (!isDragging) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 350L) {
                                    // Chạm 2 lần liên tục -> Tắt HUD
                                    lastTapTime = 0L
                                    tapHandler.removeCallbacksAndMessages(null)
                                    android.widget.Toast.makeText(
                                        this@FloatingThermalOverlayService,
                                        "Đã đóng Cửa Sổ Nổi HUD",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    stopSelf()
                                } else {
                                    lastTapTime = now
                                    // Chờ xem có đúp chạm lần 2 không, nếu không thì mở trang giám sát nhiệt độ
                                    tapHandler.removeCallbacksAndMessages(null)
                                    tapHandler.postDelayed({
                                        val intent = Intent(this@FloatingThermalOverlayService, ThermalMonitorActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        }
                                        startActivity(intent)
                                    }, 350L)
                                }
                            }
                            isDragging = false
                            return true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            isDragging = false
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager?.addView(floatingView, params)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onThermalDataUpdate(data: SharedThermalData) {
        if (floatingView != null) {
            // 1. CPU
            val cpuUsage = data.cpuUsagePercent
            gaugeCpu?.setProgress(cpuUsage, formatUsageColor(cpuUsage))

            val cpuTemp = data.cpuTempC
            tvCpuTemp?.text = "${cpuTemp.toInt()}°C"
            tvCpuTemp?.setTextColor(formatTempColor(cpuTemp))

            // 2. GPU
            val gpuUsage = data.gpuUsagePercent
            gaugeGpu?.setProgress(gpuUsage, formatUsageColor(gpuUsage))

            val gpuTemp = data.gpuTempC
            tvGpuTemp?.text = "${gpuTemp.toInt()}°C"
            tvGpuTemp?.setTextColor(formatTempColor(gpuTemp))

            // 3. Pin (Battery)
            val batPercent = data.batPercent
            gaugeBat?.setProgress(batPercent, formatBatPercentColor(batPercent))

            val batTemp = data.batTempC
            tvBatTemp?.text = "${String.format(Locale.US, "%.1f", batTemp)}°C"
            tvBatTemp?.setTextColor(formatBatTempColor(batTemp))
        }
    }

    private fun formatUsageColor(usage: Int): Int {
        return when {
            usage > 80 -> Color.parseColor("#EF4444") // Quá 80% đỏ
            usage > 60 -> Color.parseColor("#F59E0B") // Quá 60% cam
            else -> Color.parseColor("#00E5FF")       // Dưới 60% xanh lam
        }
    }

    private fun formatTempColor(temp: Float): Int {
        return if (temp >= 50f) {
            Color.parseColor("#EF4444") // Trên 50 đỏ
        } else {
            Color.parseColor("#00E5FF") // Dưới 50 xanh lam
        }
    }

    private fun formatBatPercentColor(percent: Int): Int {
        return when {
            percent <= 20 -> Color.parseColor("#EF4444") // Từ 20 trở xuống đỏ
            percent <= 50 -> Color.parseColor("#F59E0B") // Dưới 50 cam
            else -> Color.parseColor("#00E5FF")          // Trên 50 xanh lam
        }
    }

    private fun formatBatTempColor(batTemp: Float): Int {
        return when {
            batTemp >= 45f -> Color.parseColor("#EF4444") // 45 đỏ
            batTemp >= 40f -> Color.parseColor("#F59E0B") // Trên 40 cam
            else -> Color.parseColor("#00E5FF")           // Dưới 40 xanh
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ThermalTelemetryHub.unregister(this)
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (_: Throwable) {}
            floatingView = null
        }
    }
}

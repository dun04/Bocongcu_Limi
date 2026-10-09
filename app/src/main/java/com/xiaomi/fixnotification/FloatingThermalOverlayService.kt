package com.xiaomi.fixnotification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.Locale

class FloatingThermalOverlayService : Service(), ThermalDataListener {

    private var windowManager: WindowManager? = null

    private var floatingView1: View? = null
    private var params1: WindowManager.LayoutParams? = null
    private var cardFloatingRoot: com.google.android.material.card.MaterialCardView? = null
    private var gaugeCpu: HudGaugeView? = null
    private var tvCpuTemp: TextView? = null
    private var gaugeGpu: HudGaugeView? = null
    private var tvGpuTemp: TextView? = null
    private var gaugeBat: HudGaugeView? = null
    private var tvBatTemp: TextView? = null

    private var floatingView2: View? = null
    private var params2: WindowManager.LayoutParams? = null
    private var cardFloatingFpsRoot: com.google.android.material.card.MaterialCardView? = null
    private var tvFloatingFps: TextView? = null
    private var tvFloatingPowerWatts: TextView? = null

    private var tapCount = 0
    private val tapHandler = Handler(Looper.getMainLooper())
    private val tapRunnable = Runnable {
        when (tapCount) {
            1 -> handleSingleTap()
            2 -> handleDoubleTap()
        }
        tapCount = 0
    }

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

        initFloatingWindows()
        ThermalTelemetryHub.register(this, this)

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cửa Sổ Nổi Giám Sát Nhiệt Độ & FPS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị HUD nhiệt độ CPU, GPU, Pin, FPS và Công suất Chip"
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
            .setContentTitle("Bộ 2 Cửa Sổ Nổi Giám Sát (Nhiệt Độ & FPS/W)")
            .setContentText("1 chạm: Đo biểu đồ · 2 chạm: Mở trang giám sát · 3 chạm: Đóng HUD")
            .setSmallIcon(R.drawable.ic_popup_window)
            .setContentIntent(pendingOpen)
            .addAction(R.drawable.ic_close_white, "Đóng HUD", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun initFloatingWindows() {
        if (floatingView1 != null && floatingView2 != null) return

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_FixNotificationXiaomi)
            val inflater = LayoutInflater.from(themedContext)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val density = resources.displayMetrics.density
            val defaultX = (resources.displayMetrics.widthPixels - (165 * density).toInt()).coerceAtLeast(20)

            if (floatingView1 == null) {
                floatingView1 = inflater.inflate(R.layout.layout_floating_thermal_hud, null)
                params1 = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = defaultX
                    y = (120 * density).toInt()
                }

                cardFloatingRoot = floatingView1?.findViewById(R.id.cardFloatingRoot)
                gaugeCpu = floatingView1?.findViewById(R.id.gaugeFloatingCpu)
                tvCpuTemp = floatingView1?.findViewById(R.id.tvFloatingCpuTemp)
                gaugeGpu = floatingView1?.findViewById(R.id.gaugeFloatingGpu)
                tvGpuTemp = floatingView1?.findViewById(R.id.tvFloatingGpuTemp)
                gaugeBat = floatingView1?.findViewById(R.id.gaugeFloatingBat)
                tvBatTemp = floatingView1?.findViewById(R.id.tvFloatingBatTemp)

                attachDragAndTouchListener(floatingView1, params1)
                windowManager?.addView(floatingView1, params1)
            }

            if (floatingView2 == null) {
                floatingView2 = inflater.inflate(R.layout.layout_floating_fps_hud, null)
                params2 = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = defaultX
                    y = (162 * density).toInt()
                }

                cardFloatingFpsRoot = floatingView2?.findViewById(R.id.cardFloatingFpsRoot)
                tvFloatingFps = floatingView2?.findViewById(R.id.tvFloatingFps)
                tvFloatingPowerWatts = floatingView2?.findViewById(R.id.tvFloatingPowerWatts)

                attachDragAndTouchListener(floatingView2, params2)
                windowManager?.addView(floatingView2, params2)
            }

        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTouchListener(view: View?, p: WindowManager.LayoutParams?) {
        val targetView = view ?: return
        val targetParams = p ?: return
        val density = resources.displayMetrics.density
        val touchSlop = 4f * density

        targetView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = targetParams.x
                        initialY = targetParams.y
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
                            val hudW = targetView.width.takeIf { it > 0 } ?: (160 * density).toInt()
                            val hudH = targetView.height.takeIf { it > 0 } ?: (40 * density).toInt()

                            val maxX = (screenW - hudW).coerceAtLeast(0)
                            val maxY = (screenH - hudH).coerceAtLeast(0)

                            targetParams.x = (initialX + dx).toInt().coerceIn(0, maxX)
                            targetParams.y = (initialY + dy).toInt().coerceIn(0, maxY)

                            try {
                                windowManager?.updateViewLayout(targetView, targetParams)
                            } catch (_: Throwable) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            onHudTapAction()
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
    }

    private fun onHudTapAction() {
        tapCount++
        tapHandler.removeCallbacks(tapRunnable)
        if (tapCount >= 3) {

            tapCount = 0
            handleTripleTap()
        } else {

            tapHandler.postDelayed(tapRunnable, 300L)
        }
    }

    private fun handleSingleTap() {
        val isRec = ThermalTelemetryHub.toggleRecording(this)
        if (isRec) {
            Toast.makeText(
                this,
                "Đã BẮT ĐẦU phiên đo (Ghi nhận CPU, GPU, Pin, FPS, W)",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                "Đã KẾT THÚC phiên đo (Biểu đồ đã lưu vào trang Giám sát)",
                Toast.LENGTH_SHORT
            ).show()
        }
        updateRecStatusUI(isRec)
    }

    private fun handleDoubleTap() {
        val intent = Intent(this, ThermalMonitorActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("KEY_ACTIVE_TAB", 3)
        }
        startActivity(intent)
    }

    private fun handleTripleTap() {
        Toast.makeText(this, "Đã đóng cả hai Cửa Sổ Nổi HUD", Toast.LENGTH_SHORT).show()
        stopSelf()
    }

    private fun updateRecStatusUI(isRecording: Boolean) {
        val density = resources.displayMetrics.density

        val strokeColor = if (isRecording) Color.parseColor("#EF4444") else Color.parseColor("#20FFFFFF")
        val strokeWidth = if (isRecording) (1.2f * density).toInt().coerceAtLeast(2) else (0.8f * density).toInt().coerceAtLeast(1)
        cardFloatingRoot?.strokeColor = strokeColor
        cardFloatingRoot?.strokeWidth = strokeWidth
        cardFloatingFpsRoot?.strokeColor = strokeColor
        cardFloatingFpsRoot?.strokeWidth = strokeWidth
    }

    override fun onThermalDataUpdate(data: SharedThermalData) {

        updateRecStatusUI(data.isRecording)

        if (floatingView1 != null) {
            val cpuUsage = data.cpuUsagePercent
            gaugeCpu?.setProgress(cpuUsage, formatUsageColor(cpuUsage))

            val cpuTemp = data.cpuTempC
            tvCpuTemp?.text = "${cpuTemp.toInt()}°C"
            tvCpuTemp?.setTextColor(formatTempColor(cpuTemp))

            val gpuUsage = data.gpuUsagePercent
            gaugeGpu?.setProgress(gpuUsage, formatUsageColor(gpuUsage))

            val gpuTemp = data.gpuTempC
            tvGpuTemp?.text = "${gpuTemp.toInt()}°C"
            tvGpuTemp?.setTextColor(formatTempColor(gpuTemp))

            val batPercent = data.batPercent
            gaugeBat?.setProgress(batPercent, formatBatPercentColor(batPercent))

            val batTemp = data.batTempC
            tvBatTemp?.text = "${String.format(Locale.US, "%.1f", batTemp)}°C"
            tvBatTemp?.setTextColor(formatBatTempColor(batTemp))
        }

        if (floatingView2 != null) {

            tvFloatingFps?.text = "${data.fps.toInt()}"

            val watts = data.powerWatts
            tvFloatingPowerWatts?.text = "${String.format(Locale.US, "%.1f", watts)} W"
            if (watts > 10.0f) {
                tvFloatingPowerWatts?.setTextColor(Color.parseColor("#EF4444"))
            } else {
                tvFloatingPowerWatts?.setTextColor(Color.parseColor("#00E5FF"))
            }
        }
    }

    private fun formatUsageColor(usage: Int): Int {
        return when {
            usage > 80 -> Color.parseColor("#EF4444")
            usage > 60 -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#00E5FF")
        }
    }

    private fun formatTempColor(temp: Float): Int {
        return if (temp >= 50f) {
            Color.parseColor("#EF4444")
        } else {
            Color.parseColor("#00E5FF")
        }
    }

    private fun formatBatPercentColor(percent: Int): Int {
        return when {
            percent <= 20 -> Color.parseColor("#EF4444")
            percent <= 50 -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#00E5FF")
        }
    }

    private fun formatBatTempColor(batTemp: Float): Int {
        return when {
            batTemp >= 45f -> Color.parseColor("#EF4444")
            batTemp >= 40f -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#00E5FF")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ThermalTelemetryHub.unregister(this)

        if (floatingView1 != null) {
            try {
                windowManager?.removeView(floatingView1)
            } catch (_: Throwable) {}
            floatingView1 = null
        }

        if (floatingView2 != null) {
            try {
                windowManager?.removeView(floatingView2)
            } catch (_: Throwable) {}
            floatingView2 = null
        }
    }
}

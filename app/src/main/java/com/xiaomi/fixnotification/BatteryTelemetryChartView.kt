package com.xiaomi.fixnotification

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class ChartMetricType {
    CURRENT_MA,
    VOLTAGE_V,
    POWER_WATTS,
    TEMP_C,
    ALL_METRICS
}

class BatteryTelemetryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<TelemetryPoint>()
    private var metricType = ChartMetricType.CURRENT_MA

    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.8f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(18f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * resources.displayMetrics.density
        color = Color.parseColor("#1FFFFFFF")
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.scaledDensity
        color = Color.parseColor("#8E99A8")
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }

    private val activePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.parseColor("#9900E5FF")
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EB121622")
    }

    private val tooltipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
        color = Color.parseColor("#6600E5FF")
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * resources.displayMetrics.scaledDensity
        color = Color.WHITE
        isFakeBoldText = true
    }

    // Modern High-Tech Neon Palette
    private val colorCurrent = Color.parseColor("#00E5FF")   // Electric Cyan
    private val colorVoltage = Color.parseColor("#00E676")   // Emerald Green
    private val colorPower = Color.parseColor("#FF9E0B")     // Amber Flame
    private val colorTemp = Color.parseColor("#FF4D6D")      // Neon Coral
    private val colorBattery = Color.parseColor("#A855F7")   // Cyber Violet

    // Touch interaction
    private var isTouching = false
    private var touchX = -1f
    private var touchedPoint: TelemetryPoint? = null

    // Chart Paths
    private val linePath = Path()
    private val fillPath = Path()

    fun setData(points: List<TelemetryPoint>, type: ChartMetricType = metricType) {
        dataPoints.clear()
        dataPoints.addAll(points)
        metricType = type
        postInvalidateOnAnimation()
    }

    fun setMetricType(type: ChartMetricType) {
        if (metricType != type) {
            metricType = type
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isTouching = true
                touchX = event.x.coerceIn(paddingLeft.toFloat(), (width - paddingRight).toFloat())
                findTouchedPoint()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                touchedPoint = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findTouchedPoint() {
        if (dataPoints.isEmpty()) {
            touchedPoint = null
            return
        }
        val plotW = width - paddingLeft - paddingRight
        if (plotW <= 0) return

        val frac = ((touchX - paddingLeft) / plotW).coerceIn(0f, 1f)
        val idx = (frac * (dataPoints.size - 1)).toInt().coerceIn(0, dataPoints.size - 1)
        touchedPoint = dataPoints[idx]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        val padL = paddingLeft.toFloat() + 32f * resources.displayMetrics.density
        val padR = paddingRight.toFloat() + 12f * resources.displayMetrics.density
        val padT = paddingTop.toFloat() + 18f * resources.displayMetrics.density
        val padB = paddingBottom.toFloat() + 26f * resources.displayMetrics.density

        val plotW = w - padL - padR
        val plotH = h - padT - padB

        if (plotW <= 0 || plotH <= 0) return

        // 1. Vẽ lưới ngang (Grid lines)
        val gridLines = 4
        for (i in 0..gridLines) {
            val y = padT + (plotH / gridLines) * i
            canvas.drawLine(padL, y, padL + plotW, y, gridPaint)
        }

        if (dataPoints.isEmpty()) {
            val emptyText = "Đang chờ dữ liệu cảm biến sạc..."
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(emptyText, padL + plotW / 2, padT + plotH / 2, textPaint)
            return
        }

        if (metricType == ChartMetricType.ALL_METRICS) {
            drawAllMetrics(canvas, padL, padT, plotW, plotH)
            return
        }

        // 2. Lấy giá trị Min/Max cho Metric hiện tại
        val (minVal, maxVal, unit, mainColor) = getMetricConfig(metricType)

        // Nhãn trục Y (Min, Mid, Max)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatMetricValue(maxVal, metricType) + unit, padL - 8f, padT + 10f, textPaint)
        canvas.drawText(formatMetricValue((maxVal + minVal) / 2.0, metricType) + unit, padL - 8f, padT + plotH / 2 + 4f, textPaint)
        canvas.drawText(formatMetricValue(minVal, metricType) + unit, padL - 8f, padT + plotH, textPaint)

        // 3. Xây dựng đường cong Bézier
        linePath.reset()
        fillPath.reset()

        val pointsCount = dataPoints.size
        val range = (maxVal - minVal).coerceAtLeast(0.01)

        val coords = mutableListOf<Pair<Float, Float>>()
        var maxIdx = 0
        var maxValFound = Double.MIN_VALUE

        for (i in 0 until pointsCount) {
            val pt = dataPoints[i]
            val v = getPointValue(pt, metricType)
            if (v > maxValFound) {
                maxValFound = v
                maxIdx = i
            }
            val x = padL + (i.toFloat() / (pointsCount - 1).coerceAtLeast(1)) * plotW
            val normY = ((v - minVal) / range).coerceIn(0.0, 1.0).toFloat()
            val y = padT + plotH - (normY * plotH)
            coords.add(Pair(x, y))
        }

        if (coords.isNotEmpty()) {
            linePath.moveTo(coords[0].first, coords[0].second)
            fillPath.moveTo(coords[0].first, padT + plotH)
            fillPath.lineTo(coords[0].first, coords[0].second)

            for (i in 1 until coords.size) {
                val prev = coords[i - 1]
                val curr = coords[i]
                val cx = (prev.first + curr.first) / 2f
                linePath.cubicTo(cx, prev.second, cx, curr.second, curr.first, curr.second)
                fillPath.cubicTo(cx, prev.second, cx, curr.second, curr.first, curr.second)
            }

            fillPath.lineTo(coords.last().first, padT + plotH)
            fillPath.close()

            // Tô gradient chuyển màu dạ quang bên dưới (Liquid Glass Glow Fill)
            fillPaint.shader = LinearGradient(
                0f, padT, 0f, padT + plotH,
                Color.argb(90, Color.red(mainColor), Color.green(mainColor), Color.blue(mainColor)),
                Color.argb(0, Color.red(mainColor), Color.green(mainColor), Color.blue(mainColor)),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)

            // Vẽ nét viền chính
            linePaint.color = mainColor
            canvas.drawPath(linePath, linePaint)

            // Đánh dấu điểm cực đại (Peak Marker)
            if (coords.isNotEmpty() && maxIdx in coords.indices) {
                val peakCoord = coords[maxIdx]
                activePointPaint.color = mainColor
                canvas.drawCircle(peakCoord.first, peakCoord.second, 4.5f * resources.displayMetrics.density, activePointPaint)
                activePointPaint.color = Color.WHITE
                canvas.drawCircle(peakCoord.first, peakCoord.second, 2f * resources.displayMetrics.density, activePointPaint)

                // Nhãn Peak
                val peakStr = "Đỉnh: ${formatMetricValue(maxValFound, metricType)}$unit"
                badgePaint.color = mainColor
                badgePaint.textAlign = Paint.Align.CENTER
                val badgeY = max(peakCoord.second - 10f * resources.displayMetrics.density, padT + 8f)
                canvas.drawText(peakStr, peakCoord.first.coerceIn(padL + 40f, padL + plotW - 40f), badgeY, badgePaint)
            }

            // Vẽ điểm mút phát sáng cuối cùng (Live pulse point)
            val last = coords.last()
            activeGlowPaint.color = Color.argb(60, Color.red(mainColor), Color.green(mainColor), Color.blue(mainColor))
            canvas.drawCircle(last.first, last.second, 12f * resources.displayMetrics.density, activeGlowPaint)

            activePointPaint.color = mainColor
            canvas.drawCircle(last.first, last.second, 5f * resources.displayMetrics.density, activePointPaint)

            activePointPaint.color = Color.WHITE
            canvas.drawCircle(last.first, last.second, 2.2f * resources.displayMetrics.density, activePointPaint)
        }

        // 4. Nhãn trục X (Thời gian bắt đầu, giữa, kết thúc)
        textPaint.textAlign = Paint.Align.LEFT
        val firstTime = formatElapsedSeconds(dataPoints.first().elapsedSec)
        canvas.drawText(firstTime, padL, padT + plotH + 18f * resources.displayMetrics.density, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        val lastTime = formatElapsedSeconds(dataPoints.last().elapsedSec)
        canvas.drawText(lastTime, padL + plotW, padT + plotH + 18f * resources.displayMetrics.density, textPaint)

        // 5. Vẽ Cursor tương tác chạm (Touch Tooltip)
        if (isTouching && touchedPoint != null) {
            val pt = touchedPoint!!
            val touchedIdx = dataPoints.indexOf(pt)
            val x = padL + (touchedIdx.toFloat() / (pointsCount - 1).coerceAtLeast(1)) * plotW
            val v = getPointValue(pt, metricType)
            val normY = ((v - minVal) / range).coerceIn(0.0, 1.0).toFloat()
            val y = padT + plotH - (normY * plotH)

            cursorPaint.color = mainColor
            canvas.drawLine(x, padT, x, padT + plotH, cursorPaint)

            // Vẽ điểm chọn
            canvas.drawCircle(x, y, 6.5f * resources.displayMetrics.density, activePointPaint.apply { color = mainColor })
            canvas.drawCircle(x, y, 3f * resources.displayMetrics.density, activePointPaint.apply { color = Color.WHITE })

            // Hộp Tooltip
            val valStr = "${formatMetricValue(v, metricType)} $unit"
            val timeStr = "${formatElapsedSeconds(pt.elapsedSec)} (${pt.level}%)"
            val textToDraw = "$valStr · $timeStr"

            val tooltipW = tooltipTextPaint.measureText(textToDraw) + 24f * resources.displayMetrics.density
            val tooltipH = 28f * resources.displayMetrics.density

            var boxL = x - tooltipW / 2f
            if (boxL < padL) boxL = padL
            if (boxL + tooltipW > padL + plotW) boxL = padL + plotW - tooltipW
            val boxT = max(padT - tooltipH - 8f, 4f)

            val rect = RectF(boxL, boxT, boxL + tooltipW, boxT + tooltipH)
            tooltipStrokePaint.color = mainColor
            canvas.drawRoundRect(rect, 10f * resources.displayMetrics.density, 10f * resources.displayMetrics.density, tooltipBgPaint)
            canvas.drawRoundRect(rect, 10f * resources.displayMetrics.density, 10f * resources.displayMetrics.density, tooltipStrokePaint)

            tooltipTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(textToDraw, rect.centerX(), rect.centerY() + 4f * resources.displayMetrics.density, tooltipTextPaint)
        }
    }

    private fun drawAllMetrics(canvas: Canvas, padL: Float, padT: Float, plotW: Float, plotH: Float) {
        val metrics = listOf(
            ChartMetricType.CURRENT_MA,
            ChartMetricType.VOLTAGE_V,
            ChartMetricType.POWER_WATTS,
            ChartMetricType.TEMP_C
        )

        for (m in metrics) {
            val (minVal, maxVal, _, color) = getMetricConfig(m)
            val range = (maxVal - minVal).coerceAtLeast(0.01)

            linePath.reset()
            val coords = mutableListOf<Pair<Float, Float>>()
            for (i in dataPoints.indices) {
                val pt = dataPoints[i]
                val v = getPointValue(pt, m)
                val x = padL + (i.toFloat() / (dataPoints.size - 1).coerceAtLeast(1)) * plotW
                val normY = ((v - minVal) / range).coerceIn(0.0, 1.0).toFloat()
                val y = padT + plotH - (normY * plotH)
                coords.add(Pair(x, y))
            }

            if (coords.isNotEmpty()) {
                linePath.moveTo(coords[0].first, coords[0].second)
                for (i in 1 until coords.size) {
                    val prev = coords[i - 1]
                    val curr = coords[i]
                    val cx = (prev.first + curr.first) / 2f
                    linePath.cubicTo(cx, prev.second, cx, curr.second, curr.first, curr.second)
                }
                linePaint.color = color
                canvas.drawPath(linePath, linePaint)
            }
        }
    }

    private fun getMetricConfig(type: ChartMetricType): MetricConfig {
        if (dataPoints.isEmpty()) {
            return MetricConfig(0.0, 100.0, "", colorCurrent)
        }

        var minVal = Double.MAX_VALUE
        var maxVal = Double.MIN_VALUE

        for (pt in dataPoints) {
            val v = getPointValue(pt, type)
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        return when (type) {
            ChartMetricType.CURRENT_MA -> {
                val padMin = max(0.0, minVal * 0.9)
                val padMax = maxVal * 1.1 + 100.0
                MetricConfig(padMin, padMax, " mA", colorCurrent)
            }
            ChartMetricType.VOLTAGE_V -> {
                val padMin = max(3.0, minVal - 0.15)
                val padMax = min(5.0, maxVal + 0.15)
                MetricConfig(padMin, padMax, " V", colorVoltage)
            }
            ChartMetricType.POWER_WATTS -> {
                val padMin = max(0.0, minVal * 0.85)
                val padMax = maxVal * 1.15 + 1.0
                MetricConfig(padMin, padMax, " W", colorPower)
            }
            ChartMetricType.TEMP_C -> {
                val padMin = max(20.0, minVal - 2.0)
                val padMax = maxVal + 3.0
                MetricConfig(padMin, padMax, "°C", colorTemp)
            }
            ChartMetricType.ALL_METRICS -> MetricConfig(0.0, 100.0, "", colorCurrent)
        }
    }

    private fun getPointValue(pt: TelemetryPoint, type: ChartMetricType): Double {
        return when (type) {
            ChartMetricType.CURRENT_MA -> pt.currentMa
            ChartMetricType.VOLTAGE_V -> pt.voltageMv / 1000.0
            ChartMetricType.POWER_WATTS -> pt.powerWatts
            ChartMetricType.TEMP_C -> pt.tempC
            ChartMetricType.ALL_METRICS -> pt.currentMa
        }
    }

    private fun formatMetricValue(v: Double, type: ChartMetricType): String {
        return when (type) {
            ChartMetricType.CURRENT_MA -> v.toInt().toString()
            ChartMetricType.VOLTAGE_V -> String.format(Locale.US, "%.2f", v)
            ChartMetricType.POWER_WATTS -> String.format(Locale.US, "%.1f", v)
            ChartMetricType.TEMP_C -> String.format(Locale.US, "%.1f", v)
            ChartMetricType.ALL_METRICS -> v.toInt().toString()
        }
    }

    private fun formatElapsedSeconds(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private data class MetricConfig(
        val minVal: Double,
        val maxVal: Double,
        val unit: String,
        val color: Int
    )
}

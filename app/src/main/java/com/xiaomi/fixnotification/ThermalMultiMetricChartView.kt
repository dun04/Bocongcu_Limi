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
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class ThermalChartMode {
    FPS_POWER,
    TEMPERATURE,
    USAGE,
    CORES
}

class ThermalMultiMetricChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val samples = mutableListOf<ThermalSample>()
    private var chartMode = ThermalChartMode.TEMPERATURE

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.35f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(14f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * resources.displayMetrics.density
        color = Color.parseColor("#18FFFFFF")
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * resources.displayMetrics.scaledDensity
        color = Color.parseColor("#94A3B8")
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
        color = Color.parseColor("#80FFFFFF")
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F0080E1C")
    }

    private val tooltipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = Color.parseColor("#40FFFFFF")
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.scaledDensity
        color = Color.WHITE
        isFakeBoldText = true
    }

    private val colorCpu = Color.parseColor("#EF4444")
    private val colorGpu = Color.parseColor("#06B6D4")
    private val colorBat = Color.parseColor("#F59E0B")
    private val colorFps = Color.parseColor("#C084FC")
    private val colorPower = Color.parseColor("#00E5FF")

    private val coreColors = intArrayOf(
        Color.parseColor("#60A5FA"),
        Color.parseColor("#34D399"),
        Color.parseColor("#FBBF24"),
        Color.parseColor("#F87171"),
        Color.parseColor("#A78BFA"),
        Color.parseColor("#F472B6"),
        Color.parseColor("#38BDF8"),
        Color.parseColor("#FB923C")
    )

    private var isTouching = false
    private var touchX = -1f
    private var touchedSample: ThermalSample? = null

    private val linePath = Path()
    private val fillPath = Path()

    fun setChartData(data: List<ThermalSample>, mode: ThermalChartMode = chartMode) {
        samples.clear()
        samples.addAll(data)
        chartMode = mode
        isTouching = false
        touchedSample = null
        invalidate()
    }

    fun setMode(mode: ThermalChartMode) {
        chartMode = mode
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (samples.size < 2) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isTouching = true
                touchX = event.x
                calculateTouchedSample()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isTouching = false
                touchedSample = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun calculateTouchedSample() {
        val density = resources.displayMetrics.density
        val paddingLeft = 32f * density
        val paddingRight = 10f * density
        val plotWidth = width.toFloat() - paddingLeft - paddingRight
        if (plotWidth <= 0 || samples.isEmpty()) return

        val clampedX = touchX.coerceIn(paddingLeft, paddingLeft + plotWidth)
        val fraction = (clampedX - paddingLeft) / plotWidth
        val index = (fraction * (samples.size - 1)).toInt().coerceIn(0, samples.size - 1)
        touchedSample = samples[index]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val paddingLeft = 36f * density
        val paddingRight = 12f * density
        val paddingTop = 12f * density
        val paddingBottom = 22f * density

        val plotWidth = w - paddingLeft - paddingRight
        val plotHeight = h - paddingTop - paddingBottom
        if (plotWidth <= 0f || plotHeight <= 0f) return

        val (minVal, maxVal, unit) = getMinMaxUnit()

        val gridLines = 3
        for (i in 0..gridLines) {
            val ratio = i.toFloat() / gridLines
            val y = paddingTop + plotHeight * (1f - ratio)
            canvas.drawLine(paddingLeft, y, w - paddingRight, y, gridPaint)

            val labelVal = (minVal + (maxVal - minVal) * ratio).toInt()
            val labelStr = "$labelVal$unit"
            canvas.drawText(labelStr, 4f * density, y + 3.5f * density, textPaint)
        }

        if (samples.isEmpty()) {
            val emptyMsg = "Chưa có dữ liệu đo trong phiên này"
            val tw = textPaint.measureText(emptyMsg)
            canvas.drawText(emptyMsg, paddingLeft + (plotWidth - tw) / 2f, paddingTop + plotHeight / 2f, textPaint)
            return
        }

        val firstSec = samples.first().elapsedSec
        val lastSec = samples.last().elapsedSec
        val leftTimeStr = formatSec(firstSec)
        val rightTimeStr = formatSec(lastSec)
        canvas.drawText(leftTimeStr, paddingLeft, h - 5f * density, textPaint)
        val rtw = textPaint.measureText(rightTimeStr)
        canvas.drawText(rightTimeStr, w - paddingRight - rtw, h - 5f * density, textPaint)

        when (chartMode) {
            ThermalChartMode.FPS_POWER -> {
                drawSeries(canvas, samples.map { it.fps }, colorFps, minVal, maxVal, paddingLeft, paddingTop, plotWidth, plotHeight, true)
                drawSeries(canvas, samples.map { it.powerWatts }, colorPower, minVal, maxVal, paddingLeft, paddingTop, plotWidth, plotHeight, false)
            }
            ThermalChartMode.TEMPERATURE -> {
                drawSeries(canvas, samples.map { it.cpuTempC }, colorCpu, minVal, maxVal, paddingLeft, paddingTop, plotWidth, plotHeight, false)
                drawSeries(canvas, samples.map { it.gpuTempC }, colorGpu, minVal, maxVal, paddingLeft, paddingTop, plotWidth, plotHeight, false)
                drawSeries(canvas, samples.map { it.batTempC }, colorBat, minVal, maxVal, paddingLeft, paddingTop, plotWidth, plotHeight, false)
            }
            ThermalChartMode.USAGE -> {
                drawSeries(canvas, samples.map { it.cpuUsagePercent.toFloat() }, colorCpu, minVal, maxVal, paddingLeft, paddingTop, plotWidth, plotHeight, true)
                drawSeries(canvas, samples.map { it.gpuUsagePercent.toFloat() }, colorGpu, minVal, maxVal, paddingLeft, paddingTop, plotWidth, plotHeight, false)
            }
            ThermalChartMode.CORES -> {
                val coreCount = samples.firstOrNull()?.coreFreqs?.size ?: 0
                val coreMaxFreqMap = mutableMapOf<Int, Float>()
                for (c in 0 until coreCount) {
                    val maxValFound = samples.maxOfOrNull { it.coreFreqs[c]?.toFloat() ?: 0f } ?: 2000f
                    coreMaxFreqMap[c] = maxValFound.coerceAtLeast(1800f)
                }

                val littleCores = (0..min(3, coreCount - 1)).toList()
                val littleSeries = samples.map { sample ->
                    val pcts = littleCores.map { c ->
                        val mhz = sample.coreFreqs[c]?.toFloat() ?: 0f
                        val maxF = coreMaxFreqMap[c] ?: 2400f
                        (mhz / maxF * 100f).coerceIn(0f, 100f)
                    }
                    if (pcts.isNotEmpty()) pcts.average().toFloat() else 0f
                }

                val bigCores = if (coreCount >= 7) (4..min(6, coreCount - 1)).toList() else if (coreCount > 4) (4 until coreCount).toList() else emptyList()
                val bigSeries = if (bigCores.isNotEmpty()) {
                    samples.map { sample ->
                        val pcts = bigCores.map { c ->
                            val mhz = sample.coreFreqs[c]?.toFloat() ?: 0f
                            val maxF = coreMaxFreqMap[c] ?: 2400f
                            (mhz / maxF * 100f).coerceIn(0f, 100f)
                        }
                        pcts.average().toFloat()
                    }
                } else emptyList()

                val primeCoreIdx = if (coreCount >= 8) 7 else if (coreCount > 1) coreCount - 1 else -1
                val primeSeries = if (primeCoreIdx >= 0) {
                    samples.map { sample ->
                        val mhz = sample.coreFreqs[primeCoreIdx]?.toFloat() ?: 0f
                        val maxF = coreMaxFreqMap[primeCoreIdx] ?: 2400f
                        (mhz / maxF * 100f).coerceIn(0f, 100f)
                    }
                } else emptyList()

                val colorLittle = Color.parseColor("#38BDF8")
                val colorBig = Color.parseColor("#A78BFA")
                val colorPrime = Color.parseColor("#FB923C")

                if (littleSeries.isNotEmpty()) {
                    drawSeries(canvas, smoothSeries(littleSeries), colorLittle, minVal, maxVal, paddingLeft, paddingTop, plotWidth, plotHeight, false)
                }
                if (bigSeries.isNotEmpty()) {
                    drawSeries(canvas, smoothSeries(bigSeries), colorBig, minVal, maxVal, paddingLeft, paddingTop, plotWidth, plotHeight, false)
                }
                if (primeSeries.isNotEmpty()) {
                    drawSeries(canvas, smoothSeries(primeSeries), colorPrime, minVal, maxVal, paddingLeft, paddingTop, plotWidth, plotHeight, false)
                }
            }
        }

        if (isTouching && touchedSample != null) {
            val s = touchedSample!!
            val sampleIdx = samples.indexOf(s).coerceAtLeast(0)
            val stepX = plotWidth / (samples.size - 1).coerceAtLeast(1)
            val currentTouchX = paddingLeft + (sampleIdx * stepX)

            canvas.drawLine(currentTouchX, paddingTop, currentTouchX, paddingTop + plotHeight, cursorPaint)

            drawHighlightPoint(canvas, currentTouchX, s, minVal, maxVal, paddingTop, plotHeight)

            drawTooltip(canvas, currentTouchX, s, density, w, h)
        }
    }

    private fun smoothSeries(data: List<Float>): List<Float> {
        if (data.size <= 2) return data
        val result = ArrayList<Float>(data.size)
        for (i in data.indices) {
            val prev = data[max(0, i - 1)]
            val curr = data[i]
            val next = data[min(data.size - 1, i + 1)]
            result.add(prev * 0.22f + curr * 0.56f + next * 0.22f)
        }
        return result
    }

    private fun downsample(src: List<Float>, targetCount: Int): List<Float> {
        if (src.size <= targetCount) return src
        val result = ArrayList<Float>(targetCount)
        val step = (src.size - 1).toFloat() / (targetCount - 1).toFloat()
        for (i in 0 until targetCount) {
            val idx = (i * step).toInt().coerceIn(0, src.size - 1)
            result.add(src[idx])
        }
        return result
    }

    private fun drawSeries(
        canvas: Canvas,
        points: List<Float>,
        color: Int,
        minVal: Float,
        maxVal: Float,
        paddingLeft: Float,
        paddingTop: Float,
        plotWidth: Float,
        plotHeight: Float,
        withGradientFill: Boolean
    ) {
        if (points.isEmpty()) return

        val pts = if (points.size > 70) downsample(points, 70) else points

        linePaint.color = color
        linePath.reset()
        fillPath.reset()

        val range = (maxVal - minVal).coerceAtLeast(1f)
        val stepX = plotWidth / (pts.size - 1).coerceAtLeast(1)

        val coords = ArrayList<Pair<Float, Float>>(pts.size)
        for (i in pts.indices) {
            val x = paddingLeft + (i * stepX)
            val v = pts[i].coerceIn(minVal, maxVal)
            val y = paddingTop + plotHeight * (1f - ((v - minVal) / range))
            coords.add(x to y)
        }

        if (coords.size == 1) {
            canvas.drawCircle(coords[0].first, coords[0].second, 2.5f * resources.displayMetrics.density, linePaint)
            return
        }

        linePath.moveTo(coords[0].first, coords[0].second)
        for (i in 0 until coords.size - 1) {
            val p0 = coords[max(0, i - 1)]
            val p1 = coords[i]
            val p2 = coords[i + 1]
            val p3 = coords[min(coords.size - 1, i + 2)]

            val cp1x = p1.first + (p2.first - p0.first) * 0.18f
            val cp1y = p1.second + (p2.second - p0.second) * 0.18f
            val cp2x = p2.first - (p3.first - p1.first) * 0.18f
            val cp2y = p2.second - (p3.second - p1.second) * 0.18f

            linePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.first, p2.second)
        }

        if (withGradientFill) {
            fillPath.addPath(linePath)
            fillPath.lineTo(coords.last().first, paddingTop + plotHeight)
            fillPath.lineTo(coords.first().first, paddingTop + plotHeight)
            fillPath.close()

            val topAlpha = Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
            val botAlpha = Color.argb(2, Color.red(color), Color.green(color), Color.blue(color))
            fillPaint.shader = LinearGradient(0f, paddingTop, 0f, paddingTop + plotHeight, topAlpha, botAlpha, Shader.TileMode.CLAMP)
            canvas.drawPath(fillPath, fillPaint)
        }

        canvas.drawPath(linePath, linePaint)
    }

    private fun drawHighlightPoint(canvas: Canvas, x: Float, s: ThermalSample, minVal: Float, maxVal: Float, paddingTop: Float, plotHeight: Float) {
        val range = (maxVal - minVal).coerceAtLeast(1f)
        val mainVal = when (chartMode) {
            ThermalChartMode.FPS_POWER -> s.fps
            ThermalChartMode.TEMPERATURE -> s.cpuTempC
            ThermalChartMode.USAGE -> s.cpuUsagePercent.toFloat()
            ThermalChartMode.CORES -> {
                val mhz = s.coreFreqs[0]?.toFloat() ?: 0f
                val maxF = samples.maxOfOrNull { it.coreFreqs[0]?.toFloat() ?: 0f } ?: 2400f
                (mhz / maxF * 100f).coerceIn(0f, 100f)
            }
        }
        val y = paddingTop + plotHeight * (1f - ((mainVal.coerceIn(minVal, maxVal) - minVal) / range))

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawCircle(x, y, 4f * resources.displayMetrics.density, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * resources.displayMetrics.density
        p.color = Color.parseColor("#00E5FF")
        canvas.drawCircle(x, y, 4f * resources.displayMetrics.density, p)
    }

    private fun drawTooltip(canvas: Canvas, x: Float, s: ThermalSample, density: Float, w: Float, h: Float) {
        val lines = when (chartMode) {
            ThermalChartMode.FPS_POWER -> listOf(
                "Thời gian: ${s.elapsedSec}s",
                "FPS: ${String.format(Locale.US, "%.1f", s.fps)}",
                "Công suất Chip: ${String.format(Locale.US, "%.1f", s.powerWatts)}W"
            )
            ThermalChartMode.TEMPERATURE -> listOf(
                "Thời gian: ${s.elapsedSec}s",
                "CPU: ${s.cpuTempC.toInt()}°C",
                "GPU: ${s.gpuTempC.toInt()}°C",
                "Pin: ${String.format(Locale.US, "%.1f", s.batTempC)}°C"
            )
            ThermalChartMode.USAGE -> listOf(
                "Thời gian: ${s.elapsedSec}s",
                "CPU: ${s.cpuUsagePercent}%",
                "GPU: ${s.gpuUsagePercent}%",
                "Pin: ${s.batPercent}%"
            )
            ThermalChartMode.CORES -> {
                val list = mutableListOf("Thời gian: ${s.elapsedSec}s")
                val coreCount = samples.firstOrNull()?.coreFreqs?.size ?: 0
                val coreMaxFreqMap = mutableMapOf<Int, Float>()
                for (c in 0 until coreCount) {
                    val maxValFound = samples.maxOfOrNull { it.coreFreqs[c]?.toFloat() ?: 0f } ?: 2000f
                    coreMaxFreqMap[c] = maxValFound.coerceAtLeast(1800f)
                }

                val littleCores = (0..min(3, coreCount - 1)).toList()
                val avgLittle = if (littleCores.isNotEmpty()) {
                    littleCores.map { c ->
                        val mhz = s.coreFreqs[c]?.toFloat() ?: 0f
                        val maxF = coreMaxFreqMap[c] ?: 2400f
                        (mhz / maxF * 100f).coerceIn(0f, 100f)
                    }.average().toInt()
                } else 0
                list.add("Little (C0-C3): $avgLittle%")

                val bigCores = if (coreCount >= 7) (4..min(6, coreCount - 1)).toList() else if (coreCount > 4) (4 until coreCount).toList() else emptyList()
                if (bigCores.isNotEmpty()) {
                    val avgBig = bigCores.map { c ->
                        val mhz = s.coreFreqs[c]?.toFloat() ?: 0f
                        val maxF = coreMaxFreqMap[c] ?: 2400f
                        (mhz / maxF * 100f).coerceIn(0f, 100f)
                    }.average().toInt()
                    list.add("Big (C4-C6): $avgBig%")
                }

                val primeCoreIdx = if (coreCount >= 8) 7 else if (coreCount > 1) coreCount - 1 else -1
                if (primeCoreIdx >= 0) {
                    val mhz = s.coreFreqs[primeCoreIdx]?.toFloat() ?: 0f
                    val maxF = coreMaxFreqMap[primeCoreIdx] ?: 2400f
                    val primePct = (mhz / maxF * 100f).toInt().coerceIn(0, 100)
                    list.add("Prime (C7): $primePct% (${mhz.toInt()}MHz)")
                }
                list
            }
        }

        var maxLineWidth = 0f
        for (l in lines) {
            val lw = tooltipTextPaint.measureText(l)
            if (lw > maxLineWidth) maxLineWidth = lw
        }

        val boxWidth = maxLineWidth + 20f * density
        val lineHeight = 14f * density
        val boxHeight = (lines.size * lineHeight) + 12f * density

        var boxX = x + 10f * density
        if (boxX + boxWidth > w - 8f * density) {
            boxX = x - boxWidth - 10f * density
        }
        val boxY = (16f * density).coerceAtLeast(8f * density)

        val rect = RectF(boxX, boxY, boxX + boxWidth, boxY + boxHeight)
        canvas.drawRoundRect(rect, 8f * density, 8f * density, tooltipBgPaint)
        canvas.drawRoundRect(rect, 8f * density, 8f * density, tooltipStrokePaint)

        var textY = boxY + 12f * density
        for (l in lines) {
            canvas.drawText(l, boxX + 10f * density, textY, tooltipTextPaint)
            textY += lineHeight
        }
    }

    private fun getMinMaxUnit(): Triple<Float, Float, String> {
        return when (chartMode) {
            ThermalChartMode.FPS_POWER -> {
                val maxFps = samples.maxOfOrNull { it.fps } ?: 60f
                val maxP = samples.maxOfOrNull { it.powerWatts } ?: 10f
                val max = max(maxFps, maxP * 4f).coerceAtLeast(60f)
                Triple(0f, max, "")
            }
            ThermalChartMode.TEMPERATURE -> {
                val maxT = samples.maxOfOrNull { maxOf(it.cpuTempC, it.gpuTempC, it.batTempC) } ?: 55f
                val minT = (samples.minOfOrNull { minOf(it.cpuTempC, it.gpuTempC, it.batTempC) } ?: 25f).coerceAtLeast(20f)
                Triple((minT - 5f).coerceAtLeast(15f), (maxT + 8f).coerceAtLeast(60f), "°")
            }
            ThermalChartMode.USAGE -> {
                Triple(0f, 100f, "%")
            }
            ThermalChartMode.CORES -> {
                Triple(0f, 100f, "%")
            }
        }
    }

    private fun formatSec(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "${m}m${s}s" else "${s}s"
    }
}

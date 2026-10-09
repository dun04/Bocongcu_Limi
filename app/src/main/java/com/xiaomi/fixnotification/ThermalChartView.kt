package com.xiaomi.fixnotification

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class ThermalChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.35f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * resources.displayMetrics.density
        color = Color.parseColor("#30808080")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * resources.displayMetrics.scaledDensity
        color = Color.parseColor("#8E8E93")
    }

    private val dataPoints = mutableListOf<Float>()
    private var maxCapacity = 180
    private var minVal = 0f
    private var maxVal = 100f
    private var isCpuTheme = false
    private var showGridLabels = true
    private var customColor: Int? = null
    private var unitStr: String = ""

    private val linePath = Path()
    private val fillPath = Path()

    fun setChartConfig(
        min: Float = 0f,
        max: Float = 100f,
        capacity: Int = 180,
        isCpu: Boolean = false,
        showLabels: Boolean = true,
        customColor: Int? = null,
        unit: String = ""
    ) {
        this.minVal = min
        this.maxVal = max
        this.maxCapacity = capacity
        this.isCpuTheme = isCpu
        this.showGridLabels = showLabels
        this.customColor = customColor
        this.unitStr = unit
        invalidate()
    }

    fun addDataPoint(value: Float) {
        if (dataPoints.size >= maxCapacity) {
            dataPoints.removeAt(0)
        }
        dataPoints.add(value)
        invalidate()
    }

    fun setDataPoints(points: List<Float>) {
        dataPoints.clear()
        val takeCount = points.takeLast(maxCapacity)
        dataPoints.addAll(takeCount)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val paddingLeft = if (showGridLabels) 36f * density else 4f * density
        val paddingRight = 8f * density
        val paddingTop = 10f * density
        val paddingBottom = if (showGridLabels) 18f * density else 4f * density

        val plotWidth = w - paddingLeft - paddingRight
        val plotHeight = h - paddingTop - paddingBottom

        if (plotWidth <= 0f || plotHeight <= 0f) return

        val gridLines = 3
        for (i in 0..gridLines) {
            val ratio = i.toFloat() / gridLines
            val y = paddingTop + plotHeight * (1f - ratio)
            canvas.drawLine(paddingLeft, y, w - paddingRight, y, gridPaint)

            if (showGridLabels) {
                val labelVal = (minVal + (maxVal - minVal) * ratio).toInt()
                val labelStr = if (unitStr.isNotEmpty()) "$labelVal$unitStr" else if (maxVal <= 120f) "${labelVal}°C" else "$labelVal"
                canvas.drawText(labelStr, 4f * density, y + 3.5f * density, textPaint)
            }
        }

        if (showGridLabels) {
            val midX = paddingLeft + plotWidth / 2f

            canvas.drawLine(midX, paddingTop, midX, paddingTop + plotHeight, gridPaint)

            canvas.drawText("3 phút", paddingLeft, h - 3f * density, textPaint)
            val midLabel = "1.5 phút"
            val midW = textPaint.measureText(midLabel)
            canvas.drawText(midLabel, midX - midW / 2f, h - 3f * density, textPaint)

            val rightLabel = "Hiện tại"
            val textWidth = textPaint.measureText(rightLabel)
            canvas.drawText(rightLabel, w - paddingRight - textWidth, h - 3f * density, textPaint)
        }

        if (dataPoints.isEmpty()) return

        val strokeColor = customColor ?: if (isCpuTheme) Color.parseColor("#FF8A00") else Color.parseColor("#26C6DA")
        val topFillColor = customColor?.let { Color.argb(140, Color.red(it), Color.green(it), Color.blue(it)) }
            ?: if (isCpuTheme) Color.parseColor("#90FF8A00") else Color.parseColor("#9026C6DA")
        val bottomFillColor = customColor?.let { Color.argb(10, Color.red(it), Color.green(it), Color.blue(it)) }
            ?: if (isCpuTheme) Color.parseColor("#08FF8A00") else Color.parseColor("#0826C6DA")

        linePaint.color = strokeColor
        fillPaint.shader = LinearGradient(
            0f, paddingTop,
            0f, paddingTop + plotHeight,
            topFillColor, bottomFillColor,
            Shader.TileMode.CLAMP
        )

        linePath.reset()
        fillPath.reset()

        val stepX = plotWidth / (maxCapacity - 1).coerceAtLeast(1)
        val startOffset = (maxCapacity - dataPoints.size) * stepX

        val pointsCoords = mutableListOf<Pair<Float, Float>>()
        for (i in dataPoints.indices) {
            val x = paddingLeft + startOffset + (i * stepX)
            val v = dataPoints[i].coerceIn(minVal, maxVal)
            val norm = ((v - minVal) / (maxVal - minVal).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val y = paddingTop + plotHeight * (1f - norm)
            pointsCoords.add(Pair(x, y))
        }

        if (pointsCoords.isNotEmpty()) {
            linePath.moveTo(pointsCoords[0].first, pointsCoords[0].second)
            fillPath.moveTo(pointsCoords[0].first, paddingTop + plotHeight)
            fillPath.lineTo(pointsCoords[0].first, pointsCoords[0].second)

            for (i in 0 until pointsCoords.size - 1) {
                val p0 = pointsCoords[i]
                val p1 = pointsCoords[i + 1]
                val cx = (p0.first + p1.first) / 2f
                linePath.cubicTo(cx, p0.second, cx, p1.second, p1.first, p1.second)
                fillPath.cubicTo(cx, p0.second, cx, p1.second, p1.first, p1.second)
            }

            val lastPoint = pointsCoords.last()
            fillPath.lineTo(lastPoint.first, paddingTop + plotHeight)
            fillPath.close()

            canvas.drawPath(fillPath, fillPaint)

            canvas.drawPath(linePath, linePaint)

            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = strokeColor
                style = Paint.Style.FILL
            }
            canvas.drawCircle(lastPoint.first, lastPoint.second, 3.5f * resources.displayMetrics.density, dotPaint)
        }
    }
}

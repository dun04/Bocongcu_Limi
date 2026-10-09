package com.xiaomi.fixnotification

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class GpuChipGraphicView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var temperatureC: Int = 45
    private var usagePercent: Int = 20

    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val outerFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val innerCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val subCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D1D5DB")
        textAlign = Paint.Align.CENTER
    }

    private val outerRect = RectF()
    private val innerRect = RectF()
    private val subCoreRect = RectF()

    fun setGpuData(temp: Int, usage: Int = usagePercent) {
        this.temperatureC = temp
        this.usagePercent = usage
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val isHot = temperatureC >= 50
        val baseColor = if (isHot) Color.parseColor("#EF4444") else Color.parseColor("#2979FF")
        val outerColor = if (isHot) Color.parseColor("#80EF4444") else Color.parseColor("#802979FF")
        val subCoreColor = if (isHot) Color.parseColor("#40EF4444") else Color.parseColor("#302979FF")

        pinPaint.color = baseColor
        outerFramePaint.color = outerColor
        innerCorePaint.color = baseColor
        subCorePaint.color = subCoreColor

        val pinLength = w * 0.10f
        val pinWidth = w * 0.07f
        pinPaint.strokeWidth = pinWidth

        val chipLeft = pinLength
        val chipTop = pinLength
        val chipRight = w - pinLength
        val chipBottom = h - pinLength

        val stepX = (chipRight - chipLeft) / 5f
        for (i in 1..4) {
            val px = chipLeft + i * stepX

            canvas.drawLine(px, pinWidth / 2f, px, chipTop, pinPaint)

            canvas.drawLine(px, chipBottom, px, h - pinWidth / 2f, pinPaint)
        }

        val stepY = (chipBottom - chipTop) / 5f
        for (i in 1..4) {
            val py = chipTop + i * stepY

            canvas.drawLine(pinWidth / 2f, py, chipLeft, py, pinPaint)

            canvas.drawLine(chipRight, py, w - pinWidth / 2f, py, pinPaint)
        }

        val outerCorner = 8f * resources.displayMetrics.density
        outerRect.set(chipLeft, chipTop, chipRight, chipBottom)
        canvas.drawRoundRect(outerRect, outerCorner, outerCorner, outerFramePaint)

        val innerPadding = w * 0.05f
        val innerLeft = chipLeft + innerPadding
        val innerTop = chipTop + innerPadding
        val innerRight = chipRight - innerPadding
        val innerBottom = chipBottom - innerPadding
        val coreW = (innerRight - innerLeft - 4f * resources.displayMetrics.density) / 2f
        val coreH = (innerBottom - innerTop - 4f * resources.displayMetrics.density) / 2f
        val gap = 4f * resources.displayMetrics.density

        for (row in 0..1) {
            for (col in 0..1) {
                val cl = innerLeft + col * (coreW + gap)
                val ct = innerTop + row * (coreH + gap)
                subCoreRect.set(cl, ct, cl + coreW, ct + coreH)
                canvas.drawRoundRect(subCoreRect, 4f * resources.displayMetrics.density, 4f * resources.displayMetrics.density, subCorePaint)
            }
        }

        val centerPadding = w * 0.12f
        val innerCorner = 6f * resources.displayMetrics.density
        innerRect.set(chipLeft + centerPadding, chipTop + centerPadding, chipRight - centerPadding, chipBottom - centerPadding)
        canvas.drawRoundRect(innerRect, innerCorner, innerCorner, innerCorePaint)

        textPaint.textSize = (chipRight - chipLeft) * 0.26f
        val textY = (chipTop + chipBottom) / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("${temperatureC}°C", w / 2f, textY, textPaint)
    }
}

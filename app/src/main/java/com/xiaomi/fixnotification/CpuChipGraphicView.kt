package com.xiaomi.fixnotification

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CpuChipGraphicView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var temperatureC: Int = 47

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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val outerRect = RectF()
    private val innerRect = RectF()

    fun setCpuTemperature(temp: Int) {
        this.temperatureC = temp
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Quy tắc màu theo yêu cầu của người dùng:
        // >= 50°C: Đỏ (#EF4444)
        // < 50°C: Xanh dương chuẩn ảnh mẫu (#4285F4)
        val isHot = temperatureC >= 50
        val baseColor = if (isHot) Color.parseColor("#EF4444") else Color.parseColor("#4285F4")
        val outerColor = if (isHot) Color.parseColor("#80EF4444") else Color.parseColor("#804285F4")

        pinPaint.color = baseColor
        outerFramePaint.color = outerColor
        innerCorePaint.color = baseColor

        val pinLength = w * 0.12f
        val pinWidth = w * 0.08f
        pinPaint.strokeWidth = pinWidth

        val chipLeft = pinLength
        val chipTop = pinLength
        val chipRight = w - pinLength
        val chipBottom = h - pinLength

        // 1. Vẽ 12 chân cắm vi mạch (3 trên, 3 dưới, 3 trái, 3 phải)
        val stepX = (chipRight - chipLeft) / 4f
        for (i in 1..3) {
            val px = chipLeft + i * stepX
            // Top pins
            canvas.drawLine(px, 0f + pinWidth / 2f, px, chipTop, pinPaint)
            // Bottom pins
            canvas.drawLine(px, chipBottom, px, h - pinWidth / 2f, pinPaint)
        }

        val stepY = (chipBottom - chipTop) / 4f
        for (i in 1..3) {
            val py = chipTop + i * stepY
            // Left pins
            canvas.drawLine(0f + pinWidth / 2f, py, chipLeft, py, pinPaint)
            // Right pins
            canvas.drawLine(chipRight, py, w - pinWidth / 2f, py, pinPaint)
        }

        // 2. Vẽ khung ngoài bo góc (Outer Frame)
        val outerCorner = 8f * resources.displayMetrics.density
        outerRect.set(chipLeft, chipTop, chipRight, chipBottom)
        canvas.drawRoundRect(outerRect, outerCorner, outerCorner, outerFramePaint)

        // 3. Vẽ lõi chip trung tâm (Inner Core)
        val innerPadding = w * 0.06f
        val innerCorner = 6f * resources.displayMetrics.density
        innerRect.set(chipLeft + innerPadding, chipTop + innerPadding, chipRight - innerPadding, chipBottom - innerPadding)
        canvas.drawRoundRect(innerRect, innerCorner, innerCorner, innerCorePaint)

        // 4. Vẽ nhiệt độ CPU chính giữa
        textPaint.textSize = (chipRight - chipLeft) * 0.32f
        val textY = (chipTop + chipBottom) / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("${temperatureC}°C", w / 2f, textY, textPaint)
    }
}

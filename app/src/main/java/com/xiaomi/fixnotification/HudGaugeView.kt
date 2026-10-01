package com.xiaomi.fixnotification

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Biểu đồ hình tròn đo mức sử dụng (%) siêu nhỏ gọn dành riêng cho màn HUD nổi
 */
class HudGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var percent: Int = 0
    private var gaugeColor: Int = Color.parseColor("#00E5FF")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#26FFFFFF")
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = Color.WHITE
    }

    private val arcBounds = RectF()
    private var textY = 0f

    init {
        val strokeW = 2.2f * resources.displayMetrics.density
        trackPaint.strokeWidth = strokeW
        progressPaint.strokeWidth = strokeW
    }

    fun setProgress(progress: Int, color: Int) {
        val clamped = progress.coerceIn(0, 100)
        if (this.percent == clamped && this.gaugeColor == color) return
        this.percent = clamped
        this.gaugeColor = color
        progressPaint.color = color
        textPaint.color = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val strokeW = trackPaint.strokeWidth
        val pad = strokeW / 2f + 1f
        arcBounds.set(pad, pad, w - pad, h - pad)
        textPaint.textSize = w * 0.32f
        textY = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        if (w <= 0f || height <= 0) return

        // 1. Vẽ vòng tròn nền mờ
        canvas.drawOval(arcBounds, trackPaint)

        // 2. Vẽ vòng cung phần trăm sử dụng (từ đỉnh 12h: -90 độ)
        if (percent > 0) {
            val sweepAngle = (percent / 100f) * 360f
            canvas.drawArc(arcBounds, -90f, sweepAngle, false, progressPaint)
        }

        // 3. Vẽ số % ở chính giữa biểu đồ tròn
        canvas.drawText("${percent}%", w / 2f, textY, textPaint)
    }
}

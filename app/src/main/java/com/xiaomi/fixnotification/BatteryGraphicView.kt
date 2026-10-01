package com.xiaomi.fixnotification

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class BatteryGraphicView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var percent: Int = 96
    private var isCharging: Boolean = false

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val bodyRect = RectF()
    private val capRect = RectF()
    private val highlightRect = RectF()

    fun setBatteryPercent(level: Int, charging: Boolean = false) {
        this.percent = level.coerceIn(0, 100)
        this.isCharging = charging
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Quy tắc màu theo yêu cầu của người dùng:
        // - Khi đang sạc pin: Xanh lục (#10B981)
        // - Khi không sạc:
        //    + >= 50%: Xanh lam (#4285F4)
        //    + < 50% (21..49%): Vàng (#F59E0B)
        //    + <= 20%: Đỏ (#EF4444)
        val mainColor = when {
            isCharging -> Color.parseColor("#10B981")    // Xanh lục chỉ khi sạc pin
            percent <= 20 -> Color.parseColor("#EF4444") // Đỏ
            percent < 50 -> Color.parseColor("#F59E0B")  // Vàng
            else -> Color.parseColor("#4285F4")          // Xanh lam chuẩn ảnh mẫu
        }

        bodyPaint.color = mainColor
        capPaint.color = mainColor
        highlightPaint.color = Color.argb(60, 255, 255, 255)

        val capWidth = w * 0.42f
        val capHeight = h * 0.08f
        val capCorner = 2f * resources.displayMetrics.density

        val bodyTop = capHeight + 1f * resources.displayMetrics.density
        val bodyCorner = 4f * resources.displayMetrics.density

        // 1. Vẽ chóp cực dương Pin (Top Cap)
        capRect.set((w - capWidth) / 2f, 0f, (w + capWidth) / 2f, capHeight)
        canvas.drawRoundRect(capRect, capCorner, capCorner, capPaint)

        // 2. Vẽ thân Pin hình trụ (Main Body)
        bodyRect.set(0f, bodyTop, w, h)
        canvas.drawRoundRect(bodyRect, bodyCorner, bodyCorner, bodyPaint)

        // 3. Vạch viền sáng mờ phía trên thân pin
        highlightRect.set(2f * resources.displayMetrics.density, bodyTop + 2f * resources.displayMetrics.density, w - 2f * resources.displayMetrics.density, bodyTop + 5f * resources.displayMetrics.density)
        canvas.drawRoundRect(highlightRect, 2f * resources.displayMetrics.density, 2f * resources.displayMetrics.density, highlightPaint)

        // 4. Vẽ số phần trăm pin chính giữa
        textPaint.textSize = w * 0.36f
        val textY = bodyTop + (h - bodyTop) / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("$percent%", w / 2f, textY, textPaint)
    }
}

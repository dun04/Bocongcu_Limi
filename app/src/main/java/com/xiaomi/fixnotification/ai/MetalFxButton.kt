package com.xiaomi.fixnotification.ai

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.xiaomi.fixnotification.R

class MetalFxButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var isStopMode = false
        set(value) {
            if (field != value) {
                field = value
                lastWidth = 0
                lastHeight = 0
                invalidate()
            }
        }

    // Touch feedback
    private var scaleFactor = 1f
    private val density = resources.displayMetrics.density

    // Paths in 24x24 coordinate system
    private val rawSendPath = Path().apply {
        // Outer paper plane triangle
        moveTo(22f, 2f)
        lineTo(15f, 22f)
        lineTo(11f, 13f)
        lineTo(2f, 9f)
        close()
        // Center crease
        moveTo(22f, 2f)
        lineTo(11f, 13f)
    }

    private val rawStopPath = Path().apply {
        val rect = RectF(4.5f, 4.5f, 19.5f, 19.5f)
        addRoundRect(rect, 2.5f, 2.5f, Path.Direction.CW)
    }

    private val transformedSendPath = Path()
    private val transformedStopPath = Path()

    private var lastWidth = 0
    private var lastHeight = 0

    // Paints
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val startTime = SystemClock.uptimeMillis()
    private val shaderMatrix = Matrix()

    private var sendSweepShader: SweepGradient? = null
    private var stopSweepShader: SweepGradient? = null
    private var glowSendShader: SweepGradient? = null
    private var glowStopShader: SweepGradient? = null

    init {
        isClickable = true
        isFocusable = true
    }

    private fun isDarkMode(): Boolean {
        return (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun updatePaths(w: Int, h: Int) {
        lastWidth = w
        lastHeight = h

        val cx = w / 2f
        val cy = h / 2f
        val iconSize = 20f * density
        val scale = iconSize / 24f

        val sendMatrix = Matrix().apply {
            postScale(scale, scale)
            // Optical center alignment
            postTranslate(cx - iconSize / 2f - 0.5f * density, cy - iconSize / 2f + 0.5f * density)
        }
        rawSendPath.transform(sendMatrix, transformedSendPath)

        val stopMatrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(cx - iconSize / 2f, cy - iconSize / 2f)
        }
        rawStopPath.transform(stopMatrix, transformedStopPath)

        // Colors for Send mode (Vibrant Xiaomi Amber-Orange to Coral to Electric White tip)
        val sendColors = intArrayOf(
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            Color.parseColor("#FF6900"), // Brand Orange
            Color.parseColor("#FFA000"), // Amber Gold
            Color.parseColor("#FF5252"), // Coral
            Color.parseColor("#FFFFFF")  // Electric White Laser Tip
        )
        val sendGlowColors = intArrayOf(
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            Color.parseColor("#44FF6900"),
            Color.parseColor("#66FFA000"),
            Color.parseColor("#88FF5252"),
            Color.parseColor("#AAFFFFFF")
        )

        // Colors for Stop mode (Fiery Crimson Red to Electric White tip)
        val stopColors = intArrayOf(
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            Color.parseColor("#991B1B"), // Deep Crimson
            Color.parseColor("#DC2626"), // Vivid Red
            Color.parseColor("#EF4444"), // Neon Red
            Color.parseColor("#FFFFFF")  // Electric White Laser Tip
        )
        val stopGlowColors = intArrayOf(
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            Color.parseColor("#44991B1B"),
            Color.parseColor("#66DC2626"),
            Color.parseColor("#88EF4444"),
            Color.parseColor("#AAFFFFFF")
        )

        val positions = floatArrayOf(0f, 0.45f, 0.65f, 0.80f, 0.92f, 1f)

        sendSweepShader = SweepGradient(cx, cy, sendColors, positions)
        glowSendShader = SweepGradient(cx, cy, sendGlowColors, positions)

        stopSweepShader = SweepGradient(cx, cy, stopColors, positions)
        glowStopShader = SweepGradient(cx, cy, stopGlowColors, positions)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        if (width != lastWidth || height != lastHeight) {
            updatePaths(width, height)
        }

        val cx = width / 2f
        val cy = height / 2f

        canvas.save()
        canvas.scale(scaleFactor, scaleFactor, cx, cy)

        val isDark = isDarkMode()
        val currentPath = if (isStopMode) transformedStopPath else transformedSendPath

        // 1. Base outline: ensures icon structure is always recognizable with subtle elegance
        val baseColor = if (isStopMode) {
            ContextCompat.getColor(context, R.color.accent_red)
        } else {
            if (isDark) Color.WHITE else ContextCompat.getColor(context, R.color.primary)
        }
        val baseAlpha = if (isDark) 90 else 120
        basePaint.color = Color.argb(baseAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
        canvas.drawPath(currentPath, basePaint)

        // 2. Animated laser beam running along icon contour
        val elapsed = SystemClock.uptimeMillis() - startTime
        val progress = (elapsed % 2400L).toFloat() / 2400f
        val angle = progress * 360f

        shaderMatrix.setRotate(angle, cx, cy)

        val coreShader = if (isStopMode) stopSweepShader else sendSweepShader
        val glowShader = if (isStopMode) glowStopShader else glowSendShader

        coreShader?.setLocalMatrix(shaderMatrix)
        glowShader?.setLocalMatrix(shaderMatrix)

        glowPaint.shader = glowShader
        canvas.drawPath(currentPath, glowPaint)

        laserPaint.shader = coreShader
        canvas.drawPath(currentPath, laserPaint)

        canvas.restore()

        if (visibility == View.VISIBLE && isAttachedToWindow) {
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isClickable || !isEnabled) return super.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(150)
                    .setInterpolator(OvershootInterpolator()).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(150)
                    .setInterpolator(OvershootInterpolator()).start()
            }
        }
        return super.onTouchEvent(event)
    }
}

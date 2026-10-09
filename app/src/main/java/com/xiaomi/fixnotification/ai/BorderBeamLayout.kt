package com.xiaomi.fixnotification.ai

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.*

/**
 * Native Android implementation of BorderBeam from Libraries.dev (border-beam).
 * Wraps any layout or card and rides an animated multi-colored rainbow glow beam around its border.
 * Features:
 * - Subtle underlying border track
 * - Flawless multi-stop neon iridescent color gradient (SweepGradient)
 * - Ambient bloom glow + crisp laser core
 * - Zero external dependencies, hardware accelerated 60-120fps.
 */
class BorderBeamLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class ColorVariant {
        COLORFUL,
        OCEAN,
        SUNSET,
        ICE,
        MONO,
        AMBER_ORANGE,
        CRIMSON
    }

    var colorVariant: ColorVariant = ColorVariant.COLORFUL
        set(value) {
            field = value
            lastW = 0f
            invalidate()
        }

    var beamBorderRadius: Float = 18f * resources.displayMetrics.density
        set(value) {
            field = value
            invalidate()
        }

    var beamStrokeWidth: Float = 2.2f * resources.displayMetrics.density
        set(value) {
            field = value
            corePaint.strokeWidth = value
            glowPaint.strokeWidth = value * 4f
            invalidate()
        }

    var beamLengthFraction: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.1f, 0.9f)
            lastW = 0f
            invalidate()
        }

    var beamDurationMs: Long = 4000L
        set(value) {
            field = max(1000L, value)
            invalidate()
        }

    var strength: Float = 0.85f
        set(value) {
            field = value.coerceIn(0f, 1f)
            lastW = 0f
            invalidate()
        }

    var active: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    postInvalidateOnAnimation()
                } else {
                    invalidate()
                }
            }
        }

    private val density = resources.displayMetrics.density

    // Background & track paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // color is set dynamically
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#25FFFFFF") // Subtle translucent border track
    }

    // Beam paints
    private val rectF = RectF()

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val startTime = SystemClock.uptimeMillis()
    
    private var lastW = 0f
    private var lastH = 0f
    private var coreShader: Shader? = null
    private var glowShader: Shader? = null
    private val shaderMatrix = Matrix()

    private val clipPath = Path()

    init {
        setWillNotDraw(false)
        corePaint.strokeWidth = beamStrokeWidth
        glowPaint.strokeWidth = beamStrokeWidth * 4f // Wider ambient glow
        updateThemeColors()
    }

    private fun isDarkMode(): Boolean {
        return (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun updateThemeColors() {
        val isDark = isDarkMode()
        bgPaint.color = if (isDark) Color.parseColor("#E60F172A") else Color.parseColor("#E6FFFFFF")
        invalidate()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateThemeColors()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE && active) {
            postInvalidateOnAnimation()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE && active) {
            postInvalidateOnAnimation()
        }
    }

    private fun updateShaders(w: Float, h: Float) {
        lastW = w
        lastH = h
        
        clipPath.reset()
        clipPath.addRoundRect(rectF, beamBorderRadius, beamBorderRadius, Path.Direction.CW)
        
        val baseColors = getColorPalette(colorVariant)
        val numColors = baseColors.size
        val colorsCount = 2 + numColors
        
        val coreColors = IntArray(colorsCount)
        val glowColors = IntArray(colorsCount)
        val positions = FloatArray(colorsCount)
        
        // Start transparent part
        coreColors[0] = Color.TRANSPARENT
        glowColors[0] = Color.TRANSPARENT
        positions[0] = 0f
        
        coreColors[1] = Color.TRANSPARENT
        glowColors[1] = Color.TRANSPARENT
        positions[1] = 1f - beamLengthFraction
        
        // Beam part
        for (i in 0 until numColors) {
            val idx = 2 + i
            val fraction = i.toFloat() / (numColors - 1).coerceAtLeast(1) // 0f to 1f
            positions[idx] = (1f - beamLengthFraction) + fraction * beamLengthFraction
            
            val baseColor = baseColors[i]
            
            val alphaFactor = (fraction.pow(1.8f) * strength).coerceIn(0f, 1f)
            val alphaByte = (alphaFactor * 255f).roundToInt()
            
            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)
            
            coreColors[idx] = Color.argb(alphaByte, r, g, b)
            
            val glowAlpha = (alphaByte * 0.35f).roundToInt().coerceIn(0, 255)
            glowColors[idx] = Color.argb(glowAlpha, r, g, b)
        }
        
        coreShader = SweepGradient(w / 2f, h / 2f, coreColors, positions)
        glowShader = SweepGradient(w / 2f, h / 2f, glowColors, positions)
        
        corePaint.shader = coreShader
        glowPaint.shader = glowShader
    }

    override fun draw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0 && h > 0) {
            val halfStroke = beamStrokeWidth / 2f
            rectF.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)

            // 1. Draw sleek frosted capsule background
            canvas.drawRoundRect(rectF, beamBorderRadius, beamBorderRadius, bgPaint)

            // 2. Draw subtle border track underneath
            canvas.drawRoundRect(rectF, beamBorderRadius, beamBorderRadius, trackPaint)
        }

        // 3. Draw child views
        super.draw(canvas)

        // 4. Draw riding border beam on top of border
        if (!active || w <= 0 || h <= 0) return

        if (w != lastW || h != lastH) {
            updateShaders(w, h)
        }

        val elapsed = SystemClock.uptimeMillis() - startTime
        val progress = (elapsed % beamDurationMs).toFloat() / beamDurationMs.toFloat()
        
        // Rotate shader
        shaderMatrix.setRotate(360f * progress, w / 2f, h / 2f)
        coreShader?.setLocalMatrix(shaderMatrix)
        glowShader?.setLocalMatrix(shaderMatrix)

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRoundRect(rectF, beamBorderRadius, beamBorderRadius, glowPaint)
        canvas.restore()

        canvas.drawRoundRect(rectF, beamBorderRadius, beamBorderRadius, corePaint)

        if (visibility == View.VISIBLE && isAttachedToWindow && active) {
            postInvalidateOnAnimation()
        }
    }

    private fun getColorPalette(variant: ColorVariant): IntArray {
        return when (variant) {
            ColorVariant.COLORFUL -> intArrayOf(
                Color.parseColor("#ffaa40"), // Vibrant Orange
                Color.parseColor("#f9a8d4"), // Pink
                Color.parseColor("#9c40ff"), // Purple
                Color.parseColor("#38bdf8"), // Cyan/Blue
                Color.parseColor("#fde047"), // Yellow
                Color.parseColor("#FFFFFF")  // Blazing White tip
            )
            ColorVariant.OCEAN -> intArrayOf(
                Color.parseColor("#0369A1"),
                Color.parseColor("#0EA5E9"),
                Color.parseColor("#38BDF8"),
                Color.parseColor("#7DD3FC"),
                Color.parseColor("#FFFFFF")
            )
            ColorVariant.SUNSET -> intArrayOf(
                Color.parseColor("#E11D48"),
                Color.parseColor("#EA580C"),
                Color.parseColor("#F59E0B"),
                Color.parseColor("#FDE047"),
                Color.parseColor("#FFFFFF")
            )
            ColorVariant.ICE -> intArrayOf(
                Color.parseColor("#0891B2"),
                Color.parseColor("#06B6D4"),
                Color.parseColor("#22D3EE"),
                Color.parseColor("#E0F2FE"),
                Color.parseColor("#FFFFFF")
            )
            ColorVariant.MONO -> intArrayOf(
                Color.parseColor("#475569"),
                Color.parseColor("#94A3B8"),
                Color.parseColor("#E2E8F0"),
                Color.parseColor("#FFFFFF")
            )
            ColorVariant.AMBER_ORANGE -> intArrayOf(
                Color.parseColor("#EA580C"), // Vibrant Xiaomi Orange
                Color.parseColor("#F59E0B"), // Amber Gold
                Color.parseColor("#FB923C"), // Bright Coral
                Color.parseColor("#FDE047"), // Warm Yellow
                Color.parseColor("#FFFFFF")  // Blazing White tip
            )
            ColorVariant.CRIMSON -> intArrayOf(
                Color.parseColor("#991B1B"), // Deep Crimson
                Color.parseColor("#DC2626"), // Bright Red
                Color.parseColor("#EF4444"), // Vivid Neon Red
                Color.parseColor("#FCA5A5"), // Soft Coral Pink
                Color.parseColor("#FFFFFF")  // Blazing White tip
            )
        }
    }
}

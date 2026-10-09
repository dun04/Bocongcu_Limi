package com.xiaomi.fixnotification

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatDelegate

object ThemeUtils {
    const val PREF_NAME = "app_theme_prefs"
    const val KEY_THEME_MODE = "key_theme_mode"
    const val KEY_ENABLE_DYNAMIC_BG = "key_enable_dynamic_bg"

    const val THEME_SYSTEM = "system"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    fun applySavedTheme(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_THEME_MODE, THEME_DARK) ?: THEME_DARK
        applyThemeMode(mode)
    }

    fun getSavedTheme(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_THEME_MODE, THEME_DARK) ?: THEME_DARK
    }

    fun setThemeMode(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
        applyThemeMode(mode)
    }

    fun isDynamicBackgroundEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLE_DYNAMIC_BG, true)
    }

    fun setDynamicBackgroundEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLE_DYNAMIC_BG, enabled).apply()
    }

    private fun applyThemeMode(mode: String) {
        when (mode) {
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun attachAuroraBackground(view: View, cornerRadiusDp: Float = 0f): ValueAnimator? {
        val resources = view.resources
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val radiusPx = cornerRadiusDp * resources.displayMetrics.density

        if (!isDynamicBackgroundEnabled(view.context)) {
            val defaultBgColor = if (isDarkMode) Color.parseColor("#0B0C10") else Color.parseColor("#F4F5F9")
            if (radiusPx > 0f) {
                view.background = GradientDrawable().apply {
                    setColor(defaultBgColor)
                    cornerRadius = radiusPx
                }
            } else {
                view.setBackgroundColor(defaultBgColor)
            }
            return null
        }

        val bgColorsDark = arrayOf(
            intArrayOf(Color.parseColor("#09101F"), Color.parseColor("#0E2A47"), Color.parseColor("#151833")),
            intArrayOf(Color.parseColor("#130E2E"), Color.parseColor("#29154C"), Color.parseColor("#0A2138")),
            intArrayOf(Color.parseColor("#0B2228"), Color.parseColor("#14382F"), Color.parseColor("#2A173E")),
            intArrayOf(Color.parseColor("#1C1322"), Color.parseColor("#3B1B29"), Color.parseColor("#0E1D36")),
            intArrayOf(Color.parseColor("#09101F"), Color.parseColor("#0E2A47"), Color.parseColor("#151833"))
        )
        val bgColorsLight = arrayOf(
            intArrayOf(Color.parseColor("#F4F6FB"), Color.parseColor("#E0F2FE"), Color.parseColor("#EDE9FE")),
            intArrayOf(Color.parseColor("#F8FAFC"), Color.parseColor("#FCE7F3"), Color.parseColor("#E0F2FE")),
            intArrayOf(Color.parseColor("#F1F5F9"), Color.parseColor("#DCFCE7"), Color.parseColor("#E0E7FF")),
            intArrayOf(Color.parseColor("#FAFAF9"), Color.parseColor("#FEF3C7"), Color.parseColor("#F3E8FF")),
            intArrayOf(Color.parseColor("#F4F6FB"), Color.parseColor("#E0F2FE"), Color.parseColor("#EDE9FE"))
        )
        val colorKeyframes = if (isDarkMode) bgColorsDark else bgColorsLight

        val argbEvaluator = ArgbEvaluator()

        val animator = ValueAnimator.ofFloat(0f, (colorKeyframes.size - 1).toFloat()).apply {
            duration = 14000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                if (!view.isAttachedToWindow) return@addUpdateListener
                val progress = anim.animatedValue as Float
                val fromIndex = progress.toInt().coerceIn(0, colorKeyframes.size - 2)
                val toIndex = (fromIndex + 1).coerceIn(0, colorKeyframes.size - 1)
                val fraction = progress - fromIndex

                val c1Start = colorKeyframes[fromIndex][0]
                val c2Start = colorKeyframes[fromIndex][1]
                val c3Start = colorKeyframes[fromIndex][2]

                val c1End = colorKeyframes[toIndex][0]
                val c2End = colorKeyframes[toIndex][1]
                val c3End = colorKeyframes[toIndex][2]

                val currentC1 = argbEvaluator.evaluate(fraction, c1Start, c1End) as Int
                val currentC2 = argbEvaluator.evaluate(fraction, c2Start, c2End) as Int
                val currentC3 = argbEvaluator.evaluate(fraction, c3Start, c3End) as Int

                val grad = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(currentC1, currentC2, currentC3)
                ).apply {
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    if (radiusPx > 0f) {
                        cornerRadius = radiusPx
                    }
                }
                view.background = grad
            }
        }
        animator.start()
        return animator
    }

    fun applyBackground(view: View, cornerRadiusDp: Float = 0f, existingAnimator: ValueAnimator? = null): ValueAnimator? {
        try { existingAnimator?.cancel() } catch (_: Throwable) {}
        return attachAuroraBackground(view, cornerRadiusDp)
    }
}

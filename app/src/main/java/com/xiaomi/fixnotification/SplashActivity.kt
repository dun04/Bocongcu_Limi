package com.xiaomi.fixnotification

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xiaomi.fixnotification.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private var hasNavigated = false
    private var isPreview = false

    private val timeoutRunnable = Runnable {
        finishSplash()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applySavedTheme(this)
        super.onCreate(savedInstanceState)

        isPreview = intent.getBooleanExtra(EXTRA_IS_PREVIEW, false)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isSplashEnabled = prefs.getBoolean(KEY_ENABLE_SPLASH, true)

        if (!isSplashEnabled && !isPreview) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val useCustomSplash = prefs.getBoolean(KEY_USE_CUSTOM_SPLASH, false)
        val customFile = java.io.File(filesDir, "custom_splash.mp4")

        val videoUri = if (useCustomSplash && customFile.exists()) {
            Uri.fromFile(customFile)
        } else {
            Uri.parse("android.resource://$packageName/${R.raw.khoidong}")
        }
        binding.videoViewSplash.setVideoURI(videoUri)

        binding.videoViewSplash.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = false
            binding.videoViewSplash.start()
        }

        binding.videoViewSplash.setOnCompletionListener {
            finishSplash()
        }

        binding.videoViewSplash.setOnErrorListener { _, _, _ ->
            finishSplash()
            true
        }

        ViewAnimationExtensions.applySpringTouch(binding.btnSkipSplash)

        binding.btnSkipSplash.setOnClickListener { anchor ->
            ViewAnimationExtensions.animateBounce(anchor)
            finishSplash()
        }

        handler.postDelayed(timeoutRunnable, 10000)
    }

    private fun finishSplash() {
        if (hasNavigated) return
        hasNavigated = true
        handler.removeCallbacks(timeoutRunnable)

        try {
            if (binding.videoViewSplash.isPlaying) {
                binding.videoViewSplash.stopPlayback()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        if (!isPreview) {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timeoutRunnable)
    }

    companion object {
        const val PREFS_NAME = "app_settings"
        const val KEY_ENABLE_SPLASH = "enable_splash_video"
        const val KEY_USE_CUSTOM_SPLASH = "use_custom_splash"
        const val EXTRA_IS_PREVIEW = "is_preview"
    }
}

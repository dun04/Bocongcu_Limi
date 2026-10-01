package com.xiaomi.fixnotification

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.xiaomi.fixnotification.databinding.ActivityVietnamVideoBinding
import kotlin.random.Random

class VietnamVideoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVietnamVideoBinding
    private var isRebootDialogShowing = false

    private val videoResources = listOf(
        R.raw.icon_vietnam,
        R.raw.icon_vietnam2
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Xoay ngang màn hình (Sensor Landscape)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Ẩn toàn bộ thanh trạng thái & điều hướng (Fullscreen Immersion)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityVietnamVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Thực thi ngầm câu lệnh chuyển đổi ngôn ngữ sang Tiếng Việt (SetEdit System Locales = vi-VN)
        applyVietnameseLocale()

        // Đặt nút X gọn gàng ở góc trên bên phải không che nội dung video
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootVietnamVideo) { _, insets ->
            val cutouts = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            (binding.btnCloseVideo.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                val baseMarginTop = (12 * resources.displayMetrics.density).toInt()
                val baseMarginEnd = (12 * resources.displayMetrics.density).toInt()
                lp.topMargin = baseMarginTop + cutouts.top
                lp.rightMargin = baseMarginEnd + cutouts.right
                binding.btnCloseVideo.layoutParams = lp
            }
            insets
        }

        ViewAnimationExtensions.applySpringTouch(binding.btnCloseVideo)

        // Nhấn nút X để kết thúc video và hiện popup nhắc reboot
        binding.btnCloseVideo.setOnClickListener { anchor ->
            ViewAnimationExtensions.animateBounce(anchor)
            showRebootDialog()
        }

        // Chạm vào màn hình để tạm dừng hoặc tiếp tục phát
        binding.rootVietnamVideo.setOnClickListener {
            if (binding.videoViewVietnam.isPlaying) {
                binding.videoViewVietnam.pause()
            } else {
                binding.videoViewVietnam.start()
            }
        }

        // Quyết định video phát: Lần đầu ngẫu nhiên 1 trong 2, các lần sau phát xen kẽ video còn lại
        val videoResId = selectNextVideo()
        playVideo(videoResId)
    }

    private fun applyVietnameseLocale() {
        Thread {
            try {
                // Các lệnh cập nhật System table và Global table của SetEdit sang Tiếng Việt (vi-VN)
                val commands = listOf(
                    "settings put system system_locales vi-VN",
                    "settings put global system_locales vi-VN",
                    "settings put system locale vi_VN",
                    "setprop persist.sys.locale vi-VN",
                    "setprop persist.sys.language vi",
                    "setprop persist.sys.country VN"
                )
                for (cmd in commands) {
                    ShizukuUtils.execShizukuCommand(cmd)
                }
            } catch (_: Throwable) {}
        }.start()
    }

    private fun selectNextVideo(): Int {
        val prefs = getSharedPreferences("vietnam_video_prefs", Context.MODE_PRIVATE)
        val lastPlayedIndex = prefs.getInt("last_played_index", -1)

        val targetIndex = if (lastPlayedIndex == -1) {
            // Lần đầu tiên: Chọn ngẫu nhiên 0 hoặc 1
            Random.nextInt(videoResources.size)
        } else {
            // Lần tiếp theo: Chọn video còn lại (luân phiên)
            (lastPlayedIndex + 1) % videoResources.size
        }

        prefs.edit().putInt("last_played_index", targetIndex).apply()
        return videoResources[targetIndex]
    }

    private fun playVideo(videoResId: Int) {
        val videoUri = Uri.parse("android.resource://$packageName/$videoResId")
        binding.videoViewVietnam.setVideoURI(videoUri)

        binding.videoViewVietnam.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = false
            binding.videoViewVietnam.start()
        }

        // Khi phát hết video thì hiển thị popup nhắc khởi động lại máy
        binding.videoViewVietnam.setOnCompletionListener {
            showRebootDialog()
        }

        binding.videoViewVietnam.setOnErrorListener { _, _, _ ->
            showRebootDialog()
            true
        }
    }

    private fun showRebootDialog() {
        if (isFinishing || isDestroyed || isRebootDialogShowing) return
        isRebootDialogShowing = true

        try {
            if (binding.videoViewVietnam.isPlaying) {
                binding.videoViewVietnam.pause()
            }
        } catch (_: Throwable) {}

        runOnUiThread {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_vietnam_locale_reboot, null)
            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create()

            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.attributes?.windowAnimations = R.style.DialogPopAnimation

            val btnRebootNow = dialogView.findViewById<MaterialButton>(R.id.btnRebootNow)
            val btnRebootLater = dialogView.findViewById<MaterialButton>(R.id.btnRebootLater)

            ViewAnimationExtensions.applySpringTouch(btnRebootNow)
            ViewAnimationExtensions.applySpringTouch(btnRebootLater)

            btnRebootNow.setOnClickListener { anchor ->
                ViewAnimationExtensions.animateBounce(anchor)
                dialog.dismiss()
                Thread {
                    val result = ShizukuUtils.execShizukuCommand("svc power reboot || reboot")
                    if (result.exitCode != 0) {
                        runOnUiThread {
                            Toast.makeText(this, "Vui lòng khởi động lại máy thủ công bằng phím Nguồn.", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }.start()
            }

            btnRebootLater.setOnClickListener { anchor ->
                ViewAnimationExtensions.animateBounce(anchor)
                dialog.dismiss()
                finish()
            }

            dialog.setOnDismissListener {
                if (!isFinishing) finish()
            }

            dialog.show()

            dialog.window?.let { win ->
                val lp = win.attributes
                val displayMetrics = resources.displayMetrics
                val widthPx = (360 * displayMetrics.density).toInt()
                lp.width = Math.min(widthPx, (displayMetrics.widthPixels * 0.85).toInt())
                win.attributes = lp
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            binding.videoViewVietnam.stopPlayback()
        } catch (_: Throwable) {}
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

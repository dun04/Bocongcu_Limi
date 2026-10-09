package com.xiaomi.fixnotification.ai

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.xiaomi.fixnotification.R
import com.xiaomi.fixnotification.ViewAnimationExtensions

/**
 * Trình xem ảnh chi tiết toàn màn hình chuẩn nghệ thuật (Cinematic Fullscreen Image Viewer)
 * Dùng chung cho cả Limi AI và hộp thoại Cập nhật OTA (AppUpdateManager).
 * Tính năng:
 * - Nền mờ nghệ thuật Gaussian Blur (FastBlurHelper)
 * - Cử chỉ zoom 2 ngón (Pinch-to-zoom), double-tap zoom
 * - Vuốt xuống để đóng với hiệu ứng mờ dần (Swipe-to-dismiss)
 * - Tương thích Cutout / Đục lỗ màn hình và thanh điều hướng trong suốt
 */
object LimiImageViewerHelper {

    fun show(activity: Activity, bmp: Bitmap, sourceName: String? = null) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val viewerView = activity.layoutInflater.inflate(R.layout.dialog_image_viewer, null)
            val ivBlurBackground = viewerView.findViewById<ImageView>(R.id.ivBlurBackground)
            val ivFullPhoto = viewerView.findViewById<ZoomableImageView>(R.id.ivFullPhoto)
            val btnCloseViewer = viewerView.findViewById<View>(R.id.btnCloseImageViewer)
            val layoutRoot = viewerView.findViewById<View>(R.id.layoutImageViewerRoot)
            val tvImageSource = viewerView.findViewById<TextView>(R.id.tvImageSource)

            val blurred = FastBlurHelper.blur(bmp, radius = 14, scale = 0.12f)
            if (blurred != null) {
                ivBlurBackground?.setImageBitmap(blurred)
            } else {
                ivBlurBackground?.setImageBitmap(bmp)
            }

            val viewDarkOverlay = viewerView.findViewById<View>(R.id.viewDarkOverlay)
            ivFullPhoto?.setImageBitmap(bmp)
            if (!sourceName.isNullOrBlank()) {
                tvImageSource?.text = sourceName
                tvImageSource?.visibility = View.VISIBLE
            } else {
                tvImageSource?.visibility = View.GONE
            }

            val viewerDialog = AlertDialog.Builder(activity)
                .setView(viewerView)
                .setCancelable(true)
                .create()

            ivFullPhoto?.onSingleTap = { viewerDialog.dismiss() }
            ivFullPhoto?.onDismissRequest = { viewerDialog.dismiss() }
            ivFullPhoto?.onSwipeProgress = { alpha, _ ->
                ivBlurBackground?.alpha = alpha * 0.85f
                viewDarkOverlay?.alpha = alpha
                btnCloseViewer?.alpha = alpha
            }

            viewerDialog.show()
            viewerDialog.window?.let { window ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                    window.isStatusBarContrastEnforced = false
                }
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.decorView.fitsSystemWindows = false
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.decorView.setPadding(0, 0, 0, 0)
                window.attributes?.windowAnimations = R.style.DialogPopAnimation
            }

            val density = activity.resources.displayMetrics.density
            ViewCompat.setOnApplyWindowInsetsListener(viewerView) { _, insets ->
                val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                val topSafe = statusBars.top.coerceAtLeast((24 * density).toInt())
                (btnCloseViewer?.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                    lp.topMargin = topSafe + (8 * density).toInt()
                    btnCloseViewer.layoutParams = lp
                }
                insets
            }

            btnCloseViewer?.let { ViewAnimationExtensions.applySpringTouch(it) }
            btnCloseViewer?.setOnClickListener { viewerDialog.dismiss() }
            layoutRoot?.setOnClickListener { viewerDialog.dismiss() }
        } catch (_: Throwable) {
            Toast.makeText(activity, "Không thể mở ảnh phóng to", Toast.LENGTH_SHORT).show()
        }
    }
}

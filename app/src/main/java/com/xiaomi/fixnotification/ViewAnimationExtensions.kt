package com.xiaomi.fixnotification

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

object ViewAnimationExtensions {

    /**
     * Hiệu ứng "Giọt nước đàn hồi & Đuôi chất lỏng tốc độ cao" (High-Speed Fluid Jelly & Visible Trailing Tail Stretch)
     * - Tốc độ chuyển động siêu nhanh 240ms, phản hồi tức thì không bị hãm phanh khi vào 2 đầu mép.
     * - Khoảng cách di chuyển càng xa -> Đuôi càng kéo dãn rõ rệt (scaleX lên tới 1.60x).
     * - Khi đáp vào 2 nút hai đầu: Đầu viên thuốc ghim chặt an toàn [0, maxBoundaryX] không bị lọt ra ngoài,
     *   trong khi đuôi chất lỏng vẫn kịp kéo dãn phía sau rồi giật co đàn hồi snap-back về 1.0x cực kỳ rõ nét.
     */
    fun animateFluidJellyPill(
        pillView: View,
        targetX: Float,
        targetWidth: Int,
        duration: Long = 240L,
        maxBoundaryX: Float = Float.MAX_VALUE
    ) {
        val startX = pillView.x
        val startWidth = pillView.width

        val distance = kotlin.math.abs(startX - targetX)
        val isMoving = distance > 4f

        // 1. Animator vị trí X (Chuyển động tốc độ cao, dùng OvershootInterpolator và ghim chặt mép [0, maxBoundaryX])
        val posAnimator = ValueAnimator.ofFloat(startX, targetX).apply {
            this.duration = duration
            interpolator = OvershootInterpolator(1.25f)
            addUpdateListener { animator ->
                val curX = animator.animatedValue as Float
                val clampedX = if (maxBoundaryX != Float.MAX_VALUE) {
                    curX.coerceIn(0f, maxBoundaryX)
                } else {
                    curX.coerceAtLeast(0f)
                }
                pillView.x = clampedX
            }
        }

        // 2. Animator bề rộng nếu kích thước tab đích khác biệt
        val widthAnimator = if (startWidth > 0 && targetWidth > 0 && startWidth != targetWidth) {
            ValueAnimator.ofInt(startWidth, targetWidth).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator(1.2f)
                addUpdateListener { animator ->
                    val lp = pillView.layoutParams
                    lp.width = animator.animatedValue as Int
                    pillView.layoutParams = lp
                }
            }
        } else null

        // 3. Hiệu ứng kéo dãn đuôi chất lỏng theo quán tính & khoảng cách di chuyển
        val actualWidth = (if (targetWidth > 0) targetWidth else pillView.width).toFloat()
        val isLeftEdge = targetX <= 4f
        val isRightEdge = maxBoundaryX != Float.MAX_VALUE && targetX >= (maxBoundaryX - 4f)

        // Tính toán độ giãn đuôi tối đa dựa trên quãng đường di chuyển (càng xa giãn càng mạnh)
        val maxScaleX = if (isMoving) {
            (1.30f + (distance / 280f) * 0.30f).coerceIn(1.30f, 1.60f)
        } else {
            1.15f
        }

        val pivotPointX = when {
            isLeftEdge || (startX > targetX) -> {
                // Di chuyển sang trái hoặc đáp vào mép trái: đầu trái giữ tại 0, đuôi dãn dài sang phải
                0f
            }
            isRightEdge || (startX < targetX) -> {
                // Di chuyển sang phải hoặc đáp vào mép phải: đầu phải giữ tại mép, đuôi dãn dài sang trái
                actualWidth
            }
            else -> {
                actualWidth / 2f
            }
        }

        pillView.pivotX = pivotPointX
        pillView.pivotY = (if (pillView.height > 0) pillView.height else 56) / 2f

        // Đường cong kéo dãn đuôi:
        // Pha 1 (0% -> 35%): Đuôi dãn dài nhanh chóng đạt đỉnh maxScaleX
        // Pha 2 (35% -> 65%): Duy trì độ dãn đuôi trong khi đầu viên thuốc lao nhanh về đích
        // Pha 3 (65% -> 100%): Đuôi giật co đàn hồi (snap-back) về 1.0x khi đầu đã cập bến
        val scaleXStretch = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                val currentScaleX = when {
                    fraction < 0.35f -> {
                        val p = fraction / 0.35f
                        1.0f + (maxScaleX - 1.0f) * kotlin.math.sin(p * Math.PI / 2).toFloat()
                    }
                    fraction < 0.65f -> {
                        val p = (fraction - 0.35f) / 0.30f
                        maxScaleX - (maxScaleX - 1.0f) * 0.15f * p
                    }
                    else -> {
                        val p = (fraction - 0.65f) / 0.35f
                        1.0f + (maxScaleX - 1.0f) * 0.85f * kotlin.math.cos(p * Math.PI / 2).toFloat()
                    }
                }
                pillView.scaleX = currentScaleX.coerceAtLeast(1.0f)
            }
        }

        val scaleYCompress = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                val minScaleY = if (isMoving) 0.86f else 0.92f
                val currentScaleY = when {
                    fraction < 0.35f -> {
                        val p = fraction / 0.35f
                        1.0f - (1.0f - minScaleY) * kotlin.math.sin(p * Math.PI / 2).toFloat()
                    }
                    fraction < 0.65f -> {
                        val p = (fraction - 0.35f) / 0.30f
                        minScaleY + (1.0f - minScaleY) * 0.15f * p
                    }
                    else -> {
                        val p = (fraction - 0.65f) / 0.35f
                        1.0f - (1.0f - minScaleY) * 0.85f * kotlin.math.cos(p * Math.PI / 2).toFloat()
                    }
                }
                pillView.scaleY = currentScaleY.coerceIn(minScaleY, 1.0f)
            }
        }

        AnimatorSet().apply {
            if (widthAnimator != null) {
                playTogether(posAnimator, widthAnimator, scaleXStretch, scaleYCompress)
            } else {
                playTogether(posAnimator, scaleXStretch, scaleYCompress)
            }
            start()
        }
    }

    /**
     * Gắn hiệu ứng đàn hồi lò xo Morphicons (Micro-Spring Touch Feedback chuẩn https://www.morphicons.com/)
     * Khi ấn ngón tay xuống (DOWN): Co nén 0.88x với góc nghiêng nhẹ đón lực
     * Khi nhả ngón tay ra (UP): Bung nảy đàn hồi (Spring Bounce Overshoot 2.8f) về trạng thái cân bằng 1.0x
     */
    @SuppressLint("ClickableViewAccessibility")
    fun applySpringTouch(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.88f)
                        .scaleY(0.88f)
                        .rotation(-2.5f)
                        .setDuration(90)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .rotation(0f)
                        .setDuration(280)
                        .setInterpolator(OvershootInterpolator(2.8f))
                        .start()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .rotation(0f)
                        .setDuration(160)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            false // Trả về false để click listener chuẩn của view vẫn được kích hoạt bình thường
        }
    }

    /**
     * Hiệu ứng Morphicons Icon Spring Physics (Chuẩn https://www.morphicons.com/)
     * - Co nén nhanh (0.80x) kết hợp góc xoay lệch (Rotational Kick)
     * - Bung nảy đàn hồi lò xo (Overshoot 2.8f) và dao động phục hồi vị trí cân bằng 1.0x, 0 độ
     */
    fun animateMorphIcon(
        view: View,
        rotationDegrees: Float = 12f,
        scaleFactor: Float = 1.22f,
        duration: Long = 320L,
        onEnd: (() -> Unit)? = null
    ) {
        view.animate().cancel()
        view.scaleX = 0.80f
        view.scaleY = 0.80f
        view.rotation = -rotationDegrees

        view.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .rotation(0f)
            .setDuration(duration)
            .setInterpolator(OvershootInterpolator(2.8f))
            .withEndAction {
                view.scaleX = 1.0f
                view.scaleY = 1.0f
                view.rotation = 0f
                onEnd?.invoke()
            }
            .start()
    }

    /**
     * Hiệu ứng xoay tròn lò xo Morphicons (Spring Spin) dành cho Icon Làm Mới / Sync / Refresh
     */
    fun animateMorphSpin(
        view: View,
        duration: Long = 450L,
        onEnd: (() -> Unit)? = null
    ) {
        view.animate().cancel()
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        view.animate()
            .rotationBy(360f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(duration)
            .setInterpolator(OvershootInterpolator(1.8f))
            .withEndAction {
                view.scaleX = 1.0f
                view.scaleY = 1.0f
                onEnd?.invoke()
            }
            .start()
    }

    /**
     * Hiệu ứng nảy đàn hồi lò xo khi ấn (Click Bounce Animation)
     * Co lại 0.90x rồi bung nảy đàn hồi (Overshoot 2.8f) về kích thước ban đầu 1.0x
     */
    fun animateBounce(view: View, onEnd: (() -> Unit)? = null) {
        view.animate()
            .scaleX(0.90f)
            .scaleY(0.90f)
            .setDuration(90)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(2.8f))
                    .withEndAction {
                        onEnd?.invoke()
                    }
                    .start()
            }
            .start()
    }

    /**
     * Hiệu ứng bung mở bảng Popup mượt mà 120Hz từ vị trí nút bấm (Anchor View):
     * - Khởi tạo từ kích thước nhỏ (scale 0.15x), độ mờ đục 0%
     * - Mở từ từ lớn dần (0.15x -> 1.0x) đạt chuẩn tần số quét cao 120Hz với độ nảy đàn hồi HyperOS
     */
    fun revealPopupFromAnchor(popupView: View, anchorView: View? = null) {
        popupView.scaleX = 0.15f
        popupView.scaleY = 0.15f
        popupView.alpha = 0f

        popupView.post {
            updatePivotTowardsAnchor(popupView, anchorView)

            popupView.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(250)
                .setInterpolator(OvershootInterpolator(1.22f))
                .start()
        }
    }

    /**
     * Thu gọn Popup mượt mà thu nhỏ dần về nút bấm trước khi đóng (120Hz dismissal animation)
     */
    fun dismissPopup(
        popupView: View,
        popupWindow: android.widget.PopupWindow,
        anchorView: View? = null,
        onDismissEnd: (() -> Unit)? = null
    ) {
        if (popupView.getTag(R.id.tag_is_dismissing) == true) return
        popupView.setTag(R.id.tag_is_dismissing, true)

        updatePivotTowardsAnchor(popupView, anchorView)

        popupView.animate()
            .scaleX(0.15f)
            .scaleY(0.15f)
            .alpha(0f)
            .setDuration(190)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                try {
                    popupWindow.dismiss()
                } catch (_: Exception) {}
                onDismissEnd?.invoke()
            }
            .start()
    }

    /**
     * Hiệu ứng bung mở bảng Dialog từ nút bấm (Anchor View) hoặc tâm màn hình mượt mà 120Hz
     */
    fun revealDialog(dialogView: View, anchorView: View? = null) {
        dialogView.scaleX = 0.15f
        dialogView.scaleY = 0.15f
        dialogView.alpha = 0f

        dialogView.post {
            updatePivotTowardsAnchor(dialogView, anchorView)

            dialogView.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(260)
                .setInterpolator(OvershootInterpolator(1.22f))
                .start()
        }
    }

    /**
     * Thu gọn Dialog mượt mà thu nhỏ dần về nút bấm trước khi đóng (120Hz dismissal animation)
     */
    fun dismissDialog(dialogView: View, dialog: android.app.Dialog, anchorView: View? = null) {
        updatePivotTowardsAnchor(dialogView, anchorView)

        dialogView.animate()
            .scaleX(0.15f)
            .scaleY(0.15f)
            .alpha(0f)
            .setDuration(190)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                try {
                    dialog.dismiss()
                } catch (_: Exception) {}
            }
            .start()
    }

    private fun updatePivotTowardsAnchor(view: View, anchorView: View?) {
        if (anchorView != null && anchorView.isAttachedToWindow && view.isAttachedToWindow) {
            val anchorLoc = IntArray(2)
            anchorView.getLocationOnScreen(anchorLoc)
            val viewLoc = IntArray(2)
            view.getLocationOnScreen(viewLoc)

            val anchorCenterX = anchorLoc[0] + anchorView.width / 2f
            val anchorCenterY = anchorLoc[1] + anchorView.height / 2f

            val relX = (anchorCenterX - viewLoc[0]).coerceIn(0f, view.width.toFloat().coerceAtLeast(1f))
            val relY = (anchorCenterY - viewLoc[1]).coerceIn(0f, view.height.toFloat().coerceAtLeast(1f))

            view.pivotX = relX
            view.pivotY = relY
        } else if (view.width > 0 && view.height > 0) {
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
        }
    }
}

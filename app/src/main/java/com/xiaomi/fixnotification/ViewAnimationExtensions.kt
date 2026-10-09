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

        val actualWidth = (if (targetWidth > 0) targetWidth else pillView.width).toFloat()
        val isLeftEdge = targetX <= 4f
        val isRightEdge = maxBoundaryX != Float.MAX_VALUE && targetX >= (maxBoundaryX - 4f)

        val maxScaleX = if (isMoving) {
            (1.30f + (distance / 280f) * 0.30f).coerceIn(1.30f, 1.60f)
        } else {
            1.15f
        }

        val pivotPointX = when {
            isLeftEdge || (startX > targetX) -> {

                0f
            }
            isRightEdge || (startX < targetX) -> {

                actualWidth
            }
            else -> {
                actualWidth / 2f
            }
        }

        pillView.pivotX = pivotPointX
        pillView.pivotY = (if (pillView.height > 0) pillView.height else 56) / 2f

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

    @SuppressLint("ClickableViewAccessibility")
    fun applySpringTouch(view: View) {
        val density = view.resources.displayMetrics.density
        view.cameraDistance = 9000f * density

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val w = v.width.toFloat()
                    val h = v.height.toFloat()
                    if (w > 0f && h > 0f) {
                        val centerX = w / 2f
                        val centerY = h / 2f

                        val dx = ((event.x - centerX) / centerX).coerceIn(-1.0f, 1.0f)
                        val dy = ((event.y - centerY) / centerY).coerceIn(-1.0f, 1.0f)

                        val maxAngle = 7.5f
                        val targetRotX = -dy * maxAngle
                        val targetRotY = dx * maxAngle

                        val shift = 2.0f * density
                        val targetTransX = dx * shift
                        val targetTransY = dy * shift

                        v.animate()
                            .scaleX(0.94f)
                            .scaleY(0.94f)
                            .rotationX(targetRotX)
                            .rotationY(targetRotY)
                            .rotation(0f)
                            .translationX(targetTransX)
                            .translationY(targetTransY)
                            .setDuration(90L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    } else {
                        v.animate()
                            .scaleX(0.94f)
                            .scaleY(0.94f)
                            .setDuration(90L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val w = v.width.toFloat()
                    val h = v.height.toFloat()
                    if (w > 0f && h > 0f) {
                        if (event.x in 0f..w && event.y in 0f..h) {
                            val centerX = w / 2f
                            val centerY = h / 2f
                            val dx = ((event.x - centerX) / centerX).coerceIn(-1.0f, 1.0f)
                            val dy = ((event.y - centerY) / centerY).coerceIn(-1.0f, 1.0f)

                            val maxAngle = 7.5f
                            val targetRotX = -dy * maxAngle
                            val targetRotY = dx * maxAngle

                            val shift = 2.0f * density
                            val targetTransX = dx * shift
                            val targetTransY = dy * shift

                            v.animate()
                                .rotationX(targetRotX)
                                .rotationY(targetRotY)
                                .translationX(targetTransX)
                                .translationY(targetTransY)
                                .setDuration(35L)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .rotationX(0f)
                        .rotationY(0f)
                        .rotation(0f)
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(280L)
                        .setInterpolator(OvershootInterpolator(2.4f))
                        .start()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .rotationX(0f)
                        .rotationY(0f)
                        .rotation(0f)
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(160L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            false
        }
    }

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

    fun animateBounce(view: View, onEnd: (() -> Unit)? = null) {
        view.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(90)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(2.4f))
                    .withEndAction {
                        onEnd?.invoke()
                    }
                    .start()
            }
            .start()
    }

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

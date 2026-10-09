package com.xiaomi.fixnotification.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView

/**
 * ZoomableImageView:
 * - Zoom đa điểm mượt mà (Pinch to Zoom) với giới hạn co giãn đàn hồi (Rubber-band).
 * - Di chuyển ảnh (Pan/Drag) mượt mà khi đang phóng to.
 * - Chạm đúp (Double tap) để phóng to / hoàn nguyên về vị trí gốc chuẩn tâm.
 * - Chạm đơn (Single tap) để đóng trình xem ảnh.
 * - Vuốt xuống (Swipe down to dismiss) chỉ kích hoạt khi ở 1.0x với 1 ngón tay,
 *   tuyệt đối không bị xung đột hay tụt ảnh xuống đáy khi chụm 2 ngón tay thu phóng.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val currentMatrix = Matrix()
    private val baseMatrix = Matrix()

    private val minScale = 1.0f
    private val maxScale = 5.0f
    private var currentScale = 1.0f

    // Quản lý cảm ứng 1 ngón khi di chuyển hoặc vuốt đóng
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isSwipingDown = false
    private var isDraggingImage = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Quản lý tâm ngón tay khi chụm 2 ngón
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    var onSingleTap: (() -> Unit)? = null
    var onDismissRequest: (() -> Unit)? = null
    var onSwipeProgress: ((alpha: Float, translationY: Float) -> Unit)? = null

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        // Giảm độ trễ phát hiện cử chỉ scale trên Android
        scaleDetector.isQuickScaleEnabled = false

        gestureDetector = GestureDetector(context, GestureListener())
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetImageMatrix() }
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        post { resetImageMatrix() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetImageMatrix()
    }

    /**
     * Căn chỉnh ảnh vừa vặn chính giữa màn hình (Fit Center)
     */
    fun resetImageMatrix() {
        val d = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val dWidth = d.intrinsicWidth.toFloat()
        val dHeight = d.intrinsicHeight.toFloat()
        if (dWidth <= 0f || dHeight <= 0f) return

        val sx = viewWidth / dWidth
        val sy = viewHeight / dHeight
        val scale = sx.coerceAtMost(sy)

        val dx = (viewWidth - dWidth * scale) / 2f
        val dy = (viewHeight - dHeight * scale) / 2f

        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)

        currentMatrix.set(baseMatrix)
        imageMatrix = currentMatrix
        currentScale = 1.0f

        // Đảm bảo các thuộc tính View luôn ở trạng thái gốc
        this.translationY = 0f
        this.scaleX = 1.0f
        this.scaleY = 1.0f
        this.alpha = 1.0f
        isSwipingDown = false
        isDraggingImage = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Cho ScaleGestureDetector và GestureDetector xử lý trước
        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)

        val pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.rawX
                touchDownY = event.rawY
                lastTouchX = event.x
                lastTouchY = event.y
                isSwipingDown = false
                isDraggingImage = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Khi có ngón thứ 2 chạm vào: HỦY NGAY chế độ vuốt đóng (swipe dismiss)
                if (isSwipingDown) {
                    cancelSwipeDismiss()
                }
                isDraggingImage = false
                lastFocusX = scaleDetector.focusX
                lastFocusY = scaleDetector.focusY
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    // Đang chụm phóng to / thu nhỏ: Cập nhật di chuyển theo tâm 2 ngón tay
                    val focusX = scaleDetector.focusX
                    val focusY = scaleDetector.focusY
                    val deltaX = focusX - lastFocusX
                    val deltaY = focusY - lastFocusY

                    currentMatrix.postTranslate(deltaX, deltaY)
                    fixTranslationBounds()
                    imageMatrix = currentMatrix

                    lastFocusX = focusX
                    lastFocusY = focusY
                    return true
                }

                if (pointerCount == 1) {
                    if (currentScale > 1.05f) {
                        // Đang phóng to: Di chuyển ảnh (Pan) trong giới hạn
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY

                        if (!isDraggingImage && (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop / 2f)) {
                            isDraggingImage = true
                        }

                        if (isDraggingImage) {
                            currentMatrix.postTranslate(dx, dy)
                            fixTranslationBounds()
                            imageMatrix = currentMatrix
                        }

                        lastTouchX = event.x
                        lastTouchY = event.y
                    } else {
                        // Đang ở 1.0x: Chỉ kích hoạt vuốt xuống để đóng khi vuốt dọc thẳng xuống
                        val deltaX = event.rawX - touchDownX
                        val deltaY = event.rawY - touchDownY

                        if (!isSwipingDown) {
                            if (deltaY > touchSlop && deltaY > Math.abs(deltaX) * 1.6f) {
                                isSwipingDown = true
                            }
                        }

                        if (isSwipingDown) {
                            val pullDistance = deltaY.coerceAtLeast(0f)
                            val dragScale = (1.0f - (pullDistance / (height * 3.0f))).coerceIn(0.75f, 1.0f)
                            val dragAlpha = (1.0f - (pullDistance / (height * 1.0f))).coerceIn(0.2f, 1.0f)

                            translationY = pullDistance
                            scaleX = dragScale
                            scaleY = dragScale
                            alpha = dragAlpha
                            onSwipeProgress?.invoke(dragAlpha, pullDistance)
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Một ngón nhấc lên khi đang dùng 2 ngón: cập nhật lại vị trí ngón còn lại
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastTouchX = event.getX(remainingIndex)
                    lastTouchY = event.getY(remainingIndex)
                    touchDownX = event.getRawX()
                    touchDownY = event.getRawY()
                }
                isDraggingImage = false
                isSwipingDown = false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSwipingDown) {
                    val deltaY = event.rawY - touchDownY
                    if (deltaY > height * 0.18f) {
                        // Đạt ngưỡng kéo xuống -> Trượt mượt ra ngoài màn hình và đóng
                        animate().translationY(height.toFloat()).alpha(0f).setDuration(180)
                            .setInterpolator(DecelerateInterpolator())
                            .withEndAction {
                                onDismissRequest?.invoke()
                            }.start()
                    } else {
                        // Kéo nhẹ chưa đủ -> Nảy mượt về vị trí cũ
                        animate().translationY(0f).scaleX(1.0f).scaleY(1.0f).alpha(1.0f)
                            .setDuration(220)
                            .setInterpolator(DecelerateInterpolator())
                            .withEndAction {
                                onSwipeProgress?.invoke(1.0f, 0f)
                            }.start()
                    }
                    isSwipingDown = false
                } else if (currentScale < minScale) {
                    // Nếu người dùng thu nhỏ dưới 1.0x -> Tự động nảy về 1.0x chính giữa
                    animateBackToBaseMatrix()
                } else if (currentScale > maxScale) {
                    // Giới hạn maxScale
                    animateScaleTo(maxScale, width / 2f, height / 2f)
                }
                isDraggingImage = false
            }
        }

        return true
    }

    private fun cancelSwipeDismiss() {
        isSwipingDown = false
        animate().translationY(0f).scaleX(1.0f).scaleY(1.0f).alpha(1.0f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                onSwipeProgress?.invoke(1.0f, 0f)
            }.start()
    }

    /**
     * Giữ cho ảnh không bị trôi ra khỏi biên màn hình khi phóng to
     */
    private fun fixTranslationBounds() {
        val rect = getImageDisplayRect() ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= viewWidth) {
            deltaX = (viewWidth - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0f) deltaX = -rect.left
            if (rect.right < viewWidth) deltaX = viewWidth - rect.right
        }

        if (rect.height() <= viewHeight) {
            deltaY = (viewHeight - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0f) deltaY = -rect.top
            if (rect.bottom < viewHeight) deltaY = viewHeight - rect.bottom
        }

        currentMatrix.postTranslate(deltaX, deltaY)
    }

    private fun getImageDisplayRect(): RectF? {
        val d = drawable ?: return null
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        currentMatrix.mapRect(rect)
        return rect
    }

    /**
     * Hoàn nguyên ảnh về vị trí căn giữa chuẩn xác 100% không bị lệch toạ độ
     */
    fun animateBackToBaseMatrix() {
        val startMatrixValues = FloatArray(9)
        val targetMatrixValues = FloatArray(9)
        currentMatrix.getValues(startMatrixValues)
        baseMatrix.getValues(targetMatrixValues)

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val interpolatedValues = FloatArray(9)
                for (i in 0 until 9) {
                    interpolatedValues[i] = startMatrixValues[i] + (targetMatrixValues[i] - startMatrixValues[i]) * fraction
                }
                currentMatrix.setValues(interpolatedValues)
                imageMatrix = currentMatrix
            }
        }
        currentScale = 1.0f
        animator.start()
    }

    /**
     * Phóng to / thu nhỏ có animation mượt mà đến tỉ lệ mong muốn
     */
    fun animateScaleTo(targetScale: Float, focusX: Float, focusY: Float) {
        val startScale = currentScale
        val animator = ValueAnimator.ofFloat(startScale, targetScale).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val animatedScale = anim.animatedValue as Float
                val factor = animatedScale / currentScale
                currentScale = animatedScale
                currentMatrix.postScale(factor, factor, focusX, focusY)
                fixTranslationBounds()
                imageMatrix = currentMatrix
            }
        }
        animator.start()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            cancelSwipeDismiss()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            if (scaleFactor.isNaN() || scaleFactor.isInfinite()) return false

            val targetScale = currentScale * scaleFactor

            // Cho phép co giãn đàn hồi nhẹ (0.75x đến 6.0x)
            if (targetScale in (minScale * 0.75f)..(maxScale * 1.5f)) {
                currentScale = targetScale
                currentMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                fixTranslationBounds()
                imageMatrix = currentMatrix
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            super.onScaleEnd(detector)
            if (currentScale < minScale) {
                animateBackToBaseMatrix()
            } else if (currentScale > maxScale) {
                animateScaleTo(maxScale, width / 2f, height / 2f)
            }
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > 1.2f) {
                animateBackToBaseMatrix()
            } else {
                animateScaleTo(2.5f, e.x, e.y)
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }
    }
}

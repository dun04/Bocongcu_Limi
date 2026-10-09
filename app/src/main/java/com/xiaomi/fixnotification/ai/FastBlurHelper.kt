package com.xiaomi.fixnotification.ai

import android.graphics.Bitmap

object FastBlurHelper {

    /**
     * Tạo Bitmap mờ chất lượng cao (Stack Blur) siêu nhẹ từ Bitmap gốc để làm hình nền
     */
    fun blur(sentBitmap: Bitmap, radius: Int = 10, scale: Float = 0.12f): Bitmap? {
        return try {
            val width = (sentBitmap.width * scale).toInt().coerceAtLeast(1)
            val height = (sentBitmap.height * scale).toInt().coerceAtLeast(1)
            val input = Bitmap.createScaledBitmap(sentBitmap, width, height, false)

            val bmp = input.copy(input.config ?: Bitmap.Config.ARGB_8888, true)
            if (radius < 1) return bmp

            val w = bmp.width
            val h = bmp.height
            val pix = IntArray(w * h)
            bmp.getPixels(pix, 0, w, 0, 0, w, h)

            val wm = w - 1
            val hm = h - 1
            val wh = w * h
            val div = radius + radius + 1

            val r = IntArray(wh)
            val g = IntArray(wh)
            val b = IntArray(wh)
            var rsum: Int
            var gsum: Int
            var bsum: Int
            var x: Int
            var y: Int
            var i: Int
            var p: Int
            var yp: Int
            var yi: Int
            var yw: Int
            val vmin = IntArray(Math.max(w, h))

            var divsum = (div + 1) shr 1
            divsum *= divsum
            val dv = IntArray(256 * divsum)
            for (j in 0 until 256 * divsum) {
                dv[j] = j / divsum
            }

            yi = 0
            yw = 0

            val stack = Array(div) { IntArray(3) }
            var stackpointer: Int
            var stackstart: Int
            var rbs: Int
            val r1 = radius + 1
            var routsum: Int
            var goutsum: Int
            var boutsum: Int
            var rinsum: Int
            var ginsum: Int
            var binsum: Int

            y = 0
            while (y < h) {
                bsum = 0
                gsum = 0
                rsum = 0
                boutsum = 0
                goutsum = 0
                routsum = 0
                binsum = 0
                ginsum = 0
                rinsum = 0
                for (k in -radius..radius) {
                    p = pix[yi + Math.min(wm, Math.max(k, 0))]
                    val sir = stack[k + radius]
                    sir[0] = (p and 0xff0000) shr 16
                    sir[1] = (p and 0x00ff00) shr 8
                    sir[2] = (p and 0x0000ff)
                    val rbsVal = r1 - Math.abs(k)
                    rsum += sir[0] * rbsVal
                    gsum += sir[1] * rbsVal
                    bsum += sir[2] * rbsVal
                    if (k > 0) {
                        rinsum += sir[0]
                        ginsum += sir[1]
                        binsum += sir[2]
                    } else {
                        routsum += sir[0]
                        goutsum += sir[1]
                        boutsum += sir[2]
                    }
                }
                stackpointer = radius

                x = 0
                while (x < w) {
                    r[yi] = dv[rsum]
                    g[yi] = dv[gsum]
                    b[yi] = dv[bsum]

                    rsum -= routsum
                    gsum -= goutsum
                    bsum -= boutsum

                    stackstart = stackpointer - radius + div
                    val sir = stack[stackstart % div]

                    routsum -= sir[0]
                    goutsum -= sir[1]
                    boutsum -= sir[2]

                    if (y == 0) {
                        vmin[x] = Math.min(x + radius + 1, wm)
                    }
                    p = pix[yw + vmin[x]]

                    sir[0] = (p and 0xff0000) shr 16
                    sir[1] = (p and 0x00ff00) shr 8
                    sir[2] = (p and 0x0000ff)

                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]

                    rsum += rinsum
                    gsum += ginsum
                    bsum += binsum

                    stackpointer = (stackpointer + 1) % div
                    val sirNext = stack[stackpointer % div]

                    routsum += sirNext[0]
                    goutsum += sirNext[1]
                    boutsum += sirNext[2]

                    rinsum -= sirNext[0]
                    ginsum -= sirNext[1]
                    binsum -= sirNext[2]

                    yi++
                    x++
                }
                yw += w
                y++
            }

            x = 0
            while (x < w) {
                bsum = 0
                gsum = 0
                rsum = 0
                boutsum = 0
                goutsum = 0
                routsum = 0
                binsum = 0
                ginsum = 0
                rinsum = 0
                yp = -radius * w
                for (k in -radius..radius) {
                    yi = Math.max(0, yp) + x
                    val sir = stack[k + radius]
                    sir[0] = r[yi]
                    sir[1] = g[yi]
                    sir[2] = b[yi]
                    val rbsVal = r1 - Math.abs(k)
                    rsum += r[yi] * rbsVal
                    gsum += g[yi] * rbsVal
                    bsum += b[yi] * rbsVal
                    if (k > 0) {
                        rinsum += sir[0]
                        ginsum += sir[1]
                        binsum += sir[2]
                    } else {
                        routsum += sir[0]
                        goutsum += sir[1]
                        boutsum += sir[2]
                    }
                    if (k < hm) {
                        yp += w
                    }
                }
                yi = x
                stackpointer = radius
                y = 0
                while (y < h) {
                    pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                    rsum -= routsum
                    gsum -= goutsum
                    bsum -= boutsum

                    stackstart = stackpointer - radius + div
                    val sir = stack[stackstart % div]

                    routsum -= sir[0]
                    goutsum -= sir[1]
                    boutsum -= sir[2]

                    if (x == 0) {
                        vmin[y] = Math.min(y + r1, hm) * w
                    }
                    p = x + vmin[y]

                    sir[0] = r[p]
                    sir[1] = g[p]
                    sir[2] = b[p]

                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]

                    rsum += rinsum
                    gsum += ginsum
                    bsum += binsum

                    stackpointer = (stackpointer + 1) % div
                    val sirNext = stack[stackpointer]

                    routsum += sirNext[0]
                    goutsum += sirNext[1]
                    boutsum += sirNext[2]

                    rinsum -= sirNext[0]
                    ginsum -= sirNext[1]
                    binsum -= sirNext[2]

                    yi += w
                    y++
                }
                x++
            }

            bmp.setPixels(pix, 0, w, 0, 0, w, h)
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}

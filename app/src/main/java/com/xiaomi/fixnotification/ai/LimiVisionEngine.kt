package com.xiaomi.fixnotification.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Bộ máy Xử Lý Hình Ảnh Đa Tầng (Limi Vision Engine)
 *
 * Tầng 1: Đọc & Nén ảnh Bitmap chuẩn dung lượng siêu nhẹ (downscale max 1024px)
 * Tầng 2: Chuyển đổi Base64 tương thích chuẩn Gemini Multimodal API (Pool 12 Key)
 * Tầng 3: Nhận diện nhanh các từ khóa lỗi hệ thống trong văn bản ảnh
 */
object LimiVisionEngine {

    /**
     * Đọc Bitmap từ Uri và tự động giảm tỉ lệ (downscale) để tiết kiệm RAM & băng thông
     */
    fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int = 1024, reqHeight: Int = 1024): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Chuyển đổi Bitmap sang chuỗi Base64 JPEG để gửi qua Gemini Multimodal API
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 75): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val byteArray = baos.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * Kiểm tra nhanh logic lỗi nếu phát hiện các cụm từ quen thuộc trong ảnh
     */
    /**
     * Ủy quyền mở Google Lens của hệ thống để nhận diện nhanh vật thể / phụ kiện (Miễn phí token)
     */
     fun openGoogleLens(context: Context, imageUri: Uri? = null): Boolean {
         return try {
             val lensPackage = "com.google.ar.lens"
             val googlePackage = "com.google.android.googlequicksearchbox"

             if (imageUri != null) {
                 // Intent 1: Direct Google Lens với image Uri
                 val lensIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                     setPackage(lensPackage)
                     setDataAndType(imageUri, "image/*")
                     addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                 }
                 if (context.packageManager.resolveActivity(lensIntent, 0) != null) {
                     context.startActivity(lensIntent)
                     return true
                 }

                 // Intent 2: Google App Lens
                 val gIntent = android.content.Intent("com.google.android.apps.googlecamera.GALLERY_LENS").apply {
                     setPackage(googlePackage)
                     setDataAndType(imageUri, "image/*")
                     addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                 }
                 if (context.packageManager.resolveActivity(gIntent, 0) != null) {
                     context.startActivity(gIntent)
                     return true
                 }

                 // Intent 3: Send to Lens
                 val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                     type = "image/*"
                     putExtra(android.content.Intent.EXTRA_STREAM, imageUri)
                     setPackage(lensPackage)
                     addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                 }
                 if (context.packageManager.resolveActivity(sendIntent, 0) != null) {
                     context.startActivity(sendIntent)
                     return true
                 }

                 // Intent 4: Generic chooser
                 val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                     setDataAndType(imageUri, "image/*")
                     addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                 }
                 context.startActivity(android.content.Intent.createChooser(viewIntent, "Mở bằng Google Lens / Bộ sưu tập"))
                 return true
             } else {
                 // Mở app Google Lens trực tiếp
                 val launchIntent = context.packageManager.getLaunchIntentForPackage(lensPackage)
                 if (launchIntent != null) {
                     launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                     context.startActivity(launchIntent)
                     return true
                 }

                 // Fallback: Google App Lens Deep Link
                 val gLensIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("googleapp://lens")).apply {
                     addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                 }
                 if (context.packageManager.resolveActivity(gLensIntent, 0) != null) {
                     context.startActivity(gLensIntent)
                     return true
                 }

                 // Fallback: Google Lens Web
                 val webIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://lens.google.com")).apply {
                     addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                 }
                 context.startActivity(webIntent)
                 return true
             }
         } catch (_: Throwable) {
             false
         }
     }
}

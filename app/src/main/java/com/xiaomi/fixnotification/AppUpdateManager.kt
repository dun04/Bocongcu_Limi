package com.xiaomi.fixnotification

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object AppUpdateManager {

    private const val GITHUB_REPO = "dun04/Bocongcu_Limi"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class UpdateInfo(
        val tagName: String,
        val versionName: String,
        val title: String,
        val changelog: String,
        val downloadUrl: String,
        val apkSize: Long,
        val hasUpdate: Boolean
    )

    private var pendingInstallApk: File? = null
    var cachedUpdateInfo: UpdateInfo? = null
    var onUpdateBadgeListener: ((hasUpdate: Boolean) -> Unit)? = null

    /**
     * Tự động quét cập nhật trong nền khi mở ứng dụng (không hiện popup)
     */
    fun checkUpdateSilently(activity: Activity, onResult: ((hasUpdate: Boolean, info: UpdateInfo?) -> Unit)? = null) {
        executor.execute {
            try {
                val currentVersion = getCurrentVersionName(activity)
                val result = fetchLatestRelease(currentVersion)
                cachedUpdateInfo = result
                mainHandler.post {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onUpdateBadgeListener?.invoke(result.hasUpdate)
                        onResult?.invoke(result.hasUpdate, result)
                    }
                }
            } catch (_: Throwable) {
                mainHandler.post {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onResult?.invoke(false, null)
                    }
                }
            }
        }
    }

    /**
     * Kiểm tra cập nhật và hiển thị hộp thoại giao diện HyperOS Frosted Glass
     */
    fun checkUpdate(activity: Activity, manualTrigger: Boolean = true) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnClose = dialogView.findViewById<ImageView>(R.id.btnUpdateClose)
        val layoutChecking = dialogView.findViewById<LinearLayout>(R.id.layoutChecking)
        val layoutUpToDate = dialogView.findViewById<LinearLayout>(R.id.layoutUpToDate)
        val layoutUpdateAvailable = dialogView.findViewById<LinearLayout>(R.id.layoutUpdateAvailable)
        val layoutDownloading = dialogView.findViewById<LinearLayout>(R.id.layoutDownloading)
        val layoutError = dialogView.findViewById<LinearLayout>(R.id.layoutError)

        val tvUpToDateInfo = dialogView.findViewById<TextView>(R.id.tvUpToDateInfo)
        val btnUpToDateClose = dialogView.findViewById<MaterialButton>(R.id.btnUpToDateClose)

        val tvNewVersionTag = dialogView.findViewById<TextView>(R.id.tvNewVersionTag)
        val tvCurrentVersionLabel = dialogView.findViewById<TextView>(R.id.tvCurrentVersionLabel)
        val tvApkFileSize = dialogView.findViewById<TextView>(R.id.tvApkFileSize)
        val tvChangelog = dialogView.findViewById<TextView>(R.id.tvChangelog)
        val layoutChangelogImages = dialogView.findViewById<LinearLayout>(R.id.layoutChangelogImages)
        val scrollChangelog = dialogView.findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollChangelog)
        val btnUpdateLater = dialogView.findViewById<MaterialButton>(R.id.btnUpdateLater)
        val btnUpdateDownload = dialogView.findViewById<MaterialButton>(R.id.btnUpdateDownload)

        // Tối ưu chiều cao popup cập nhật dài hơn để hiển thị trọn vẹn và nhiều nội dung mô tả
        val displayMetrics = activity.resources.displayMetrics
        val targetScrollHeight = (displayMetrics.heightPixels * 0.48f).toInt()
            .coerceAtLeast((360 * displayMetrics.density).toInt())
            .coerceAtMost((displayMetrics.heightPixels * 0.58f).toInt())
        scrollChangelog?.layoutParams?.height = targetScrollHeight

        val tvDownloadStatusTitle = dialogView.findViewById<TextView>(R.id.tvDownloadStatusTitle)
        val tvDownloadPercent = dialogView.findViewById<TextView>(R.id.tvDownloadPercent)
        val progressDownload = dialogView.findViewById<ProgressBar>(R.id.progressDownload)
        val tvDownloadBytes = dialogView.findViewById<TextView>(R.id.tvDownloadBytes)
        val tvDownloadSpeed = dialogView.findViewById<TextView>(R.id.tvDownloadSpeed)
        val btnCancelDownload = dialogView.findViewById<MaterialButton>(R.id.btnCancelDownload)

        val tvErrorTitle = dialogView.findViewById<TextView>(R.id.tvErrorTitle)
        val tvErrorMessage = dialogView.findViewById<TextView>(R.id.tvErrorMessage)
        val btnErrorRetry = dialogView.findViewById<MaterialButton>(R.id.btnErrorRetry)

        val isDownloadCancelled = AtomicBoolean(false)

        btnClose.setOnClickListener {
            isDownloadCancelled.set(true)
            dialog.dismiss()
        }
        btnUpToDateClose.setOnClickListener { dialog.dismiss() }
        btnUpdateLater.setOnClickListener { dialog.dismiss() }

        fun showState(stateView: View) {
            layoutChecking.visibility = View.GONE
            layoutUpToDate.visibility = View.GONE
            layoutUpdateAvailable.visibility = View.GONE
            layoutDownloading.visibility = View.GONE
            layoutError.visibility = View.GONE
            stateView.visibility = View.VISIBLE
        }

        fun applyUpdateInfo(result: UpdateInfo, currentVersion: String) {
            cachedUpdateInfo = result
            onUpdateBadgeListener?.invoke(result.hasUpdate)
            if (!result.hasUpdate) {
                showState(layoutUpToDate)
                tvUpToDateInfo.text = "Phiên bản hiện tại: v$currentVersion\nỨng dụng của bạn đang là phiên bản mới nhất."
            } else {
                showState(layoutUpdateAvailable)
                tvNewVersionTag.text = result.tagName
                tvCurrentVersionLabel.text = "Đang dùng: v$currentVersion"
                tvApkFileSize.text = if (result.apkSize > 0) formatFileSize(result.apkSize) else "Gói APK chính thức"

                // Phân tích changelog: tách chữ và hình ảnh đính kèm
                val (cleanText, imageUrls) = parseChangelog(result.changelog)

                tvChangelog.text = if (cleanText.isNotBlank()) {
                    cleanText
                } else if (imageUrls.isNotEmpty()) {
                    "Xem hình ảnh minh họa tính năng mới bên dưới:"
                } else {
                    "• Tối ưu hóa hiệu năng và độ ổn định hệ thống.\n• Cập nhật các bản sửa lỗi mới nhất."
                }

                // Render hình ảnh đính kèm trong changelog
                layoutChangelogImages.removeAllViews()
                if (imageUrls.isNotEmpty()) {
                    layoutChangelogImages.visibility = View.VISIBLE
                    for (imageUrl in imageUrls) {
                        addImageItem(activity, layoutChangelogImages, imageUrl)
                    }
                } else {
                    layoutChangelogImages.visibility = View.GONE
                }

                btnUpdateDownload.setOnClickListener {
                    showState(layoutDownloading)
                    startDownloadAndInstall(
                        activity = activity,
                        downloadUrl = result.downloadUrl,
                        expectedSize = result.apkSize,
                        isCancelled = isDownloadCancelled,
                        dialog = dialog,
                        progressView = progressDownload,
                        tvPercent = tvDownloadPercent,
                        tvBytes = tvDownloadBytes,
                        tvSpeed = tvDownloadSpeed,
                        tvStatusTitle = tvDownloadStatusTitle,
                        btnCancel = btnCancelDownload
                    )
                }
            }
        }

        fun performCheck() {
            val cached = cachedUpdateInfo
            val currentVersion = getCurrentVersionName(activity)
            if (cached != null && cached.hasUpdate) {
                applyUpdateInfo(cached, currentVersion)
                return
            }

            showState(layoutChecking)

            executor.execute {
                try {
                    val result = fetchLatestRelease(currentVersion)
                    mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        applyUpdateInfo(result, currentVersion)
                    }
                } catch (e: Throwable) {
                    mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        showState(layoutError)
                        tvErrorTitle.text = "Không thể kiểm tra"
                        tvErrorMessage.text = "Lỗi kết nối: ${e.message ?: "Chưa thể kết nối tới máy chủ cập nhật"}\nVui lòng kiểm tra lại kết nối mạng hoặc thử lại sau."
                        btnErrorRetry.setOnClickListener { performCheck() }
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            isDownloadCancelled.set(true)
        }

        dialog.show()
        dialog.window?.let { window ->
            val screenWidth = activity.resources.displayMetrics.widthPixels
            val targetWidth = (screenWidth * 0.92f).toInt().coerceAtMost((480 * activity.resources.displayMetrics.density).toInt())
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        performCheck()
    }

    /**
     * Tách nội dung văn bản và trích xuất danh sách link hình ảnh từ Markdown hoặc HTML của GitHub Release
     */
    private fun parseChangelog(rawText: String): Pair<String, List<String>> {
        val imageUrls = LinkedHashSet<String>()

        // 1. Thẻ HTML <img ... src="..."... />
        val imgTagRegex = Regex("""<img\s+[^>]*?src=["']([^"']+)["'][^>]*?>""", RegexOption.IGNORE_CASE)
        imgTagRegex.findAll(rawText).forEach { match ->
            match.groupValues.getOrNull(1)?.trim()?.let { url ->
                if (url.isNotBlank()) imageUrls.add(url)
            }
        }

        // 2. Cú pháp Markdown ![alt](url)
        val markdownImgRegex = Regex("""!\[([^\]]*)\]\((https?://[^\s)]+)\)""")
        markdownImgRegex.findAll(rawText).forEach { match ->
            match.groupValues.getOrNull(2)?.trim()?.let { url ->
                if (url.isNotBlank()) imageUrls.add(url)
            }
        }

        // Xóa các thẻ HTML img và Markdown img khỏi văn bản hiển thị
        var clean = rawText
            .replace(imgTagRegex, "")
            .replace(markdownImgRegex, "")

        // 3. Link ảnh trực tiếp kết thúc bằng đuôi ảnh hoặc link assets GitHub đứng riêng dòng
        val rawLineRegex = Regex("""(?m)^https?://(?:github\.com/user-attachments/assets/[^\s]+|[^\s]+\.(?:png|jpg|jpeg|webp|gif))\S*$""", RegexOption.IGNORE_CASE)
        rawLineRegex.findAll(clean).forEach { match ->
            val url = match.value.trim()
            if (url.isNotBlank()) imageUrls.add(url)
        }
        clean = clean.replace(rawLineRegex, "")

        // Làm sạch khoảng trắng và dòng trống liên tiếp
        val cleanedLines = mutableListOf<String>()
        var lastWasBlank = false
        clean.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (!lastWasBlank && cleanedLines.isNotEmpty()) {
                    cleanedLines.add("")
                    lastWasBlank = true
                }
            } else {
                cleanedLines.add(line.trimEnd())
                lastWasBlank = false
            }
        }

        return Pair(cleanedLines.joinToString("\n").trim(), imageUrls.toList())
    }

    /**
     * Thêm view hình ảnh minh họa tính năng vào Changelog (Thu gọn nhỏ gọn, xem chi tiết qua LimiImageViewerHelper)
     */
    private fun addImageItem(activity: Activity, container: LinearLayout, imageUrl: String) {
        val density = activity.resources.displayMetrics.density

        val cardView = com.google.android.material.card.MaterialCardView(activity).apply {
            radius = 12f * density
            strokeWidth = (1 * density).toInt()
            setStrokeColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF")))
            setCardBackgroundColor(Color.parseColor("#15FFFFFF"))
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (135 * density).toInt()
            ).apply {
                bottomMargin = (10 * density).toInt()
            }
        }

        val frameLayout = android.widget.FrameLayout(activity).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val progressBar = ProgressBar(activity).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (28 * density).toInt(),
                (28 * density).toInt(),
                android.view.Gravity.CENTER
            )
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#38BDF8"))
        }

        val imageView = ImageView(activity).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        // Nhãn tag gợi ý chạm xem ảnh phóng to
        val tvHint = TextView(activity).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.END
            ).apply {
                setMargins(0, 0, (8 * density).toInt(), (8 * density).toInt())
            }
            text = "🔍 Chạm để xem chi tiết"
            textSize = 10f
            setTextColor(Color.WHITE)
            val pillBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 10 * density
                setColor(Color.parseColor("#99000000"))
            }
            background = pillBg
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            visibility = View.GONE
        }

        frameLayout.addView(progressBar)
        frameLayout.addView(imageView)
        frameLayout.addView(tvHint)
        cardView.addView(frameLayout)
        container.addView(cardView)

        executor.execute {
            val bitmap = loadBitmapWithCache(activity, imageUrl)
            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                progressBar.visibility = View.GONE
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                    tvHint.visibility = View.VISIBLE
                    cardView.setOnClickListener {
                        showFullScreenImageDialog(activity, bitmap)
                    }
                } else {
                    cardView.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Tải và cache ảnh từ URL với xử lý HTTP redirects & decode tối ưu RAM
     */
    private fun loadBitmapWithCache(context: Context, urlString: String, maxWidth: Int = 1080): android.graphics.Bitmap? {
        try {
            val cacheDir = File(context.cacheDir, "changelog_images").apply { if (!exists()) mkdirs() }
            val safeName = "img_${urlString.hashCode()}.cache"
            val cachedFile = File(cacheDir, safeName)

            if (!cachedFile.exists() || cachedFile.length() == 0L) {
                var currentUrl = urlString
                var redirectCount = 0
                var finalConn: HttpURLConnection? = null
                var finalStream: InputStream? = null

                while (redirectCount < 6) {
                    val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 15000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Android; LIMI App)")
                        setRequestProperty("Accept", "image/*,*/*")
                        instanceFollowRedirects = true
                    }
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location") ?: break
                        currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
                        redirectCount++
                        conn.disconnect()
                    } else if (code in 200..299) {
                        finalConn = conn
                        finalStream = conn.inputStream
                        break
                    } else {
                        conn.disconnect()
                        break
                    }
                }

                finalStream?.use { input ->
                    FileOutputStream(cachedFile).use { output ->
                        input.copyTo(output)
                    }
                }
                finalConn?.disconnect()
            }

            if (!cachedFile.exists() || cachedFile.length() == 0L) return null

            val boundsOptions = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath, boundsOptions)

            var sampleSize = 1
            if (boundsOptions.outWidth > maxWidth) {
                sampleSize = boundsOptions.outWidth / maxWidth
                if (sampleSize < 1) sampleSize = 1
            }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }

            return android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath, decodeOptions)
        } catch (e: Throwable) {
            return null
        }
    }

    /**
     * Mở hộp thoại phóng to ảnh toàn màn hình chất lượng cao như Limi AI khi người dùng bấm vào ảnh
     */
    private fun showFullScreenImageDialog(activity: Activity, bitmap: android.graphics.Bitmap) {
        com.xiaomi.fixnotification.ai.LimiImageViewerHelper.show(activity, bitmap, "Minh họa cập nhật")
    }

    /**
     * Lấy versionName từ package hiện tại
     */
    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.2.8.5.ntd"
        } catch (_: Throwable) {
            "1.2.8.5.ntd"
        }
    }

    /**
     * So sánh 2 chuỗi version, kiểm tra xem bản remote có mới hơn bản local không
     */
    fun isNewerVersion(current: String, remote: String): Boolean {
        try {
            val curClean = current.lowercase(Locale.ROOT).removePrefix("v").substringBefore("-")
            val remClean = remote.lowercase(Locale.ROOT).removePrefix("v").substringBefore("-")

            val curSegments = curClean.split(".").mapNotNull { it.toIntOrNull() }
            val remSegments = remClean.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(curSegments.size, remSegments.size)
            for (i in 0 until maxLen) {
                val c = curSegments.getOrElse(i) { 0 }
                val r = remSegments.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }

            // Nếu các số phiên bản bằng nhau (ví dụ 1.3.3.1.ntd vs v1.3.3.1.ntd hoặc 1.3.3.1), không coi là mới hơn
            return false
        } catch (_: Throwable) {
            return false
        }
    }

    /**
     * Gọi API GitHub Releases để lấy bản phát hành mới nhất
     */
    private fun fetchLatestRelease(currentVersion: String): UpdateInfo {
        val url = URL(API_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("User-Agent", "LimiApp/$currentVersion")
            setRequestProperty("Accept", "application/vnd.github.v3+json")
        }

        val code = conn.responseCode
        if (code == 404) {
            // Chưa có release nào trên repo GitHub -> Coi như đã là bản mới nhất
            return UpdateInfo(
                tagName = "v$currentVersion",
                versionName = currentVersion,
                title = "Bộ công cụ LIMI",
                changelog = "",
                downloadUrl = "",
                apkSize = 0,
                hasUpdate = false
            )
        }

        if (code !in 200..299) {
            throw IllegalStateException("GitHub trả về mã phản hồi HTTP $code")
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)

        val tagName = json.optString("tag_name", "").trim()
        val title = json.optString("name", tagName)
        val body = json.optString("body", "")

        val assets = json.optJSONArray("assets")
        var downloadUrl = ""
        var apkSize: Long = 0

        if (assets != null && assets.length() > 0) {
            // Tìm asset có đuôi .apk (ưu tiên file có tên Limi)
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = asset.optString("browser_download_url", "")
                    apkSize = asset.optLong("size", 0L)
                    if (assetName.contains("Limi", ignoreCase = true)) {
                        break // Ưu tiên bản chính thức Limi
                    }
                }
            }
        }

        val cleanTag = tagName.removePrefix("v").removePrefix("V")
        val hasUpdate = isNewerVersion(currentVersion, cleanTag) && downloadUrl.isNotBlank()

        return UpdateInfo(
            tagName = if (tagName.startsWith("v", ignoreCase = true)) tagName else "v$tagName",
            versionName = cleanTag,
            title = title,
            changelog = body,
            downloadUrl = downloadUrl,
            apkSize = apkSize,
            hasUpdate = hasUpdate
        )
    }

    /**
     * Tải file APK từ GitHub và tự động gọi cài đặt
     */
    private fun startDownloadAndInstall(
        activity: Activity,
        downloadUrl: String,
        expectedSize: Long,
        isCancelled: AtomicBoolean,
        dialog: AlertDialog,
        progressView: ProgressBar,
        tvPercent: TextView,
        tvBytes: TextView,
        tvSpeed: TextView,
        tvStatusTitle: TextView,
        btnCancel: MaterialButton
    ) {
        val downloadDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
        val apkFile = File(downloadDir, "Limi_Latest_Update.apk")

        btnCancel.setOnClickListener {
            isCancelled.set(true)
            dialog.dismiss()
        }

        executor.execute {
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            var conn: HttpURLConnection? = null

            try {
                var currentUrl = downloadUrl
                var redirectCount = 0

                // Xử lý follow HTTP Redirects (GitHub Release link trỏ sang AWS S3)
                while (redirectCount < 6) {
                    val url = URL(currentUrl)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("User-Agent", "Mozilla/5.0 LimiApp")
                    }

                    val code = conn.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val newLocation = conn.getHeaderField("Location")
                        if (!newLocation.isNullOrBlank()) {
                            currentUrl = newLocation
                            redirectCount++
                            continue
                        }
                    }
                    break
                }

                if (conn == null || conn.responseCode !in 200..299) {
                    throw IllegalStateException("Không thể tải file (Mã lỗi ${conn?.responseCode ?: -1})")
                }

                val totalLength = if (conn.contentLengthLong > 0) conn.contentLengthLong else expectedSize
                if (apkFile.exists()) apkFile.delete()

                inputStream = BufferedInputStream(conn.inputStream)
                outputStream = FileOutputStream(apkFile)

                val data = ByteArray(8192)
                var totalDownloaded: Long = 0
                var lastTime = System.currentTimeMillis()
                var lastDownloaded = 0L

                var count: Int
                while (inputStream.read(data).also { count = it } != -1) {
                    if (isCancelled.get()) {
                        outputStream.flush()
                        outputStream.close()
                        inputStream.close()
                        if (apkFile.exists()) apkFile.delete()
                        return@execute
                    }

                    outputStream.write(data, 0, count)
                    totalDownloaded += count

                    val now = System.currentTimeMillis()
                    if (now - lastTime >= 350) {
                        val speedBytesPerSec = ((totalDownloaded - lastDownloaded) * 1000) / maxOf(1, now - lastTime)
                        val speedStr = formatSpeed(speedBytesPerSec)
                        val percent = if (totalLength > 0) ((totalDownloaded * 100) / totalLength).toInt() else 0

                        lastTime = now
                        lastDownloaded = totalDownloaded

                        mainHandler.post {
                            if (activity.isFinishing || activity.isDestroyed) return@post
                            progressView.progress = percent
                            tvPercent.text = "$percent%"
                            tvBytes.text = "${formatFileSize(totalDownloaded)} / ${formatFileSize(totalLength)}"
                            tvSpeed.text = speedStr
                        }
                    }
                }

                outputStream.flush()
                apkFile.setReadable(true, false)

                // Hoàn tất tải xuống
                mainHandler.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    progressView.progress = 100
                    tvPercent.text = "100%"
                    tvBytes.text = "${formatFileSize(totalDownloaded)} / ${formatFileSize(totalLength)}"
                    tvSpeed.text = "Hoàn tất"
                    tvStatusTitle.text = "Đang tiến hành cài đặt bản cập nhật..."

                    // Bắt đầu cài đặt
                    executeInstall(activity, apkFile, dialog)
                }
            } catch (e: Throwable) {
                if (isCancelled.get()) return@execute
                mainHandler.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    Toast.makeText(activity, "Lỗi tải bản cập nhật: ${e.message}", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
            } finally {
                try { outputStream?.close() } catch (_: Throwable) {}
                try { inputStream?.close() } catch (_: Throwable) {}
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Thực hiện cài đặt: Ưu tiên cài ngầm 1 chạm qua Shizuku, nếu không có Shizuku thì gọi trình cài đặt hệ thống
     */
    private fun executeInstall(activity: Activity, apkFile: File, dialog: AlertDialog) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(activity, "File cài đặt bị lỗi hoặc không tồn tại.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            return
        }

        // Cách 1: Cài đặt trực tiếp 1 chạm qua Shizuku nếu đang hoạt động
        if (ShizukuUtils.hasShizukuPermission()) {
            executor.execute {
                val tempAdbPath = "/data/local/tmp/limi_update.apk"
                val cmd = "cp '${apkFile.absolutePath}' $tempAdbPath 2>/dev/null || cat '${apkFile.absolutePath}' > $tempAdbPath && chmod 644 $tempAdbPath && pm install -r -d $tempAdbPath; rm -f $tempAdbPath"
                val result = ShizukuUtils.execShizukuCommand(cmd)

                mainHandler.post {
                    if (result.exitCode == 0 || result.stdout.contains("Success", ignoreCase = true)) {
                        Toast.makeText(activity, "Cập nhật LIMI thành công!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        // Nếu Shizuku gặp lỗi phân quyền pm install -> Chuyển sang trình cài đặt hệ thống
                        installViaSystem(activity, apkFile, dialog)
                    }
                }
            }
        } else {
            // Cách 2: Trình cài đặt hệ thống Android tiêu chuẩn
            installViaSystem(activity, apkFile, dialog)
        }
    }

    /**
     * Cài đặt qua Android PackageInstaller chuẩn (FileProvider)
     */
    private fun installViaSystem(activity: Activity, apkFile: File, dialog: AlertDialog) {
        pendingInstallApk = apkFile

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(activity, "Vui lòng cho phép quyền cài đặt ứng dụng để LIMI tự nâng cấp", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                dialog.dismiss()
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(installIntent)
            dialog.dismiss()
        } catch (e: Throwable) {
            Toast.makeText(activity, "Không thể mở gói cài đặt: ${e.message}", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }
    }

    /**
     * Kiểm tra và tiếp tục cài đặt nếu người dùng vừa cấp quyền cài đặt ứng dụng không rõ nguồn gốc
     */
    fun onResumeCheckPendingInstall(activity: Activity) {
        val apk = pendingInstallApk ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.packageManager.canRequestPackageInstalls()) {
            if (apk.exists()) {
                try {
                    val apkUri = FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        apk
                    )
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(installIntent)
                    pendingInstallApk = null
                } catch (_: Throwable) {}
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 KB/s"
        val kb = bytesPerSec / 1024.0
        return if (kb > 1024) {
            String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
        } else {
            String.format(Locale.US, "%.0f KB/s", kb)
        }
    }
}

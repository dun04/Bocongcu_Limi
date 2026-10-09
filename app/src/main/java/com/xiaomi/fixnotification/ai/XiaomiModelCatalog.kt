package com.xiaomi.fixnotification.ai

import android.content.Context
import android.os.Build
import java.util.Locale

data class XiaomiModelInfo(
    val modelCode: String,
    val marketingName: String,
    val series: String = "",
    val chipset: String = "",
    val defaultOs: String = ""
)

/**
 * Quản lý tra cứu model máy Xiaomi / Redmi / POCO động từ Google Sheets
 * Không lưu hardcode tĩnh trong code -> Triệt tiêu hiện tượng lag giật và truy xuất rườm rà
 */
object XiaomiModelCatalog {

    /**
     * Tra cứu tên thương mại động trực tiếp từ dữ liệu Google Sheet đã đồng bộ trong bộ nhớ RAM
     */
    fun findMarketingName(modelCode: String?): String? {
        if (modelCode.isNullOrBlank()) return null
        val clean = modelCode.uppercase(Locale.US).trim()

        val items = LimiKnowledgeBase.getCustomKnowledge()
        for (item in items) {
            val allKw = item.keywords.map { it.uppercase(Locale.US).trim() }
            if (allKw.any { it.contains(clean) || (clean.length >= 8 && it.contains(clean.dropLast(1))) }) {
                return item.title.replace("📱", "").replace(Regex("(?i)\\(.*\\)"), "").trim()
            }
        }
        return null
    }

    /**
     * Trích xuất mã máy Xiaomi xuất hiện trong chuỗi câu hỏi của người dùng
     * Format mã máy Xiaomi: 4 chữ số năm/tháng (22xx, 23xx, 24xx, 25xx, 26xx...) hoặc mã Mi (M21xx, M20xx, M19xx...)
     */
    fun extractModelCodeFromQuery(query: String): String? {
        val regex = Regex("""\b(2[0-9]{3}[0-9A-Z]{4,8}|M[0-9]{4}[A-Z0-9]{2,6})\b""", RegexOption.IGNORE_CASE)
        val match = regex.find(query)
        if (match != null) {
            val candidate = match.value.uppercase(Locale.US)
            if (candidate.length in 7..14) {
                return candidate
            }
        }
        return null
    }

    /**
     * Lấy thông tin thiết bị đang chạy ứng dụng
     */
    fun getRunningDeviceSummary(context: Context? = null): String {
        val rawModel = Build.MODEL.trim()
        val rawDevice = Build.DEVICE.trim()
        val marketName = findMarketingName(rawModel) ?: findMarketingName(rawDevice)

        return if (marketName != null) {
            "$marketName (Mã máy: $rawModel)"
        } else {
            "${Build.MANUFACTURER} $rawModel (Device: $rawDevice)"
        }
    }
}

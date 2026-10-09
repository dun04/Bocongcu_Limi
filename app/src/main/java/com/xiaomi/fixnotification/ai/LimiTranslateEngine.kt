package com.xiaomi.fixnotification.ai

import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Bộ máy Dịch thuật Đa tầng Siêu Tốc (Zero API Key Bottleneck)
 *
 * Tầng 1: Google Translate CDN (translate.googleapis.com) - Miễn phí, 0.05s, chịu tải không giới hạn thiết bị
 * Tầng 2: MyMemory Translation API dự phòng khi mạng hạn chế
 */
object LimiTranslateEngine {

    data class TranslationResult(
        val originalText: String,
        val translatedText: String,
        val sourceLanguage: String = "auto",
        val targetLanguage: String,
        val isSuccess: Boolean,
        val engine: String = "Google Engine"
    )

    /**
     * Dịch nhanh văn bản sang ngôn ngữ đích.
     * @param text Nội dung cần dịch
     * @param targetLang Mã ngôn ngữ đích ("vi", "en", "zh-CN", "ja", "ko", "fr", "de", "ru",...)
     */
    fun translate(text: String, targetLang: String = "vi"): TranslationResult {
        if (text.isBlank()) {
            return TranslationResult(
                originalText = text,
                translatedText = "",
                targetLanguage = targetLang,
                isSuccess = false,
                engine = "None"
            )
        }

        // 1. Thử Tầng 1: Google Translate Engine
        try {
            val googleResult = translateViaGoogle(text, targetLang)
            if (googleResult.isNotBlank()) {
                return TranslationResult(
                    originalText = text,
                    translatedText = googleResult,
                    targetLanguage = targetLang,
                    isSuccess = true,
                    engine = "Google Translate Engine"
                )
            }
        } catch (_: Throwable) {}

        // 2. Thử Tầng 2: MyMemory Fallback Engine
        try {
            val fallbackResult = translateViaMyMemory(text, targetLang)
            if (fallbackResult.isNotBlank()) {
                return TranslationResult(
                    originalText = text,
                    translatedText = fallbackResult,
                    targetLanguage = targetLang,
                    isSuccess = true,
                    engine = "MyMemory Backup Engine"
                )
            }
        } catch (_: Throwable) {}

        return TranslationResult(
            originalText = text,
            translatedText = "",
            targetLanguage = targetLang,
            isSuccess = false,
            engine = "Failed"
        )
    }

    /**
     * Gọi endpoint Google Translate CDN trực tiếp từ client (Không tốn token API Key)
     */
    private fun translateViaGoogle(text: String, targetLang: String): String {
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val endpoint = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
        
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0)")
            setRequestProperty("Accept-Charset", "UTF-8")
        }

        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_OK) {
            val rawResponse = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            val jsonArray = JSONArray(rawResponse)
            val sentencesArray = jsonArray.optJSONArray(0) ?: return ""
            
            val sb = StringBuilder()
            for (i in 0 until sentencesArray.length()) {
                val sentenceItem = sentencesArray.optJSONArray(i)
                if (sentenceItem != null) {
                    val translatedPart = sentenceItem.optString(0, "")
                    sb.append(translatedPart)
                }
            }
            return sb.toString().trim()
        }
        return ""
    }

    /**
     * Gọi MyMemory REST API dự phòng
     */
    private fun translateViaMyMemory(text: String, targetLang: String): String {
        val pair = "autodetect|$targetLang"
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val endpoint = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=$pair"

        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        }

        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val raw = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            val json = org.json.JSONObject(raw)
            val responseData = json.optJSONObject("responseData")
            val translated = responseData?.optString("translatedText") ?: ""
            if (translated.isNotBlank() && !translated.startsWith("MYMEMORY WARNING")) {
                return translated
            }
        }
        return ""
    }

    /**
     * Nhận diện ngôn ngữ đích từ câu lệnh của người dùng
     */
    fun parseTargetLanguage(query: String): Pair<String, String> {
        val qLower = query.lowercase(Locale.ROOT)
        return when {
            qLower.contains("tiếng anh") || qLower.contains("sang anh") || qLower.contains("to english") || qLower.contains("in english") -> Pair("en", "Tiếng Anh (English)")
            qLower.contains("tiếng trung") || qLower.contains("sang trung") || qLower.contains("tiếng hoa") || qLower.contains("to chinese") -> Pair("zh-CN", "Tiếng Trung (Chinese)")
            qLower.contains("tiếng nhật") || qLower.contains("sang nhật") || qLower.contains("to japanese") -> Pair("ja", "Tiếng Nhật (Japanese)")
            qLower.contains("tiếng hàn") || qLower.contains("sang hàn") || qLower.contains("to korean") -> Pair("ko", "Tiếng Hàn (Korean)")
            qLower.contains("tiếng pháp") || qLower.contains("sang pháp") || qLower.contains("to french") -> Pair("fr", "Tiếng Pháp (French)")
            qLower.contains("tiếng đức") || qLower.contains("sang đức") || qLower.contains("to german") -> Pair("de", "Tiếng Đức (German)")
            qLower.contains("tiếng nga") || qLower.contains("sang nga") || qLower.contains("to russian") -> Pair("ru", "Tiếng Nga (Russian)")
            qLower.contains("tiếng việt") || qLower.contains("sang việt") || qLower.contains("to vietnamese") -> Pair("vi", "Tiếng Việt (Vietnamese)")
            else -> {
                // Nếu người dùng nhập tiếng nước ngoài hoặc không nói rõ -> mặc định dịch sang Tiếng Việt
                Pair("vi", "Tiếng Việt (Vietnamese)")
            }
        }
    }

    /**
     * Bóc tách đoạn văn bản thực sự cần dịch từ câu lệnh
     * Ví dụ: "Dịch sang tiếng anh: Xin chào bạn" -> "Xin chào bạn"
     */
    fun extractContentToTranslate(query: String): String {
        var clean = query.trim()
        
        // Cắt theo dấu hai chấm nếu có
        if (clean.contains(":")) {
            val parts = clean.split(":", limit = 2)
            if (parts.size == 2 && parts[1].trim().isNotEmpty()) {
                return parts[1].trim()
            }
        }

        // Loại bỏ các tiền tố dịch thông dụng
        val prefixes = listOf(
            "dịch sang tiếng anh", "dịch sang tiếng việt", "dịch sang tiếng trung",
            "dịch sang tiếng nhật", "dịch sang tiếng hàn", "dịch sang tiếng pháp",
            "dịch sang tiếng đức", "dịch sang tiếng nga", "dịch sang anh", "dịch sang việt",
            "dịch đoạn này sang tiếng anh", "dịch đoạn này sang tiếng việt", "dịch đoạn này",
            "dịch bài này sang tiếng anh", "dịch bài này", "dịch câu này", "dịch giúp tôi",
            "dịch giùm tôi", "dịch giùm", "dịch hộ", "dịch hộ tôi", "dịch văn bản",
            "dịch từ", "dịch:", "dịch ", "translate to english", "translate to vietnamese",
            "translate:", "translate "
        )

        for (prefix in prefixes) {
            if (clean.lowercase(Locale.ROOT).startsWith(prefix)) {
                clean = clean.substring(prefix.length).trim()
                if (clean.startsWith(":") || clean.startsWith("-") || clean.startsWith(",")) {
                    clean = clean.substring(1).trim()
                }
                break
            }
        }

        return clean
    }
}

package com.xiaomi.fixnotification.ai

import java.util.Locale

/**
 * Bộ máy Tóm Tắt Văn Bản Đa Tầng Siêu Nhẹ (Zero API Key Bottleneck)
 *
 * Tầng 1: Thuật toán Trích xuất Ý chính Nhanh (Extractive Summarization / TF-IDF Scoring) - 0.01s, chạy Offline cục bộ trên máy, không tốn API Key
 * Tầng 2: Kết nối Gemini Multi-Model AI khi người dùng yêu cầu tóm tắt chuyên sâu/viết lại văn phong
 */
object LimiSummarizerEngine {

    data class SummaryResult(
        val originalText: String,
        val summaryText: String,
        val keySentences: List<String>,
        val wordCountBefore: Int,
        val wordCountAfter: Int,
        val compressionRatio: Int, // Tỷ lệ thu gọn (%)
        val isSuccess: Boolean
    )

    /**
     * Tóm tắt nhanh văn bản bằng giải thuật trích xuất câu trọng tâm (0 tốn mạng, 0 tốn token)
     */
    fun summarize(text: String, maxSentences: Int = 3): SummaryResult {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return SummaryResult(trimmed, "", emptyList(), 0, 0, 0, false)
        }

        // Tách các câu dựa vào dấu chấm, chấm than, hỏi chấm, xuống dòng
        val sentences = trimmed.split(Regex("(?<=[.!?\\n])\\s+"))
            .map { it.trim() }
            .filter { it.length >= 15 } // Bỏ qua các câu quá ngắn/vụn vặt

        val wordsBefore = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }.size

        // Nếu văn bản quá ngắn (dưới 3 câu) -> Giữ nguyên câu
        if (sentences.size <= maxSentences) {
            val summary = sentences.joinToString("\n• ", prefix = "• ")
            val wordsAfter = summary.split(Regex("\\s+")).filter { it.isNotBlank() }.size
            val ratio = if (wordsBefore > 0) ((wordsBefore - wordsAfter).coerceAtLeast(0) * 100) / wordsBefore else 0
            return SummaryResult(trimmed, summary, sentences, wordsBefore, wordsAfter, ratio, true)
        }

        // 1. Đếm tần suất xuất hiện của từ (Frequency Map)
        val stopWords = setOf(
            "là", "và", "của", "có", "trong", "được", "cho", "với", "các", "những",
            "khi", "để", "một", "này", "đó", "thì", "mà", "như", "ra", "vào",
            "ở", "tại", "từ", "về", "đã", "sẽ", "đang", "rất", "nhiều", "lại",
            "the", "and", "is", "of", "to", "in", "for", "with", "that", "this", "on", "at"
        )

        val wordFreq = mutableMapOf<String, Int>()
        val allWords = trimmed.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{Nd}]+"))
        for (w in allWords) {
            if (w.length >= 2 && w !in stopWords) {
                wordFreq[w] = (wordFreq[w] ?: 0) + 1
            }
        }

        val maxFreq = wordFreq.values.maxOrNull() ?: 1

        // 2. Chấm điểm từng câu dựa trên trọng số từ khóa & vị trí xuất hiện (ưu tiên câu đầu và câu cuối)
        val sentenceScores = sentences.mapIndexed { index, sentence ->
            val words = sentence.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{Nd}]+"))
            var score = 0.0
            for (w in words) {
                if (w in wordFreq) {
                    score += (wordFreq[w]!!.toDouble() / maxFreq)
                }
            }

            // Chuẩn hóa theo độ dài câu để không thiên vị câu quá dài
            val lengthFactor = if (words.isNotEmpty()) Math.sqrt(words.size.toDouble()) else 1.0
            var normalizedScore = score / lengthFactor

            // Tăng ưu tiên cho câu mở đầu (lead sentence) và câu kết
            if (index == 0) normalizedScore *= 1.4
            if (index == sentences.size - 1) normalizedScore *= 1.2

            Pair(sentence, normalizedScore)
        }

        // 3. Lấy Top N câu có điểm cao nhất theo thứ tự xuất hiện gốc trong bài
        val topSentences = sentenceScores
            .sortedByDescending { it.second }
            .take(maxSentences)
            .map { it.first }

        // Sắp xếp lại theo đúng trình tự tự nhiên trong bài gốc
        val orderedSummary = sentences.filter { it in topSentences }
        val summaryFormatted = orderedSummary.joinToString("\n• ", prefix = "• ")
        val wordsAfter = summaryFormatted.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val ratio = if (wordsBefore > 0) ((wordsBefore - wordsAfter).coerceAtLeast(0) * 100) / wordsBefore else 0

        return SummaryResult(
            originalText = trimmed,
            summaryText = summaryFormatted,
            keySentences = orderedSummary,
            wordCountBefore = wordsBefore,
            wordCountAfter = wordsAfter,
            compressionRatio = ratio,
            isSuccess = true
        )
    }

    /**
     * Bóc tách đoạn văn bản thực sự cần tóm tắt từ câu lệnh
     * Ví dụ: "Tóm tắt bài này giúp tôi: [văn bản...]" -> "[văn bản...]"
     */
    fun extractContentToSummarize(query: String): String {
        var clean = query.trim()
        if (clean.contains(":")) {
            val parts = clean.split(":", limit = 2)
            if (parts.size == 2 && parts[1].trim().isNotEmpty()) {
                return parts[1].trim()
            }
        }

        val prefixes = listOf(
            "tóm tắt bài này", "tóm tắt đoạn này", "tóm tắt văn bản", "tóm tắt ý chính",
            "tóm tắt ngắn gọn", "tóm tắt giúp tôi", "tóm tắt giùm tôi", "tóm tắt hộ",
            "tóm tắt nội dung", "tóm tắt bài viết", "tóm tắt:", "tóm tắt ",
            "summarize this", "summarize article", "summarize:", "summarize "
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

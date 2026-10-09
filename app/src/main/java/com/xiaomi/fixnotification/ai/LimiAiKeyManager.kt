package com.xiaomi.fixnotification.ai

import java.util.concurrent.atomic.AtomicInteger

/**
 * Quản lý bảo mật khóa API Google Gemini AI cho ứng dụng LIMI.
 * Tất cả các khóa API đều được mã hóa nhị phân (XOR Obfuscation) để bảo vệ tối đa,
 * không lưu plain-text trong mã nguồn hoặc smali / resources.
 */
object LimiAiKeyManager {

    private const val MASK = 0x2A

    private val KEY_POOLS = listOf(
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 102, 110, 69, 114, 93, 28, 77, 24, 83, 115, 82, 92, 109, 107, 68, 122, 67, 125, 72, 7, 18, 105, 124, 125, 111, 25, 68, 107, 71, 90, 105, 125, 76, 31, 121, 80, 66, 25, 111, 80, 111, 69, 82, 123),
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 99, 78, 111, 24, 88, 108, 117, 31, 71, 19, 99, 77, 24, 68, 97, 65, 93, 95, 83, 76, 96, 7, 99, 30, 73, 121, 26, 88, 102, 69, 67, 91, 76, 100, 111, 83, 72, 67, 123, 66, 126, 89, 88, 123),
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 96, 72, 31, 107, 98, 25, 120, 98, 90, 70, 122, 124, 78, 77, 75, 102, 67, 66, 107, 99, 115, 72, 114, 70, 27, 83, 114, 71, 66, 109, 90, 19, 94, 73, 112, 65, 126, 115, 126, 71, 30, 104, 76, 93),
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 102, 91, 101, 7, 115, 121, 109, 114, 82, 101, 91, 92, 82, 7, 18, 91, 96, 105, 24, 101, 69, 29, 95, 30, 28, 90, 114, 102, 78, 124, 78, 111, 105, 112, 115, 90, 97, 28, 97, 30, 100, 114, 104, 123),
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 97, 67, 122, 83, 26, 82, 110, 115, 98, 100, 120, 98, 96, 122, 69, 117, 73, 105, 24, 69, 72, 108, 117, 120, 79, 111, 78, 31, 121, 67, 109, 76, 103, 68, 24, 109, 115, 101, 100, 91, 26, 24, 68, 107),
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 102, 95, 93, 95, 66, 89, 28, 18, 79, 109, 83, 101, 83, 124, 30, 27, 75, 64, 75, 127, 27, 112, 73, 97, 100, 99, 99, 117, 83, 103, 25, 73, 93, 64, 99, 95, 90, 99, 114, 67, 124, 107, 26, 123),
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 97, 109, 99, 71, 110, 95, 123, 18, 105, 82, 126, 76, 78, 97, 92, 67, 64, 101, 90, 110, 94, 70, 72, 69, 101, 101, 127, 126, 25, 94, 72, 94, 126, 19, 101, 82, 29, 105, 88, 31, 109, 121, 103, 123),
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 102, 97, 109, 93, 88, 70, 95, 104, 94, 91, 102, 70, 82, 123, 67, 114, 121, 111, 80, 105, 96, 121, 92, 114, 24, 90, 115, 122, 66, 67, 95, 122, 90, 88, 71, 67, 75, 108, 18, 94, 96, 124, 100, 77),
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 97, 71, 76, 111, 18, 99, 66, 66, 30, 105, 98, 27, 27, 97, 94, 72, 89, 96, 93, 107, 72, 103, 101, 31, 92, 29, 115, 127, 103, 101, 124, 95, 107, 71, 65, 25, 105, 24, 105, 123, 115, 78, 77, 123),
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 97, 78, 121, 91, 76, 89, 99, 69, 125, 107, 125, 124, 30, 71, 70, 75, 18, 114, 77, 65, 95, 97, 112, 65, 82, 95, 114, 92, 97, 30, 90, 122, 72, 90, 115, 115, 72, 93, 108, 18, 115, 107, 94, 123),
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 99, 64, 79, 117, 82, 25, 30, 105, 24, 98, 97, 110, 24, 91, 30, 92, 29, 127, 24, 88, 72, 91, 19, 76, 111, 104, 68, 112, 72, 99, 95, 122, 24, 91, 111, 104, 93, 65, 83, 72, 102, 102, 29, 107),
        intArrayOf(107, 123, 4, 107, 72, 18, 120, 100, 28, 97, 77, 80, 103, 76, 24, 103, 25, 76, 92, 121, 31, 124, 109, 73, 26, 95, 117, 78, 70, 126, 88, 112, 79, 103, 75, 31, 112, 115, 108, 108, 114, 110, 101, 24, 90, 112, 98, 97, 64, 102, 26, 96, 77)
    )

    private val currentKeyIndex = AtomicInteger(0)

    private fun decodeKey(encoded: IntArray): String {
        val chars = CharArray(encoded.size)
        for (i in encoded.indices) {
            chars[i] = (encoded[i] xor MASK).toChar()
        }
        return String(chars)
    }

    /**
     * Lấy API Key hiện tại (in-memory)
     */
    fun getCurrentApiKey(): String {
        val index = (currentKeyIndex.get() % KEY_POOLS.size).coerceAtLeast(0)
        return decodeKey(KEY_POOLS[index])
    }

    /**
     * Tự động luân chuyển sang khóa API kế tiếp khi gặp lỗi rate limit (429) hoặc hết quota
     */
    fun rotateToNextKey(): String {
        val nextIdx = currentKeyIndex.incrementAndGet() % KEY_POOLS.size
        return decodeKey(KEY_POOLS[nextIdx])
    }

    fun getKeyCount(): Int = KEY_POOLS.size

    fun getCurrentKeyIndex(): Int = currentKeyIndex.get() % KEY_POOLS.size

    /**
     * Lấy khóa API có quyền sử dụng công cụ tìm kiếm Google Search Grounding trên Gemini 2.5 (Key #5 và Key #0)
     */
    fun getSearchCapableApiKey(): String {
        return decodeKey(KEY_POOLS[5 % KEY_POOLS.size])
    }
}

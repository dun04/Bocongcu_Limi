package com.xiaomi.fixnotification.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object LimiImageSearchEngine {

    private val imageCache = object : LruCache<String, Bitmap>(30 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    private val executor = Executors.newFixedThreadPool(4)

    fun cacheImageLocally(topic: String, bitmap: Bitmap, context: android.content.Context? = null) {
        val key = topic.trim().lowercase()
        if (key.isNotEmpty()) {
            imageCache.put(key, bitmap)
            val normKey = LimiAiService.stripAccents(key)
            if (normKey != key) {
                imageCache.put(normKey, bitmap)
            }
            // Không lưu file ảnh vào bộ nhớ máy (disk) để giữ dung lượng app luôn nhẹ
            if (context != null) {
                try {
                    val dir = java.io.File(context.filesDir, "limi_custom_images")
                    if (dir.exists()) {
                        dir.deleteRecursively()
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Nhận diện xem câu hỏi có chứa ý định tìm / hiển thị hình ảnh hay không
     */
    fun isImageSearchQuery(rawQuery: String, hasAttachedImage: Boolean = false): Boolean {
        // Nếu người dùng gửi kèm ảnh (OCR, tóm tắt, giải bài, phân tích ảnh) -> Mặc định KHÔNG tìm ảnh trên mạng
        if (hasAttachedImage) {
            val qNorm = LimiAiService.stripAccents(rawQuery.lowercase()).trim()
            if (!qNorm.startsWith("tim anh") && !qNorm.startsWith("tim them anh") && !qNorm.startsWith("kiem anh tren mang")) {
                return false
            }
        }

        val q = LimiAiService.normalizeVietnameseQuery(rawQuery).trim().lowercase()
        val stripped = LimiAiService.stripAccents(q)
        if (q.length < 2) return false

        // 1. BỘ LỌC LOẠI TRỪ TUYỆT ĐỐI (Negative Filter): Các câu hỏi tóm tắt, đọc chữ, giải bài, hỏi về nội dung ảnh người dùng gửi
        val analysisPatterns = listOf(
            "tóm tắt", "tom tat", "đoạn chat", "doan chat", "tin nhắn trong ảnh", "tin nhan trong anh",
            "trong ảnh", "trong anh", "trong hình", "trong hinh", "trong bức ảnh", "trong buc anh", "trong tấm ảnh", "trong tam anh",
            "từ ảnh", "tu anh", "từ hình", "tu hinh", "ở ảnh", "o anh", "trên ảnh", "tren anh",
            "đọc ảnh", "doc anh", "đọc chữ", "doc chu", "đọc văn bản", "doc van ban", "đọc nội dung", "doc noi dung",
            "dịch ảnh", "dich anh", "dịch chữ", "dich chu", "dịch văn bản", "dich van ban", "dịch đoạn", "dich doan",
            "giải bài", "giai bai", "giải toán", "giai toan", "giải câu", "giai cau", "giải giúp", "giai giup",
            "phân tích ảnh", "phan tich anh", "phân tích hình", "phan tich hinh", "phân tích nội dung", "phan tich noi dung",
            "nhận diện", "nhan dien", "nhận dạng", "nhan dang", "nhận biết", "nhan biet", "nhìn vào ảnh", "nhin vao anh",
            "ảnh này là", "anh nay la", "ảnh này có", "anh nay co", "ảnh này chụp", "anh nay chup", "ảnh này nói", "anh nay noi",
            "ảnh đính kèm", "anh dinh kem", "ảnh vừa gửi", "anh vua gui", "ảnh tôi gửi", "anh toi gui", "ảnh đã gửi", "anh da gui",
            "ảnh chụp màn hình", "anh chup man hinh", "ảnh màn hình", "anh man hinh", "screenshot",
            "ai trong ảnh", "người trong ảnh", "chữ trong ảnh", "văn bản trong ảnh", "nội dung ảnh", "chi tiết trong ảnh",
            "xem ảnh này giúp", "nhìn ảnh này", "xem giúp tôi ảnh này", "xem hộ ảnh này"
        )

        for (ap in analysisPatterns) {
            if (q.contains(ap) || stripped.contains(ap)) {
                // Trừ khi câu bắt đầu bằng lệnh tìm kiếm ảnh rõ ràng trên internet
                if (!stripped.startsWith("tim anh ") && !stripped.startsWith("tim hinh ") && !stripped.startsWith("cho toi xem anh cua ")) {
                    return false
                }
            }
        }

        // 2. Câu hỏi ngữ cảnh yêu cầu xem ảnh tiếp nối (VD: "đưa xem ảnh mẫu", "cho xem ảnh 3 nơi đó", "ảnh đâu")
        if (isContextualQuery(rawQuery)) {
            return true
        }

        // 3. Tiền tố bắt đầu yêu cầu tìm ảnh rõ ràng
        val startPhrases = listOf(
            "tìm ảnh", "tim anh", "tìm hình", "tim hinh", "tìm hình ảnh", "tim hinh anh", "tìm kiếm ảnh", "tim kiem anh",
            "cho tôi xem ảnh", "cho toi xem anh", "cho xem ảnh", "cho xem anh", "cho xem hình", "cho xem hinh",
            "cho mình xem ảnh", "cho minh xem anh", "cho em xem ảnh", "cho em xem anh",
            "cho xin ảnh", "cho xin anh", "cho xin hình", "cho xin hinh", "xin ảnh", "xin anh", "xin hình", "xin hinh",
            "gửi ảnh", "gui anh", "gửi hình", "gui hinh", "gửi cho tôi ảnh", "gửi cho mình ảnh",
            "đưa xem ảnh", "dua xem anh", "đưa xem hình", "dua xem hinh", "đưa tôi xem ảnh", "đưa cho tôi xem ảnh",
            "lấy ảnh", "lay anh", "lấy hình", "lay hinh", "xuất ảnh", "xuat anh",
            "hãy tìm ảnh", "hay tim anh", "kiếm ảnh", "kiem anh", "kiếm hình", "kiem hinh",
            "ảnh của", "anh cua", "ảnh chiếc", "anh chiec", "ảnh xe", "anh xe", "ảnh ô tô", "anh o to",
            "ảnh bánh", "anh banh", "ảnh đồ", "anh do", "ảnh món", "anh mon", "ảnh quả", "anh qua",
            "ảnh trái", "anh trai", "ảnh con", "anh con", "ảnh hoa", "anh hoa", "ảnh cây", "anh cay",
            "ảnh người", "anh nguoi", "ảnh anime", "anh anime", "ảnh hoạt hình", "anh hoat hinh",
            "ảnh phong cảnh", "anh phong canh", "ảnh địa điểm", "anh dia diem", "ảnh thành phố", "anh thanh pho",
            "hình ảnh của", "hinh anh cua", "hình ảnh chiếc", "hinh anh chiec", "hình ảnh xe", "hinh anh xe",
            "hình ảnh bánh", "hinh anh banh", "hình ảnh món", "hinh anh mon", "hình ảnh hoa", "hinh anh hoa",
            "hình ảnh con", "hinh anh con", "hình ảnh quả", "hinh anh qua", "hình ảnh đẹp", "hinh anh dep",
            "ảnh chụp", "anh chup", "ảnh render", "anh render", "ảnh thực tế", "anh thuc te", "ảnh thực", "anh thuc",
            "xem ảnh", "xem anh", "xem hình", "xem hinh", "hiển thị ảnh", "hien thi anh", "bật ảnh", "bat anh",
            "tạo ảnh", "tao anh", "vẽ ảnh", "ve anh", "sinh ảnh", "sinh anh", "vẽ hình", "ve hinh"
        )

        for (s in startPhrases) {
            if (q.startsWith(s) || stripped.startsWith(s)) return true
        }

        // 4. Regex phát hiện tiền tố yêu cầu ảnh linh hoạt
        val flexibleRegex = Regex(
            "(?i)^(tìm|tim|kiếm|kiem|cho|gửi|gui|đưa|dua|xin|lấy|lay|xem|hiển thị|hien thi|xuất|xuat|tạo|tao|vẽ|ve|sinh)\\s+(giúp|giup|tôi|toi|mình|minh|em|anh)?\\s*(xem|thấy)?\\s*(ảnh|anh|hình|hinh|bức ảnh|buc anh|tấm ảnh|tam anh|bức hình|buc hinh|photo|image|picture)\\b"
        )
        if (flexibleRegex.containsMatchIn(q) || flexibleRegex.containsMatchIn(stripped)) {
            return true
        }

        // 5. Từ khóa trực tiếp về xe & món ăn cụ thể khi người dùng gõ ngắn
        val directSpecificKeywords = listOf(
            "ảnh vf9", "anh vf9", "ảnh vf 9", "anh vf 9", "ảnh vf3", "anh vf3", "ảnh vf8", "anh vf8",
            "ảnh su7", "anh su7", "ảnh xiaomi su7", "anh xiaomi su7", "ảnh hyperos", "anh hyperos",
            "ảnh bánh mì", "anh banh mi", "ảnh bánh mỳ", "anh banh my", "ảnh bánh mì que", "anh banh mi que",
            "ảnh bánh mì sợi", "anh banh mi soi", "ảnh phở bò", "anh pho bo", "ảnh bún chả", "anh bun cha",
            "ảnh hà giang", "ảnh hạ long", "ảnh sa pa", "ảnh sapa", "ảnh đà lạt", "ảnh ninh bình", "ảnh tràng an"
        )
        for (k in directSpecificKeywords) {
            if (q.contains(k) || stripped.contains(k)) return true
        }

        // 6. Hậu tố kết thúc bằng từ chỉ ảnh
        val endKeywords = listOf(
            " hình ảnh", " hinh anh", " bức ảnh", " buc anh", " tấm ảnh", " tam anh",
            " ảnh", " anh", " hình", " hinh", " photo", " image", " wallpaper", " hình nền", " hinh nen", " render"
        )
        for (e in endKeywords) {
            if ((q.endsWith(e) && q.length <= e.length + 30) || (stripped.endsWith(e) && stripped.length <= e.length + 30)) {
                return true
            }
        }

        return false
    }

    /**
     * Nhận diện xem câu hỏi yêu cầu ảnh có phải là câu tham chiếu ngữ cảnh (hỏi về đối tượng vừa nói trước đó) hay không
     */
    fun isContextualQuery(rawQuery: String): Boolean {
        val q = LimiAiService.normalizeVietnameseQuery(rawQuery).trim().lowercase()
        val stripped = LimiAiService.stripAccents(q).replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()

        val contextualPhrases = listOf(
            "mẫu", "mau", "ảnh mẫu", "anh mau", "hình mẫu", "hinh mau", "mẫu ảnh", "mau anh",
            "đưa xem ảnh mẫu", "dua xem anh mau", "cho xem ảnh mẫu", "cho xem anh mau", "cho xem mẫu",
            "cho xem ảnh", "cho xem anh", "đưa xem ảnh", "dua xem anh", "cho xin ảnh", "cho xin anh",
            "ảnh đâu", "anh dau", "xem ảnh đi", "xem anh di", "gửi ảnh đi", "gui anh di",
            "3 nơi đó", "3 noi do", "ba nơi đó", "các nơi đó", "cac noi do", "nơi đó", "noi do",
            "các địa điểm đó", "dia diem do", "địa điểm trên", "các nơi trên", "ở trên", "o tren",
            "vừa nói", "vua noi", "vừa rồi", "vua roi", "vừa gợi ý", "vua goi y",
            "nó", "no", "chúng", "chung", "đó", "do", "này", "nay", "ảnh 3 nơi đó", "anh 3 noi do",
            "ảnh của các nơi đó", "anh cua cac noi do", "xem hình các nơi đó", "xem hinh cac noi do"
        )

        for (cp in contextualPhrases) {
            if (q == cp || stripped == cp || q.startsWith("$cp ") || stripped.startsWith("$cp ") || q.endsWith(" $cp") || stripped.endsWith(" $cp")) {
                return true
            }
        }

        val cleanedNorm = LimiAiService.stripAccents(q).replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        return cleanedNorm in listOf("mau", "anh mau", "hinh mau", "mau anh", "noi do", "3 noi", "3 noi do", "ba noi", "cac noi", "dia diem", "no", "chung", "do", "nay", "dua xem anh mau", "dua xem anh", "cho xem anh mau", "cho xem anh")
    }

    /**
     * Trích xuất từ khóa chủ đề cốt lõi cần tìm ảnh (có hỗ trợ phân giải ngữ cảnh hội thoại)
     */
    fun extractSearchTopic(rawQuery: String, history: List<LimiChatMessage> = emptyList()): String {
        var q = rawQuery.trim()

        val prefixes = listOf(
            "cho tôi xin xem hình ảnh của ", "cho tôi xin xem hình ảnh chiếc ", "cho tôi xin xem hình ảnh món ", "cho tôi xin xem hình ảnh ",
            "cho tôi xem hình ảnh của ", "cho tôi xem hình ảnh chiếc ", "cho tôi xem hình ảnh món ", "cho tôi xem hình ảnh ",
            "cho tôi xem ảnh của ", "cho tôi xem ảnh chiếc ", "cho tôi xem ảnh món ", "cho tôi xem ảnh mẫu của ", "cho tôi xem ảnh mẫu ", "cho tôi xem ảnh ",
            "cho toi xem hinh anh cua ", "cho toi xem hinh anh ", "cho toi xem anh cua ", "cho toi xem anh ",
            "cho mình xem hình ảnh ", "cho mình xem ảnh mẫu ", "cho mình xem ảnh món ", "cho mình xem ảnh ",
            "cho em xem hình ảnh ", "cho em xem ảnh mẫu ", "cho em xem ảnh món ", "cho em xem ảnh ",
            "cho mình xin hình ảnh ", "cho mình xin ảnh mẫu ", "cho mình xin ảnh ",
            "cho em xin ảnh mẫu ", "cho em xin ảnh ", "cho xin hình ảnh ", "cho xin ảnh mẫu ", "cho xin ảnh ",
            "gửi cho tôi hình ảnh ", "gửi cho tôi ảnh ", "gửi cho mình ảnh ", "gửi ảnh mẫu ", "gửi ảnh của ", "gửi ảnh ", "gửi hình ",
            "đưa xem ảnh mẫu của ", "đưa xem ảnh mẫu ", "đưa xem ảnh ", "đưa tôi xem ảnh ", "đưa cho tôi xem ảnh ",
            "dua xem anh mau ", "dua xem anh ", "dua toi xem anh ",
            "cho xem ảnh mẫu của ", "cho xem ảnh mẫu ", "cho xem ảnh ", "cho xem hinh ", "cho xem ",
            "tìm kiếm hình ảnh của ", "tìm kiếm hình ảnh ", "tìm kiếm ảnh của ", "tìm kiếm ảnh ",
            "tìm hình ảnh của ", "tìm hình ảnh chiếc ", "tìm hình ảnh món ", "tìm hình ảnh ",
            "tim hinh anh cua ", "tim hinh anh chiec ", "tim hinh anh mon ", "tim hinh anh ",
            "tìm ảnh của ", "tìm ảnh chiếc ", "tìm ảnh món ", "tìm ảnh quán ", "tìm ảnh xe ", "tìm ảnh con ", "tìm ảnh quả ", "tìm ảnh ",
            "tim anh cua ", "tim anh chiec ", "tim anh mon ", "tim anh quan ", "tim anh xe ", "tim anh con ", "tim anh qua ", "tim anh ",
            "kiếm hình ảnh ", "kiếm ảnh của ", "kiếm ảnh ", "kiem hinh anh ", "kiem anh cua ", "kiem anh ",
            "tìm quán ", "tim quan ", "quán bán ", "quan ban ", "quán ", "quan ", "chỗ bán ", "cho ban ",
            "hãy tìm ảnh ", "hay tim anh ", "hãy vẽ ảnh ", "hay ve anh ", "vẽ cho tôi ảnh ", "vẽ ảnh ", "ve anh ",
            "tạo ảnh của ", "tạo ảnh ", "tao anh ", "sinh ảnh ", "sinh anh ",
            "hình ảnh của ", "hình ảnh chiếc ", "hình ảnh món ", "hình ảnh xe ", "hình ảnh ",
            "hinh anh cua ", "hinh anh chiec ", "hinh anh mon ", "hinh anh xe ", "hinh anh ",
            "ảnh ô tô ", "ảnh xe hơi ", "ảnh chiếc xe ", "ảnh xe ", "ảnh của ", "ảnh chiếc ", "ảnh món ", "ảnh quán ",
            "ảnh con ", "ảnh quả ", "ảnh về ", "ảnh đẹp của ", "ảnh mẫu của ", "ảnh mẫu ", "ảnh ",
            "anh o to ", "anh xe hoi ", "anh xe ", "anh cua ", "anh chiec ", "anh mon ", "anh quan ", "anh con ", "anh qua ", "anh ve ", "anh mau ", "anh "
        )

        for (p in prefixes) {
            if (q.startsWith(p, ignoreCase = true)) {
                q = q.substring(p.length).trim()
                break
            }
        }

        q = q.replace(Regex("(?i)\\s+(đẹp nhất|nét nhất|full hd|4k|chất lượng cao|được không|duoc khong|nhé|nha|đi|di|giúp tôi|giup toi|giúp mình|giup minh|giúp|giup|với|voi|cho tôi xem|cho mình xem|xem nào|xem thử)\\s*$"), "").trim()
        q = q.replace(Regex("(?i)\\s+(hình ảnh|hinh anh|bức ảnh|buc anh|tấm ảnh|tam anh|ảnh chụp|anh chup|ảnh mẫu|anh mau|ảnh|anh|hình|hinh|photo|wallpaper|hình nền|hinh nen)\\s*$"), "").trim()

        val qNorm = LimiAiService.stripAccents(q.lowercase()).replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val genericWords = setOf(
            "mau", "anh mau", "hinh mau", "mau anh", "noi do", "3 noi", "3 noi do", "ba noi", "ba noi do",
            "cac noi", "cac noi do", "cac noi tren", "dia diem", "cac dia diem", "noi tren", "o tren", "vua noi", "vua goi y",
            "no", "chung", "do", "nay", "anh", "hinh", "dua xem", "cho xem", "xem anh", "xem", "dau", "di", "cac dia danh",
            "nhung noi do", "nhung noi tren", "cac diem tren", "anh 3 noi do", "anh cac noi", "anh mau 3 noi", ""
        )

        // Nếu từ khóa quá chung chung (VD: "mẫu", "ảnh mẫu", "nơi đó", "3 nơi...") -> Tự động truy hồi ngữ cảnh từ lịch sử hội thoại
        if (genericWords.contains(qNorm) || isContextualQuery(rawQuery)) {
            if (history.isNotEmpty()) {
                val lastLimiMsg = history.lastOrNull { it.sender == MessageSender.LIMI && it.text.isNotBlank() }
                if (lastLimiMsg != null) {
                    val extracted = extractEntitiesFromText(lastLimiMsg.text)
                    if (extracted.isNotEmpty()) {
                        return extracted.first()
                    }
                }
                val lastUserMsg = history.dropLast(1).lastOrNull { it.sender == MessageSender.USER && it.text.isNotBlank() }
                if (lastUserMsg != null) {
                    val extracted = extractEntitiesFromText(lastUserMsg.text)
                    if (extracted.isNotEmpty()) {
                        return extracted.first()
                    }
                }
            }
            return ""
        }

        return q
    }

    /**
     * Trích xuất các thực thể món ăn / xe cộ / địa danh nổi bật trong đoạn văn bản.
     * ƯU TIÊN TUYỆT ĐỐI: Món ăn & Xe cộ -> Địa danh du lịch cụ thể -> Tỉnh thành chung chung.
     */
    fun extractEntitiesFromText(text: String): List<String> {
        val found = mutableListOf<String>()

        // 1. ƯU TIÊN SỐ 1: Quét món ăn & ẩm thực đặc sản (Tránh việc câu nói về quán ở Hà Nội lại bốc ảnh Hà Nội)
        val knownFoods = listOf(
            "Bánh mì que Hải Phòng", "Bánh mì que", "Bánh mì cay Hải Phòng", "Bánh mì cay",
            "Bánh mì sợi", "Bánh mì chà bông", "Bánh mì thịt", "Bánh mì Việt Nam", "Bánh mì",
            "Bánh mỳ que Hải Phòng", "Bánh mỳ que", "Bánh mỳ cay", "Bánh mỳ",
            "Phở bò", "Phở gà", "Phở Hà Nội", "Phở", "Bún chả Hà Nội", "Bún chả",
            "Bún bò Huế", "Bún bò", "Bún riêu cua", "Bún riêu", "Bún đậu mắm tôm",
            "Cơm tấm Sài Gòn", "Cơm tấm", "Bánh cuốn Thanh Trì", "Bánh cuốn", "Bánh xèo", "Bánh bao",
            "Pizza", "Hamburger", "Cà phê trứng", "Trà sữa", "Chè khúc bạch"
        )
        for (f in knownFoods) {
            if (text.contains(f, ignoreCase = true) && !found.contains(f)) {
                found.add(f)
            }
        }

        // 2. ƯU TIÊN SỐ 2: Quét mẫu xe & công nghệ
        val knownVehicles = listOf(
            "VinFast VF9", "VinFast VF 9", "VinFast VF8", "VinFast VF 8", "VinFast VF7", "VinFast VF 7",
            "VinFast VF6", "VinFast VF 6", "VinFast VF5", "VinFast VF 5", "VinFast VF3", "VinFast VF 3",
            "VinFast VF e34", "VinFast VFe34", "VinFast Lux A2.0", "VinFast Lux SA2.0", "VinFast Fadil",
            "VF9", "VF 9", "VF8", "VF 8", "VF7", "VF 7", "VF6", "VF 6", "VF5", "VF 5", "VF3", "VF 3",
            "Xiaomi SU7 Ultra", "Xiaomi SU7", "SU7 Ultra", "SU7", "Ferrari", "Porsche 911", "Porsche",
            "Lamborghini", "Mercedes Maybach", "Mercedes", "BMW", "Audi", "Vespa Sprint", "Vespa", "SH 150", "SH 350i"
        )
        for (v in knownVehicles) {
            if (text.contains(v, ignoreCase = true) && !found.contains(v)) {
                found.add(v)
            }
        }

        // 3. ƯU TIÊN SỐ 3: Quét danh lam thắng cảnh du lịch cụ thể
        val knownPlaces = listOf(
            "Vịnh Hạ Long", "Hạ Long", "Hà Giang", "Sa Pa", "Sapa", "Mộc Châu", "Tam Đảo",
            "Tràng An", "Ninh Bình", "Cát Bà", "Ba Bể", "Hồ Ba Bể", "Mù Cang Chải", "Thác Bản Giốc", "Bản Giốc",
            "Tà Xùa", "Cô Tô", "Mai Châu", "Ba Vì", "Hội An", "Cố đô Huế", "Huế",
            "Đà Lạt", "Phú Quốc", "Nha Trang", "Quy Nhơn", "Phan Thiết", "Mũi Né", "Côn Đảo"
        )
        for (place in knownPlaces) {
            if (text.contains(place, ignoreCase = true) && !found.contains(place)) {
                found.add(place)
            }
        }

        // 4. ƯU TIÊN SỐ 4: Tỉnh thành phố lớn (CHỈ THÊM NẾU CHƯA TÌM THẤY MÓN ĂN / XE CỘ NÀO KHÁC)
        if (found.isEmpty()) {
            val generalCities = listOf(
                "Hà Nội", "Hồ Chí Minh", "Sài Gòn", "Đà Nẵng", "Hải Phòng", "Cần Thơ", "Vũng Tàu", "Tây Ninh", "An Giang"
            )
            for (city in generalCities) {
                if (text.contains(city, ignoreCase = true) && !found.contains(city)) {
                    found.add(city)
                }
            }
        }

        // 5. Nếu chưa tìm thấy theo từ điển, bóc tách theo cấu trúc danh sách: "1. **Tên nơi**", "• **Tên nơi**"
        if (found.isEmpty()) {
            val listPattern = Regex("(?m)^\\s*(?:\\d+[\\.\\)]|[•\\-\\*]|(?:1️⃣|2️⃣|3️⃣|4️⃣|5️⃣))\\s*\\*\\*?([A-ZÀ-Ỹ][^:\\*\\(\\n]{2,30})\\*\\*?")
            val matches = listPattern.findAll(text)
            for (m in matches) {
                val candidate = m.groupValues[1].trim()
                if (candidate.isNotEmpty() && !found.contains(candidate)) {
                    found.add(candidate)
                }
            }
        }

        return found
    }

    /**
     * Tách nội dung câu trả lời của AI thành các phân đoạn (Sections) kèm từ khóa tìm ảnh tương ứng cho từng mục.
     */
    fun splitReplyIntoSections(reply: String, entities: List<String>): List<Pair<String, String>> {
        if (entities.isEmpty()) {
            return listOf(reply to "")
        }

        // Tìm vị trí xuất hiện của từng thực thể trong văn bản
        val entityPositions = mutableListOf<Pair<Int, String>>()
        for (entity in entities) {
            val idx = reply.indexOf(entity, ignoreCase = true)
            if (idx != -1) {
                // Tìm điểm bắt đầu của dòng hoặc mục (ví dụ "1. ", "• ", "\n\n")
                var lineStart = reply.lastIndexOf('\n', idx)
                if (lineStart == -1) lineStart = 0 else lineStart += 1
                entityPositions.add(lineStart to entity)
            }
        }

        entityPositions.sortBy { it.first }
        // Loại bỏ các vị trí trùng hoặc quá gần nhau (< 15 ký tự)
        val distinctPositions = mutableListOf<Pair<Int, String>>()
        for (p in entityPositions) {
            if (distinctPositions.isEmpty() || p.first >= distinctPositions.last().first + 15) {
                distinctPositions.add(p)
            }
        }

        if (distinctPositions.size <= 1) {
            return listOf(reply to (distinctPositions.firstOrNull()?.second ?: entities.first()))
        }

        val sections = mutableListOf<Pair<String, String>>()

        // Nếu có đoạn mở đầu trước mục 1
        if (distinctPositions.first().first > 0) {
            val intro = reply.substring(0, distinctPositions.first().first).trim()
            if (intro.isNotEmpty()) {
                sections.add(intro to "")
            }
        }

        for (i in 0 until distinctPositions.size) {
            val current = distinctPositions[i]
            val nextIdx = if (i + 1 < distinctPositions.size) distinctPositions[i + 1].first else reply.length
            val sectionText = reply.substring(current.first, nextIdx).trim()
            if (sectionText.isNotEmpty()) {
                sections.add(sectionText to current.second)
            }
        }

        return sections
    }

    /**
     * Tải song song hình ảnh cho từng phân đoạn địa danh / mục ẩm thực / xe cộ
     */
    fun fetchRichSectionsParallel(reply: String, entities: List<String>): List<LimiChatSection> {
        val rawSections = splitReplyIntoSections(reply, entities)
        val resultSections = mutableListOf<LimiChatSection>()

        val futures = rawSections.map { (text, topic) ->
            if (topic.isNotBlank()) {
                executor.submit<Bitmap?> {
                    searchAndFetchImage(topic)
                } to (text to topic)
            } else {
                null to (text to "")
            }
        }

        for ((future, textTopic) in futures) {
            val (text, topic) = textTopic
            val bmp = try {
                future?.get(6, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Throwable) {
                null
            }
            resultSections.add(
                LimiChatSection(
                    text = text,
                    imageBitmap = bmp,
                    imageCaption = if (bmp != null) "Ảnh $topic" else ""
                )
            )
        }

        return resultSections
    }

    /**
     * Trích xuất danh sách các từ khóa tìm kiếm Wikimedia tối ưu
     */
    private fun buildSearchQueryCandidates(topic: String): List<String> {
        val candidates = mutableListOf<String>()
        val clean = topic.trim()
        val tNorm = LimiAiService.stripAccents(clean.lowercase())

        // 1. Phân giải món ăn & bánh mì chuẩn xác (ƯU TIÊN HÀNG ĐẦU)
        if (tNorm.contains("banh my") || tNorm.contains("banh mi")) {
            if (tNorm.contains("que") || tNorm.contains("cay") || tNorm.contains("hai phong")) {
                candidates.add("Bánh mì que với thịt xông khói")
                candidates.add("Bánh mì que")
                candidates.add("Bánh mì que Hải Phòng")
                candidates.add("Banh mi que")
                candidates.add("Bánh mì cay Hải Phòng")
                candidates.add("Bánh mì cay")
                candidates.add("Bánh mì stick")
            } else if (tNorm.contains("soi") || tNorm.contains("xe") || tNorm.contains("cha bong") || tNorm.contains("pate")) {
                candidates.add("Bánh mì Việt Nam")
                candidates.add("Bánh mì thịt nguội")
                candidates.add("Banh mi")
                candidates.add("Bánh mì")
            } else {
                candidates.add("Bánh mì Việt Nam")
                candidates.add("Bánh mì")
                candidates.add("Banh mi")
            }
        } else if (tNorm.contains("pho bo")) {
            candidates.add("Phở bò")
            candidates.add("Pho bo")
        } else if (tNorm.contains("pho ga")) {
            candidates.add("Phở gà")
            candidates.add("Pho ga")
        } else if (tNorm.contains("bun cha")) {
            candidates.add("Bún chả")
            candidates.add("Bun cha")
        } else if (tNorm.contains("bun bo")) {
            candidates.add("Bún bò Huế")
            candidates.add("Bun bo")
        } else if (tNorm.contains("bun rieu")) {
            candidates.add("Bún riêu")
        } else if (tNorm.contains("bun dau")) {
            candidates.add("Bún đậu mắm tôm")
        } else if (tNorm.contains("com tam")) {
            candidates.add("Cơm tấm")
            candidates.add("Com tam")
        } else if (tNorm.contains("banh cuon")) {
            candidates.add("Bánh cuốn")
        } else if (tNorm.contains("banh xeo")) {
            candidates.add("Bánh xèo")
        } else if (tNorm.contains("banh bao")) {
            candidates.add("Bánh bao")
        } else if (tNorm.contains("pizza")) {
            candidates.add("Pizza")
        } else if (tNorm.contains("hamburger") || tNorm.contains("burger")) {
            candidates.add("Hamburger")
        }

        // 2. Thêm từ khóa gốc chính xác của người dùng
        if (!candidates.contains(clean)) candidates.add(clean)
        val stripped = LimiAiService.stripAccents(clean)
        if (!candidates.contains(stripped)) candidates.add(stripped)

        // 3. Phân giải địa danh du lịch nổi tiếng
        if (tNorm.contains("ha giang")) {
            candidates.add("Ha Giang landscape")
            candidates.add("Hà Giang")
            candidates.add("Đồng Văn")
        } else if (tNorm.contains("ha long")) {
            candidates.add("Ha Long Bay")
            candidates.add("Vịnh Hạ Long")
            candidates.add("Ha Long")
        } else if (tNorm.contains("sa pa") || tNorm.contains("sapa")) {
            candidates.add("Sa Pa")
            candidates.add("Sapa Vietnam")
            candidates.add("Fansipan")
        } else if (tNorm.contains("moc chau")) {
            candidates.add("Mộc Châu")
            candidates.add("Moc Chau")
        } else if (tNorm.contains("tam dao")) {
            candidates.add("Tam Đảo")
            candidates.add("Tam Dao")
        } else if (tNorm.contains("trang an")) {
            candidates.add("Tràng An")
            candidates.add("Trang An")
        } else if (tNorm.contains("ninh binh")) {
            candidates.add("Ninh Bình")
            candidates.add("Ninh Binh")
        } else if (tNorm.contains("cat ba")) {
            candidates.add("Cát Bà")
            candidates.add("Cat Ba Island")
        } else if (tNorm.contains("da nang")) {
            candidates.add("Đà Nẵng")
            candidates.add("Da Nang")
        } else if (tNorm.contains("hoi an")) {
            candidates.add("Hội An")
            candidates.add("Hoi An")
        } else if (tNorm.contains("da lat")) {
            candidates.add("Đà Lạt")
            candidates.add("Da Lat")
        } else if (tNorm.contains("phu quoc")) {
            candidates.add("Phú Quốc")
            candidates.add("Phu Quoc")
        } else if (tNorm.contains("nha trang")) {
            candidates.add("Nha Trang")
        } else if (tNorm.contains("ban gioc")) {
            candidates.add("Thác Bản Giốc")
            candidates.add("Ban Gioc Waterfall")
        } else if (tNorm.contains("ba be")) {
            candidates.add("Hồ Ba Bể")
            candidates.add("Ba Be Lake")
        } else if (tNorm.contains("mu cang chai")) {
            candidates.add("Mù Cang Chải")
            candidates.add("Mu Cang Chai")
        } else if (tNorm.contains("ta xua")) {
            candidates.add("Tà Xùa")
        } else if (tNorm.contains("ha noi") || tNorm.contains("hanoi")) {
            candidates.add("Hanoi")
            candidates.add("Hà Nội")
        } else if (tNorm.contains("sai gon") || tNorm.contains("ho chi minh")) {
            candidates.add("Ho Chi Minh City")
            candidates.add("Saigon")
        }

        // 4. Phân giải mẫu xe ô tô / xe máy
        if (tNorm.contains("vf9") || tNorm.contains("vf 9")) {
            candidates.add("VinFast VF9 xe oto ngoai that")
            candidates.add("VinFast VF 9 car exterior")
            candidates.add("Xe dien VinFast VF9")
            candidates.add("VinFast VF 9")
            candidates.add("VinFast VF9")
        } else if (tNorm.contains("vf8") || tNorm.contains("vf 8")) {
            candidates.add("VinFast VF8 xe oto")
            candidates.add("VinFast VF 8 car")
            candidates.add("VinFast VF8")
        } else if (tNorm.contains("vf7") || tNorm.contains("vf 7")) {
            candidates.add("VinFast VF7 xe oto")
            candidates.add("VinFast VF 7")
        } else if (tNorm.contains("vf6") || tNorm.contains("vf 6")) {
            candidates.add("VinFast VF6 xe oto")
            candidates.add("VinFast VF 6")
        } else if (tNorm.contains("vf5") || tNorm.contains("vf 5")) {
            candidates.add("VinFast VF5 xe oto")
            candidates.add("VinFast VF 5")
        } else if (tNorm.contains("vf3") || tNorm.contains("vf 3")) {
            candidates.add("VinFast VF3 xe oto")
            candidates.add("VinFast VF 3 car")
            candidates.add("VinFast VF3")
        } else if (tNorm.contains("vfe34") || tNorm.contains("vf e34")) {
            candidates.add("VinFast VF e34 xe oto")
            candidates.add("VinFast VF e34")
        } else if (tNorm.contains("vinfast")) {
            candidates.add("Xe oto VinFast")
            candidates.add("VinFast car")
        } else if (tNorm.contains("su7")) {
            candidates.add("Xiaomi SU7 car exterior")
            candidates.add("Xiaomi SU7 xe dien")
            candidates.add("Xiaomi SU7")
            candidates.add("Xiaomi SU7 Ultra")
        } else if (tNorm.contains("ferrari")) {
            candidates.add("Ferrari sports car")
            candidates.add("Ferrari")
        } else if (tNorm.contains("porsche")) {
            candidates.add("Porsche car")
            candidates.add("Porsche")
        } else if (tNorm.contains("lamborghini")) {
            candidates.add("Lamborghini supercar")
            candidates.add("Lamborghini")
        } else if (tNorm.contains("vespa")) {
            candidates.add("Xe máy Vespa")
            candidates.add("Vespa scooter")
        } else if (tNorm.contains("sh 150") || tNorm.contains("sh150")) {
            candidates.add("Xe máy Honda SH 150i")
            candidates.add("Honda SH150")
        }

        return candidates
    }

    /**
     * Tìm kiếm và nạp ảnh chất lượng cao dạng Bitmap (MỞ RỘNG TOÀN BỘ INTERNET + WIKIMEDIA DỰ PHÒNG + DISK CACHE ƯU TIÊN)
     */
    fun searchAndFetchImage(topic: String, context: android.content.Context? = null): Bitmap? {
        val cleanTopic = topic.trim()
        val tNorm = LimiAiService.stripAccents(cleanTopic.lowercase())
        val invalidKeywords = setOf("mau", "anh mau", "hinh mau", "mau anh", "noi do", "3 noi", "ba noi", "cac noi", "dia diem", "no", "chung", "do", "nay", "anh", "hinh", "dua xem", "cho xem", "")
        if (cleanTopic.isEmpty() || invalidKeywords.contains(tNorm)) {
            return null
        }

        val cacheKey = cleanTopic.lowercase()
        val cached = imageCache.get(cacheKey) ?: imageCache.get(tNorm)
        if (cached != null) return cached

        val searchCandidates = buildSearchQueryCandidates(cleanTopic)
        val isFoodTopic = tNorm.contains("banh") || tNorm.contains("pho") || tNorm.contains("bun") ||
                tNorm.contains("com") || tNorm.contains("an") || tNorm.contains("am thuc") ||
                tNorm.contains("food") || tNorm.contains("mon") || tNorm.contains("pizza") || tNorm.contains("burger")

        // 1. TẦNG 1: TÌM KIẾM TRÊN TOÀN BỘ MẠNG INTERNET (Web Image Search Crawler đa nguồn)
        for (queryTerm in searchCandidates) {
            try {
                val webBmp = fetchFromWebSearchEngine(queryTerm, isFoodTopic)
                if (webBmp != null) {
                    imageCache.put(cacheKey, webBmp)
                    return webBmp
                }
            } catch (_: Throwable) {}
        }

        // 2. TẦNG 2: TÌM KIẾM TỪ KHO DỮ LIỆU ẢNH WIKIMEDIA COMMONS TOÀN CẦU
        for (queryTerm in searchCandidates) {
            try {
                val wikiBmp = fetchFromWikimedia(queryTerm, isFoodTopic)
                if (wikiBmp != null) {
                    imageCache.put(cacheKey, wikiBmp)
                    return wikiBmp
                }
            } catch (_: Throwable) {}
        }

        return null
    }

    private fun fetchFromWebSearchEngine(queryTerm: String, isFoodTopic: Boolean): Bitmap? {
        val encodedQuery = Uri.encode(queryTerm)
        val searchUrl = "https://www.bing.com/images/search?q=$encodedQuery&form=HDRSC2&first=1"
        val html = downloadString(searchUrl, timeoutMs = 4500)
        if (html.isEmpty()) return null

        val imgUrlPattern = java.util.regex.Pattern.compile("murl&quot;:&quot;(https?://[^&\"]+)")
        val matcher = imgUrlPattern.matcher(html)
        var checkedCount = 0

        // Bộ lọc loại bỏ ảnh đồ họa, biểu đồ, máy tính, mô hình rác
        val junkGraphicTerms = listOf(
            "chart", "diagram", "infographic", "illustration", "vector", "clipart",
            "analytics", "graph", "statistic", "dashboard", "computer", "report",
            "presentation", "slide", "so-do", "bang-gia", "specifications", "icon", "logo",
            "drawing", "sketch", "analytics", "seo", "software", "isometric"
        )

        while (matcher.find() && checkedCount < 8) {
            val imgUrl = matcher.group(1) ?: continue
            checkedCount++

            // Bỏ qua các định dạng vector / icon / tài liệu rác
            val lower = imgUrl.lowercase()
            if (lower.endsWith(".svg") || lower.endsWith(".gif") || lower.endsWith(".pdf") || lower.endsWith(".ico")) {
                continue
            }
            if (junkGraphicTerms.any { lower.contains(it) }) {
                continue
            }
            if (isFoodTopic && (lower.contains("theatre") || lower.contains("puppet") || lower.contains("water-puppet") || lower.contains("pagoda") || lower.contains("temple"))) {
                continue
            }

            val bmp = downloadBitmapFromUrl(imgUrl, timeoutMs = 5000)
            if (bmp != null && bmp.width >= 150 && bmp.height >= 150) {
                return bmp
            }
        }
        return null
    }

    private fun fetchFromWikimedia(queryTerm: String, isFoodTopic: Boolean): Bitmap? {
        val excludedTitleKeywords = mutableListOf(
            ".pdf", ".djvu", ".tif", ".tiff", ".svg", ".ogg", ".ogv", ".webm",
            "manuscrito", "manoscritto", "manuscript", "gallica", "diễn ca", "dien ca", "press", "newspaper",
            "document", "báo cáo", "van ban", "văn bản", "texture", "marble", "marbled", "stone", "fossil",
            "specimen", "diagram", "chart", "map of", "bản đồ", "ban do",
            "paper", "tờ khai", "mẫu đơn", "mau don", "mẫu giấy", "mau giay", "tài liệu", "tai lieu",
            "certificate", "sheet", "receipt", "form ", "textbook", "page ", "cover", "letter", "contract",
            "treaty", "giấy", "giay", "chứng nhận", "chung nhan", "bằng cấp", "stamp", "tem", "banknote",
            "bill", "tờ rơi", "flyer", "brochure", "poster", "biểu mẫu", "bieu mau", "passport", "hộ chiếu",
            "cccd", "cmnd", "sơ đồ", "template", "blank"
        )

        if (isFoodTopic) {
            excludedTitleKeywords.addAll(
                listOf(
                    "theatre", "theater", "puppet", "múa rối", "mua roi", "thang long water",
                    "nhà hát", "nha hat", "temple", "pagoda", "chùa", "đền", "den ", "chua ",
                    "building", "tòa nhà", "toa nha", "street", "đường phố", "duong pho",
                    "cột cờ", "landscape", "monument", "tượng đài", "lake", "hồ gươm"
                )
            )
        }

        val encodedQuery = Uri.encode(queryTerm)
        val apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=$encodedQuery&gsrnamespace=6&gsrlimit=6&prop=imageinfo&iiprop=url&iiurlwidth=800&format=json"
        val jsonStr = downloadString(apiUrl, timeoutMs = 4500)
        if (jsonStr.isEmpty()) return null

        val jsonObj = JSONObject(jsonStr)
        val queryObj = jsonObj.optJSONObject("query")
        val pagesObj = queryObj?.optJSONObject("pages") ?: return null

        val pageList = mutableListOf<JSONObject>()
        for (pageKey in pagesObj.keys()) {
            pagesObj.optJSONObject(pageKey)?.let { pageList.add(it) }
        }
        pageList.sortBy { it.optInt("index", 999) }

        for (pageItem in pageList) {
            val title = pageItem.optString("title", "").lowercase()
            var isExcluded = false
            for (ex in excludedTitleKeywords) {
                if (title.contains(ex)) {
                    isExcluded = true
                    break
                }
            }
            if (isExcluded) continue

            val imageInfoArr = pageItem.optJSONArray("imageinfo")
            if (imageInfoArr != null && imageInfoArr.length() > 0) {
                val infoObj = imageInfoArr.getJSONObject(0)
                val targetUrl = infoObj.optString("thumburl", infoObj.optString("url", ""))
                if (targetUrl.isNotEmpty() && !targetUrl.endsWith(".pdf", ignoreCase = true)) {
                    val bmp = downloadBitmapFromUrl(targetUrl, timeoutMs = 5000)
                    if (bmp != null) return bmp
                }
            }
        }
        return null
    }

    private fun downloadBitmapFromUrl(urlString: String, timeoutMs: Int): Bitmap? {
        var conn: HttpURLConnection? = null
        var inputStream: InputStream? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0")
            }
            conn.connect()
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                inputStream = conn.inputStream
                BitmapFactory.decodeStream(inputStream)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { inputStream?.close() } catch (_: Throwable) {}
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun downloadString(urlString: String, timeoutMs: Int): String {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LimiAI-ImageSearch/1.0 (Contact: duyih122@gmail.com)")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
        } catch (_: Throwable) {
            ""
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}

package com.xiaomi.fixnotification.ai

import android.content.Context
import android.os.Build
import com.xiaomi.fixnotification.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

data class KnowledgeItem(
    val id: String = UUID.randomUUID().toString(),
    val keywords: List<String>,
    val title: String,
    val content: String,
    val contents: MutableList<String> = mutableListOf(),
    val actionButtonTitle: String? = null,
    val actionType: ChatActionType? = null,
    val payload: String? = null,
    val likes: Int = 0,
    val unlikes: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getRandomVariation(): String {
        if (contents.isNotEmpty()) {
            return contents.random()
        }
        return content
    }

    fun addContentVariation(newContent: String) {
        if (contents.isEmpty() && content.isNotBlank()) {
            contents.add(content)
        }
        if (newContent.isNotBlank() && !contents.contains(newContent)) {
            if (contents.size >= 3) {
                contents.removeAt(0)
            }
            contents.add(newContent)
        }
    }
}

data class LearnedQA(
    val id: String = UUID.randomUUID().toString(),
    val question: String,
    var answer: String,
    val answers: MutableList<String> = mutableListOf(),
    var apiCallCount: Int = 1,
    var likes: Int = 0,
    var unlikes: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getNextVariation(): String {
        if (answers.isNotEmpty()) {
            return answers.random()
        }
        return answer
    }

    fun addAnswerVariation(newAnswer: String) {
        if (answers.isEmpty() && answer.isNotBlank()) {
            answers.add(answer)
        }
        if (!answers.contains(newAnswer)) {
            if (answers.size >= 3) {
                answers.removeAt(0)
            }
            answers.add(newAnswer)
            answer = newAnswer
        }
        apiCallCount++
    }
}

/**
 * Quản lý kho tri thức động (Remote Knowledge Base) và cơ chế tự học (Self-Learning Auto-Cache) cho Trợ lý Limi AI.
 * 
 * Luồng hoạt động:
 * 1. Đọc kho dữ liệu chính thức từ Google Sheets / Google Drive / Apps Script về lưu vào bộ nhớ máy (cache).
 * 2. Khi người dùng hỏi: Quét kho tri thức trước -> Tìm thấy thì trả lời TỨC THÌ (0.05s, KHÔNG TỐN API KEY).
 * 3. Nếu kho chưa có: Gọi Gemini API -> Sau khi Gemini trả lời, tự động lưu vào Local Cache và
 *    GỬI NGẦM DỮ LIỆU ĐÓ GHI VÀO GOOGLE SHEET TRÊN DRIVE CỦA BẠN (qua Google Apps Script Webhook).
 */
object LimiKnowledgeBase {

    private const val PREFS_NAME = "limi_knowledge_prefs"
    private const val KEY_KB_URL = "limi_custom_kb_url"
    private const val KEY_CACHED_CUSTOM_JSON = "limi_cached_custom_kb_json"
    private const val KEY_LOCAL_LEARNED_JSON = "limi_local_learned_json"

    const val DEFAULT_KB_URL = "https://script.google.com/macros/s/AKfycby7jWAfSQKpqlT-nbZfbIXqJ9Sp3GvW0R3Fcvm_Qs1EM3LoSj3ZP5ifUV5mmMrNDqpM/exec"

    private val executor = Executors.newSingleThreadExecutor()

    private val customKnowledgeList = mutableListOf<KnowledgeItem>()
    private val localLearnedList = mutableListOf<LearnedQA>()
    private var isInitialized = false

    fun getCustomKnowledge(): List<KnowledgeItem> {
        return synchronized(customKnowledgeList) {
            customKnowledgeList.toList()
        }
    }

    /**
     * Dữ liệu mặc định khởi tạo ban đầu (giúp người dùng trải nghiệm ngay cả khi chưa nạp link Drive)
     */
    private val DEFAULT_STARTER_KNOWLEDGE = listOf(
        KnowledgeItem(
            keywords = listOf("cha đẻ", "cha de", "dung nguyen", "ai tạo ra limi", "ai tạo ra bạn", "ai làm ra limi", "ai viết app limi", "ai viết ra limi", "người sáng lập", "nhà phát triển", "nha phat trien", "tác giả", "tac gia", "developer"),
            title = " Nhà Phát Triển & Cha Đẻ Limi: Dung Nguyen",
            content = """
 **Thông Tin Nhà Phát Triển & Cha Đẻ Trợ Lý LIMI**:

• **Tác giả & Cha đẻ**: **Dung Nguyen**
• **Ứng dụng**: **Bộ công cụ LIMI** (Xiaomi / HyperOS Notification & System Optimizer)
• **Phiên bản**: ${com.xiaomi.fixnotification.util.AppVersionHelper.getVersionName()}
• **Sứ mệnh**: Xây dựng giải pháp tối ưu hóa thông báo, giải phóng millet và kiểm tra pin chuyên sâu, an toàn cho cộng đồng người dùng Xiaomi / Redmi / POCO tại Việt Nam.

📬 **Kênh Hỗ Trợ & Liên Hệ Chính Thức**:
•  **Nhóm Zalo hỗ trợ kỹ thuật**: https://zalo.me/g/kgjjkz596
•  **Email phản hồi**: duyih122@gmail.com
•  **Ủng hộ tác giả (Donate)**: Mở mục Nhà phát triển trong app để gửi một ly cà phê ủng hộ tác giả phát triển dự án nhé!
""".trimIndent(),
            actionButtonTitle = " Ủng Hộ Nhà Phát Triển (Donate)",
            actionType = ChatActionType.OPEN_DONATE
        ),
        KnowledgeItem(
            keywords = listOf("xiaomi 15", "xiaomi 15 pro", "15 pro", "thông số xiaomi 15", "cấu hình xiaomi 15", "xiaomi 15 series"),
            title = " Xiaomi 15 & Xiaomi 15 Pro",
            content = """
 **Thông Tin & Cấu Hình Xiaomi 15 / 15 Pro (Flagship Mới Nhất)**:

• **Phân khúc**: Siêu Flagship cao cấp
• **Hệ điều hành**: Xiaomi HyperOS 2 trên nền Android 15
• **Vi xử lý (Chipset)**: Snapdragon 8 Elite (3nm cực mạnh, GPU Adreno 830)
• **Pin & Sạc**: 5.400 mAh (Xiaomi 15) / 6.100 mAh (Xiaomi 15 Pro), sạc nhanh 90W có dây / 50W không dây
• **Màn hình & Camera**: Màn OLED 1.5K/2K 120Hz LTPO độ sáng 3.200 nits, hệ thống 3 camera Leica 50MP chuyên nghiệp
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("xiaomi 14", "xiaomi 14 pro", "xiaomi 14 ultra", "14 ultra", "thông số xiaomi 14"),
            title = " Xiaomi 14 Series",
            content = """
 **Thông Tin Xiaomi 14 / 14 Pro / 14 Ultra**:

• **Phân khúc**: Flagship cao cấp
• **Hệ điều hành**: HyperOS (Android 14 / nâng cấp Android 15)
• **Vi xử lý**: Snapdragon 8 Gen 3 (4nm)
• **Pin & Sạc**: 4.610 - 5.000 mAh, sạc nhanh 90W / 120W
• **Camera**: Ống kính quang học Leica Summilux siêu sắc nét
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("redmi k80", "redmi k80 pro", "k80", "k80 pro", "thông số k80 pro"),
            title = " Redmi K80 & Redmi K80 Pro",
            content = """
 **Thông Tin Dòng Redmi K80 / K80 Pro (Hiệu Năng Khủng)**:

• **Phân khúc**: Sát thủ cấu hình / Flagship Killer
• **Hệ điều hành**: Xiaomi HyperOS 2 (Android 15)
• **Vi xử lý**: Snapdragon 8 Gen 3 (K80) / Snapdragon 8 Elite (K80 Pro)
• **Pin & Sạc**: Pin khủng 6.550 mAh (K80) / 6.000 mAh (K80 Pro, sạc 120W)
• **Màn hình**: 6.67 inch 2K 120Hz độ sáng 3.200 nits
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("hyperos 2", "hyperos 2.0", "lộ trình hyperos 2", "cập nhật hyperos 2"),
            title = " Kế Hoạch & Tính Năng Xiaomi HyperOS 2",
            content = """
 **Thông Tin Hệ Điều Hành Xiaomi HyperOS 2**:

• **Nền tảng**: Xây dựng trên nhân **Android 15**, tối ưu sâu cho kiến trúc vi xử lý đa nhân.
• **Điểm nổi bật**: HyperCore tối ưu mượt mà, HyperConnect kết nối liền mạch đa thiết bị, và HyperAI nâng cao trải nghiệm người dùng.
• **Fix thông báo**: Tối ưu giữ nhịp tim FCM và giải phóng hạn chế đóng băng Millet.
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("mẹo sạc pin", "sac pin nhanh", "sac 120w", "sac 90w", "bảo vệ pin xiaomi", "meo sac pin"),
            title = " Mẹo Sạc Nhanh & Bảo Vệ Tuổi Thọ Pin Xiaomi",
            content = """
 **Mẹo Sạc Pin Thông Minh & Giữ Tuổi Thọ Pin Tối Đa**:

1. **Bật 'Sạc tối ưu hóa' (Optimized Charging)**: Giúp máy tự điều chỉnh dòng sạc khi sạc qua đêm, ngưng ở mức 80% và sạc đầy trước khi bạn thức dậy.
2. **Hạn chế vừa chơi game nặng vừa sạc nhanh (120W/90W)**: Nhiệt độ cao kết hợp dòng điện lớn là nguyên nhân số 1 làm tăng tốc độ chai pin.
3. **Duy trì mức pin lý tưởng 20% - 85%**: Giúp hạn chế tối đa chu kỳ điện hóa căng thẳng của các cell pin Lithium-ion.
4. **Định kỳ kiểm tra**: Mở tab **Sức Khỏe Pin** trên Bộ công cụ LIMI để theo dõi chu kỳ sạc thực tế và độ chai pin từ kernel phần cứng.
""".trimIndent(),
            actionButtonTitle = " Mở Tab Sức Khỏe Pin",
            actionType = ChatActionType.OPEN_BATTERY_HEALTH
        ),
        KnowledgeItem(
            keywords = listOf("xiaomi 15", "xiaomi 15 pro", "thông tin xiaomi 15", "ra mắt xiaomi 15", "cấu hình xiaomi 15", "pin xiaomi 15"),
            title = " Thông Tin Xiaomi 15 & 15 Pro",
            content = """
 **Thông Tin Xiaomi 15 / 15 Pro (Flagship Thế Hệ Mới)**:

• **Trạng thái**: Đã ra mắt (29/10/2024).
• **Hệ điều hành**: Xiaomi HyperOS 2 trên nền Android 15.
• **Vi xử lý (Chipset)**: Snapdragon 8 Elite (tiến trình 3nm cực mạnh).
• **Dung lượng Pin & Sạc**:
  - Xiaomi 15: 5.400 mAh, sạc nhanh 90W có dây / 50W không dây.
  - Xiaomi 15 Pro: 6.100 mAh, sạc nhanh 90W có dây / 50W không dây.
• **Màn hình & Camera Leica**: Màn hình 1.5K / 2K LTPO 120Hz viền siêu mỏng 1.38mm, cụm 3 camera Leica 50MP (bản Pro có camera tiềm vọng Sony IMX858 zoom quang 5X).
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("xiaomi 15 ultra", "thông tin xiaomi 15 ultra", "camera xiaomi 15 ultra", "ra mắt xiaomi 15 ultra"),
            title = " Siêu Flagship Xiaomi 15 Ultra",
            content = """
 **Thông Tin Siêu Flagship Xiaomi 15 Ultra**:

• **Trạng thái**: Ra mắt ngày 27/02/2025.
• **Hệ điều hành**: HyperOS 2 (Android 15).
• **Vi xử lý**: Qualcomm Snapdragon 8 Elite (3nm).
• **Pin & Sạc**: 6.000 mAh, sạc 90W có dây / 80W không dây.
• **Điểm nhấn đột phá**: Cảm biến chính 1 inch Leica thế hệ mới kết hợp camera tiềm vọng 200MP zoom cực xa, chuyên dụng cho nhiếp ảnh di động đỉnh cao.
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("xiaomi 14", "xiaomi 14 pro", "xiaomi 14 ultra", "thông tin xiaomi 14"),
            title = " Thông Tin Xiaomi 14 Series",
            content = """
 **Thông Tin Xiaomi 14 / 14 Pro / 14 Ultra**:

• **Trạng thái**: Đã ra mắt và phân phối chính hãng.
• **Hệ điều hành**: HyperOS 1.0 / HyperOS 2 (Android 14 / 15).
• **Vi xử lý**: Snapdragon 8 Gen 3.
• **Pin & Sạc**: 4.610 mAh - 5.000 mAh, sạc nhanh 90W / 120W.
• **Điểm nổi bật**: Thiết bị đầu tiên chạy HyperOS, ống kính Leica Summilux trứ danh, bản Ultra trang bị cảm biến LYT-900 1 inch khẩu độ biến thiên vô cấp.
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("xiaomi 14t", "xiaomi 14t pro", "xiaomi 15t", "thông tin xiaomi 14t"),
            title = " Dòng Cận Cao Cấp Xiaomi 14T & 15T Series",
            content = """
 **Thông Tin Dòng Xiaomi 14T Series & 15T Series**:

• **Xiaomi 14T / 14T Pro**: Ra mắt 26/09/2024, vi xử lý Dimensity 8300-Ultra / 9300+, sạc 67W / 120W, màn hình 144Hz AI, camera Leica.
• **Xiaomi 15T / 15T Pro**: Dự kiến ra mắt Q3/2025 - 2026, trang bị Dimensity 8400 / 9400+, pin 5.500 - 6.000 mAh sạc 120W và tích hợp sâu các tính năng HyperAI.
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("xiaomi mix flip", "xiaomi mix fold 4", "mix flip", "mix fold 4", "màn hình gập xiaomi"),
            title = " Flagship Màn Hình Gập Xiaomi MIX Flip & Fold 4",
            content = """
 **Dòng Flagship Màn Hình Gập Xiaomi MIX**:

• **Xiaomi MIX Flip**: Flagship gập vỏ sò thời thượng, màn hình phụ ngoài siêu lớn 4.01 inch tràn viền, chip Snapdragon 8 Gen 3, pin 4.780 mAh sạc 67W, camera kép Leica 50MP.
• **Xiaomi MIX Fold 4**: Flagship gập ngang siêu mỏng nhẹ chỉ 9.47mm khi gập, kháng nước chuẩn IPX8, pin 5.100 mAh sạc 67W / 50W không dây, 4 camera Leica chuyên nghiệp.
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf(
                "iphone 18", "iphone 18 pro", "iphone 18 pro max", "iphone 18 plus", "ip 18", "ip18", "iphone18",
                "ra mắt iphone 18", "iphone 18 bao giờ ra mắt", "iphone 18 khi nào ra mắt", "khi nào ra mắt iphone 18",
                "ngày ra mắt iphone 18", "gia iphone 18", "cấu hình iphone 18", "thông tin iphone 18"
            ),
            title = " Lộ Trình & Thông Tin iPhone 18 Series (Apple)",
            content = """
 **Thông Tin & Lộ Trình Ra Mắt iPhone 18 Series**:

• **Thời điểm ra mắt dự kiến**: Tháng 09/2026 (theo chu kỳ truyền thống hàng năm của Apple).
• **Các phiên bản dự kiến**: iPhone 18, iPhone 18 Air / Slim, iPhone 18 Pro, iPhone 18 Pro Max.
• **Vi xử lý (Chipset)**: Apple A20 Bionic / A20 Pro (sản xuất trên tiến trình 2nm tiên tiến nhất của TSMC).
• **Màn hình & Thiết kế**: Dynamic Island thu gọn tối đa với Face ID ẩn dưới màn hình (Under-display Face ID), tấm nền OLED ProMotion 120Hz tiết kiệm pin vượt trội.
• **Camera**: Cụm camera Fusion thế hệ mới, cải tiến khẩu độ biến thiên cơ học và cảm biến ảnh kích thước lớn hơn.
• **Kết nối**: Modem 5G thế hệ mới do chính Apple tự chủ thiết kế kết hợp chuẩn Wi-Fi 7 tốc độ cao.
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf(
                "iphone 17", "iphone 17 pro", "iphone 17 pro max", "iphone 17 air", "iphone 17 slim", "ip 17", "ip17", "iphone17",
                "ra mắt iphone 17", "iphone 17 bao giờ ra mắt", "iphone 17 khi nào ra mắt", "thông tin iphone 17"
            ),
            title = " Thông Tin iPhone 17 Series (Apple)",
            content = """
 **Thông Tin Dự Kiến iPhone 17 Series**:

• **Thời điểm ra mắt dự kiến**: Tháng 09/2025.
• **Điểm nhấn thiết kế**: Xuất hiện phiên bản siêu mỏng iPhone 17 Air (hoặc iPhone 17 Slim) thay thế dòng Plus.
• **Vi xử lý**: Chip Apple A19 / A19 Pro (tiến trình 3nm N3P tối ưu hóa hiệu năng và tản nhiệt).
• **Màn hình**: Tất cả các phiên bản (kể cả bản tiêu chuẩn) dự kiến sẽ được nâng cấp lên màn hình 120Hz ProMotion chống chói, chống trầy xước cao cấp.
• **Camera selfie**: Nâng cấp lên cảm biến 24MP với thấu kính 6 thành phần cho chất lượng ảnh chụp vượt trội.
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("redmi k80", "redmi k80 pro", "thông tin redmi k80"),
            title = " Flagship Hiệu Năng Redmi K80 & K80 Pro",
            content = """
 **Thông Tin Redmi K80 & Redmi K80 Pro**:

• **Trạng thái**: Ra mắt 27/11/2024.
• **Vi xử lý**: Snapdragon 8 Gen 3 (K80) / Snapdragon 8 Elite (K80 Pro).
• **Pin & Sạc**: Pin khủng 6.000 - 6.550 mAh, sạc nhanh 90W / 120W và 50W không dây.
• **Màn hình**: 2K OLED 120Hz M9 siêu sáng, kháng nước chuẩn IP68/IP69 cao nhất.
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("redmi turbo 4", "turbo 4", "thông tin redmi turbo 4"),
            title = " Chiến Thần Tầm Trung Redmi Turbo 4",
            content = """
 **Thông Tin Redmi Turbo 4 (Chiến Thần Tầm Trung)**:

• **Trạng thái**: Ra mắt ngày 02/01/2025.
• **Hệ điều hành**: HyperOS 2 (Android 15).
• **Vi xử lý**: MediaTek Dimensity 8400-Ultra hiệu năng tiệm cận Flagship.
• **Pin & Sạc**: Pin khủng 6.500 mAh, sạc nhanh 90W.
• **Màn hình**: OLED 1.5K 120Hz tối ưu chơi game mượt mà.
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("xiaomi su7", "su7", "xe điện xiaomi", "ô tô xiaomi", "su7 max", "su7 ultra"),
            title = " Siêu Xe Điện Thể Thao Xiaomi SU7 Series",
            content = """
 **Thông Tin Siêu Xe Điện Thể Thao Xiaomi SU7 / SU7 Max / SU7 Ultra**:

• **Phân khúc**: Sedan thể thao thuần điện C-segment cao cấp.
• **Trạng thái**: Đã ra mắt thương mại và bàn giao từ tháng 04/2024.
• **Động cơ & Dẫn động**: Động cơ HyperEngine V6 / V6s / V8s, dẫn động cầu sau RWD hoặc 4 bánh toàn thời gian AWD.
• **Công suất & Tốc độ**: Bản SU7 Max đạt 673 mã lực, tăng tốc 0-100 km/h chỉ trong **2.78 giây**, tốc độ tối đa 265 km/h. Bản SU7 Ultra đạt công suất hơn 1.500 mã lực!
• **Pin & Tầm hoạt động**: Pin CATL Kirin 101 kWh (công nghệ CTB 800V), tầm hoạt động lên đến **800 - 830 km (CLTC)**, sạc 15 phút đi được 510 km.
• **Buồng lái thông minh**: Màn hình 16.1 inch 3K chạy Xiaomi HyperOS buồng lái, kết nối liền mạch điện thoại x xe hơi x nhà thông minh (Human x Car x Home).
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf("xiaomi pad 7", "xiaomi pad 7 pro", "pad 7", "tablet xiaomi"),
            title = " Máy Tính Bảng Xiaomi Pad 7 & Pad 7 Pro",
            content = """
 **Thông Tin Máy Tính Bảng Xiaomi Pad 7 & Pad 7 Pro**:

• **Trạng thái**: Đã ra mắt (29/10/2024).
• **Vi xử lý**: Snapdragon 7+ Gen 3 (Pad 7) / Snapdragon 8s Gen 3 (Pad 7 Pro).
• **Màn hình**: 11.2 inch 3.2K 144Hz tỉ lệ 3:2 tối ưu làm việc, chế độ PC Workstation.
• **Pin & Sạc**: 8.850 mAh, sạc nhanh 45W / 67W.
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf(
                "11 lệnh", "11 cau lenh", "trọn bộ 11 lệnh", "fix thông báo vĩnh viễn", "mất thông báo sau 1 ngày",
                "mất thông báo sau 24h", "tịt thông báo sau 1 ngày", "nhả lệnh sau 1 ngày", "lệnh cố định", "powerkeeper reset"
            ),
            title = " Trọn Bộ 11 Lệnh Fix Thông Báo Cố Định Vĩnh Viễn (Chống Mất Sau 1 Ngày)",
            content = """
 **Giải Pháp Đặc Trị Mất Thông Báo Sau 1 Ngày / 24 Giờ Trên HyperOS (Android 16 / 15)**:

🔍 **Nguyên nhân lệnh cũ bị "nhả" sau 24h**:
1. **Dịch vụ PowerStateMachineService của PowerKeeper**: Tự động thức dậy sau chu kỳ 24h để khôi phục cấu hình tiết kiệm pin và xóa whitelist.
2. **Google Phenotype Sync**: GMS định kỳ đồng bộ cấu hình từ server Google, ghi đè mốc Heartbeat 120s.
3. **Lệnh dumpsys chỉ lưu trong RAM**: Bị hệ điều hành dọn dẹp hàng ngày quét sạch.
4. **App Standby Buckets**: Hệ thống tự động hạ app xuống nhóm RESTRICTED hoặc RARE khi tắt màn hình.

🛠️ **Bộ 11 Lệnh Cố Định Đột Phá Đã Cập Nhật Trong LIMI**:
• **Lệnh 1**: Khóa chu kỳ FCM Heartbeat/Ping 120s, tắt cơ chế gom tin/trì hoãn (Delivery Window = 0ms), kích hoạt cờ sáng màn hình `wake_up_screen_on_notification`.
• **Lệnh 2**: Vô hiệu hóa Doze sâu & AI pin thích ứng (`adaptive_battery_management_enabled 0`).
• **Lệnh 3**: Giữ Wi-Fi & Mạng di động thức liên tục, tắt nhân đóng băng CPU (`miui_freezer_enable 0`).
• **Lệnh 4**: Miễn trừ MILLET toàn diện ở cả 3 bảng System, Secure, Global cho toàn bộ app cần fix.
• **Lệnh 5 (NC 1)**: Tắt tính năng bóp băng thông mạng ngầm Millet (`network_traffic_millet_enable 0`).
• **Lệnh 6 (NC 2)**: Chặn Google Phenotype Sync ghi đè và vô hiệu hóa Phantom Process Killer.
• **Lệnh 7 (NC 3)**: Ghi VĨNH VIỄN vào `deviceidle.xml` & `netpolicy.xml` qua `cmd deviceidle whitelist` và `except-idle-whitelist` (thay thế hoàn toàn lệnh dumpsys tạm thời).
• **Lệnh 8 (NC 4)**: Cấp toàn quyền AppOps cho GMS và cưỡng bức đồng bộ Google Push Tickle.
• **Lệnh 9 (NC 5)**: Cấp quyền AppOps toàn diện cho toàn bộ app đích: `WAKE_LOCK`, `START_FOREGROUND`, Màn hình khóa `10020`, Khởi chạy nền `10021`, Pop-up `10022`, `POST_NOTIFICATION`.
• **Lệnh 10 (NC 6)**: Khóa App Standby Bucket ở mức ACTIVE (10) cho toàn bộ app đích và GMS.
• **Lệnh 11 (NC 7)**: Tước quyền PowerKeeper (`RUN_IN_BACKGROUND ignore`, `WAKE_LOCK ignore`) & vô hiệu hóa `PowerStateMachineService`.

👉 **Quy trình chuẩn chỉ chạy 1 lần**:
1. Vào tab **Hệ Thống** > Chọn app cần fix > Bấm chạy tất cả Lệnh 1 -> 4 (và 7 lệnh Flagship nếu máy cấu hình cao).
2. Mở Shizuku bấm **Dừng (Stop)**.
3. Vào Tùy chọn nhà phát triển > Tắt **Gỡ lỗi không dây (Wireless Debugging)** & Tắt **Gỡ lỗi USB (USB Debugging)**.
4. **GIỮ BẬT** công tắc tổng **Tùy chọn cho nhà phát triển** (để Android không tự động kích hoạt lại bộ đóng băng ngầm `cached_apps_freezer`, duy trì trọn vẹn 94% tiến độ).
5. Khởi động lại máy. 100% App ngân hàng (BIDV, MB Bank, VCB, Techcombank...) và VNeID vào bình thường không bị chặn, thông báo nổ tức thì 24/24!
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf(
                "tắt màn hình không nổ thông báo", "mở màn hình mới nổ", "tat man hinh", "mo man hinh moi no",
                "delay thông báo khi khóa máy", "khóa máy không nhận tin nhắn", "bật màn hình mới nhận được tin", "10020", "10021", "10022"
            ),
            title = "Khắc Phục Lỗi: Tắt Màn Hình Không Nổ Thông Báo, Mở Màn Mới Nổ",
            content = """
Đặc Trị Hiện Tượng Tắt Màn Hình 1 Phút Bị Tịt Thông Báo, Mở Màn Mới Nổ:

🔍 BẢN CHẤT 4 NGUYÊN NHÂN CỐT LÕI KHI TẮT MÀN 60 GIÂY:
1. Tính năng dọn dẹp RAM sau 1 phút khóa máy của Xiaomi (screen_off_clean_time):
   - Mặc định HyperOS/MIUI cài đặt "Xóa bộ nhớ đệm khi thiết bị bị khóa sau 1 phút". Đúng 60 giây sau khi tắt màn hình, hệ điều hành tự động diệt các process ngầm trong RAM, khiến app bị ngắt socket!
2. GMS thiếu quyền AppOps 10021 (Background Start Activity / Khởi chạy liên kết):
   - Khi tắt màn hình, Google Play Services vẫn nhận được push FCM từ máy chủ, nhưng khi GMS cố gắng phát broadcast để đánh thức app đích (MB Bank, Zalo, Messenger, TikTok...), HyperOS lập tức CHẶN vì thiếu quyền 10021! Gói tin bị ép lưu vào hàng đợi, tới khi người dùng mở màn hình mới được xả ra!
3. Lệnh NetPolicy cũ thiếu UID:
   - Android NetPolicy chỉ chấp nhận UID số nguyên (không nhận tên package). Khi tắt màn hình, NetPolicy bật Data Saver bóp băng thông mạng ngầm của app vì app chưa nằm trong whitelist UID!
4. Cơ chế ngắt mạng khi tắt màn hình của PowerKeeper (smart_power_network_idle_mode):
   - PowerKeeper tự động ngắt kết nối socket ngầm sau chu kỳ màn hình tắt.

✨ BỘ 11 LỆNH MỚI CỦA LIMI ĐÃ ĐẶC TRỊ DỨT ĐIỂM:
1. Đặt screen_off_clean_time = 0 & screen_off_clean_memory_time = 0: Chặn đứng hoàn toàn việc Xiaomi dọn dẹp RAM sau 1 phút tắt màn!
2. Cấp AppOps 10021 (Khởi chạy từ nền) cho CẢ GMS, GSF và TOÀN BỘ APP: GMS được phép phát broadcast đánh thức tức thì app đích ngay trong giây thứ 60 tắt màn hình!
3. Cấp Doze Whitelist, Except-Idle Whitelist & NetPolicy Whitelist theo đúng UID: App và GMS giữ kết nối mạng và WakeLock thông suốt 24/24 trong Doze!
4. Kích hoạt cờ sáng màn hình và hiệu ứng viền HyperOS: wake_up_screen_on_notification 1, notification_wake_screen 1, screen_lighting_mode 1.
5. Vô hiệu hóa smart_power_network_idle_mode và bóp băng thông mạng Millet Traffic.
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf(
                "kiểm tra fcm", "kiem tra fcm", "*#*#426#*#*", "fcm diagnostics", "xem kết nối fcm", "fcm connected"
            ),
            title = "Cách Kiểm Tra Kết Nối FCM Bằng Mã *#*#426#*#*",
            content = """
Hướng Dẫn Kiểm Tra Kết Nối Push FCM Không Cần Bật Shizuku:

1. Mở bàn phím cuộc gọi điện thoại, gõ dãy mã:
   *#*#426#*#*
2. Màn hình FCM Diagnostics sẽ lập tức hiện ra:
   • Status: Nếu hiển thị CONNECTED (kèm chấm tròn xanh) nghĩa là socket kết nối tới máy chủ Google FCM đang mở thông suốt.
   • Events: Liệt kê các lần ping / heartbeat định kỳ 120s được gửi đi đều đặn mà không có thông báo DISCONNECTED hay SOCKET_CLOSED.
3. Nếu Status báo CONNECTED: Toàn bộ thông báo push từ Zalo, Messenger, Telegram, BIDV, MB Bank sẽ được đẩy về tức thì!
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf(
                "fcm disconnect", "fcm disconect", "fcm mất kết nối", "gọi mess lúc được lúc không",
                "gọi mess lúc đc lúc ko", "gọi zalo được gọi mess không được", "gọi messenger chập chờn",
                "fcm chập chờn", "fcm ngắt kết nối", "err_io_fin", "greezer gms"
            ),
            title = "Tại Sao FCM Disconnect, Gọi Mess Chập Chờn Nhưng Zalo Vẫn Được?",
            content = """
TẠI SAO FCM HAY BỊ DISCONNECT KHI TẮT MÀN? (GỌI MESS LÚC ĐƯỢC LÚC KHÔNG NHƯNG ZALO VẪN ĐƯỢC):

1. Khác Biệt Giữa Zalo Và Messenger:
• Zalo duy trì socket mạng riêng độc lập với Google. Khi bạn đã chạy Lệnh cấp quyền Zalo và nạp Lệnh 4, tiến trình Zalo sống trong RAM và socket Zalo vẫn giữ kết nối trực tiếp, nên cuộc gọi Zalo đổ chuông ngay.
• Messenger không có socket ngầm riêng mà phụ thuộc 100% vào FCM Push Token của Google Play Services (cổng mtalk.google.com:5228). Nếu socket FCM bị ngắt kết nối (Disconnect), Messenger sẽ hoàn toàn không nhận được tín hiệu cuộc gọi cho đến khi bật sáng màn hình!

2. 3 Nguyên Nhân Gây Ngắt Kết Nối FCM Trên HyperOS:
• Greezer GMS Limiter: Sau khi tắt màn hình 10 giây, HyperOS tự động gọi triggerGMSLimitAction() đóng băng toàn bộ tiến trình Google Play Services.
• Lỗi ERR_IO_FIN do spam Heartbeat 120s: Gửi tín hiệu nhịp tim quá dày đặc khiến máy chủ Google hoặc nhà mạng gửi cờ TCP FIN ngắt kết nối.
• Tường lửa gms_wall của PowerKeeper: Cần đưa cả Google Play Store (com.android.vending) về "Không hạn chế pin" để tắt firewall này.

3. Khắc Phục Triệt Để Bằng Bản LIMI Mới:
• Chạy "Tất cả Lệnh 1 -> 4" tại tab Hệ Thống.
• Chạy "NC 3 (Lệnh 7)" và "NC 5 (Lệnh 9)" tại tab Flagship.
• Khởi động lại máy. Lệnh mới sẽ tắt cờ Greezer GMS (dumpsys greezer IM GMS disable), chuyển sang Adaptive Heartbeat và thêm com.android.vending, giữ FCM CONNECTED xanh ổn định 24/7!
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf(
                "88%", "94%", "tụt 88%", "tiến độ 88%", "tiến độ 94%", "tắt dev mode", "tắt nhà phát triển",
                "tùy chọn nhà phát triển", "tắt gỡ lỗi", "gỡ lỗi không dây", "wireless debugging", "app ngân hàng",
                "ngân hàng bị chặn", "cached_apps_freezer", "freezer", "tại sao tụt %", "tụt tiến độ", "mất lệnh"
            ),
            title = "Giải Thích Tiến Độ 94% vs 88%, Hướng Dẫn Tắt Gỡ Lỗi Không Dây & Quy Chuẩn App Ngân Hàng",
            content = """
TẠI SAO TIẾN ĐỘ ĐẠT 94% NHƯNG KHI TẮT NHÀ PHÁT TRIỂN LẠI TỤT VỀ 88%?

1. Bản Chất Kỹ Thuật (Cơ Chế cached_apps_freezer Của Android):
• Khi bạn chạy fix xong, LIMI đã vô hiệu hóa thành công bộ đóng băng tiến trình ngầm (cached_apps_freezer = 0). Lúc này máy đạt tiến độ tối đa 94% (mức hoàn hảo nhất Android cho phép).
• Tuy nhiên, cached_apps_freezer là tính năng gắn chặt với công tắc "Tùy chọn cho nhà phát triển".
• Nếu bạn TẮT HẲN công tắc tổng "Tùy chọn cho nhà phát triển": Hệ điều hành Android sẽ tự động kích hoạt lại bộ đóng băng (cached_apps_freezer = 1). Khi đó, LIMI đo đạc trung thực sẽ thấy freezer bị bật lại, khiến tiến độ hiển thị tụt từ 94% xuống 88% (mất 6% an toàn khi tắt màn hình lâu).

2. Bật "Tùy Chọn Nhà Phát Triển" Có Bị App Ngân Hàng (BIDV, MB, VCB...) Chặn Không?
• KHÔNG HỀ BỊ CHẶN (100% An Toàn)!
• Các app ngân hàng và VNeID CHỈ quét và chặn 2 mục: Gỡ lỗi USB (USB Debugging) và Gỡ lỗi không dây (Wireless Debugging).
• Chúng TUYỆT ĐỐI KHÔNG quét hay chặn công tắc tổng "Tùy chọn cho nhà phát triển" nếu các cổng gỡ lỗi ADB đã được tắt.

3. HƯỚNG DẪN THIẾT LẬP CHUẨN VÀNG SAU KHI FIX XONG:
Bước 1: Mở app Shizuku -> Bấm nút Dừng (Stop).
Bước 2: Vào Cài đặt máy -> Cài đặt bổ sung -> Tùy chọn nhà phát triển:
   • TẮT Gỡ lỗi không dây (Wireless Debugging) - cổng ghép nối Wi-Fi.
   • TẮT Gỡ lỗi USB (USB Debugging).
   • GIỮ BẬT công tắc tổng "Tùy chọn cho nhà phát triển" ở trên cùng!
Bước 3: Khởi động lại điện thoại (Restart).

KẾT QUẢ ĐẠT ĐƯỢC:
• Tiến độ Fix giữ vững 94% vĩnh viễn (các app ngầm không bị đóng băng khi tắt màn hình).
• App ngân hàng (BIDV, MB Bank, Vietcombank, Techcombank...), ví điện tử và VNeID đăng nhập mượt mà 100%, không báo lỗi ADB.
• Thông báo nổ tức thì 24/24 ngay cả khi để máy qua đêm!
""".trimIndent()
        ),
        KnowledgeItem(
            keywords = listOf(
                "tiktok", "tik tok", "có app tiktok chưa", "app tiktok", "danh sách app", "danh sach app",
                "các app đã có", "thêm app", "them app", "thêm ứng dụng", "lệnh 4 gồm app nào", "package name", "các app được fix"
            ),
            title = "Danh Sách Các App Đã Tích Hợp Sẵn Trong Lệnh Fix & Hướng Dẫn Thêm App Mới",
            content = """
DANH SÁCH ỨNG DỤNG ĐÃ CÓ SẴN TRONG BỘ LỆNH FIX & HƯỚNG DẪN THÊM APP:

1. Đã Có App TikTok Trong Lệnh Fix Chưa?
• ĐÃ CÓ 100%! LIMI đã tích hợp sẵn toàn bộ các phiên bản TikTok phổ biến:
  - com.ss.android.ugc.trill (TikTok phiên bản Châu Á / Việt Nam trên CH Play)
  - com.zhiliaoapp.musically (TikTok phiên bản Quốc tế / Global)
  - com.zhiliaoapp.musically.go (TikTok Lite)
  - com.ss.android.ugc.aweme (Douyin / TikTok nội địa Trung)

2. Toàn Bộ Danh Sách Ứng Dụng Mặc Định Đã Tích Hợp Sẵn:
• Nhắn tin, Gọi & Danh bạ: Zalo (com.zing.zalo), Messenger (com.facebook.orca), Telegram (org.telegram.messenger), WhatsApp (com.whatsapp), Danh bạ máy (com.android.contacts), Tin nhắn RCS Google IMS (com.google.android.ims).
• Mạng xã hội & Video: TikTok (cả 3 bản), Facebook (com.facebook.katana), Instagram (com.instagram.android), Threads (com.instagram.barcelona), YouTube ReVanced (app.revanced.android.youtube).
• Email & Dịch vụ Google: Gmail (com.google.android.gm), Mi Health / Mi Fitness (com.mi.health), MicroG GMS (app.revanced.android.gms), Google Play Services (com.google.android.gms, com.google.android.gsf).
• Ngân hàng phổ biến: BIDV (com.vnpay.bidv), MB Bank (com.mbmobile), Vietcombank (com.VCB), Techcombank (com.techcombank.mobile), VietinBank iPay (vn.com.vietinbank.ipay).

3. Hướng Dẫn Cách Thêm Ứng Dụng Mới Bất Kỳ:
Nếu bạn dùng thêm app khác (như Shopee, Lazada, Be, Grab, Skype, Teams, MoMo, Cake, VPBank, TPBank, ShopeePay...):
• Bước 1: Vào tab "Hệ Thống" trong LIMI.
• Bước 2: Tại ô "Danh sách gói ứng dụng (Package Name)", gõ thêm tên package của app (cách nhau bởi dấu phẩy) hoặc bấm nút chọn app trên giao diện để tự động thêm.
• Bước 3: Bấm nút "Chạy Lệnh 4" (hoặc "Chạy tất cả Lệnh 1 -> 4").
LIMI sẽ tự động cấp quyền Doze Whitelist, Millet Whitelist, AppOps 10020 (Màn hình khóa), 10021 (Khởi chạy nền), 10022 (Pop-up), Post Notification và khóa Standby Bucket mức ACTIVE cho tất cả app bạn vừa thêm!
""".trimIndent()
        )
    )

    fun initIfNeeded(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            isInitialized = true
        }

        // Nạp dữ liệu mẫu ban đầu vào RAM
        synchronized(customKnowledgeList) {
            if (customKnowledgeList.isEmpty()) {
                customKnowledgeList.addAll(DEFAULT_STARTER_KNOWLEDGE)
            }
        }

        // Chạy dọn dẹp cache rác và bộ nhớ disk trong nền
        executor.execute {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .remove(KEY_CACHED_CUSTOM_JSON)
                    .remove(KEY_LOCAL_LEARNED_JSON)
                    .apply()

                val customImgDir = java.io.File(context.filesDir, "limi_custom_images")
                if (customImgDir.exists()) {
                    customImgDir.deleteRecursively()
                }
            } catch (_: Throwable) {}
        }
    }

    fun getKnowledgeBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_KB_URL, "") ?: ""
        return if (saved.isNotBlank()) saved else DEFAULT_KB_URL
    }

    fun setKnowledgeBaseUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_KB_URL, url.trim()).apply()
        // Kích hoạt đồng bộ lại ngay sau khi đổi URL
        syncRemoteKnowledge(context)
    }

    fun hasCustomUrl(context: Context): Boolean {
        return getKnowledgeBaseUrl(context).isNotBlank()
    }

    fun getCustomKnowledgeCount(): Int = synchronized(customKnowledgeList) { customKnowledgeList.size }
    fun getLocalLearnedCount(): Int = synchronized(localLearnedList) { localLearnedList.size }

    private fun saveCustomCache(context: Context, rawData: String) {
        // Dữ liệu giữ thuần túy trên RAM, không lưu disk
    }

    private fun saveLearnedCache(context: Context) {
        // Dữ liệu giữ thuần túy trên RAM, không lưu disk
    }

    /**
     * Đồng bộ kho dữ liệu từ xa (Google Apps Script Web App hoặc Google Sheets CSV)
     */
    fun syncRemoteKnowledge(
        context: Context,
        onComplete: ((success: Boolean, count: Int, message: String) -> Unit)? = null
    ) {
        initIfNeeded(context)
        val urlStr = getKnowledgeBaseUrl(context)
        if (urlStr.isBlank()) {
            onComplete?.invoke(false, customKnowledgeList.size, "Chưa thiết lập liên kết kho dữ liệu Drive / Sheet.")
            return
        }

        executor.execute {
            try {
                var currentUrl = urlStr
                var conn: HttpURLConnection
                var responseCode: Int
                var redirects = 0

                while (true) {
                    val targetUrl = URL(currentUrl)
                    conn = (targetUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 20000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0 LimiAI-KnowledgeSync/1.0")
                    }

                    responseCode = conn.responseCode
                    if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                        responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                        responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                        responseCode == 307
                    ) {
                        val newLocation = conn.getHeaderField("Location")
                        if (!newLocation.isNullOrBlank() && redirects < 5) {
                            currentUrl = newLocation
                            redirects++
                            continue
                        }
                    }
                    break
                }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val rawContent = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()

                    val items = when {
                        rawContent.startsWith("[") || rawContent.startsWith("{") -> parseKnowledgeJson(rawContent)
                        rawContent.contains(",") || rawContent.contains("\t") -> parseCsvContent(rawContent)
                        else -> emptyList()
                    }

                    if (items.isNotEmpty()) {
                        synchronized(customKnowledgeList) {
                            customKnowledgeList.clear()
                            customKnowledgeList.addAll(items)
                        }
                        saveCustomCache(context, rawContent)
                        onComplete?.invoke(true, items.size, "Đồng bộ thành công ${items.size} mục tri thức từ Google Drive!")
                        return@execute
                    } else {
                        // Nếu Google Sheet chưa có mục nào (dữ liệu rỗng []), giữ nguyên các mục mẫu ban đầu
                        onComplete?.invoke(true, customKnowledgeList.size, "Đã kết nối Google Drive thành công! (Hiện tại kho chưa có mục mới)")
                        return@execute
                    }
                } else {
                    onComplete?.invoke(false, customKnowledgeList.size, "Lỗi máy chủ HTTP $responseCode")
                    return@execute
                }
            } catch (e: Exception) {
                onComplete?.invoke(false, customKnowledgeList.size, "Lỗi kết nối: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Chuẩn hóa từ khóa thiết bị & số model dính liền (VD: 18Pro -> 18 Pro, 17ProMax -> 17 Pro Max, Pad7 -> Pad 7)
     */
    /**
     * Chuẩn hóa từ khóa thiết bị & số model dính liền (VD: 18Pro -> 18 Pro, 17ProMax -> 17 Pro Max, Pad7 -> Pad 7)
     */
    fun smartNormalizeProductQuery(query: String): String {
        var q = query.trim()
        // Tách số và chữ dính nhau: 18Pro -> 18 Pro, Mi18 -> Mi 18
        q = q.replace(Regex("(?<=\\d)(?=[a-zA-Z\\p{L}])|(?<=[a-zA-Z\\p{L}])(?=\\d)"), " ")
        // Tách các hậu tố model dính liền: ProMax -> Pro Max, UltraMax -> Ultra Max
        q = q.replace(Regex("(?i)(pro)(max)"), "$1 $2")
        q = q.replace(Regex("(?i)(pad)(\\d+)"), "$1 $2")
        q = q.replace(Regex("(?i)(k)(\\d+)"), "$1 $2")
        q = q.replace(Regex("(?i)(turbo)(\\d+)"), "$1 $2")
        val norm = LimiAiService.normalizeVietnameseQuery(q)
        return LimiAiService.stripAccents(norm).replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Kiểm tra xem 2 chuỗi truy vấn có xung đột về phiên bản, hậu tố model, hoặc số thế hệ hay không
     * Ví dụ: "Xiaomi 18 Pro Max" KHÔNG ĐƯỢC khớp với "Xiaomi 18"
     * "Xiaomi 15 Pro" KHÔNG ĐƯỢC khớp với "Xiaomi 15"
     * "Lệnh 15" KHÔNG ĐƯỢC khớp với "Lệnh 1"
     */
    fun hasConflictingProductVariant(queryNorm: String, targetNorm: String): Boolean {
        val qTokens = queryNorm.split(" ").filter { it.isNotBlank() }.toSet()
        val tTokens = targetNorm.split(" ").filter { it.isNotBlank() }.toSet()

        // Danh sách các hậu tố, phân khúc, biến thể dòng máy
        val variantTokens = setOf(
            "pro", "max", "promax", "ultra", "plus", "lite", "se", "fe", "mini", "fold", "flip",
            "turbo", "zoom", "prime", "explorer", "racing", "youth", "play", "power", "gt", "neo",
            "master", "titanium", "standard", "tieu chuan", "thuong", "5g", "4g", "pro+", "civi",
            "pad", "tablet", "watch", "band", "buds"
        )

        // Nếu câu này có chứa hậu tố model mà câu kia không có -> Xung đột phiên bản
        val qVariants = qTokens.intersect(variantTokens)
        val tVariants = tTokens.intersect(variantTokens)
        if (qVariants != tVariants) return true

        // Kiểm tra các con số thế hệ (VD: 18 vs 17 vs 15 vs 1 vs 2 vs 10...)
        val numRegex = Regex("^\\d+$")
        val qNums = qTokens.filter { it.matches(numRegex) }.toSet()
        val tNums = tTokens.filter { it.matches(numRegex) }.toSet()
        if (qNums != tNums) return true

        return false
    }

    /**
     * Dạy hoặc sửa tri thức mới trực tiếp từ người dùng (Ưu tiên cao nhất, ghi đè tri thức cũ và tự duyệt lên server)
     */
    fun teachUserKnowledge(
        context: Context,
        topicOrQuestion: String,
        taughtAnswer: String,
        keywords: List<String> = emptyList(),
        imageBitmap: android.graphics.Bitmap? = null,
        imageBitmaps: List<android.graphics.Bitmap> = emptyList()
    ) {
        initIfNeeded(context)
        val cleanQ = topicOrQuestion.trim()
        val cleanA = taughtAnswer.trim()
        if (cleanQ.isEmpty() || cleanA.isEmpty()) return

        val normQ = smartNormalizeProductQuery(cleanQ)

        val allBitmaps = mutableListOf<android.graphics.Bitmap>()
        if (imageBitmap != null) allBitmaps.add(imageBitmap)
        for (b in imageBitmaps) {
            if (!allBitmaps.contains(b)) allBitmaps.add(b)
        }

        // 0. Nếu người dùng có đóng góp ảnh minh họa -> Nạp ngay vào Image Cache bộ nhớ và ổ đĩa máy
        if (allBitmaps.isNotEmpty()) {
            try {
                LimiImageSearchEngine.cacheImageLocally(cleanQ, allBitmaps[0], context)
                LimiImageSearchEngine.cacheImageLocally(normQ, allBitmaps[0], context)
                val topic = LimiImageSearchEngine.extractSearchTopic(cleanQ)
                if (topic.isNotBlank()) {
                    LimiImageSearchEngine.cacheImageLocally(topic, allBitmaps[0], context)
                }
            } catch (_: Throwable) {}
        }

        // 1. Xóa toàn bộ các dữ liệu cũ/sai trong localLearnedList trùng với chủ đề này
        synchronized(localLearnedList) {
            localLearnedList.removeAll { 
                val norm = smartNormalizeProductQuery(it.question)
                norm == normQ || (!hasConflictingProductVariant(norm, normQ) && (norm.contains(normQ) || normQ.contains(norm)))
            }
            localLearnedList.add(LearnedQA(question = cleanQ, answer = cleanA))
            if (localLearnedList.size > 200) localLearnedList.removeAt(0)
            saveLearnedCache(context)
        }

        // 2. Xóa/ghi đè các mục trong customKnowledgeList trùng với chủ đề này
        synchronized(customKnowledgeList) {
            customKnowledgeList.removeAll { item ->
                item.keywords.any { kw ->
                    val normKw = smartNormalizeProductQuery(kw)
                    normKw == normQ || (!hasConflictingProductVariant(normKw, normQ) && (normKw.contains(normQ) || normQ.contains(normKw)))
                } || (!hasConflictingProductVariant(smartNormalizeProductQuery(item.title), normQ) && smartNormalizeProductQuery(item.title).contains(normQ))
            }
            val kwList = mutableListOf(cleanQ.lowercase())
            if (!kwList.contains(normQ)) kwList.add(normQ)
            kwList.addAll(keywords)
            
            customKnowledgeList.add(
                0,
                KnowledgeItem(
                    keywords = kwList,
                    title = " Thông Tin Đã Dạy: $cleanQ",
                    content = cleanA,
                    contents = mutableListOf(cleanA)
                )
            )
        }

        // 3. Gửi lên Google Apps Script Webhook với autoApprove = true (Tự động duyệt không cần chờ và upload danh sách ảnh lên Drive nếu có)
        val webhookUrl = getKnowledgeBaseUrl(context)
        if (webhookUrl.isNotBlank() && webhookUrl.startsWith("http")) {
            executor.execute {
                try {
                    val imagesBase64Arr = JSONArray()
                    for (bmp in allBitmaps) {
                        try {
                            val baos = java.io.ByteArrayOutputStream()
                            val maxDim = 800
                            val bmpToCompress = if (bmp.width > maxDim || bmp.height > maxDim) {
                                val scale = maxDim.toFloat() / Math.max(bmp.width, bmp.height)
                                android.graphics.Bitmap.createScaledBitmap(
                                    bmp,
                                    (bmp.width * scale).toInt(),
                                    (bmp.height * scale).toInt(),
                                    true
                                )
                            } else {
                                bmp
                            }
                            bmpToCompress.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                            val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                            imagesBase64Arr.put(b64)
                        } catch (_: Throwable) {}
                    }

                    val postJson = JSONObject().apply {
                        put("action", "record_learned_qa")
                        put("question", cleanQ)
                        put("answer", cleanA)
                        put("autoApprove", true) // Tự động duyệt trực tiếp, không cần chờ duyệt
                        put("status", "Đã duyệt")
                        put("likes", 1)
                        put("unlikes", 0)
                        put("source", "user_teach_direct")
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("os", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                        put("timestamp", System.currentTimeMillis())
                        if (imagesBase64Arr.length() > 0) {
                            put("image_base64", imagesBase64Arr.optString(0))
                            put("images_base64", imagesBase64Arr)
                            put("image_title", cleanQ)
                        }
                    }

                    val postBytes = postJson.toString().toByteArray(Charsets.UTF_8)
                    val url = URL(webhookUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 15000
                        doOutput = true
                        instanceFollowRedirects = true
                        setFixedLengthStreamingMode(postBytes.size)
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        setRequestProperty("User-Agent", "LimiAI-UserTeach/1.0")
                    }

                    conn.outputStream.use { os ->
                        os.write(postBytes)
                        os.flush()
                    }

                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_SEE_OTHER) {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Tìm kiếm trong kho tri thức cá nhân (Custom Knowledge) và kho tự học (Learned Q&A)
     * Trả về LimiOfflineResult nếu tìm thấy kết quả phù hợp (0.05s, 0 token API)
     */
    fun findMatch(query: String, context: Context?): LimiOfflineResult? {
        if (context != null) {
            initIfNeeded(context)
        }

        val cleanStripped = smartNormalizeProductQuery(query)
        if (cleanStripped.length < 2) return null

        // 0. Nếu câu hỏi là một Prompt chỉ thị AI (tóm tắt, phân tích, trích xuất, so sánh, dịch thuật, yêu cầu tra cứu AI...)
        // -> Bỏ qua toàn bộ matching offline để chuyển thẳng cho Online AI Gemini xử lý chuyên sâu!
        val isComplexAiPrompt = cleanStripped.contains("tom tat") || cleanStripped.contains("chat loc") ||
                cleanStripped.contains("phan tich") || cleanStripped.contains("danh gia") ||
                cleanStripped.contains("so sanh") || cleanStripped.contains("dich sang") ||
                cleanStripped.contains("viet bai") || cleanStripped.contains("viet code") ||
                cleanStripped.contains("giai thich chi tiet") || cleanStripped.contains("giai thich ro") ||
                cleanStripped.contains("tra cuu them") || cleanStripped.contains("tra cuu lai") ||
                cleanStripped.contains("tim lai bang ai") || cleanStripped.contains("tra cuu bang ai") ||
                cleanStripped.contains("doan van ban sau") || cleanStripped.contains("y cot loi") ||
                cleanStripped.contains("so lieu quan trong") ||
                (cleanStripped.length > 70 && !cleanStripped.startsWith("day") && !cleanStripped.startsWith("train"))
        if (isComplexAiPrompt) {
            return null
        }

        // 1. Quét kho tự học RAM (Learned Q&A)
        synchronized(localLearnedList) {
            for (learned in localLearnedList.reversed()) {
                val lStripped = smartNormalizeProductQuery(learned.question)

                // Nếu xung đột về hậu tố model (VD: Xiaomi 18 vs Xiaomi 18 Pro Max) hoặc số thế hệ -> Không khớp
                if (hasConflictingProductVariant(cleanStripped, lStripped)) {
                    continue
                }

                val isExactMatch = cleanStripped == lStripped || query.trim().equals(learned.question.trim(), ignoreCase = true)
                val qTokens = cleanStripped.split(" ").filter { it.length > 1 }.toSet()
                val lTokens = lStripped.split(" ").filter { it.length > 1 }.toSet()
                val isTokenMatch = qTokens.isNotEmpty() && qTokens == lTokens

                if (isExactMatch || isTokenMatch) {
                    // Lần 1 -> Lần 3: Nếu chưa đủ 3 cách diễn đạt khác nhau và chưa gọi 3 lần -> Trả về null để gọi API lấy câu mới và gửi lên Google Sheet
                    if (learned.apiCallCount < 3 && learned.answers.size < 3) {
                        return null
                    }

                    // Từ lần 4 trở đi (hoặc đã có đủ 3 phiên bản trả lời): Tuyệt đối không gửi request lên API Key nữa, lấy ngay từ Data (xoay vòng ngẫu nhiên)
                    var matchedBmp: android.graphics.Bitmap? = null
                    try {
                        if (LimiImageSearchEngine.isImageSearchQuery(query)) {
                            val topic = LimiImageSearchEngine.extractSearchTopic(query)
                            matchedBmp = LimiImageSearchEngine.searchAndFetchImage(topic, context)
                        }
                    } catch (_: Throwable) {}

                    val chosenAnswer = learned.getNextVariation()
                    return LimiOfflineResult(
                        text = chosenAnswer,
                        imageBitmap = matchedBmp,
                        actionButtons = listOf(
                            ChatActionButton(" Limi Tìm Thêm", R.drawable.ic_sparkles, ChatActionType.SUMMARIZE_WITH_AI, payload = query)
                        )
                    )
                }
            }
        }

        // Trích xuất và phân giải mã model máy phần cứng (VD: 2410DPN6CC -> Xiaomi 15 Pro)
        val modelCode = XiaomiModelCatalog.extractModelCodeFromQuery(query)
        val mappedMarketingName = if (modelCode != null) XiaomiModelCatalog.findMarketingName(modelCode) else null
        val mappedStripped = if (mappedMarketingName != null) smartNormalizeProductQuery(mappedMarketingName) else null

        // 2. Quét kho tri thức chính thức (Custom Knowledge từ Google Sheet / Starter)
        synchronized(customKnowledgeList) {
            var bestItem: KnowledgeItem? = null
            var bestScore = 0

            for (item in customKnowledgeList) {
                // Thử cả từ khóa và tiêu đề của item
                val allKeywords = item.keywords.toMutableList()
                allKeywords.add(item.title)

                for (kw in allKeywords) {
                    val strippedKw = smartNormalizeProductQuery(kw)
                    if (strippedKw.isBlank() || strippedKw.length < 3) continue

                    // Nếu xung đột biến thể (VD: người dùng hỏi Pro Max mà keyword chỉ là tiêu chuẩn hoặc Pro) -> Bỏ qua
                    if (hasConflictingProductVariant(cleanStripped, strippedKw)) {
                        continue
                    }

                    var score = 0
                    if (cleanStripped == strippedKw || (mappedStripped != null && mappedStripped == strippedKw)) {
                        score = 2000 // Khớp chính xác 100%
                    } else if (modelCode != null && strippedKw.contains(modelCode.lowercase())) {
                        score = 1800 // Khớp chính xác mã máy
                    } else if (mappedStripped != null && (cleanStripped.contains(mappedStripped) || strippedKw.contains(mappedStripped))) {
                        score = 1500 // Khớp qua tên thương mại của mã máy
                    } else if (cleanStripped.startsWith(strippedKw) || cleanStripped.endsWith(strippedKw)) {
                        score = 1000 + strippedKw.length * 20
                    } else if (cleanStripped.contains(" $strippedKw ") || cleanStripped.startsWith("$strippedKw ") || cleanStripped.endsWith(" $strippedKw")) {
                        score = 900 + strippedKw.length * 15
                    }

                    if (score > bestScore) {
                        bestScore = score
                        bestItem = item
                    }
                }
            }

            val targetItem = bestItem
            if (targetItem != null && bestScore >= 700) {
                val normQ = smartNormalizeProductQuery(query)
                val learnedItem = synchronized(localLearnedList) {
                    localLearnedList.firstOrNull {
                        val lNorm = smartNormalizeProductQuery(it.question)
                        lNorm == normQ || (!hasConflictingProductVariant(lNorm, normQ) && (lNorm.contains(normQ) || normQ.contains(lNorm)))
                    }
                }

                val totalVariations = targetItem.contents.size.coerceAtLeast(1)
                val totalCalls = learnedItem?.apiCallCount ?: (if (totalVariations >= 3) 3 else 1)

                // Lần 1 -> Lần 3: Nếu câu hỏi chưa đủ 3 câu trả lời khác nhau và chưa gọi 3 lần -> Trả về null để gọi API lấy câu mới và gửi lên Google Sheet
                if (totalCalls < 3 && totalVariations < 3) {
                    return null
                }

                // Từ lần 4 trở đi (hoặc khi Data đã đủ 3 phiên bản): Phục vụ 100% từ Data (xoay vòng ngẫu nhiên, 0s, 0 token)
                val actionButtons = mutableListOf<ChatActionButton>()
                val btnTitle = targetItem.actionButtonTitle
                val btnAction = targetItem.actionType
                if (!btnTitle.isNullOrBlank() && btnAction != null) {
                    actionButtons.add(
                        ChatActionButton(
                            title = btnTitle,
                            iconRes = R.drawable.ic_sparkles,
                            actionType = btnAction,
                            payload = targetItem.payload
                        )
                    )
                }

                var matchedBmp: android.graphics.Bitmap? = null
                try {
                    if (LimiImageSearchEngine.isImageSearchQuery(query)) {
                        val topic = LimiImageSearchEngine.extractSearchTopic(query)
                        matchedBmp = LimiImageSearchEngine.searchAndFetchImage(topic, context)
                    }
                } catch (_: Throwable) {}

                val chosenAnswer = targetItem.getRandomVariation()
                return LimiOfflineResult(
                    text = chosenAnswer,
                    imageBitmap = matchedBmp,
                    actionButtons = actionButtons
                )
            }
        }

        return null
    }

    /**
     * Tự động ghi lại câu hỏi & câu trả lời vừa học được từ Gemini:
     * 1. Cập nhật vào Memory RAM (không lưu disk cache để không gây tăng dung lượng app).
     * 2. Gửi ngầm (POST) thẳng lên Google Sheet & thư mục Limi_Images trên Google Drive (qua Apps Script Webhook).
     */
    fun recordLearnedAnswer(
        context: Context,
        question: String,
        answer: String,
        imageBitmap: android.graphics.Bitmap? = null,
        imageTitle: String? = null
    ) {
        initIfNeeded(context)

        // Không ghi lại nếu câu trả lời là lỗi hệ thống hoặc câu quá ngắn
        if (answer.contains(" [Limi AI] Không thể kết nối", ignoreCase = true) ||
            answer.contains("Lỗi kết nối", ignoreCase = true) ||
            answer.contains("QUOTA_EXCEEDED", ignoreCase = true) ||
            question.trim().length < 4 ||
            answer.trim().length < 10
        ) {
            return
        }

        val cleanQ = question.trim()
        val cleanA = answer.trim()
        val qLower = cleanQ.lowercase()

        // Phân loại câu hỏi thời gian thực (giá vàng, thời tiết, tỷ giá, ngày mai, hôm nay, hôm qua...)
        val isTimeSensitive = qLower.contains("hôm nay") || qLower.contains("hom nay") ||
                qLower.contains("ngày mai") || qLower.contains("ngay mai") ||
                qLower.contains("hôm qua") || qLower.contains("hom qua") ||
                qLower.contains("ngày kia") || qLower.contains("ngay kia") ||
                qLower.contains("thời tiết") || qLower.contains("thoi tiet") ||
                qLower.contains("nhiệt độ") || qLower.contains("nhiet do") ||
                qLower.contains("giá vàng") || qLower.contains("gia vang") ||
                qLower.contains("tỷ giá") || qLower.contains("ty gia")

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN"))
        val nowCal = Calendar.getInstance()
        val todayStr = sdf.format(nowCal.time)
        val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowStr = sdf.format(tomorrowCal.time)
        val yesterdayCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayStr = sdf.format(yesterdayCal.time)

        // Chuẩn hóa câu hỏi tương đối gắn chặt với ngày cụ thể để tra cứu hồi cứu
        val anchoredQuestion = when {
            qLower.contains("ngày mai") || qLower.contains("ngay mai") -> cleanQ.replace(Regex("(?i)ngày mai|ngay mai"), "ngày $tomorrowStr")
            qLower.contains("hôm qua") || qLower.contains("hom qua") -> cleanQ.replace(Regex("(?i)hôm qua|hom qua"), "ngày $yesterdayStr")
            qLower.contains("hôm nay") || qLower.contains("hom nay") -> cleanQ.replace(Regex("(?i)hôm nay|hom nay"), "ngày $todayStr")
            isTimeSensitive && !cleanQ.contains("/") -> "$cleanQ (ngày $todayStr)"
            else -> cleanQ
        }

        val normQ = smartNormalizeProductQuery(cleanQ)

        // 1. Cập nhật vào Memory RAM (không lưu disk cache để không làm tăng dung lượng app)
        synchronized(localLearnedList) {
            val existing = localLearnedList.firstOrNull {
                it.question.equals(anchoredQuestion, ignoreCase = true) ||
                it.question.equals(cleanQ, ignoreCase = true) ||
                smartNormalizeProductQuery(it.question) == normQ
            }
            if (existing != null) {
                existing.addAnswerVariation(cleanA)
            } else {
                val newLearned = LearnedQA(
                    question = anchoredQuestion,
                    answer = cleanA,
                    answers = mutableListOf(cleanA),
                    apiCallCount = 1
                )
                localLearnedList.add(newLearned)
                if (localLearnedList.size > 200) {
                    localLearnedList.removeAt(0)
                }
            }
        }

        synchronized(customKnowledgeList) {
            val existingCustom = customKnowledgeList.firstOrNull { item ->
                item.keywords.any { kw ->
                    val normKw = smartNormalizeProductQuery(kw)
                    normKw == normQ || (!hasConflictingProductVariant(normKw, normQ) && (normKw.contains(normQ) || normQ.contains(normKw)))
                } || (!hasConflictingProductVariant(smartNormalizeProductQuery(item.title), normQ) && smartNormalizeProductQuery(item.title).contains(normQ))
            }
            if (existingCustom != null) {
                existingCustom.addContentVariation(cleanA)
            } else {
                customKnowledgeList.add(
                    0,
                    KnowledgeItem(
                        keywords = listOf(cleanQ.lowercase(), normQ),
                        title = " $cleanQ",
                        content = cleanA,
                        contents = mutableListOf(cleanA)
                    )
                )
            }
        }

        // 2. Gửi ngầm thẳng lên Google Sheet / Apps Script Webhook trên Google Drive (kèm ảnh nếu có)
        val webhookUrl = getKnowledgeBaseUrl(context)
        if (webhookUrl.isNotBlank() && webhookUrl.startsWith("http")) {
            executor.execute {
                try {
                    val imageBase64 = try {
                        if (imageBitmap != null) {
                            val baos = java.io.ByteArrayOutputStream()
                            val maxDim = 800
                            val bmpToCompress = if (imageBitmap.width > maxDim || imageBitmap.height > maxDim) {
                                val scale = maxDim.toFloat() / Math.max(imageBitmap.width, imageBitmap.height)
                                android.graphics.Bitmap.createScaledBitmap(
                                    imageBitmap,
                                    (imageBitmap.width * scale).toInt(),
                                    (imageBitmap.height * scale).toInt(),
                                    true
                                )
                            } else {
                                imageBitmap
                            }
                            bmpToCompress.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                            android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                        } else null
                    } catch (_: Throwable) {
                        null
                    }

                    val postJson = JSONObject().apply {
                        put("action", "record_learned_qa")
                        put("question", anchoredQuestion)
                        put("answer", cleanA)
                        put("autoApprove", true) // Tự động duyệt trực tiếp, không cần chờ duyệt
                        put("status", "Đã duyệt")
                        put("likes", 1)
                        put("unlikes", 0)
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("os", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                        put("timestamp", System.currentTimeMillis())
                        if (!imageBase64.isNullOrBlank()) {
                            put("image_base64", imageBase64)
                            put("image_title", imageTitle ?: question)
                        }
                    }

                    val postBytes = postJson.toString().toByteArray(Charsets.UTF_8)
                    val url = URL(webhookUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 15000
                        doOutput = true
                        instanceFollowRedirects = true
                        setFixedLengthStreamingMode(postBytes.size)
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        setRequestProperty("User-Agent", "LimiAI-AutoLearn/1.0")
                    }

                    conn.outputStream.use { os ->
                        os.write(postBytes)
                        os.flush()
                    }

                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_SEE_OTHER) {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    }
                } catch (_: Throwable) {
                    // Chạy ngầm, không làm phiền trải nghiệm người dùng nếu mạng yếu
                }
            }
        }
    }

    /**
     * Lấy số lượt Like và Unlike hiển thị của câu trả lời (lưu trữ cục bộ bền vững + cộng đồng)
     */
    /**
     * Lấy số lượt Like và Unlike hiển thị của câu trả lời (chỉ lấy số thực tế từ Data hoặc thiết bị, không tạo số ngẫu nhiên)
     */
    fun getVoteCounts(context: Context, answer: String): Pair<Int, Int> {
        if (answer.isBlank()) return Pair(0, 0)
        val prefs = context.getSharedPreferences("limi_feedback_votes", Context.MODE_PRIVATE)
        val key = "vote_" + Math.abs(answer.trim().hashCode())
        val storedLikes = prefs.getInt("${key}_likes", -1)
        val storedUnlikes = prefs.getInt("${key}_unlikes", -1)

        if (storedLikes >= 0 && storedUnlikes >= 0) {
            return Pair(storedLikes, storedUnlikes)
        }

        // Lấy số Like/Unlike thực tế từ kho dữ liệu Google Sheet đã đồng bộ
        var dataLikes = 0
        var dataUnlikes = 0

        val cleanA = answer.trim()
        synchronized(customKnowledgeList) {
            val matched = customKnowledgeList.firstOrNull { it.content.trim().equals(cleanA, ignoreCase = true) || cleanA.contains(it.content.trim()) }
            if (matched != null) {
                dataLikes = matched.likes
                dataUnlikes = matched.unlikes
            }
        }

        if (dataLikes == 0 && dataUnlikes == 0) {
            synchronized(localLearnedList) {
                val matched = localLearnedList.firstOrNull { it.answer.trim().equals(cleanA, ignoreCase = true) || cleanA.contains(it.answer.trim()) }
                if (matched != null) {
                    dataLikes = matched.likes
                    dataUnlikes = matched.unlikes
                }
            }
        }

        val finalLikes = if (storedLikes >= 0) storedLikes else dataLikes
        val finalUnlikes = if (storedUnlikes >= 0) storedUnlikes else dataUnlikes

        return Pair(finalLikes.coerceAtLeast(0), finalUnlikes.coerceAtLeast(0))
    }

    /**
     * Lưu cập nhật số lượt Like và Unlike của câu trả lời
     */
    fun saveVoteCounts(context: Context, answer: String, likes: Int, unlikes: Int) {
        if (answer.isBlank()) return
        val prefs = context.getSharedPreferences("limi_feedback_votes", Context.MODE_PRIVATE)
        val key = "vote_" + Math.abs(answer.trim().hashCode())
        prefs.edit()
            .putInt("${key}_likes", likes.coerceAtLeast(0))
            .putInt("${key}_unlikes", unlikes.coerceAtLeast(0))
            .apply()
    }

    /**
     * Gửi đánh giá Like / Unlike cho câu trả lời của AI:
     * - Like: Tăng uy tín câu trả lời, giữ lại trong kho dữ liệu
     * - Unlike: Tăng điểm tiêu cực, nếu tích lũy từ 5 lượt Unlike trở lên sẽ tự động xóa khỏi Data tổng!
     */
    fun submitVote(
        context: Context,
        question: String,
        answer: String,
        voteType: String, // "like" hoặc "unlike"
        onComplete: ((success: Boolean, message: String) -> Unit)? = null
    ) {
        initIfNeeded(context)
        val cleanQ = question.trim()
        val cleanA = answer.trim()

        // Cập nhật số like/unlike trong memory list
        if (voteType == "like") {
            synchronized(localLearnedList) {
                localLearnedList.filter { it.question.equals(cleanQ, ignoreCase = true) || it.answer.equals(cleanA, ignoreCase = true) }
                    .forEach { item ->
                        val idx = localLearnedList.indexOf(item)
                        if (idx >= 0) localLearnedList[idx] = item.copy(likes = item.likes + 1)
                    }
                saveLearnedCache(context)
            }
        }

        // Nếu Unlike: Loại bỏ ngay khỏi cache máy này để tránh trả lời lại câu lỗi
        if (voteType == "unlike") {
            synchronized(localLearnedList) {
                localLearnedList.removeAll { it.question.equals(cleanQ, ignoreCase = true) || it.answer.equals(cleanA, ignoreCase = true) }
                saveLearnedCache(context)
            }
            synchronized(customKnowledgeList) {
                customKnowledgeList.removeAll { it.content.equals(cleanA, ignoreCase = true) }
            }
        }

        val webhookUrl = getKnowledgeBaseUrl(context)
        if (webhookUrl.isNotBlank() && webhookUrl.startsWith("http")) {
            executor.execute {
                try {
                    val postJson = JSONObject().apply {
                        put("action", "vote_qa")
                        put("voteType", voteType) // "like" hoặc "unlike"
                        put("question", cleanQ)
                        put("answer", cleanA)
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("timestamp", System.currentTimeMillis())
                    }

                    val postBytes = postJson.toString().toByteArray(Charsets.UTF_8)
                    val url = URL(webhookUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 15000
                        doOutput = true
                        instanceFollowRedirects = true
                        setFixedLengthStreamingMode(postBytes.size)
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        setRequestProperty("User-Agent", "LimiAI-Vote/1.0")
                    }

                    conn.outputStream.use { os ->
                        os.write(postBytes)
                        os.flush()
                    }

                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_SEE_OTHER) {
                        onComplete?.invoke(true, if (voteType == "like") "Đã ghi nhận đánh giá hữu ích! " else "Đã ghi nhận phản hồi! ")
                    } else {
                        onComplete?.invoke(false, "Mã phản hồi: $code")
                    }
                } catch (e: Exception) {
                    onComplete?.invoke(false, "Lỗi kết nối: ${e.localizedMessage}")
                }
            }
        }
    }

    /**
     * Gửi đóng góp hoặc sửa câu trả lời từ người dùng (Cách 1 & Cách 2)
     */
    fun submitUserContribution(
        context: Context,
        question: String,
        suggestedAnswer: String,
        source: String = "feedback_dialog",
        imageBitmap: android.graphics.Bitmap? = null,
        imageBitmaps: List<android.graphics.Bitmap> = emptyList(),
        onComplete: ((success: Boolean, message: String) -> Unit)? = null
    ) {
        initIfNeeded(context)
        val cleanQ = question.trim()
        val cleanA = suggestedAnswer.trim()
        if (cleanQ.isEmpty() || cleanA.isEmpty()) {
            onComplete?.invoke(false, "Vui lòng nhập đầy đủ nội dung đóng góp.")
            return
        }

        // Tự động ghi đè và lưu vào local cache + tự duyệt lên server (kèm danh sách ảnh minh họa nếu có)
        teachUserKnowledge(context, cleanQ, cleanA, imageBitmap = imageBitmap, imageBitmaps = imageBitmaps)
        onComplete?.invoke(true, "Đã lưu bài học vào máy và cập nhật lên Google Drive thành công!")
    }

    /**
     * Parse JSON kho tri thức
     */
    /**
     * Parse JSON kho tri thức từ Apps Script Webhook
     */
    private fun parseKnowledgeJson(jsonStr: String): List<KnowledgeItem> {
        val list = mutableListOf<KnowledgeItem>()
        try {
            val root = if (jsonStr.startsWith("{")) {
                val obj = JSONObject(jsonStr)
                obj.optJSONArray("items") ?: obj.optJSONArray("data") ?: JSONArray()
            } else {
                JSONArray(jsonStr)
            }

            for (i in 0 until root.length()) {
                val itemObj = root.optJSONObject(i) ?: continue

                // 1. Kiểm tra trạng thái duyệt ("Oke", "Đã duyệt", "OK", "ok", "duyệt"...)
                val status = itemObj.optString("status", "").ifBlank {
                    itemObj.optString("trang_thai", "").ifBlank {
                        itemObj.optString("trangThai", "").ifBlank {
                            itemObj.optString("Trạng Thái", "")
                        }
                    }
                }.trim().lowercase()

                val isApproved = status.isEmpty() || status.contains("oke") || status.contains("ok") ||
                        status.contains("duyệt") || status.contains("duyet") || status == "true"
                if (!isApproved) continue // Bỏ qua các mục "Chờ duyệt" hoặc "Từ chối"

                // 2. Trích xuất câu hỏi & từ khóa
                val question = itemObj.optString("question", "").ifBlank {
                    itemObj.optString("cau_hoi", "").ifBlank {
                        itemObj.optString("cau_hoi_cua_nguoi_dung", "").ifBlank {
                            itemObj.optString("Câu Hỏi Của Người Dùng", "").ifBlank {
                                itemObj.optString("title", "")
                            }
                        }
                    }
                }.trim()

                val modelCode = itemObj.optString("modelCode", "").ifBlank {
                    itemObj.optString("ma_model", "").ifBlank {
                        itemObj.optString("Mã Model", "")
                    }
                }.trim()

                val kwRaw = itemObj.opt("keywords")
                val keywords = when (kwRaw) {
                    is JSONArray -> {
                        val arr = mutableListOf<String>()
                        for (k in 0 until kwRaw.length()) {
                            val s = kwRaw.optString(k, "").trim()
                            if (s.isNotEmpty()) arr.add(s)
                        }
                        arr
                    }
                    is String -> kwRaw.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    else -> mutableListOf()
                }
                if (question.isNotEmpty() && !keywords.contains(question.lowercase())) {
                    keywords.add(0, question.lowercase())
                }
                if (modelCode.isNotEmpty() && !keywords.contains(modelCode.lowercase())) {
                    keywords.add(modelCode.lowercase())
                }

                // 3. Trích xuất câu trả lời
                val content = itemObj.optString("content", "").ifBlank {
                    itemObj.optString("answer", "").ifBlank {
                        itemObj.optString("cau_tra_loi_cua_gemini", "").ifBlank {
                            itemObj.optString("Câu Trả Lời Của Gemini", "").ifBlank {
                                itemObj.optString("cau_tra_loi", "").ifBlank {
                                    itemObj.optString("noi_dung", "")
                                }
                            }
                        }
                    }
                }.trim()

                val title = itemObj.optString("title", "").ifBlank {
                    if (question.isNotEmpty()) " $question" else " Tri Thức Xiaomi"
                }

                val btnTitle = itemObj.optString("actionButtonTitle", "").ifBlank { itemObj.optString("nut_bam", "") }
                val actionTypeStr = itemObj.optString("actionType", "").ifBlank { itemObj.optString("hanh_dong", "") }
                val payload = if (itemObj.has("payload")) itemObj.optString("payload") else null

                val likes = itemObj.optInt("likes", itemObj.optInt("like", itemObj.optInt("Like", 0)))
                val unlikes = itemObj.optInt("unlikes", itemObj.optInt("unlike", itemObj.optInt("Unlike", 0)))

                val actionType = try {
                    when {
                        actionTypeStr.startsWith("EXECUTE_15") -> ChatActionType.EXECUTE_15_FIX_COMMANDS
                        actionTypeStr.startsWith("EXECUTE_7") || actionTypeStr.startsWith("EXECUTE_FLAGSHIP") -> ChatActionType.EXECUTE_7_FLAGSHIP_COMMANDS
                        actionTypeStr.startsWith("EXECUTE_6") || actionTypeStr.startsWith("EXECUTE_GPS") -> ChatActionType.EXECUTE_6_GPS_COMMANDS
                        actionTypeStr.startsWith("OPEN_SHIZUKU") -> ChatActionType.OPEN_SHIZUKU_GUIDE
                        actionTypeStr.startsWith("OPEN_BATTERY") -> ChatActionType.OPEN_BATTERY_HEALTH
                        actionTypeStr.startsWith("OPEN_DONATE") -> ChatActionType.OPEN_DONATE
                        actionTypeStr.startsWith("OPEN_DEBLOAT") -> ChatActionType.OPEN_DEBLOAT_TAB
                        actionTypeStr.startsWith("OPEN_PERMISSIONS") -> ChatActionType.OPEN_PERMISSIONS_TAB
                        actionTypeStr.startsWith("EXECUTE_RESET") -> ChatActionType.EXECUTE_RESET_ALL
                        actionTypeStr.isNotBlank() -> ChatActionType.valueOf(actionTypeStr)
                        else -> null
                    }
                } catch (_: Throwable) {
                    null
                }

                if (keywords.isNotEmpty() && content.isNotBlank()) {
                    val existing = list.firstOrNull { item ->
                        item.keywords.any { kw -> keywords.contains(kw) } || item.title.equals(title, ignoreCase = true)
                    }
                    if (existing != null) {
                        existing.addContentVariation(content)
                    } else {
                        list.add(
                            KnowledgeItem(
                                keywords = keywords,
                                title = title,
                                content = content,
                                contents = mutableListOf(content),
                                actionButtonTitle = if (btnTitle.isNotBlank()) btnTitle else null,
                                actionType = actionType,
                                payload = payload,
                                likes = likes,
                                unlikes = unlikes
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {}
        return list
    }

    /**
     * Parse CSV kho tri thức từ Google Sheet
     * Tương thích bảng: Thời Gian | Câu Hỏi Của Người Dùng | Câu Trả Lời Của Gemini | Thiết Bị | Hệ Điều Hành | Trạng Thái | Like | Ghi Chú | Mã Model | Unlike
     */
    private fun parseCsvContent(csvStr: String): List<KnowledgeItem> {
        val list = mutableListOf<KnowledgeItem>()
        val lines = csvStr.lines()
        if (lines.isEmpty()) return list

        var colIdxQuestion = 1
        var colIdxAnswer = 2
        var colIdxStatus = 5
        var colIdxLike = 6
        var colIdxModel = 8
        var colIdxUnlike = 9
        var isHeaderFound = false

        // Kiểm tra header để ánh xạ đúng cột động
        val headerTokens = parseCsvLine(lines[0]).map { it.trim().lowercase() }
        for ((idx, h) in headerTokens.withIndex()) {
            when {
                h.contains("câu hỏi") || h.contains("cau hoi") || h.contains("question") || h.contains("tu_khoa") || h.contains("keywords") -> {
                    colIdxQuestion = idx
                    isHeaderFound = true
                }
                h.contains("trả lời") || h.contains("tra loi") || h.contains("answer") || h.contains("content") || h.contains("noi_dung") -> {
                    colIdxAnswer = idx
                    isHeaderFound = true
                }
                h.contains("trạng thái") || h.contains("trang thai") || h.contains("status") -> {
                    colIdxStatus = idx
                    isHeaderFound = true
                }
                h == "like" || h == "likes" || h.contains("thích") -> {
                    colIdxLike = idx
                    isHeaderFound = true
                }
                h == "unlike" || h == "unlikes" -> {
                    colIdxUnlike = idx
                    isHeaderFound = true
                }
                h.contains("mã model") || h.contains("ma model") || h.contains("model") -> {
                    colIdxModel = idx
                    isHeaderFound = true
                }
            }
        }

        val startRow = if (isHeaderFound) 1 else 0
        for (i in startRow until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue

            val tokens = parseCsvLine(line)
            if (tokens.size <= colIdxAnswer) continue

            val status = if (tokens.size > colIdxStatus) tokens[colIdxStatus].trim().lowercase() else ""
            val isApproved = status.isEmpty() || status.contains("oke") || status.contains("ok") ||
                    status.contains("duyệt") || status.contains("duyet") || status == "true"
            if (!isApproved) continue // Bỏ qua "Chờ duyệt"

            val question = if (tokens.size > colIdxQuestion) tokens[colIdxQuestion].trim() else ""
            val content = if (tokens.size > colIdxAnswer) tokens[colIdxAnswer].trim() else ""
            val modelCode = if (tokens.size > colIdxModel) tokens[colIdxModel].trim() else ""
            val likes = if (tokens.size > colIdxLike) tokens[colIdxLike].trim().toIntOrNull() ?: 0 else 0
            val unlikes = if (tokens.size > colIdxUnlike) tokens[colIdxUnlike].trim().toIntOrNull() ?: 0 else 0

            if (question.isNotBlank() && content.isNotBlank()) {
                val cleanContent = content.replace("\\n", "\n")
                val keywords = mutableListOf(question.lowercase())
                if (modelCode.isNotBlank()) {
                    keywords.add(modelCode.lowercase())
                }
                val existing = list.firstOrNull { it.title.equals(" $question", ignoreCase = true) || it.keywords.contains(question.lowercase()) }
                if (existing != null) {
                    existing.addContentVariation(cleanContent)
                } else {
                    list.add(
                        KnowledgeItem(
                            keywords = keywords,
                            title = " $question",
                            content = cleanContent,
                            contents = mutableListOf(cleanContent),
                            likes = likes,
                            unlikes = unlikes
                        )
                    )
                }
            }
        }
        return list
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false

        for (ch in line) {
            when {
                ch == '\"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(ch)
            }
        }
        result.add(cur.toString())
        return result
    }

    private fun parseLearnedJson(jsonStr: String): List<LearnedQA> {
        val list = mutableListOf<LearnedQA>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val answersList = mutableListOf<String>()
                val answersArr = obj.optJSONArray("answers")
                if (answersArr != null) {
                    for (j in 0 until answersArr.length()) {
                        val a = answersArr.optString(j, "")
                        if (a.isNotBlank() && !answersList.contains(a)) {
                            answersList.add(a)
                        }
                    }
                }
                val ans = obj.optString("answer", "")
                if (answersList.isEmpty() && ans.isNotBlank()) {
                    answersList.add(ans)
                }
                list.add(
                    LearnedQA(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        question = obj.optString("question", ""),
                        answer = ans,
                        answers = answersList,
                        apiCallCount = obj.optInt("apiCallCount", answersList.size.coerceAtLeast(1)),
                        likes = obj.optInt("likes", obj.optInt("like", 0)),
                        unlikes = obj.optInt("unlikes", obj.optInt("unlike", 0)),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Throwable) {}
        return list
    }
}

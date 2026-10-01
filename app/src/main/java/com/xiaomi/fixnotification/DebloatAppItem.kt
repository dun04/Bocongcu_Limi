package com.xiaomi.fixnotification

import android.graphics.drawable.Drawable

enum class AppType(val displayName: String, val badgeBgColor: String, val badgeTextColor: String) {
    BLOATWARE("Bloatware Rác", "#33FF6B00", "#FF7A00"),
    SYSTEM("Hệ Thống", "#33FF3B30", "#FF4D4D"),
    GOOGLE("Google Service", "#2638BDF8", "#38BDF8"),
    USER("Người Dùng", "#2634D399", "#34D399")
}

data class DebloatAppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    val appType: AppType,
    val description: String = "",
    var isSelected: Boolean = false,
    var isUninstalled: Boolean = false,
    var isDisabled: Boolean = false
) {
    companion object {
        // Danh sách các bloatware rác Xiaomi phổ biến có thể gỡ an toàn
        val KNOWN_BLOATWARE_MAP = mapOf(
            "com.miui.msa.global" to "MIUI System Ads (Quảng cáo hệ thống)",
            "com.miui.msa" to "MIUI System Ads (Quảng cáo)",
            "com.miui.analytics" to "MIUI Analytics (Thu thập & quảng cáo)",
            "com.miui.daemon" to "MIUI Daemon (Thu thập dữ liệu ngầm)",
            "com.xiaomi.joyose" to "Joyose (Bóp hiệu năng & theo dõi)",
            "com.miui.hybrid" to "Quick Apps (Ứng dụng rác chạy ngầm)",
            "com.miui.hybrid.accessory" to "Quick Apps Accessory",
            "com.mi.globalbrowser" to "Mi Browser (Trình duyệt mặc định)",
            "com.android.browser" to "Mi Browser",
            "com.miui.videoplayer" to "Mi Video (Ứng dụng video nhiều quảng cáo)",
            "com.miui.player" to "Mi Music (Nhạc Xiaomi)",
            "com.miui.yellowpage" to "Trang vàng Xiaomi",
            "com.mipay.wallet" to "Mi Pay (Ví thanh toán Xiaomi)",
            "com.xiaomi.payment" to "Xiaomi Payment Service",
            "com.miui.bugreport" to "Báo cáo lỗi Xiaomi ngầm",
            "com.miui.cleanmaster" to "Clean Master (Dọn rác quảng cáo)",
            "com.facebook.system" to "Facebook System Service",
            "com.facebook.appmanager" to "Facebook App Manager",
            "com.facebook.services" to "Facebook Services",
            "com.tencent.soter.soterserver" to "Tencent Soter Service",
            "com.google.android.apps.tachyon" to "Google Duo / Meet",
            "com.google.android.videos" to "Google TV / Phim",
            "com.google.android.music" to "Google Music",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.miui.carlink" to "CarWith (Kết nối ô tô Xiaomi)",
            "com.xiaomi.carlink" to "CarWith / Xiaomi CarLink",
            "com.miui.carwith" to "CarWith",
            "com.xiaomi.mis.service" to "小米汽车互联服务 (Xiaomi Car Interconnection Service)",
            "com.xiaomi.mis" to "小米汽车互联服务 (Dịch vụ kết nối xe Xiaomi)",
            "com.xiaomi.car" to "Xiaomi Auto / Car Service",
            "com.xiaomi.drive" to "Xiaomi Smart Drive"
        )
    }
}

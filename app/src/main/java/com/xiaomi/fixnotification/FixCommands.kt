package com.xiaomi.fixnotification

object FixCommands {

    // Regex xác thực tên gói hợp lệ của Android (chỉ chứa chữ cái, số, dấu chấm và gạch dưới) để chống Shell Injection
    private val PACKAGE_REGEX = Regex("^[a-zA-Z0-9._]+$")

    // Danh sách app cơ bản ban đầu (hệ thống, chat, MXH, giải trí & ngân hàng phổ biến)
    val BASE_MILLET_PACKAGES = listOf(
        "app.revanced.android.gms",
        "com.android.contacts",
        "com.google.android.gm",
        "com.google.android.ims",
        "com.mi.health",
        "app.revanced.android.youtube",
        "com.ss.android.ugc.trill",
        "com.facebook.orca",
        "com.facebook.katana",
        "com.google.android.gms",
        "com.zing.zalo",
        "org.telegram.messenger",
        "com.instagram.android",
        "com.instagram.barcelona",
        "com.vnpay.bidv",
        "com.mbmobile",
        "com.VCB",
        "com.techcombank.mobile",
        "vn.com.vietinbank.ipay"
    )

    val DEFAULT_MILLET_PACKAGES = BASE_MILLET_PACKAGES.joinToString(", ")

    const val CMD_1 = "settings put global fcm_reschedule_needed 1; settings put global gtalk_heartbeat_interval_ms 120000; settings put secure gtalk_heartbeat_interval_ms 120000; settings put system gtalk_heartbeat_interval_ms 120000; settings put global gtalk_nosync_heartbeat_ping_interval_ms 120000; (device_config put gms_network gtalk_heartbeat_interval_ms 120000 2>/dev/null || true); (device_config put gms_network gtalk_nosync_heartbeat_ping_interval_ms 120000 2>/dev/null || true); (device_config put gms_network gtalk_wifi_heartbeat_ping_interval_ms 120000 2>/dev/null || true); (device_config put gms_network gtalk_cell_heartbeat_ping_interval_ms 120000 2>/dev/null || true); am broadcast -a com.google.android.intent.action.GTALK_HEARTBEAT; true"
    const val CMD_2 = "settings put global doze_always_on 0; settings put global app_standby_enabled 0; settings put global app_idle_constants min_light_idle_mode_duration_ms=86400000,min_deep_idle_mode_duration_ms=86400000; settings put global deviceidle_constants light_after_inactive_to=86400000,light_pre_idle_to=86400000,light_idle_to=86400000,light_idle_factor=2.0,light_max_idle_to=86400000,light_idle_maintenance_min_budget=86400000,light_idle_maintenance_max_budget=86400000,min_light_idle_mode_duration_ms=86400000,min_deep_idle_mode_duration_ms=86400000,inactive_to=86400000,sensing_to=86400000,locating_to=86400000,location_accuracy=20.0,motion_inactive_to=86400000,idle_after_inactive_to=86400000,idle_pending_to=86400000,max_idle_pending_to=86400000,idle_pending_factor=2.0,idle_to=86400000,max_idle_to=86400000,idle_factor=2.0,min_time_to_alarm=3600000,max_temp_app_allowlist_duration_ms=300000,mms_temp_app_allowlist_duration_ms=60000,sms_temp_app_allowlist_duration_ms=60000,notification_allowlist_duration_ms=30000; (device_config put activity_manager_deviceidle min_light_idle_mode_duration_ms 86400000 2>/dev/null || true); (device_config put activity_manager_deviceidle min_deep_idle_mode_duration_ms 86400000 2>/dev/null || true); (device_config put activity_manager_deviceidle light_idle_to 86400000 2>/dev/null || true); (device_config put activity_manager_deviceidle light_after_inactive_to 86400000 2>/dev/null || true); true"
    const val CMD_3 = "settings put global wifi_power_save 0; settings put global wifi_sleep_policy 2; settings put global wifi_stay_on 2; settings put secure wifi_stay_on 2; settings put global mobile_data_always_on 1; settings put system screen_off_mobile_data_close_time 0; settings put system screen_off_wifi_close_time 0; settings put system sleep_mode 0; settings put global greezer_enable 0; settings put global sys_freezer 0; (setprop persist.sys.greezer_enable false 2>/dev/null || true); (setprop persist.vendor.greezer_enable false 2>/dev/null || true); (setprop persist.sys.spc.cpulimit.enabled false 2>/dev/null || true); (setprop persist.sys.hypercore.freezer false 2>/dev/null || true); (setprop persist.sys.miui_freezer.enable false 2>/dev/null || true); true"

    // ================= DÀNH RIÊNG CHO CÁC DÒNG FLAGSHIP XIAOMI & HYPEROS 3 / 2 (ANDROID 16 / 15) =================
    // NC 1: Tắt hoàn toàn tính năng đóng băng theo lưu lượng mạng (Millet Traffic Freeze) của HyperOS 3 / 2
    const val CMD_ADV_1_MILLET_TRAFFIC = "settings put system network_traffic_millet_enable 0; true"

    // NC 2: Vô hiệu hóa Android 16/15/14 Phantom Process Killer (Ngăn diệt tiến trình con trên máy Flagship RAM lớn)
    const val CMD_ADV_2_PHANTOM_KILLER = "device_config set_sync_disabled_for_tests persistent; device_config put activity_manager max_phantom_processes 2147483647; true"

    // NC 3: Đặc trị Greezer 3.0, Aurogon & Deep Idle HyperOS 3 / Android 16 - Bảo vệ toàn diện GMS & GSF (Chống ngắt socket FCM 5228 sau 2-3 tiếng)
    const val CMD_ADV_3_GMS_WHITELIST = "dumpsys greezer IM GMS disable; dumpsys greezer LM add com.google.android.gms; dumpsys greezer LM add com.google.android.gsf; dumpsys greezer LM add com.google.android.gms.persistent; dumpsys deviceidle whitelist +com.google.android.gms; dumpsys deviceidle whitelist +com.google.android.gsf; cmd deviceidle whitelist +com.google.android.gms; cmd deviceidle whitelist +com.google.android.gsf; dumpsys deviceidle except-idle-whitelist +com.google.android.gms; dumpsys deviceidle except-idle-whitelist +com.google.android.gsf; cmd deviceidle except-idle-whitelist +com.google.android.gms; cmd deviceidle except-idle-whitelist +com.google.android.gsf; (cmd netpolicy add restrict-background-whitelist com.google.android.gms 2>/dev/null || true); (cmd netpolicy add restrict-background-whitelist com.google.android.gsf 2>/dev/null || true); cmd appops set com.google.android.gms WAKE_LOCK allow; cmd appops set com.google.android.gms RUN_IN_BACKGROUND allow; cmd appops set com.google.android.gms RUN_ANY_IN_BACKGROUND allow; cmd appops set com.google.android.gsf RUN_IN_BACKGROUND allow; cmd appops set com.google.android.gsf RUN_ANY_IN_BACKGROUND allow; cmd appops set com.google.android.gms DATA_SAVER_EXEMPT allow; cmd appops set com.google.android.gsf DATA_SAVER_EXEMPT allow; cmd appops set com.google.android.gms 10008 allow; cmd appops set com.google.android.gsf 10008 allow; cmd appops set com.google.android.gms 10021 allow; cmd appops set com.google.android.gsf 10021 allow; cmd appops set com.google.android.gms BOOT_COMPLETED allow; cmd appops set com.google.android.gsf BOOT_COMPLETED allow; cmd appops set com.google.android.gms SCHEDULE_EXACT_ALARM allow; cmd appops set com.google.android.gms USE_EXACT_ALARM allow; cmd appops set com.google.android.gsf SCHEDULE_EXACT_ALARM allow; cmd appops set com.google.android.gsf USE_EXACT_ALARM allow; (pm grant com.google.android.gms android.permission.SCHEDULE_EXACT_ALARM 2>/dev/null || true); (pm grant com.google.android.gsf android.permission.SCHEDULE_EXACT_ALARM 2>/dev/null || true); true"

    // NC 4: Cưỡng bức đồng bộ tài khoản Google & Push Tickles (Khắc phục triệt để lỗi Gmail và dịch vụ không tự nổ chuông - dùng content insert chuẩn Android)
    const val CMD_ADV_4_FORCE_SYNC = "(content insert --uri content://sync/settings --bind name:s:listen_for_tickles --bind value:s:true 2>/dev/null || cmd sync --tickles true 2>/dev/null || true); (content insert --uri content://sync/settings --bind name:s:sync_automatically --bind value:s:true 2>/dev/null || cmd sync --sync-automatically true 2>/dev/null || true); am broadcast -a com.google.android.c2dm.intent.REGISTER; am broadcast -a com.google.android.intent.action.GTALK_HEARTBEAT; true"

    // NC 5: Làm mới tức thì Socket kết nối FCM tới mtalk.google.com (Cấp tốc phục hồi sau khi sạc hoặc rớt sóng)
    const val CMD_ADV_5_REFRESH_FCM = "settings put global gtalk_heartbeat_interval_ms 120000; settings put secure gtalk_heartbeat_interval_ms 120000; (device_config put gms_network gtalk_heartbeat_interval_ms 120000 2>/dev/null || true); am broadcast -a com.google.android.intent.action.GTALK_HEARTBEAT; am broadcast -a com.google.android.c2dm.intent.REGISTER; true"

    // NC 6: Tắt AI Pin thích ứng & Hạn chế app tự động (Ngăn hệ thống tự học thói quen và bóp các app ít mở)
    const val CMD_ADV_6_ADAPTIVE_BATTERY = "settings put global adaptive_battery_management_enabled 0; settings put global app_restriction_enabled 0; settings put global app_auto_restriction_enabled 0; true"

    // NC 7: Vô hiệu hóa tính năng tối ưu pin ngầm của PowerKeeper, SPC & HyperCore Freezer (Ngăn ngắt ngầm khi màn hình tắt trên 2 tiếng trên HyperOS 3 / Android 16)
    const val CMD_ADV_7_POWERKEEPER = "settings put global miui_powerkeeper_smart_power_enabled 0; settings put global miui_freezer_enable 0; settings put global miui_spc_power_mode 0; settings put system miui_spc_power_mode 0; settings put system power_supersave_mode 0; settings put system power_save_mode 0; settings put system smart_network_metered_disable 0; settings put system smart_power_network_idle_mode 0; (setprop persist.sys.spc.enabled false 2>/dev/null || true); (setprop persist.sys.spc.v2.enabled false 2>/dev/null || true); (setprop persist.sys.spc.cpulimit.enabled false 2>/dev/null || true); (setprop persist.sys.miui.freezer_enable false 2>/dev/null || true); (setprop persist.sys.miui_freezer.enable false 2>/dev/null || true); (setprop persist.sys.hypercore.freezer false 2>/dev/null || true); (setprop persist.sys.miui.powerkeeper.disable true 2>/dev/null || true); (setprop persist.vendor.powerkeeper.smart_power false 2>/dev/null || true); true"

    // ================= LỆNH KHÔI PHỤC MẶC ĐỊNH TOÀN DIỆN (FULL RESET TẤT CẢ LỆNH TRONG APP) =================
    // 1. Reset FCM & Heartbeat Interval
    const val CMD_RESET_1 = "settings put global fcm_reschedule_needed 0; settings delete global gtalk_heartbeat_interval_ms 2>/dev/null; settings delete secure gtalk_heartbeat_interval_ms 2>/dev/null; settings delete system gtalk_heartbeat_interval_ms 2>/dev/null; settings delete global gtalk_nosync_heartbeat_ping_interval_ms 2>/dev/null; true"

    // 2. Reset Doze & Standby (Bật lại chế độ ngủ sâu & xóa hằng số deviceidle tùy biến)
    const val CMD_RESET_2 = "settings put global doze_always_on 1; settings put global app_standby_enabled 1; settings delete global deviceidle_constants 2>/dev/null; settings delete global app_idle_constants 2>/dev/null; true"

    // 3. Reset Wi-Fi Power Save & Greezer (Bật lại tiết kiệm pin Wi-Fi và Greezer hệ thống, xóa timer tắt mạng màn hình)
    const val CMD_RESET_3 = "settings put global wifi_power_save 1; settings put global wifi_sleep_policy 2; settings put global greezer_enable 1; settings put global sys_freezer 1; settings delete system screen_off_mobile_data_close_time 2>/dev/null; settings delete system screen_off_wifi_close_time 2>/dev/null; settings delete system sleep_mode 2>/dev/null; (setprop persist.sys.greezer_enable true 2>/dev/null || true); (setprop persist.vendor.greezer_enable true 2>/dev/null || true); (setprop persist.sys.spc.cpulimit.enabled true 2>/dev/null || true); (setprop persist.sys.hypercore.freezer true 2>/dev/null || true); (setprop persist.sys.miui_freezer.enable true 2>/dev/null || true); true"

    // 4. Reset Danh sách trắng Millet (Xóa danh sách app chạy ngầm không giới hạn)
    const val CMD_RESET_4 = "settings delete system MILLET_NO_RESTRICT_APP; settings put system MILLET_NO_RESTRICT_APP \"\"; true"

    // 5. Reset Millet Traffic Freeze (Bật lại cờ đóng băng mạng của HyperOS)
    const val CMD_RESET_ADV_1 = "settings put system network_traffic_millet_enable 1; true"

    // 6. Reset Phantom Process Killer (Khôi phục giới hạn 32 tiến trình con)
    const val CMD_RESET_ADV_2 = "device_config set_sync_disabled_for_tests none 2>/dev/null; device_config put activity_manager max_phantom_processes 32 2>/dev/null; true"

    // 7. Reset Greezer & GMS Whitelist (Khôi phục quản lý GMS mặc định)
    const val CMD_RESET_ADV_3 = "dumpsys greezer IM GMS enable 2>/dev/null; dumpsys greezer LM remove com.google.android.gms 2>/dev/null; dumpsys greezer LM remove com.google.android.gsf 2>/dev/null; dumpsys greezer LM remove com.google.android.gms.persistent 2>/dev/null; dumpsys deviceidle whitelist -com.google.android.gms 2>/dev/null; dumpsys deviceidle whitelist -com.google.android.gsf 2>/dev/null; cmd deviceidle whitelist -com.google.android.gms 2>/dev/null; cmd deviceidle whitelist -com.google.android.gsf 2>/dev/null; (cmd netpolicy remove restrict-background-whitelist com.google.android.gms 2>/dev/null || true); (cmd netpolicy remove restrict-background-whitelist com.google.android.gsf 2>/dev/null || true); cmd appops set com.google.android.gms default 2>/dev/null; cmd appops set com.google.android.gsf default 2>/dev/null; true"

    // 8. Reset Google Push Tickles & Sync
    const val CMD_RESET_ADV_4 = "(content insert --uri content://sync/settings --bind name:s:listen_for_tickles --bind value:s:true 2>/dev/null || true); (content insert --uri content://sync/settings --bind name:s:sync_automatically --bind value:s:true 2>/dev/null || true); true"

    // 9. Reset AI Pin Thích Ứng (Bật lại quản lý pin thích ứng và hạn chế app tự động)
    const val CMD_RESET_ADV_6 = "settings put global adaptive_battery_management_enabled 1; settings put global app_restriction_enabled 1; settings put global app_auto_restriction_enabled 1; true"

    // 10. Reset PowerKeeper & HyperCore Freezer (Bật lại quản lý pin PowerKeeper & SPC)
    const val CMD_RESET_ADV_7 = "settings put global miui_powerkeeper_smart_power_enabled 1; settings put global miui_freezer_enable 1; settings delete global miui_spc_power_mode 2>/dev/null; settings delete system miui_spc_power_mode 2>/dev/null; settings delete system smart_network_metered_disable 2>/dev/null; settings delete system smart_power_network_idle_mode 2>/dev/null; (setprop persist.sys.spc.enabled true 2>/dev/null || true); (setprop persist.sys.spc.v2.enabled true 2>/dev/null || true); (setprop persist.sys.miui.freezer_enable true 2>/dev/null || true); (setprop persist.sys.miui.powerkeeper.disable false 2>/dev/null || true); (setprop persist.vendor.powerkeeper.smart_power true 2>/dev/null || true); true"

    // 11. Reset Định vị GPS: NTP Server & Timeout (Xóa NTP Việt Nam về máy chủ gốc hệ thống)
    const val CMD_RESET_GPS_1 = "settings delete global ntp_server 2>/dev/null; settings delete global ntp_timeout 2>/dev/null; true"

    // 12. Reset Định vị GPS: GMS Fine Location AppOps (Khôi phục quyền vị trí GMS mặc định)
    const val CMD_RESET_GPS_2 = "cmd appops set com.google.android.gms FINE_LOCATION default 2>/dev/null; cmd appops set com.google.android.gms COARSE_LOCATION default 2>/dev/null; cmd appops set com.google.android.gms MONITOR_LOCATION default 2>/dev/null; cmd appops set com.google.android.gms MONITOR_HIGH_POWER_LOCATION default 2>/dev/null; true"

    // 13. Reset Định vị GPS: Fused Location Doze Whitelist (Gỡ Fused Location khỏi danh sách trắng)
    const val CMD_RESET_GPS_3 = "dumpsys deviceidle whitelist -com.android.location.fused 2>/dev/null; dumpsys deviceidle whitelist -com.google.android.location.fused 2>/dev/null; cmd appops set com.android.location.fused default 2>/dev/null; cmd appops set com.google.android.location.fused default 2>/dev/null; true"

    // 14. Reset Định vị GPS: Quét Wi-Fi & BLE Ngầm (Tắt tính năng quét liên tục ngốn pin)
    const val CMD_RESET_GPS_4 = "settings put global wifi_scan_always_enabled 0 2>/dev/null; settings put global ble_scan_always_enabled 0 2>/dev/null; true"

    // 15. Reset DeviceConfig Activity Manager & GMS Network Constants (Xóa sạch cờ tùy biến)
    const val CMD_RESET_DEVICE_CONFIG = "(device_config delete activity_manager_deviceidle min_light_idle_mode_duration_ms 2>/dev/null || true); (device_config delete activity_manager_deviceidle min_deep_idle_mode_duration_ms 2>/dev/null || true); (device_config delete activity_manager_deviceidle light_idle_to 2>/dev/null || true); (device_config delete activity_manager_deviceidle light_after_inactive_to 2>/dev/null || true); (device_config delete gms_network gtalk_heartbeat_interval_ms 2>/dev/null || true); (device_config delete gms_network gtalk_nosync_heartbeat_ping_interval_ms 2>/dev/null || true); (device_config delete gms_network gtalk_wifi_heartbeat_ping_interval_ms 2>/dev/null || true); (device_config delete gms_network gtalk_cell_heartbeat_ping_interval_ms 2>/dev/null || true); true"

    data class ResetCommandItem(
        val index: Int,
        val name: String,
        val command: String
    )

    val ALL_RESET_COMMANDS = listOf(
        ResetCommandItem(1, "1. Hoàn nguyên FCM & Heartbeat", CMD_RESET_1),
        ResetCommandItem(2, "2. Bật lại Doze, Standby & Xóa Deviceidle", CMD_RESET_2),
        ResetCommandItem(3, "3. Bật lại Wi-Fi Power Save & Greezer", CMD_RESET_3),
        ResetCommandItem(4, "4. Xóa Danh sách trắng Millet Whitelist", CMD_RESET_4),
        ResetCommandItem(5, "5. Bật lại Millet Traffic Freeze", CMD_RESET_ADV_1),
        ResetCommandItem(6, "6. Đặt lại Phantom Killer (32 Subprocesses)", CMD_RESET_ADV_2),
        ResetCommandItem(7, "7. Hoàn nguyên Aurogon & GMS/GSF Whitelist", CMD_RESET_ADV_3),
        ResetCommandItem(8, "8. Đặt lại Google Sync & Push Tickles", CMD_RESET_ADV_4),
        ResetCommandItem(9, "9. Bật lại AI Quản Lý Pin Thích Ứng", CMD_RESET_ADV_6),
        ResetCommandItem(10, "10. Bật lại PowerKeeper & HyperCore Freezer", CMD_RESET_ADV_7),
        ResetCommandItem(11, "11. Hoàn nguyên NTP Server GPS Việt Nam", CMD_RESET_GPS_1),
        ResetCommandItem(12, "12. Khôi phục quyền GPS Google Services", CMD_RESET_GPS_2),
        ResetCommandItem(13, "13. Gỡ Fused Location khỏi Doze Whitelist", CMD_RESET_GPS_3),
        ResetCommandItem(14, "14. Tắt Quét Wi-Fi & BLE Định Vị Ngầm", CMD_RESET_GPS_4),
        ResetCommandItem(15, "15. Xóa Toàn Diện DeviceConfig Constants", CMD_RESET_DEVICE_CONFIG)
    )

    val ALL_RESET_COMMANDS_TEXT = ALL_RESET_COMMANDS.joinToString("\n") { it.command }

    data class GpsFixCommand(
        val index: Int,
        val title: String,
        val tag: String,
        val description: String,
        val command: String
    )

    val GPS_COMMANDS = listOf(
        GpsFixCommand(
            index = 1,
            title = "Đồng bộ NTP Server Việt Nam",
            tag = "NTP vn.pool.ntp.org",
            description = "Chuyển máy chủ thời gian về vn.pool.ntp.org và đặt thời gian chờ 5s. Giúp chip GPS đồng bộ nano-giây tức thì với trạm mặt đất Việt Nam.",
            command = "settings put global ntp_server vn.pool.ntp.org; settings put global ntp_timeout 5000; true"
        ),
        GpsFixCommand(
            index = 2,
            title = "Bật A-GPS & Chế Độ Vị Trí Cao Cấp",
            tag = "A-GPS & High Accuracy",
            description = "Kích hoạt A-GPS (Assisted GPS), đặt Location Mode 3 (High Accuracy) và cho phép cả GPS vệ tinh lẫn trạm phát mạng di động.",
            command = "settings put global assisted_gps_enabled 1; settings put secure location_mode 3; settings put secure location_providers_allowed +gps,+network; true"
        ),
        GpsFixCommand(
            index = 3,
            title = "Tối Ưu Quyền Cho Google Play Services",
            tag = "GMS Fine Location",
            description = "Cấp quyền định vị chính xác cao (FINE_LOCATION) và định vị năng lượng cao (MONITOR_HIGH_POWER_LOCATION) liên tục cho Google Play Services.",
            command = "cmd appops set com.google.android.gms FINE_LOCATION allow; cmd appops set com.google.android.gms COARSE_LOCATION allow; cmd appops set com.google.android.gms MONITOR_LOCATION allow; cmd appops set com.google.android.gms MONITOR_HIGH_POWER_LOCATION allow; true"
        ),
        GpsFixCommand(
            index = 4,
            title = "Chống Đóng Băng Dịch Vụ Fused Location",
            tag = "Fused Location Provider",
            description = "Đưa bộ gom vị trí ngầm (com.android.location.fused) vào danh sách trắng Doze và cấp quyền chạy nền không bị HyperOS bóp.",
            command = "dumpsys deviceidle whitelist +com.android.location.fused; dumpsys deviceidle whitelist +com.google.android.location.fused; cmd appops set com.android.location.fused RUN_IN_BACKGROUND allow; cmd appops set com.android.location.fused RUN_ANY_IN_BACKGROUND allow; cmd appops set com.google.android.location.fused RUN_IN_BACKGROUND allow; true"
        ),
        GpsFixCommand(
            index = 5,
            title = "Bật Quét Wi-Fi & Bluetooth Định Vị Trong Nhà",
            tag = "Wi-Fi & BLE Scanning",
            description = "Kích hoạt wifi_scan_always_enabled và ble_scan_always_enabled giúp định vị chuẩn xác từng mét khi đi vào hầm xe, tòa nhà cao tầng hoặc nơi khuất vệ tinh.",
            command = "settings put global wifi_scan_always_enabled 1; settings put global ble_scan_always_enabled 1; true"
        ),
        GpsFixCommand(
            index = 6,
            title = "Làm Mới Chip GPS & Nạp Lại Quỹ Đạo Vệ Tinh",
            tag = "Refresh GPS / XTRA",
            description = "Phát broadcast làm mới chip GPS, xóa cache vệ tinh cũ và nạp lại toàn bộ dữ liệu vệ tinh GPS, GLONASS, BeiDou, Galileo theo thời gian thực.",
            command = "am broadcast -a android.location.GPS_ENABLED_CHANGE --ez enabled true; am broadcast -a android.location.PROVIDERS_CHANGED; am broadcast -a android.intent.action.TIME_SET; true"
        )
    )

    val ALL_GPS_COMMANDS_TEXT = GPS_COMMANDS.joinToString("\n") { it.command }

    data class AdvancedCommand(
        val index: Int,
        val title: String,
        val tag: String,
        val description: String,
        val command: String
    )

    val ADVANCED_COMMANDS = listOf(
        AdvancedCommand(
            index = 1,
            title = "Tắt Đóng Băng Mạng Millet",
            tag = "Millet Network",
            description = "Vô hiệu hóa network_traffic_millet_enable. Ngăn HyperOS 3 / 2 tự động ngắt kết nối mạng ngầm của socket ứng dụng khi tắt màn hình.",
            command = CMD_ADV_1_MILLET_TRAFFIC
        ),
        AdvancedCommand(
            index = 2,
            title = "Tắt Phantom Process Killer",
            tag = "HyperOS 3 / 2",
            description = "Nâng giới hạn max_phantom_processes lên tối đa. Ngăn hệ điều hành tự ý diệt các tiến trình con (subprocesses) của Zalo, Telegram, App ngân hàng trên máy Flagship RAM lớn.",
            command = CMD_ADV_2_PHANTOM_KILLER
        ),
        AdvancedCommand(
            index = 3,
            title = "Khóa Greezer & Bảo Vệ GMS / GSF",
            tag = "Greezer & Aurogon",
            description = "Tắt cờ hạn chế GMS (dumpsys greezer IM GMS disable), đưa GMS & GSF vào Aurogon Allowlist (dumpsys greezer LM add) và Doze Whitelist. Cấp quyền WAKE_LOCK, chạy nền liên tục và Tự khởi chạy. Giữ cổng kết nối FCM 5228 luôn thông suốt.",
            command = CMD_ADV_3_GMS_WHITELIST
        ),
        AdvancedCommand(
            index = 4,
            title = "Cưỡng Bức Đồng Bộ Google & Gmail",
            tag = "Google Push Sync",
            description = "Bật listen_for_tickles và sync_automatically cho cơ chế đồng bộ tài khoản Google, kết hợp phát broadcast c2dm. Giúp Gmail và app Google nhận thư lập tức không cần mở app.",
            command = CMD_ADV_4_FORCE_SYNC
        ),
        AdvancedCommand(
            index = 5,
            title = "Làm mới lại FCM Server",
            tag = "Heartbeat & Register",
            description = "Gửi tín hiệu GTALK_HEARTBEAT và REGISTER để thiết lập lại phiên kết nối ngay lập tức với máy chủ Google sau khi sạc pin trên 20% hoặc chuyển vùng Wi-Fi/5G.",
            command = CMD_ADV_5_REFRESH_FCM
        ),
        AdvancedCommand(
            index = 6,
            title = "Tắt AI Quản Lý Pin Thích Ứng",
            tag = "Adaptive Battery AI",
            description = "Vô hiệu hóa AI học thói quen sử dụng pin, tắt chế độ ngủ thông minh và ngăn hệ điều hành tự động bóp hoặc giới hạn ngầm các ứng dụng ít mở.",
            command = CMD_ADV_6_ADAPTIVE_BATTERY
        ),
        AdvancedCommand(
            index = 7,
            title = "Vô Hiệu Hóa PowerKeeper & Freezer",
            tag = "PowerKeeper & HyperCore",
            description = "Tắt tính năng thông minh tự bóp ngầm của PowerKeeper, vô hiệu hóa miui_freezer_enable và chế độ siêu tiết kiệm pin. Ngăn HyperOS 3 tự động ngắt kết nối mạng ngầm và xóa danh sách trắng.",
            command = CMD_ADV_7_POWERKEEPER
        )
    )

    const val CMD_OPEN_XIAOMI_AUTOSTART = "am start -n com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"
    const val CMD_OPEN_XIAOMI_BATTERY = "am start -n com.miui.powerkeeper/.ui.HiddenAppsContainerManagementActivity || am start -n com.miui.powerkeeper/.ui.HiddenAppsConfigActivity"

    fun isValidPackageName(pkg: String): Boolean {
        return pkg.isNotEmpty() && pkg.length <= 128 && PACKAGE_REGEX.matches(pkg)
    }

    fun getCmd4(packagesString: String): String {
        val userList = parsePackages(packagesString)
        val combinedList = (userList + listOf("com.google.android.gms", "com.google.android.gsf")).distinct()

        val sb = StringBuilder()
        sb.append("dumpsys greezer IM GMS disable")
        sb.append("; dumpsys greezer LM add com.google.android.gms")
        sb.append("; dumpsys greezer LM add com.google.android.gsf")
        sb.append("; dumpsys greezer LM add com.google.android.gms.persistent")
        for (pkg in combinedList) {
            sb.append("; dumpsys deviceidle whitelist +$pkg")
            sb.append("; cmd deviceidle whitelist +$pkg")
            sb.append("; cmd deviceidle except-idle-whitelist +$pkg")
            sb.append("; (cmd netpolicy add restrict-background-whitelist $pkg 2>/dev/null || true)")
            sb.append("; cmd appops set $pkg RUN_IN_BACKGROUND allow")
            sb.append("; cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
            sb.append("; cmd appops set $pkg WAKE_LOCK allow")
            sb.append("; cmd appops set $pkg 10008 allow")
            sb.append("; cmd appops set $pkg BOOT_COMPLETED allow")
        }
        val cleanPackages = combinedList.joinToString(",")
        sb.append("; settings put system MILLET_NO_RESTRICT_APP \"$cleanPackages\"")
        sb.append("; true")
        return sb.toString()
    }

    /**
     * Tạo danh sách các lệnh cấp quyền toàn diện cho Xiaomi / HyperOS / MIUI:
     * - Quyền Thông báo (POST_NOTIFICATIONS)
     * - Hiển thị trên Màn hình khóa (Show on Lock screen - Op 10020)
     * - Cửa sổ Pop-up (Display pop-up windows - SYSTEM_ALERT_WINDOW & Op 10022)
     * - Mở cửa sổ mới khi chạy nền (Background start activity - Op 10021)
     * - Phím tắt màn hình chính (Home screen shortcuts - Op 10017)
     * - Tự khởi chạy (Auto-start MIUI - Op 10008, BOOT_COMPLETED)
     * - Không hạn chế pin (Dumpsys deviceidle whitelist + RUN_IN_BACKGROUND allow)
     * - Aurogon Allowlist (dumpsys greezer LM add - Chống đóng băng socket HyperOS 3/2)
     */
    fun getFullAppPermCommands(
        pkg: String,
        enableNotification: Boolean = true,
        enableLockscreen: Boolean = true,
        enablePopup: Boolean = true,
        enableBackgroundWindow: Boolean = true,
        enableShortcuts: Boolean = true,
        enableAutostart: Boolean = true,
        enableBatteryWhitelist: Boolean = true
    ): List<String> {
        val cleanPkg = pkg.trim()
        if (!isValidPackageName(cleanPkg)) return emptyList()

        val list = mutableListOf<String>()

        if (enableNotification) {
            list.add("pm grant $cleanPkg android.permission.POST_NOTIFICATIONS 2>/dev/null")
            list.add("cmd appops set $cleanPkg POST_NOTIFICATION allow")
        }

        // Quyền hiển thị trên màn hình khóa (Show on Lock screen - Op 10020)
        if (enableLockscreen) {
            list.add("cmd appops set $cleanPkg 10020 allow")
        }

        // Quyền hiển thị cửa sổ pop-up (Display pop-up windows - SYSTEM_ALERT_WINDOW & Op 10022)
        if (enablePopup) {
            list.add("cmd appops set $cleanPkg SYSTEM_ALERT_WINDOW allow")
            list.add("cmd appops set $cleanPkg 10022 allow 2>/dev/null")
        }

        // Quyền mở cửa sổ khi chạy nền (Open new windows while running in background - Op 10021)
        if (enableBackgroundWindow) {
            list.add("cmd appops set $cleanPkg 10021 allow")
        }

        // Phím tắt màn hình chính (Home screen shortcuts - Op 10017)
        if (enableShortcuts) {
            list.add("cmd appops set $cleanPkg 10017 allow")
        }

        // Bật tự khởi chạy (Auto-start MIUI - Op 10008, BOOT_COMPLETED)
        if (enableAutostart) {
            list.add("cmd appops set $cleanPkg 10008 allow")
            list.add("cmd appops set $cleanPkg BOOT_COMPLETED allow")
            list.add("cmd appops set $cleanPkg AUTO_START allow 2>/dev/null")
        }

        // 7. Quyền đánh thức CPU & Khởi chạy Foreground Service
        list.add("cmd appops set $cleanPkg WAKE_LOCK allow")
        list.add("cmd appops set $cleanPkg START_FOREGROUND allow 2>/dev/null")

        // 8. Không hạn chế pin (Doze whitelist vĩnh viễn + Except-idle whitelist + Netpolicy vĩnh viễn + Standby Bucket ACTIVE + Run in background)
        if (enableBatteryWhitelist) {
            list.add("cmd deviceidle whitelist +$cleanPkg")
            list.add("cmd deviceidle except-idle-whitelist +$cleanPkg")
            list.add("(for u in \$(pm list packages -U $cleanPkg 2>/dev/null | grep -o 'uid:[0-9]*' | cut -d: -f2); do cmd netpolicy add restrict-background-whitelist \$u 2>/dev/null; done || true)")
            list.add("am set-standby-bucket $cleanPkg active")
            list.add("cmd appops set $cleanPkg RUN_IN_BACKGROUND allow")
            list.add("cmd appops set $cleanPkg RUN_ANY_IN_BACKGROUND allow")
            list.add("cmd appops set $cleanPkg DATA_SAVER_EXEMPT allow")
        }

        return list
    }

    fun parsePackages(packagesString: String): List<String> {
        return packagesString
            .split(",")
            .map { it.trim() }
            .filter { isValidPackageName(it) }
    }
}


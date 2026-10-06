# LIMI Tool - Bộ Công Cụ Tối Ưu & Fix Thông Báo Toàn Diện Cho Xiaomi / HyperOS

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="100" height="100" alt="LIMI Icon" />
</p>

<p align="center">
  <b>Phiên bản:</b> v1.3.1.7.ntd | <b>Tác giả & Phát triển:</b> DUNGNGUYEN
</p>

<p align="center">
  <i>Giải pháp toàn diện tối ưu hóa hiệu năng, đặc trị trễ thông báo HyperOS / MIUI, đo lường sức khỏe pin Coulomb Counting và định vị GPS chuẩn xác cho các thiết bị Xiaomi, Redmi, POCO.</i>
</p>

---

> [!NOTE]
> **THÔNG BÁO VỀ KHO LƯU TRỮ (SOURCE CODE NOTICE):**  
> Đây là kho lưu trữ tài liệu giới thiệu và hướng dẫn sử dụng công khai của ứng dụng **LIMI Tool**. Để đảm bảo bản quyền và tính toàn vẹn của phần mềm, kho lưu trữ này **không chứa đầy đủ toàn bộ mã nguồn đóng (Proprietary Source Code)** của ứng dụng.

---

## 🌟 Giới Thiệu Tổng Quan

**LIMI Tool** là bộ công cụ tối ưu hóa hệ thống chuyên sâu dành riêng cho người dùng các dòng máy Xiaomi, Redmi và POCO chạy giao diện HyperOS (1, 2, 3) hoặc MIUI (Android 13, 14, 15, 16). 

Ứng dụng giúp can thiệp sâu vào các tầng quản lý tiến trình của Android và nhân tùy biến của Xiaomi mà không cần can thiệp Root máy (thông qua môi trường cấp phép Shizuku hoặc lệnh ADB tiêu chuẩn), giải quyết dứt điểm các hạn chế cố hữu về trễ thông báo, đóng băng ứng dụng chạy ngầm, sai lệch GPS và đo đạc chính xác độ chai của pin.

---

## 🚀 Các Tính Năng & Công Dụng Chính

### 1. Đặc Trị Trễ Thông Báo Chuyên Sâu (Doze & HyperOS Freeze Fix)
* **Vô hiệu hóa Cached Apps Freezer của Android 15/16:** Ngăn chặn cơ chế đóng băng tiến trình cấp nhân kernel khi tắt màn hình trên Xiaomi 14/15/15 Pro và các thiết bị cập nhật HyperOS 2 / HyperOS 3.
* **Xóa bỏ nghẽn Doze Sleep 24h:** Gỡ triệt để hằng số `deviceidle_constants` 86.400.000ms gây kẹt mạng và trễ thông báo suốt cả ngày.
* **Duy trì Socket FCM Heartbeat 120s:** Giữ kết nối liên tục với máy chủ Google Cloud Messaging, đảm bảo tin nhắn Zalo, Messenger, Telegram, Gmail, App Ngân hàng (BIDV, MB Bank, Vietcombank, Techcombank, Vietinbank...) nổ chuông ngay tức thì kể cả khi tắt màn hình qua đêm.
* **Cấp quyền chạy ngầm tuyệt đối AppOps 10008 & AUTO_START:** Mở quyền thức ngầm và tự khởi chạy cho Google Services (GMS/GSF) và danh sách ứng dụng được chọn.
* **Kích hoạt đánh thức màn hình khóa:** Đảm bảo sáng màn hình và hiển thị nội dung thông báo đầy đủ trên màn hình khóa HyperOS.
* **Miễn trừ danh sách trắng MILLET:** Nạp trực tiếp danh sách ứng dụng quan trọng vào danh sách trắng của cơ chế quản lý tài nguyên độc quyền Millet Xiaomi.

### 2. Bộ 7 Lệnh Flagship Nâng Cao (Dành cho máy cấu hình cao)
* **Tắt Millet Traffic Freeze:** Mở cổng socket dữ liệu mạng nền không bị ngắt.
* **Vô hiệu hóa Android Phantom Process Killer:** Nâng giới hạn tiến trình con ngầm từ 32 lên mức tối đa `2.147.483.647`, ngăn chặn hệ thống tự ý tắt tiến trình dịch vụ ngầm.
* **Khóa bộ đóng băng MIUI Freezer & HyperCore:** Giữ app chạy nền mượt mà, không bị load lại.
* **Tắt AI quản lý pin thích ứng (Adaptive Battery Management):** Chống AI tự động bóp tài nguyên mạng và CPU của các app ít mở.
* **Tối ưu hóa PowerKeeper:** Giữ lại cơ chế đánh thức nhưng vô hiệu hóa hành vi tự đóng băng app.

### 3. Tối Ưu Định Vị GPS Chuẩn Xác Tại Việt Nam
* **Đồng bộ NTP Server Việt Nam:** Chuyển máy chủ thời gian về `vn.pool.ntp.org`, giúp GPS bắt sóng vệ tinh nhanh gấp 3 lần và triệt tiêu độ trễ đồng hồ nguyên tử.
* **Kích hoạt A-GPS (Assisted GPS):** Tải trước dữ liệu quỹ đạo vệ tinh định kỳ.
* **Quét Wi-Fi/BLE định vị độ chính xác cao:** Hỗ trợ điều hướng bản đồ (Google Maps, Grab, Be...) chuẩn xác từng mét ngay cả khi ở trong nhà hay đường hầm.

### 4. Đo Chai Pin & Giám Sát Cảm Biến Sạc Chuyên Nghiệp
* **Thuật toán Sensor Fusion & Coulomb Counting:** Đo lượng điện tích nạp vào (mAh) trực tiếp qua cảm biến chip nguồn Xiaomi Surge / Qualcomm PMIC kết hợp chu kỳ nạp phần cứng BMS.
* **Biểu đồ Telemetry thời gian thực:** Theo dõi trực quan Dòng nạp (mA), Điện thế (V), Công suất sạc (W) và Nhiệt độ pin (°C).
* **Tính năng độc quyền cho người thay pin độ:** 
  > Ấn giữ vào thẻ **Dung lượng (Giá trị xếp hạng)** khoảng **2 giây** để nhập dung lượng mới cho các dòng pin nén độ dung lượng cao (ví dụ: 6000mAh, 6500mAh...), giúp tính toán chính xác tuyệt đối tỷ lệ % sức khỏe của viên pin mới.

### 5. Giám Sát Nhiệt Độ, FPS & HUD Nổi Màn Hình
* HUD nổi giám sát thời gian thực nhiệt độ CPU, GPU, Pin và số khung hình trên giây (FPS).
* Cảnh báo quá nhiệt thông minh theo thời gian thực khi chơi game hoặc chạy tác vụ nặng.

### 6. Bảng Kiểm Tra Tiến Độ FIX Hệ Thống
* Đo lường chi tiết 17 tiêu chí hệ thống theo thời gian thực.
* **Chuẩn xác cả khi có và không có Shizuku:** Ứng dụng đọc trực tiếp cấu hình qua `ContentResolver` của Android, tự động phát hiện chính xác các mục đã fix và chưa fix để điều hướng người dùng xử lý nhanh chóng.

---

## 📖 Hướng Dẫn Sử Dụng

### Bước 1: Chuẩn bị quyền thực thi
Ứng dụng có thể hoạt động hoàn toàn không cần Root qua 1 trong 2 cách:
1. **Qua ứng dụng Shizuku (Khuyên dùng):**
   * Cài đặt và kích hoạt [Shizuku](https://shizuku.rikka.app/) trên điện thoại qua Wi-Fi Debugging.
   * Mở ứng dụng LIMI và cấp quyền Shizuku khi có thông báo yêu cầu.
2. **Qua máy tính (ADB PC):**
   * Nếu không sử dụng Shizuku, bạn có thể sao chép trực tiếp từng dòng lệnh trong ứng dụng và chạy thông qua cửa sổ dòng lệnh ADB trên máy tính.

### Bước 2: Chạy bộ lệnh tối ưu thông báo
1. Mở tab **Hệ thống** trong ứng dụng LIMI.
2. Kiểm tra danh sách các ứng dụng cần fix tại mục **Danh sách ứng dụng** (có thể thêm bớt app tùy nhu cầu).
3. Nhấn **"Chạy tất cả Lệnh 1 -> 4"** (hoặc chạy từng lệnh từ Lệnh 1 đến Lệnh 4).
4. Khởi động lại thiết bị để hệ điều hành nạp lại toàn bộ cấu hình mới.

### Bước 3: Kiểm tra tiến độ Fix
* Nhấn vào biểu tượng **Tiến độ Fix** ở đầu trang chính.
* Hệ thống sẽ tự động quét và chấm điểm tỷ lệ % tối ưu của máy (17/17 mục).

### Bước 4: Thiết lập sau khi Fix (Khuyên dùng cho App Ngân Hàng & Giữ 94% tiến độ)
* **TẮT "Gỡ lỗi không dây" (Wireless Debugging)** & **TẮT "Gỡ lỗi USB" (USB Debugging)**: Giúp 100% các ứng dụng ngân hàng (BIDV, MB Bank, Vietcombank, Techcombank...), ví điện tử và VNeID mở và giao dịch bình thường, hoàn toàn không bị chặn hay báo phát hiện ADB.
* **GIỮ BẬT công tắc tổng "Tùy chọn cho nhà phát triển"**: Ngăn hệ điều hành Android tự động kích hoạt lại bộ đóng băng ngầm `cached_apps_freezer` (duy trì vững chắc tiến độ **94%**, bảo đảm thông báo luôn nổ tức thì 24/24 ngay cả khi tắt màn hình qua đêm).
* **Danh sách app đã có sẵn**: Zalo, Messenger, Telegram, WhatsApp, TikTok (đầy đủ bản Quốc tế `com.zhiliaoapp.musically`, Châu Á `com.ss.android.ugc.trill` và TikTok Lite), Facebook, Instagram, Threads, YouTube ReVanced, Gmail, Mi Fitness, BIDV, MB Bank, VCB, Techcombank, VietinBank iPay... Người dùng có thể thêm bất kỳ ứng dụng nào khác (Shopee, Grab, Be...) vào ô package ở tab Hệ Thống và bấm chạy lại Lệnh 4 là xong.

---

## 👨‍💻 Tác Giả & Bản Quyền

* **Tác giả phát triển:** **DUNGNGUYEN**
* **Dự án:** LIMI Tool (Bộ công cụ LIMI)
* **Bản quyền:** © 2026 DUNGNGUYEN. Mọi quyền được bảo lưu.

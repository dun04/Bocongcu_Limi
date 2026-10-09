# LIMI Tool - Bộ Công Cụ Tối Ưu, Trợ Lý AI & Fix Thông Báo Toàn Diện Cho Xiaomi / HyperOS

<p align="center">
  <img src="https://raw.githubusercontent.com/dun04/Bocongcu_Limi/main/demo1.jpg" width="45%" alt="Giao diện LIMI Tool" />
  <img src="https://raw.githubusercontent.com/dun04/Bocongcu_Limi/main/demo2.jpg" width="45%" alt="Limi AI & Telemetry" />
</p>

<p align="center">
  <b>Phiên bản:</b> v1.3.3.2.ntd | <b>Tác giả & Phát triển:</b> DUNGNGUYEN
</p>

<p align="center">
  <i>Giải pháp toàn diện tối ưu hóa hiệu năng, đặc trị trễ thông báo HyperOS / MIUI, tích hợp Trợ lý Limi AI chuyên sâu, tự động cập nhật OTA, đo lường sức khỏe pin Coulomb Counting và định vị GPS chuẩn xác cho các thiết bị Xiaomi, Redmi, POCO.</i>
</p>

---

## 🌟 Giới Thiệu Tổng Quan

**LIMI Tool** là bộ công cụ tối ưu hóa hệ thống chuyên sâu và toàn diện dành riêng cho người dùng các dòng máy Xiaomi, Redmi và POCO chạy giao diện HyperOS (1, 2, 3) hoặc MIUI trên các phiên bản Android 13, 14, 15, 16.

Ứng dụng can thiệp an toàn vào các tầng quản lý tiến trình Android và nhân tùy biến của Xiaomi mà **không cần can thiệp Root** (thông qua môi trường cấp phép Shizuku hoặc lệnh ADB tiêu chuẩn), giải quyết dứt điểm các hạn chế cố hữu về trễ thông báo, đóng băng ứng dụng chạy ngầm, sai lệch GPS, đo lường chính xác độ chai pin và tích hợp sẵn Trợ lý AI thông minh giải đáp mọi lỗi kỹ thuật.

---

## 🚀 Các Tính Năng Nổi Bật & Công Dụng Chính

### 1. 🔔 Đặc Trị Trễ Thông Báo Chuyên Sâu (Doze & HyperOS Freeze Fix)
* **Vô hiệu hóa Cached Apps Freezer của Android 15/16:** Ngăn chặn cơ chế đóng băng tiến trình cấp nhân kernel khi tắt màn hình trên Xiaomi 14/15/15 Pro và các thiết bị cập nhật HyperOS 2 / HyperOS 3.
* **Xóa bỏ nghẽn Doze Sleep 24h:** Gỡ triệt để hằng số `deviceidle_constants` 86.400.000ms gây kẹt mạng và trễ thông báo suốt cả ngày.
* **Duy trì Socket FCM Heartbeat 120s:** Giữ kết nối liên tục với máy chủ Google Cloud Messaging, đảm bảo tin nhắn Zalo, Messenger, Telegram, Gmail, App Ngân hàng (BIDV, MB Bank, Vietcombank, Techcombank, Vietinbank...) nổ chuông tức thì kể cả khi tắt màn hình qua đêm.
* **Cấp quyền chạy ngầm tuyệt đối AppOps 10008 & AUTO_START:** Mở quyền thức ngầm và tự khởi chạy cho Google Services (GMS/GSF) và danh sách ứng dụng được chọn.
* **Kích hoạt đánh thức màn hình khóa:** Đảm bảo sáng màn hình và hiển thị nội dung thông báo đầy đủ trên màn hình khóa HyperOS.
* **Miễn trừ danh sách trắng MILLET:** Nạp trực tiếp danh sách ứng dụng quan trọng vào danh sách trắng của cơ chế quản lý tài nguyên độc quyền Millet Xiaomi.

### 2. ⚡ Bộ Lệnh Flagship Nâng Cao (Dành cho máy cấu hình cao)
* **Tắt Millet Traffic Freeze:** Mở cổng socket dữ liệu mạng nền không bị ngắt.
* **Vô hiệu hóa Android Phantom Process Killer:** Nâng giới hạn tiến trình con ngầm từ 32 lên mức tối đa `2.147.483.647`, ngăn chặn hệ thống tự ý tắt tiến trình dịch vụ ngầm.
* **Khóa bộ đóng băng MIUI Freezer & HyperCore:** Giữ app chạy nền mượt mà, không bị load lại.
* **Tắt AI quản lý pin thích ứng (Adaptive Battery Management):** Chống AI tự động bóp tài nguyên mạng và CPU của các app ít mở.
* **Tối ưu hóa PowerKeeper:** Giữ lại cơ chế đánh thức nhưng vô hiệu hóa hành vi tự đóng băng app.

### 3. 🤖 Trợ Lý Thông Minh Limi AI (Tích Hợp Sẵn)
* **Tư vấn & khắc phục lỗi Xiaomi/HyperOS chuyên sâu:** Được nạp sẵn kho tri thức kỹ thuật độc quyền của LIMI, tự động phân tích và đưa ra giải pháp sửa lỗi theo từng mã máy.
* **Hỗ trợ phân tích đa phương tiện:** Cho phép đính kèm hình ảnh chụp màn hình lỗi và tài liệu văn bản (`.txt`, `.log`, `.md`, `.json`, `.csv`, `.docx`, `.pdf`).
* **Trình xem ảnh nghệ thuật toàn màn hình (Cinematic Viewer):** Hỗ trợ phóng to thu nhỏ 2 ngón (pinch-to-zoom), vuốt để đóng (swipe-to-dismiss) với hiệu ứng nền mờ Gaussian Blur tinh tế.
* **Trải nghiệm hội thoại cao cấp:** Hỗ trợ bôi đen copy có định dạng, hiệu ứng phát sáng động quanh biểu tượng và lưu bản nháp trò chuyện tự động.

### 4. 🔄 Cập Nhật Ứng Dụng Tự Động (In-App OTA Updates)
* **Quét ngầm tự động khi mở app:** Tự động kết nối tới GitHub Releases trong nền và so sánh phiên bản mới nhất mà không gây trễ giao diện.
* **Thông báo bằng chấm đỏ thông minh:** Tự động hiện chấm đỏ tại tab Cài đặt và nhãn "MỚI" bên cạnh nút cập nhật khi có bản phát hành mới.
* **Giao diện hộp thoại HyperOS Frosted Glass:** Khung xem nhật ký thay đổi (Changelog) mở rộng dài, cuộn mượt mà với hình ảnh minh họa tính năng mới và tính năng tải/cài đặt file APK trực tiếp trong ứng dụng.

### 5. 🔋 Đo Chai Pin & Giám Sát Cảm Biến Sạc Chuyên Nghiệp
* **Thuật toán Sensor Fusion & Coulomb Counting:** Đo lượng điện tích nạp vào (mAh) trực tiếp qua chip sạc Xiaomi Surge / Qualcomm PMIC kết hợp chu kỳ nạp phần cứng BMS.
* **Biểu đồ Telemetry thời gian thực:** Theo dõi trực quan Dòng nạp (mA), Điện thế (V), Công suất sạc (W) và Nhiệt độ pin (°C).
* **Đặc quyền cho người thay pin độ / pin dung lượng cao:** Cho phép nhấn giữ để nhập dung lượng định mức tùy chỉnh (ví dụ: 6000mAh, 6500mAh...) để tính toán chuẩn xác tỷ lệ chai pin thực tế.

### 6. 🗑️ Dọn Rác & Gỡ Ứng Dụng Hệ Thống (Debloat)
* Danh sách phân loại ứng dụng rác hệ thống an toàn có thể gỡ bỏ mà không làm treo máy hay brick hệ điều hành.
* Hỗ trợ khôi phục các ứng dụng hệ thống đã gỡ chỉ với một chạm.

### 7. 🛰️ Tối Ưu Định Vị GPS Chuẩn Xác Tại Việt Nam
* **Đồng bộ NTP Server Việt Nam:** Chuyển máy chủ thời gian về `vn.pool.ntp.org`, giúp GPS bắt sóng vệ tinh nhanh gấp 3 lần.
* **Kích hoạt A-GPS & Quét Wi-Fi/BLE độ chính xác cao:** Tải trước dữ liệu quỹ đạo vệ tinh định kỳ, hỗ trợ điều hướng Google Maps, Grab, Be... chuẩn xác từng mét.

### 8. 📊 Bảng Kiểm Tra Tiến Độ FIX Hệ Thống
* Đo lường chi tiết 17 tiêu chí hệ thống theo thời gian thực trực tiếp qua `ContentResolver`.
* Chấm điểm trực quan giúp người dùng biết chính xác thiết bị của mình đã đạt tối ưu 100% hay chưa.

---

## 📖 Hướng Dẫn Sử Dụng Nhanh

### Bước 1: Chuẩn bị quyền thực thi
Ứng dụng hoạt động hoàn toàn không cần Root qua 1 trong 2 cách:
1. **Qua ứng dụng Shizuku (Khuyên dùng):**
   * Cài đặt và kích hoạt [Shizuku](https://shizuku.rikka.app/) trên điện thoại qua Wi-Fi Debugging.
   * Mở ứng dụng LIMI và cấp quyền Shizuku khi có thông báo yêu cầu.
2. **Qua máy tính (ADB PC):**
   * Sao chép từng dòng lệnh trong ứng dụng và chạy qua cửa sổ Terminal/CMD của ADB.

### Bước 2: Chạy bộ lệnh tối ưu thông báo
1. Mở tab **Hệ thống** trong ứng dụng LIMI.
2. Kiểm tra danh sách các ứng dụng cần nhận thông báo tức thì (Zalo, Messenger, Telegram, App Ngân hàng...).
3. Nhấn **"Chạy tất cả Lệnh 1 -> 4"**.
4. Khởi động lại thiết bị để hệ điều hành nạp lại toàn bộ cấu hình mới.

### Bước 3: Thiết lập sau khi Fix (Khuyên dùng cho App Ngân Hàng & Giữ 94% tiến độ)
* **TẮT "Gỡ lỗi không dây" (Wireless Debugging)** & **TẮT "Gỡ lỗi USB" (USB Debugging)**: Giúp 100% ứng dụng ngân hàng, ví điện tử và VNeID hoạt động bình thường, không bị cảnh báo phát hiện ADB.
* **GIỮ BẬT "Tùy chọn cho nhà phát triển"**: Ngăn chặn Android tự bật lại bộ đóng băng ngầm `cached_apps_freezer`, bảo đảm thông báo nổ tức thì 24/24.

### Bước 4: Thiết lập sau khi Fix (Khuyên dùng cho App Ngân Hàng & Giữ 94% tiến độ)
* **TẮT "Gỡ lỗi không dây" (Wireless Debugging)** & **TẮT "Gỡ lỗi USB" (USB Debugging)**: Giúp 100% các ứng dụng ngân hàng (BIDV, MB Bank, Vietcombank, Techcombank...), ví điện tử và VNeID mở và giao dịch bình thường, hoàn toàn không bị chặn hay báo phát hiện ADB.
* **GIỮ BẬT công tắc tổng "Tùy chọn cho nhà phát triển"**: Ngăn hệ điều hành Android tự động kích hoạt lại bộ đóng băng ngầm `cached_apps_freezer` (duy trì vững chắc tiến độ **94%**, bảo đảm thông báo luôn nổ tức thì 24/24 ngay cả khi tắt màn hình qua đêm).
* **Danh sách app đã có sẵn**: Zalo, Messenger, Telegram, WhatsApp, TikTok (đầy đủ bản Quốc tế `com.zhiliaoapp.musically`, Châu Á `com.ss.android.ugc.trill` và TikTok Lite), Facebook, Instagram, Threads, YouTube ReVanced, Gmail, Mi Fitness, BIDV, MB Bank, VCB, Techcombank, VietinBank iPay... Người dùng có thể thêm bất kỳ ứng dụng nào khác (Shopee, Grab, Be...) vào ô package ở tab Hệ Thống và bấm chạy lại Lệnh 4 là xong.

---

## 👨‍💻 Tác Giả & Bản Quyền

* **Tác giả phát triển:** **DUNGNGUYEN**
* **Dự án:** LIMI Tool (Bộ công cụ LIMI)
* **Kho lưu trữ:** [https://github.com/dun04/Bocongcu_Limi](https://github.com/dun04/Bocongcu_Limi)
* **Bản quyền:** © 2026 DUNGNGUYEN. Mọi quyền được bảo lưu.

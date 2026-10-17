# LIMI Tool - Bộ Công Cụ Tối Ưu, Trợ Lý AI & Fix Thông Báo Toàn Diện Cho Xiaomi / HyperOS

<p align="center">
  <img src="app_icon.png" width="110" height="110" alt="LIMI Tool Icon" style="border-radius: 24px;" />
</p>

<p align="center">
  <b>Phiên bản:</b> v1.3.3.2.ntd | <b>Tác giả & Phát triển:</b> DUNGNGUYEN
</p>

<p align="center">
  <i>Giải pháp toàn diện tối ưu hóa hiệu năng, đặc trị trễ thông báo HyperOS / MIUI, tích hợp Trợ lý Limi AI chuyên sâu, tự động cập nhật OTA, đo lường sức khỏe pin Coulomb Counting và định vị GPS chuẩn xác cho các thiết bị Xiaomi, Redmi, POCO.</i>
</p>

---

## Tổng quan

Trên các bản ROM MIUI và HyperOS (đặc biệt là máy nội địa Trung Quốc hoặc các bản cập nhật Android 14/15/16), hệ thống thường áp dụng các cơ chế quản lý năng lượng nghiêm ngặt (Doze Mode, Cached Apps Freezer, Millet Traffic Freeze) khiến các ứng dụng nhắn tin và ứng dụng ngân hàng bị trễ thông báo hoặc bị đóng băng khi tắt màn hình.

**LIMI Tool** được xây dựng nhằm cung cấp một giao diện trực quan và các tập lệnh cấu hình hệ thống chuẩn mực để:
- Điều chỉnh các tham số `deviceidle`, `fcm_heartbeat` và `appops` mà không làm hỏng cấu trúc hệ điều hành.
- Giữ ứng dụng hoạt động ổn định trong nền mà không cần mở khóa Bootloader hay can thiệp vào phân vùng hệ thống.
- Cung cấp các công cụ kiểm tra pin phần cứng, dọn dẹp bloatware an toàn và hỗ trợ điều hướng GPS.

---

## Tính năng chính

### 1. Quản lý tiến trình & Khắc phục trễ thông báo
- **Cấu hình Doze Mode**: Tinh chỉnh chu kỳ sleep của `deviceidle_constants` về giá trị tiêu chuẩn, tránh tình trạng ngủ sâu kéo dài ngắt kết nối mạng ngầm.
- **Duy trì kết nối FCM Heartbeat**: Giảm khoảng thời gian heartbeat về mức khuyến nghị để duy trì socket Google Push Notification liên tục.
- **Thiết lập AppOps 10008 & Auto-start**: Cấp cờ khởi chạy nền cho Google Services Framework và các ứng dụng OTT (Zalo, Messenger, Telegram, Gmail, ứng dụng ngân hàng...).
- **Xử lý Phantom Process Killer & App Freezer**: Nâng ngưỡng giới hạn tiến trình con trên Android 13+ và giảm thiểu việc đóng băng ứng dụng đột ngột.

### 2. Gỡ bỏ ứng dụng rác (Debloat)
- Danh mục đóng gói được phân loại an toàn: Bloatware rác, dịch vụ quảng cáo và app theo dõi của hãng.
- Thực hiện lệnh `pm uninstall -k --user 0` an toàn, có thể khôi phục lại trực tiếp (`pm install-existing`) bất cứ lúc nào.

### 3. Giám sát pin & Cảm biến phần cứng
- Đọc dữ liệu trực tiếp từ các node kernel `/sys/class/power_supply/battery`.
- Hỗ trợ tính toán dung lượng thực tế qua chu kỳ sạc (Coulomb Counting / Battery Management System).
- Theo dõi điện thế, dòng sạc tức thời và nhiệt độ phần cứng theo thời gian thực.

### 4. Tối ưu GPS & NTP Server
- Đồng bộ máy chủ thời gian mạng về cụm máy chủ khu vực Việt Nam (`vn.pool.ntp.org`).
- Nạp cấu hình A-GPS và hỗ trợ bắt sóng vệ tinh nhanh hơn trong điều kiện đô thị.

### 5. Trợ lý kỹ thuật số tích hợp
- Hỗ trợ tra cứu nhanh mã lỗi, cách cấu hình phân quyền và giải đáp các vấn đề tương thích theo từng dòng máy Xiaomi.

---

## Yêu cầu hệ điều hành & Môi trường

| Mục | Yêu cầu |
| --- | --- |
| Hệ điều hành | Android 10 trở lên (Tối ưu tốt nhất trên Android 13, 14, 15, 16) |
| Giao diện | HyperOS 1.0 / 2.0 / 3.0, MIUI 12 / 13 / 14 |
| Quyền hạn | **Không cần Root**. Cần cấp quyền qua **Shizuku** hoặc **ADB qua USB / Wi-Fi** |

---

## Cài đặt & Hướng dẫn sử dụng

### Phương pháp 1: Sử dụng Shizuku (Khuyến nghị)
1. Tải và kích hoạt [Shizuku](https://shizuku.rikka.app/) trên thiết bị (qua tính năng Ghép nối Gỡ lỗi không dây).
2. Cài đặt file APK LIMI Tool từ mục Releases.
3. Mở ứng dụng LIMI và chọn **Cho phép** khi Shizuku hiển thị hộp thoại cấp quyền.

### Phương pháp 2: Sử dụng lệnh ADB qua máy tính
Nếu không dùng Shizuku, bạn có thể kết nối điện thoại với máy tính đã cài đặt ADB và chạy các lệnh cấu hình được cung cấp trực tiếp trong ứng dụng.

---

## Quy trình cấu hình khuyến nghị

1. **Bước 1**: Mở tab **Fix Noti**, chọn các ứng dụng quan trọng cần nhận thông báo tức thời.
2. **Bước 2**: Nhấn chạy bộ lệnh tối ưu (Lệnh 1 đến Lệnh 4).
3. **Bước 3**: Khởi động lại thiết bị để hệ thống nạp lại các tham số kernel và Framework.
4. **Bước 4 (Dành cho app ngân hàng)**: Sau khi chạy lệnh thành công, bạn có thể tắt **Gỡ lỗi USB** và **Gỡ lỗi không dây** trong Tùy chọn nhà phát triển. Toàn bộ cấu hình đã áp dụng vẫn được lưu vĩnh viễn trong `Settings.Global` và `Settings.Secure`, giúp các ứng dụng ngân hàng và VNeID không phát hiện môi trường debug.

---

## Biên dịch từ mã nguồn

Dự án sử dụng Gradle tiêu chuẩn với Android Gradle Plugin 8.x:

```powershell
git clone https://github.com/dun04/Bocongcu_Limi.git
cd Bocongcu_Limi

# Cấu hình JDK 17 hoặc 21
./gradlew assembleRelease
```

File APK đầu ra sẽ nằm tại `app/build/outputs/apk/release/app-release.apk`.

---

## Giấy phép & Đóng góp

- Dự án phát triển bởi **DUNGNGUYEN**.
- Đóng góp mã nguồn và báo lỗi: vui lòng tạo Issue hoặc Pull Request trên GitHub.

# HƯỚNG DẪN DÀNH CHO AI (AGENT INSTRUCTIONS & RULES)

## 📌 QUY TẮC QUAN TRỌNG: BUILD & XUẤT FILE APK

Mỗi khi người dùng yêu cầu build APK, AI **BẮT BUỘC** phải tuân thủ đúng tên file và thư mục đích sau:

### 1. Vị Trí & Tên File APK
- **Thư mục xuất file**: `C:\Users\duyih\Downloads\Bộ công cụ LIMI\`
- **Định dạng tên file**: `Limi-v<versionName>.apk`
  - Giá trị `<versionName>` được lấy trực tiếp từ `versionName` trong [`app/build.gradle.kts`](file:///c:/Users/duyih/Downloads/App%20fix%20th%C3%B4ng%20b%C3%A1o%20Xiaomi/app/build.gradle.kts).
  - *Ví dụ*: `versionName = "1.2.8.3.ntd"` -> File xuất ra là:
    `C:\Users\duyih\Downloads\Bộ công cụ LIMI\Limi-v1.2.8.3.ntd.apk`

### 2. Lệnh Build Mẫu (Powershell)
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew.bat assembleRelease
```

### 3. Tự Động Hóa Trong Gradle
Task `assembleRelease` trong `app/build.gradle.kts` đã được cấu hình tự động sao chép file `app-release.apk` vừa build sang:
- `C:\Users\duyih\Downloads\Bộ công cụ LIMI\Limi-v<versionName>.apk`
- `C:\Users\duyih\Downloads\Bộ công cụ LIMI\Limi_beta.apk`
- Thư mục gốc dự án: `Limi-v<versionName>.apk`

### 4. Kiểm Tra Sau Khi Build
AI phải kiểm tra file đích `C:\Users\duyih\Downloads\Bộ công cụ LIMI\Limi-v<versionName>.apk` và thông báo dung lượng kèm đường dẫn hoàn chỉnh.

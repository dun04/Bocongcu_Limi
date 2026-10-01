# HƯỚNG DẪN DÀNH CHO AI (GEMINI AGENT INSTRUCTIONS)

## 📌 QUY TẮC BẮT BUỘC: BUILD VÀ XUẤT FILE APK

Khi thực hiện lệnh build APK (Release hoặc Debug):

### 1. Đường Dẫn & Tên File Bắt Buộc:
- **Thư mục đích**: `C:\Users\duyih\Downloads\Bộ công cụ LIMI\`
- **Quy tắc tên file**: `Limi-v<versionName>.apk`
  - Lấy `<versionName>` từ trường `versionName` trong [`app/build.gradle.kts`](file:///c:/Users/duyih/Downloads/App%20fix%20th%C3%B4ng%20b%C3%A1o%20Xiaomi/app/build.gradle.kts).
  - Ví dụ: `versionName` là `1.2.8.3.ntd` -> File là:
    `C:\Users\duyih\Downloads\Bộ công cụ LIMI\Limi-v1.2.8.3.ntd.apk`

### 2. Lệnh Build:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew.bat assembleRelease
```

### 3. Cấu Hình Gradle Tự Động:
File `app/build.gradle.kts` đã cài sẵn `doLast` sao chép sang `C:\Users\duyih\Downloads\Bộ công cụ LIMI\Limi-v<versionName>.apk` và `Limi_beta.apk`.

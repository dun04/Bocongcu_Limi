# ==============================================================================
# CẤU HÌNH BẢO MẬT & CHỐNG MÃ HÓA NGƯỢC (ANTI-REVERSE ENGINEERING & OBFUSCATION)
# DÀNH CHO BỘ CÔNG CỤ LIMI & MODULE AI
# ==============================================================================

# 1. Tối ưu hóa & Làm rối mã nguồn tối đa (Maximum Obfuscation)
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'com.xiaomi.fixnotification.internal'

# 2. Xóa sạch thông tin gỡ lỗi, số dòng và tên file gốc (Anti-Decompilation Trace)
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
-keepattributes !SourceFile,!LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 3. Làm rối triệt để toàn bộ package AI (Obfuscate all AI classes, methods, fields)
-keepclassmembers enum com.xiaomi.fixnotification.ai.** { *; }

# 4. Giữ lại các thành phần Android bắt buộc
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View

# 5. Shizuku API
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# 6. Loại bỏ toàn bộ log gỡ lỗi trong bản Release (Strip Logs)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Quy tắc R8 / ProGuard tối ưu APK phát hành (giảm ~5 lần dung lượng, chạy mượt trên TV Box RAM 1-2GB)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# Giữ nguyên các thành phần Android Manifest (Activity, Service, Receiver, Application)
-keep class com.edusing.chamcongtvbox.AttendanceTvApp { *; }
-keep class com.edusing.chamcongtvbox.presentation.MainActivity { *; }
-keep class com.edusing.chamcongtvbox.service.AttendanceForegroundService { *; }
-keep class com.edusing.chamcongtvbox.service.BootReceiver { *; }
-keep class com.edusing.chamcongtvbox.service.TvDeviceAdminReceiver { *; }

# Giữ nguyên Tink / EncryptedSharedPreferences dùng reflection khởi tạo khoá AES-256
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**

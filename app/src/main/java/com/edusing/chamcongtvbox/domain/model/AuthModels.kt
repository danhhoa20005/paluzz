package com.edusing.chamcongtvbox.domain.model

/**
 * Tài khoản đã lưu phục vụ chọn nhanh trên màn hình đăng nhập.
 */
data class SavedAccount(
    val username: String,
    val password: String,
    val orgName: String,
    val accountId: String,
)

/**
 * Kết quả gọi API đăng nhập `POST /api/auth/login`.
 */
data class LoginResult(
    val success: Boolean,
    val accountId: String = "",
    val orgName: String = "",
    val token: String = "",
    val error: String? = null,
    val isInvalidCredentials: Boolean = false,
)

/**
 * Toàn bộ cấu hình ứng dụng được lưu cục bộ (mã hoá AES trên TV Box).
 * Bao gồm thông tin tài khoản, danh sách máy và các tham số kết nối giống `config.settings`.
 */
data class AppConfig(
    val mode: AppMode = AppMode.PALUZZ,
    val apiDomain: String,
    val accountId: String = "",
    val orgName: String = "",
    val username: String = "",
    val password: String = "",
    val rememberAccount: Boolean = true,
    val token: String = "",
    val devices: List<DeviceConfig> = emptyList(),
    val savedAccounts: List<SavedAccount> = emptyList(),
    val autoStartRunning: Boolean = true,
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    val dedupSeconds: Long = DEFAULT_DEDUP_SECONDS,
    val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
    val idleCleanIntervalMs: Long = DEFAULT_IDLE_CLEAN_INTERVAL_MS,
    val autoStartOnBoot: Boolean = true,
    // Tham số kết nối máy chấm công (tương ứng config.settings trên Windows)
    val udpMode: Boolean = false,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val defaultPort: Int = DeviceConfig.DEFAULT_PORT,
    val defaultMachineNo: Int = DeviceConfig.DEFAULT_MACHINE_NO,
    val defaultPassword: Int = DeviceConfig.DEFAULT_PASSWORD,
    val defaultLicense: Int = DeviceConfig.DEFAULT_LICENSE,
    val maxDeviceNameBytes: Int = DEFAULT_MAX_NAME_BYTES,
    // Lịch tự động đẩy nhân sự xuống máy chấm công (cố định 06:00, 12:00, 20:00 + 1 khung giờ tuỳ chọn)
    val autoPushEnabled: Boolean = true,
    val autoPushCustomEnabled: Boolean = false,
    val autoPushCustomHour: Int = AutoPushScheduleConfig.DEFAULT_CUSTOM_HOUR,
    val autoPushCustomMinute: Int = AutoPushScheduleConfig.DEFAULT_CUSTOM_MINUTE,
    // Tên miền mDNS cố định trong LAN (vd "chamcong" -> http://chamcong.local:8080; đổi được khi dùng nhiều TV Box)
    val localHostName: String = DEFAULT_LOCAL_HOST,
    // Tự động kiểm tra & cập nhật bản APK mới từ GitHub Releases
    val autoUpdateEnabled: Boolean = true,
    val updateRepoSlug: String = DEFAULT_UPDATE_REPO,
) {
    val isLoggedIn: Boolean get() = accountId.isNotBlank() && username.isNotBlank()
    val safeLocalHostName: String get() = sanitizeLocalHost(localHostName)
    val safeUpdateRepoSlug: String get() = sanitizeRepoSlug(updateRepoSlug)
    val autoPushSchedule: AutoPushScheduleConfig
        get() = AutoPushScheduleConfig(
            enabled = autoPushEnabled,
            customEnabled = autoPushCustomEnabled,
            customHour = autoPushCustomHour,
            customMinute = autoPushCustomMinute,
        )

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 10_000L
        const val DEFAULT_DEDUP_SECONDS = 60L
        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 3_000L // Khớp GuiBaoSong 3 giây trong MainForm.cs
        const val DEFAULT_IDLE_CLEAN_INTERVAL_MS = 60_000L
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_MAX_NAME_BYTES = 6
        const val DEFAULT_LOCAL_HOST = "chamcong"
        const val DEFAULT_UPDATE_REPO = "danhhoa20005/paluzz"

        fun sanitizeLocalHost(raw: String): String {
            val cleaned = raw.trim().lowercase()
                .removeSuffix(".local")
                .map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '-' }
                .joinToString("")
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(24)
            return cleaned.ifBlank { DEFAULT_LOCAL_HOST }
        }

        fun sanitizeRepoSlug(raw: String): String {
            val clean = raw.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removeSuffix(".git")
                .trim('/')
            val parts = clean.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return DEFAULT_UPDATE_REPO
            val owner = parts[0].filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            val repo = parts[1].filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
            return if (owner.isNotBlank() && repo.isNotBlank()) "$owner/$repo" else DEFAULT_UPDATE_REPO
        }
    }
}

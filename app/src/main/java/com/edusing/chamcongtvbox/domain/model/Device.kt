package com.edusing.chamcongtvbox.domain.model

/** Giao thức phần cứng máy chấm công được hỗ trợ song song trên cùng một TV Box. */
enum class DeviceProtocol(
    val code: String,
    val shortLabel: String,
    val defaultPort: Int,
) {
    FK("FK", "FK (5005)", DeviceConfig.DEFAULT_PORT),
    HIKVISION_ISAPI("HIKVISION", "Hikvision (80)", DeviceConfig.DEFAULT_HIK_PORT);

    companion object {
        fun fromCode(raw: String?): DeviceProtocol = when (raw?.trim()?.uppercase()) {
            "HIKVISION", "HIKVISION_ISAPI", "HIK", "ISAPI" -> HIKVISION_ISAPI
            else -> FK
        }
    }
}

/**
 * Cấu hình đã lưu của một máy chấm công (hỗ trợ cả dòng FK cổng 5005 và Hikvision DS-K1T320MX cổng 80/443).
 */
data class DeviceConfig(
    val ip: String,
    val port: Int = DEFAULT_PORT,
    val machineNo: Int = DEFAULT_MACHINE_NO,
    val name: String = "",
    val password: Int = DEFAULT_PASSWORD,
    val license: Int = DEFAULT_LICENSE,
    val useUdp: Boolean = false,
    val protocol: DeviceProtocol = DeviceProtocol.FK,
    val username: String = DEFAULT_HIK_USERNAME,
    val accessPassword: String = "",
    val useHttps: Boolean = false,
) {
    /** Tên hiển thị ưu tiên tên người dùng đặt; nếu bỏ trống sẽ hiện IP. */
    val displayName: String get() = name.ifBlank { ip }

    /** Khoá định danh duy nhất cho mỗi máy trong danh sách. */
    val key: String get() = "${ip.trim()}:$port"

    /** True nếu máy sử dụng giao thức HTTP/HTTPS ISAPI của Hikvision (DS-K1T320MX...). */
    val isHikvision: Boolean get() = protocol == DeviceProtocol.HIKVISION_ISAPI

    companion object {
        const val DEFAULT_PORT = 5005
        const val DEFAULT_HIK_PORT = 80
        const val DEFAULT_HIK_HTTPS_PORT = 443
        const val DEFAULT_HIK_USERNAME = "admin"
        const val DEFAULT_MACHINE_NO = 1
        const val DEFAULT_PASSWORD = 0
        /** Khớp mặc định LICENSE=5912 trong ConfigStore.cs của bản Windows. */
        const val DEFAULT_LICENSE = 5912
    }
}

/** Trạng thái kết nối hiện tại của từng máy chấm công. */
enum class DeviceConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/** Trạng thái thời gian thực của một máy chấm công trên giao diện. */
data class DeviceRuntimeStatus(
    val config: DeviceConfig,
    val state: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val statusText: String = "Chưa kết nối",
    val firmwareProfile: String = "",
    val lastError: String? = null,
    val lastPollAtMs: Long = 0L,
) {
    val isConnected: Boolean get() = state == DeviceConnectionState.CONNECTED
}

/** Bản ghi tóm tắt trạng thái từng máy gửi kèm gói Heartbeat về máy chủ. */
data class DeviceHeartbeatItem(
    val deviceName: String,
    val deviceIp: String,
    val devicePort: Int,
    val deviceNo: Int,
    val connected: Boolean,
    val running: Boolean,
    val note: String,
)

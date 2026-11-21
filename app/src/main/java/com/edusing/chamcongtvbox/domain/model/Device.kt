package com.edusing.chamcongtvbox.domain.model

/**
 * Cấu hình đã lưu của một máy chấm công.
 * Chuyển thể từ Device + ConfigStore.cs trong AppMayTinh.
 */
data class DeviceConfig(
    val ip: String,
    val port: Int = DEFAULT_PORT,
    val machineNo: Int = DEFAULT_MACHINE_NO,
    val name: String = "",
    val password: Int = DEFAULT_PASSWORD,
    val license: Int = DEFAULT_LICENSE,
    val useUdp: Boolean = false,
) {
    /** Tên hiển thị ưu tiên tên người dùng đặt; nếu bỏ trống sẽ hiện IP. */
    val displayName: String get() = name.ifBlank { ip }

    /** Khoá định danh duy nhất cho mỗi máy trong danh sách. */
    val key: String get() = "${ip.trim()}:$port"

    companion object {
        const val DEFAULT_PORT = 5005
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

package com.edusing.chamcongtvbox.domain.model

/**
 * Thông tin card mạng LAN/Wi-Fi vật lý của TV Box (đã lọc bỏ VPN/Tailscale/card ảo).
 */
data class SubnetInfo(
    val localIp: String,
    val prefix: String,          // Ví dụ: "192.168.1"
    val subnetMask: String,      // Ví dụ: "255.255.255.0"
    val gatewayGuess: String,    // Ví dụ: "192.168.1.1"
    val interfaceName: String,   // Ví dụ: "eth0" hoặc "wlan0"
)

/** Một thiết bị đang hoạt động được phát hiện trong mạng LAN. */
data class LanHostInfo(
    val ip: String,
    val port: Int = DeviceConfig.DEFAULT_PORT,
    val reachableByPing: Boolean = true,
    val alreadyConfigured: Boolean = false,
    val isFkTerminal: Boolean = false,
    val isHikvisionTerminal: Boolean = false,
    val detectedProtocol: DeviceProtocol = DeviceProtocol.FK,
    val note: String = "",
) {
    val isDetectedTerminal: Boolean get() = isFkTerminal || isHikvisionTerminal
}

/** Gợi ý cấu hình IP tĩnh (ưu tiên dải .240..200) để nhập vào menu máy chấm công mới. */
data class IpSuggestion(
    val suggestedIp: String,
    val subnetMask: String,
    val gateway: String,
    val localBoxIp: String,
    val port: Int = DeviceConfig.DEFAULT_PORT,
)

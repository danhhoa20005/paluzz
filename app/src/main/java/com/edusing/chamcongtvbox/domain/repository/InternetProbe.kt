package com.edusing.chamcongtvbox.domain.repository

/** Trạng thái kết nối Internet thực tế của Android TV Box / Điện thoại. */
enum class InternetStatus {
    CHECKING,
    ONLINE,
    OFFLINE,
    RECOVERING,
}

/** Kết quả một lần kiểm tra Internet & hạ tầng Wi-Fi/LAN chủ động. */
data class InternetCheckResult(
    val online: Boolean,
    val latencyMs: Long = 0L,
    val detail: String = "",
    val hasLanOrWifi: Boolean = true,
    val wifiHardwareDisabled: Boolean = false,
)

/**
 * Hợp đồng kiểm tra mạng Internet + Wi-Fi/LAN chủ động và kích phục hồi kết nối mạng (100% pure Kotlin).
 */
interface InternetProbe {
    /** Kiểm tra thực tế Internet tới `apiDomain` cùng trạng thái Wi-Fi/Ethernet cục bộ. */
    suspend fun checkInternet(apiDomain: String): InternetCheckResult

    /** Kiểm tra nhanh thiết bị đã có IP nội bộ trên Wi-Fi hoặc dây LAN hay chưa (đã xong DHCP). */
    fun hasLanOrWifiIpv4(): Boolean = true

    /** Chủ động kích hệ thống kết nối lại Wi-Fi/mạng khi phát hiện mất mạng hoặc rớt Wi-Fi. */
    fun requestReconnectNetwork() = Unit
}

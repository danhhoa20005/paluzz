package com.edusing.chamcongtvbox.domain.model

/** Lệnh điều khiển khoá cửa từ xa trên máy chấm công Hikvision DS-K1T320MX. */
enum class HikvisionDoorCommand(val isapiCmd: String, val label: String) {
    OPEN("open", "Mở cửa 1 lần"),
    CLOSE("close", "Khoá cửa"),
    ALWAYS_OPEN("alwaysOpen", "Giữ luôn mở"),
    ALWAYS_CLOSE("alwaysClose", "Khoá chặt");

    companion object {
        fun fromCode(raw: String?): HikvisionDoorCommand = when (raw?.trim()?.lowercase()) {
            "close" -> CLOSE
            "alwaysopen", "always_open" -> ALWAYS_OPEN
            "alwaysclose", "always_close" -> ALWAYS_CLOSE
            else -> OPEN
        }
    }
}

/** Báo cáo sức khoẻ phần cứng thời gian thực đọc từ máy Hikvision DS-K1T320MX. */
data class HikvisionHealthStatus(
    val model: String,
    val serialNumber: String,
    val firmwareVersion: String,
    val userCount: Int,
    val faceCount: Int,
    val cardCount: Int,
    val maxFaceCapacity: Int = 500,
    val doorLocked: Boolean = true,
    val antiDismantleAlert: Boolean = false,
    val isDhcp: Boolean = false,
    val ipAddress: String = "",
    val subnetMask: String = "",
    val gateway: String = "",
    val deviceTimeText: String = "",
)

/** Một bản ghi lịch sử quét mặt/thẻ hợp lệ đọc trực tiếp từ bộ nhớ máy Hikvision. */
data class HikvisionHistoryItem(
    val timeText: String,
    val epochMs: Long,
    val employeeNo: String,
    val name: String,
    val verifyMethod: String,
    val serialNo: Int,
)

/** Thiết bị Hikvision bất kỳ phát hiện tức thì qua giao thức đa hướng SADP (`239.255.255.250:37020`). */
data class HikvisionSadpDevice(
    val ip: String,
    val httpPort: Int = DeviceConfig.DEFAULT_HIK_PORT,
    val model: String = "Hikvision",
    val serialNumber: String = "",
    val mac: String = "",
    val firmwareVersion: String = "",
    val dhcpEnabled: Boolean = false,
    val activated: Boolean = true,
    val isAccessTerminal: Boolean = true,
)

/** Cấu hình Webhook (`HTTP Listening` — `/ISAPI/Event/notification/httpHosts/1`) trên máy Hikvision. */
data class HikvisionWebhookConfig(
    val enabled: Boolean,
    val protocolType: String = "HTTP",       // "HTTP" hoặc "HTTPS"
    val addressingType: String = "ipaddress", // "ipaddress" hoặc "hostname"
    val hostOrIp: String = "",
    val port: Int = 8080,
    val url: String = "/hikvision/event",
) {
    val summaryUrl: String
        get() = if (!enabled || hostOrIp.isBlank() || hostOrIp == "0.0.0.0" || port <= 0) {
            "Chưa bật Webhook"
        } else {
            val cleanPath = if (url.startsWith("/")) url else "/$url"
            "${protocolType.lowercase()}://$hostOrIp:$port$cleanPath"
        }
}

/** Thống kê nhận gói tin Webhook trực tiếp trên TV Box (`POST /hikvision/event`). */
data class HikvisionWebhookStats(
    val eventCount: Int = 0,
    val heartbeatCount: Int = 0,
    val lastReceivedAtMs: Long = 0L,
    val lastSourceIp: String = "",
    val lastSummary: String = "",
)

/** Kết quả đồng bộ bù các lượt quét còn lưu trên máy Hikvision lên máy chủ Paluzz/ĐiểmDanh. */
data class BackfillSummary(
    val totalFound: Int,
    val submitted: Int,
    val skippedDuplicate: Int,
    val failed: Int,
    val message: String,
)

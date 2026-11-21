package com.edusing.chamcongtvbox.data.hikvision

/** Phản hồi HTTP thô từ máy chấm công Hikvision ISAPI. */
internal data class HikHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val isSuccess: Boolean get() = statusCode in 200..299
    val isUnauthorized: Boolean get() = statusCode == 401
}

/** Thông tin phần cứng đọc từ `/ISAPI/System/deviceInfo` của máy Hikvision `DS-K1T320MX`. */
internal data class HikDeviceInfo(
    val deviceName: String,
    val model: String,
    val serialNumber: String,
    val firmwareVersion: String,
)

/** Bản ghi người dùng đọc từ `/ISAPI/AccessControl/UserInfo/Search`. */
internal data class HikDeviceUser(
    val employeeNo: String,
    val name: String,
    val numOfFace: Int = 0,
    val numOfCard: Int = 0,
)

internal data class HikUserSearchPage(
    val totalMatches: Int,
    val users: List<HikDeviceUser>,
)

/** Sự kiện xác thực ĐẠT HỢP LỆ (mặt / thẻ / vân tay) chuẩn hoá từ Hikvision AcsEvent hoặc Webhook. */
internal data class HikParsedEvent(
    val eventKey: String,
    val employeeNo: String,
    val employeeName: String,
    val cardNo: String,
    val serialNo: Int,
    val minor: Int,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val epochMs: Long,
)

internal data class HikAcsEventPage(
    val totalMatches: Int,
    val events: List<HikParsedEvent>,
)

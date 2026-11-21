package com.edusing.chamcongtvbox.domain.model

/**
 * Một bản ghi lượt chấm công đọc từ bộ nhớ máy chấm công.
 */
data class AttendanceLog(
    val enrollNumber: Int,
    val verifyMode: Int,
    val inOutMode: Int,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
) {
    val cardIdString: String get() = enrollNumber.toString()

    /** Định dạng thời gian theo đồng hồ máy chấm công (`HH:mm:ss dd/MM/yyyy`). */
    fun formattedDeviceTime(): String =
        "%02d:%02d:%02d %02d/%02d/%04d".format(hour, minute, second, day, month, year)
}

/**
 * Kết quả gửi một lượt chấm công lên máy chủ.
 */
data class ServerCheckInResult(
    val success: Boolean,
    val serverTimeFormatted: String,
    val serverTimeMillis: Long,
    val message: String,
    val isAuthError: Boolean = false,
    val isRetryableNetworkError: Boolean = false,
)

/** Mức độ hiển thị màu cho từng dòng nhật ký trực tiếp. */
enum class EventSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

/**
 * Một dòng hiển thị trong khung Nhật ký điểm danh trực tiếp trên màn hình chính.
 */
data class LiveEventEntry(
    val id: Long,
    val timeText: String,
    val deviceName: String,
    val cardId: String,
    val userName: String = "",
    val message: String,
    val severity: EventSeverity,
)

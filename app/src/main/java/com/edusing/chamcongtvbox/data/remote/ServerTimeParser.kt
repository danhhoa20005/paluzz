package com.edusing.chamcongtvbox.data.remote

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Xử lý chuyển đổi giờ máy chủ sang giờ Việt Nam (`GMT+07:00`).
 * Giúp nhật ký hiển thị khớp với máy chủ ngay cả khi đồng hồ TV Box bị lệch sau cúp điện.
 */
object ServerTimeParser {

    private val SERVER_TIME_REGEX = Regex("""(\d{2}):(\d{2}):(\d{2})\s+(\d{2})/(\d{2})/(\d{4})""")
    private val VN_TIMEZONE: TimeZone = TimeZone.getTimeZone("GMT+07:00")

    /** Tách thời gian từ câu thông báo trả về (`.. vào lúc: HH:mm:ss DD/MM/YYYY`). */
    fun extractFromMessage(message: String?): Pair<String, Long>? {
        if (message.isNullOrBlank()) return null
        val match = SERVER_TIME_REGEX.find(message) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val second = match.groupValues[3].toIntOrNull() ?: return null
        val day = match.groupValues[4].toIntOrNull() ?: return null
        val month = match.groupValues[5].toIntOrNull() ?: return null
        val year = match.groupValues[6].toIntOrNull() ?: return null

        val cal = Calendar.getInstance(VN_TIMEZONE).apply {
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }
        val formatted = "%02d:%02d:%02d %02d/%02d/%04d".format(hour, minute, second, day, month, year)
        return formatted to cal.timeInMillis
    }

    /** Đọc header HTTP `Date` (RFC 1123 GMT) thành epoch millis UTC. */
    fun parseHttpDateMillis(dateHeader: String?): Long? {
        if (dateHeader.isNullOrBlank()) return null
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
        )
        for (pattern in patterns) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val parsed = runCatching { fmt.parse(dateHeader) }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    /** Định dạng epoch millis thành chuỗi giờ Việt Nam (`HH:mm:ss dd/MM/yyyy`). */
    fun formatVietnamTime(epochMillis: Long): String {
        val fmt = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.US).apply {
            timeZone = VN_TIMEZONE
        }
        return fmt.format(epochMillis)
    }
}

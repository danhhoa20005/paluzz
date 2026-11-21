package com.edusing.chamcongtvbox.domain.model

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Một mốc giờ đẩy nhân sự tự động hằng ngày (theo giờ Việt Nam UTC+7). */
data class AutoPushSlot(
    val hour: Int,
    val minute: Int,
    val isFixed: Boolean,
) {
    val minuteOfDay: Int get() = hour * 60 + minute
    val timeText: String get() = String.format(Locale.US, "%02d:%02d", hour, minute)
}

/** Khung giờ đến hạn cần thực thi kèm khoá ngày (`YYYY-MM-DD|HH:MM`) chống chạy lặp. */
data class DueAutoPushSlot(
    val slot: AutoPushSlot,
    val executionKey: String,
)

/**
 * Lịch tự động đẩy danh sách nhân sự/học sinh xuống tất cả máy chấm công đang kết nối:
 * - 3 khung giờ cố định mỗi ngày: **06:00**, **12:00**, **20:00**.
 * - Thêm **1 khung giờ tuỳ chọn** (`customHour:customMinute`) bật/tắt linh hoạt.
 * - Dùng múi giờ Việt Nam (`GMT+07:00`) + cửa sổ bù 15 phút nếu TV Box vừa cúp điện đúng mốc giờ.
 */
data class AutoPushScheduleConfig(
    val enabled: Boolean = true,
    val customEnabled: Boolean = false,
    val customHour: Int = DEFAULT_CUSTOM_HOUR,
    val customMinute: Int = DEFAULT_CUSTOM_MINUTE,
) {
    val safeCustomHour: Int get() = customHour.coerceIn(0, 23)
    val safeCustomMinute: Int get() = customMinute.coerceIn(0, 59)
    val customTimeText: String get() = String.format(Locale.US, "%02d:%02d", safeCustomHour, safeCustomMinute)

    /** Trả về danh sách các mốc giờ đang kích hoạt theo thứ tự tăng dần trong ngày (đã chống trùng mốc). */
    fun activeSlots(): List<AutoPushSlot> {
        if (!enabled) return emptyList()
        val fixed = FIXED_SLOTS
        if (!customEnabled) return fixed
        val custom = AutoPushSlot(safeCustomHour, safeCustomMinute, isFixed = false)
        val merged = if (fixed.any { it.minuteOfDay == custom.minuteOfDay }) fixed else fixed + custom
        return merged.sortedBy { it.minuteOfDay }
    }

    /** Tìm khung giờ đến hạn trong cửa sổ bù [catchUpWindowMinutes] phút mà chưa chạy thành công hôm nay. */
    fun findDueSlot(
        nowEpochMs: Long,
        executedKeys: Set<String>,
        catchUpWindowMinutes: Int = DEFAULT_CATCH_UP_WINDOW_MIN,
    ): DueAutoPushSlot? {
        val slots = activeSlots()
        if (slots.isEmpty()) return null

        val cal = vietnamCalendar(nowEpochMs)
        val todayKey = formatDateKey(cal)
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val window = catchUpWindowMinutes.coerceIn(1, 60)

        // Kiểm tra các khung giờ trong ngày hiện tại
        for (slot in slots) {
            val delta = nowMin - slot.minuteOfDay
            if (delta in 0..window) {
                val execKey = "$todayKey|${slot.timeText}"
                if (execKey !in executedKeys) return DueAutoPushSlot(slot, execKey)
            }
        }

        // Hỗ trợ trường hợp khung giờ tuỳ chọn sát nửa đêm (vd 23:55) và đồng hồ vừa qua 00:0x
        if (nowMin < window) {
            val yesterdayCal = vietnamCalendar(nowEpochMs - 24L * 60 * 60_000L)
            val yesterdayKey = formatDateKey(yesterdayCal)
            for (slot in slots) {
                val delta = (nowMin + 1_440) - slot.minuteOfDay
                if (delta in 0..window) {
                    val execKey = "$yesterdayKey|${slot.timeText}"
                    if (execKey !in executedKeys) return DueAutoPushSlot(slot, execKey)
                }
            }
        }
        return null
    }

    /** Hiển thị mốc giờ kế tiếp gần nhất (vd: `12:00`, `20:00`, `06:00 (ngày mai)`). */
    fun nextSlotText(nowEpochMs: Long, executedKeys: Set<String> = emptySet()): String {
        val slots = activeSlots()
        if (slots.isEmpty()) return "Đang tắt"
        val cal = vietnamCalendar(nowEpochMs)
        val todayKey = formatDateKey(cal)
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val upcomingToday = slots.firstOrNull { slot ->
            val key = "$todayKey|${slot.timeText}"
            slot.minuteOfDay > nowMin || (slot.minuteOfDay == nowMin && key !in executedKeys)
        }
        return upcomingToday?.timeText ?: "${slots.first().timeText} (ngày mai)"
    }

    companion object {
        const val DEFAULT_CUSTOM_HOUR = 17
        const val DEFAULT_CUSTOM_MINUTE = 30
        const val DEFAULT_CATCH_UP_WINDOW_MIN = 15

        val FIXED_SLOTS: List<AutoPushSlot> = listOf(
            AutoPushSlot(hour = 6, minute = 0, isFixed = true),
            AutoPushSlot(hour = 12, minute = 0, isFixed = true),
            AutoPushSlot(hour = 20, minute = 0, isFixed = true),
        )

        private val VN_TZ: TimeZone = TimeZone.getTimeZone("GMT+07:00")

        fun vietnamDateKey(epochMs: Long): String = formatDateKey(vietnamCalendar(epochMs))

        /**
         * Lịch bảo trì dọn log cũ trên máy chấm công định kỳ **Chủ Nhật 02:00–02:15** (giờ VN UTC+7),
         * ngăn tràn bộ nhớ Flash 100.000 bản ghi khi TV Box chạy không người trông nhiều tháng.
         */
        fun findWeeklyMaintenanceKey(
            nowEpochMs: Long,
            executedKeys: Set<String>,
            windowMinutes: Int = DEFAULT_CATCH_UP_WINDOW_MIN,
        ): String? {
            val cal = vietnamCalendar(nowEpochMs)
            if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) return null
            val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val targetMin = WEEKLY_MAINTENANCE_HOUR * 60
            if (nowMin !in targetMin..(targetMin + windowMinutes.coerceIn(1, 45))) return null
            val key = "WEEKLY|${formatDateKey(cal)}"
            return if (key in executedKeys) null else key
        }

        private const val WEEKLY_MAINTENANCE_HOUR = 2

        private fun vietnamCalendar(epochMs: Long): Calendar =
            Calendar.getInstance(VN_TZ).apply { timeInMillis = epochMs }

        private fun formatDateKey(cal: Calendar): String = String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }
}

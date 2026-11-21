package com.edusing.chamcongtvbox.domain

import com.edusing.chamcongtvbox.domain.model.AutoPushScheduleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Kiểm thử bộ lập lịch tự động đẩy nhân sự (cố định 06:00, 12:00, 20:00 + 1 khung giờ tuỳ chọn theo giờ VN UTC+7). */
class AutoPushScheduleTest {

    private fun vnTimeMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("GMT+07:00")).apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun fixedSlots_fireAt0600_1200_2000_andRespectCatchUpAndDedup() {
        val cfg = AutoPushScheduleConfig(enabled = true, customEnabled = false)
        assertEquals(listOf("06:00", "12:00", "20:00"), cfg.activeSlots().map { it.timeText })

        // Đúng 06:00 -> khớp
        val due6 = cfg.findDueSlot(vnTimeMs(2026, 11, 20, 6, 0), emptySet())
        assertNotNull(due6)
        assertEquals("06:00", due6!!.slot.timeText)
        assertEquals("2026-11-20|06:00", due6.executionKey)

        // Sau khi đã chạy 06:00 -> không chạy lặp
        assertNull(cfg.findDueSlot(vnTimeMs(2026, 11, 20, 6, 5), setOf(due6.executionKey)))

        // TV Box vừa cúp điện bật lại lúc 12:08 (trong cửa sổ bù 15 phút) -> vẫn chạy bù mốc 12:00
        val due12 = cfg.findDueSlot(vnTimeMs(2026, 11, 20, 12, 8), setOf(due6.executionKey))
        assertNotNull(due12)
        assertEquals("12:00", due12!!.slot.timeText)

        // Quá cửa sổ 15 phút (12:20) -> không chạy mốc cũ nữa
        assertNull(cfg.findDueSlot(vnTimeMs(2026, 11, 20, 12, 20), emptySet()))

        // Đúng 20:00 -> khớp
        val due20 = cfg.findDueSlot(vnTimeMs(2026, 11, 20, 20, 0), emptySet())
        assertEquals("20:00", due20?.slot?.timeText)
    }

    @Test
    fun customFourthSlot_worksIndependentlyAndDeduplicatesAgainstFixed() {
        val withCustom = AutoPushScheduleConfig(enabled = true, customEnabled = true, customHour = 17, customMinute = 30)
        assertEquals(listOf("06:00", "12:00", "17:30", "20:00"), withCustom.activeSlots().map { it.timeText })

        val dueCustom = withCustom.findDueSlot(vnTimeMs(2026, 11, 20, 17, 30), emptySet())
        assertEquals("17:30", dueCustom?.slot?.timeText)

        // Khi tắt khung giờ phụ -> không kích hoạt lúc 17:30
        val customOff = withCustom.copy(customEnabled = false)
        assertNull(customOff.findDueSlot(vnTimeMs(2026, 11, 20, 17, 30), emptySet()))

        // Nếu người dùng đặt trùng 12:00 -> tự loại trùng, giữ 3 mốc gọn gàng
        val dupCustom = AutoPushScheduleConfig(enabled = true, customEnabled = true, customHour = 12, customMinute = 0)
        assertEquals(listOf("06:00", "12:00", "20:00"), dupCustom.activeSlots().map { it.timeText })
    }

    @Test
    fun nextSlotText_computesUpcomingAndWrapsTomorrow() {
        val cfg = AutoPushScheduleConfig(enabled = true, customEnabled = true, customHour = 15, customMinute = 45)
        assertEquals("06:00", cfg.nextSlotText(vnTimeMs(2026, 11, 20, 5, 30)))
        assertEquals("15:45", cfg.nextSlotText(vnTimeMs(2026, 11, 20, 12, 10)))
        assertEquals("20:00", cfg.nextSlotText(vnTimeMs(2026, 11, 20, 16, 0)))
        assertEquals("06:00 (ngày mai)", cfg.nextSlotText(vnTimeMs(2026, 11, 20, 21, 0)))
        assertEquals("Đang tắt", cfg.copy(enabled = false).nextSlotText(vnTimeMs(2026, 11, 20, 10, 0)))
    }
}

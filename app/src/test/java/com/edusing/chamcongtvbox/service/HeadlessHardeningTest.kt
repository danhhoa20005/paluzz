package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.domain.model.AutoPushScheduleConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.model.ServerCheckInResult
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.usecase.PolledEventItem
import com.edusing.chamcongtvbox.service.web.WebConsoleAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Kiểm thử các cơ chế vận hành mù 24/7 cho TV Box không màn hình: Crash guard, Chủ Nhật 02:00, Reset 00:00 & Web LAN Auth. */
class HeadlessHardeningTest {

    private fun vnTimeMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("GMT+07:00")).apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun crashRecoveryHandler_allowsRestartsAndBlocksCrashLoop() {
        val base = 1_000_000L
        var history = emptyList<Long>()
        repeat(5) { idx ->
            val d = CrashRecoveryHandler.evaluateRestart(history, base + idx * 5_000L)
            assertTrue("Lần crash thứ ${idx + 1} phải cho phép tự hồi sinh", d.allowRestart)
            history = d.updatedTimestamps
        }
        // Lần thứ 6 trong vòng 2 phút -> chặn để chống vòng lặp crash vô tận
        val blocked = CrashRecoveryHandler.evaluateRestart(history, base + 30_000L)
        assertFalse(blocked.allowRestart)

        // Sau hơn 2 phút ổn định -> cho phép hồi sinh lại bình thường
        val cooled = CrashRecoveryHandler.evaluateRestart(history, base + 200_000L)
        assertTrue(cooled.allowRestart)
    }

    @Test
    fun weeklyMaintenance_firesOnlyOnSunday0200To0215AndDeduplicates() {
        // 22/11/2026 là Chủ Nhật
        val sun0205 = vnTimeMs(2026, 11, 22, 2, 5)
        val sun0300 = vnTimeMs(2026, 11, 22, 3, 0)
        val sat0205 = vnTimeMs(2026, 11, 21, 2, 5)

        val key = AutoPushScheduleConfig.findWeeklyMaintenanceKey(sun0205, emptySet())
        assertEquals("WEEKLY|2026-11-22", key)
        assertNull("Đã dọn rồi thì không chạy lại", AutoPushScheduleConfig.findWeeklyMaintenanceKey(sun0205, setOf(key!!)))
        assertNull("Quá 02:15 thì không chạy", AutoPushScheduleConfig.findWeeklyMaintenanceKey(sun0300, emptySet()))
        assertNull("Thứ 7 không được dọn", AutoPushScheduleConfig.findWeeklyMaintenanceKey(sat0205, emptySet()))
    }

    @Test
    fun webConsoleAuth_verifiesPasswordAndManagesTokensSafely() {
        var now = 10_000L
        val auth = WebConsoleAuth(clockMillis = { now })

        // Đã đăng nhập -> yêu cầu đúng mật khẩu tài khoản web tổng
        assertTrue(auth.verifyPassword("MatKhau@123", "MatKhau@123", isLoggedIn = true))
        assertFalse(auth.verifyPassword("1234", "MatKhau@123", isLoggedIn = true))
        // Chưa đăng nhập lần đầu -> chấp nhận mã PIN mặc định 1234
        assertTrue(auth.verifyPassword("1234", "", isLoggedIn = false))

        val token = auth.issueToken()
        assertNotNull(token)
        assertTrue(auth.isTokenValid(token))
        assertFalse(auth.isTokenValid("forged-token"))

        // Hết hạn sau 24 giờ
        now += 25 * 60 * 60_000L
        assertFalse(auth.isTokenValid(token))
    }

    @Test
    fun engineStateStore_resetsTotalCheckInsTodayAtVietnamMidnight() {
        var clock = vnTimeMs(2026, 11, 20, 23, 55)
        val stubRepo = object : AttendanceRepository {
            override suspend fun submitCheckInOut(apiDomain: String, accountId: String, studentCardId: String, device: DeviceConfig) =
                ServerCheckInResult(true, "23:55:00", clock, "OK")
            override suspend fun sendHeartbeat(apiDomain: String, accountId: String, primaryMachineNo: Int, running: Boolean, devices: List<DeviceHeartbeatItem>) = Unit
            override fun lastKnownServerTimeMillis(): Long? = null
        }
        val store = EngineStateStore(stubRepo, clockMillis = { clock })
        store.appendPolledEvents(listOf(PolledEventItem("23:55:00", "May 1", "1001", "An", "OK", EventSeverity.SUCCESS)))
        assertEquals(1, store.current.totalCheckInsToday)
        assertFalse(store.ensureCurrentDay(clock))

        // Sang 00:01 ngày hôm sau (21/11/2026) -> tự reset về 0
        clock = vnTimeMs(2026, 11, 21, 0, 1)
        assertTrue(store.ensureCurrentDay(clock))
        assertEquals(0, store.current.totalCheckInsToday)
    }
}

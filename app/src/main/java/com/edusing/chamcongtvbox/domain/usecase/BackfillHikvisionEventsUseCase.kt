package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.BackfillSummary
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository

/**
 * Đồng bộ bù lịch sử chấm công từ bộ nhớ 100.000 lượt trên máy Hikvision `DS-K1T320MX` lên máy chủ:
 * dùng khi TV Box bị rút điện hoặc mất kết nối dài giờ, đảm bảo không sót lượt quét nào trong ngày.
 */
class BackfillHikvisionEventsUseCase(
    private val hikControl: HikvisionControlGateway,
    private val attendanceRepository: AttendanceRepository,
) {
    suspend operator fun invoke(
        device: DeviceConfig,
        apiDomain: String,
        accountId: String,
        sinceMs: Long,
        untilMs: Long,
        dedupSeconds: Long = 60L,
    ): Result<BackfillSummary> {
        if (!device.isHikvision) {
            return Result.failure(IllegalArgumentException("Tính năng đồng bộ bù lịch sử chỉ áp dụng cho máy Hikvision."))
        }
        val history = hikControl.fetchHistoryEvents(device, sinceMs, untilMs)
            .getOrElse { return Result.failure(it) }
            .sortedBy { it.epochMs }

        if (history.isEmpty()) {
            return Result.success(BackfillSummary(0, 0, 0, 0, "Không có lượt quét hợp lệ nào trong khoảng thời gian đã chọn."))
        }

        val windowMs = dedupSeconds.coerceAtLeast(10L) * 1_000L
        val lastSentByEmp = HashMap<String, Long>()
        var submitted = 0
        var skipped = 0
        var failed = 0

        for (item in history) {
            val prevMs = lastSentByEmp[item.employeeNo]
            if (prevMs != null && (item.epochMs - prevMs) < windowMs) {
                skipped++
                continue
            }
            val res = attendanceRepository.submitCheckInOut(
                apiDomain = apiDomain,
                accountId = accountId,
                studentCardId = item.employeeNo,
                device = device,
            )
            if (res.success) {
                submitted++
                lastSentByEmp[item.employeeNo] = item.epochMs
            } else {
                failed++
                if (res.isAuthError) break
            }
        }

        val msg = "Tìm thấy ${history.size} lượt • Đẩy bù thành công: $submitted • Bỏ qua trùng: $skipped" +
            if (failed > 0) " • Lỗi: $failed" else ""
        return Result.success(BackfillSummary(history.size, submitted, skipped, failed, msg))
    }
}

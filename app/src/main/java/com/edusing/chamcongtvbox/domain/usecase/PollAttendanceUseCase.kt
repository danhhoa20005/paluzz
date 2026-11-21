package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.PendingCheckInStorage
import java.util.concurrent.ConcurrentHashMap

/** Một sự kiện quẹt thẻ sau khi gửi lên máy chủ để hiển thị ra Nhật ký trực tiếp. */
data class PolledEventItem(
    val timeText: String,
    val deviceName: String,
    val cardId: String,
    val userName: String = "",
    val message: String,
    val severity: EventSeverity,
)

/** Kết quả một vòng quét dữ liệu chấm công của một máy. */
data class PollDeviceOutcome(
    val deviceKey: String,
    val needsReconnect: Boolean = false,
    val authError: Boolean = false,
    val events: List<PolledEventItem> = emptyList(),
    val recordsRead: Int = 0,
    val error: String? = null,
)

/**
 * Quét một chu kỳ chấm công cho 1 máy đang kết nối:
 * - Đọc log mới -> dọn bộ đệm -> chống quẹt trùng trong [dedupSeconds] giây -> gửi lên máy chủ.
 * - Chỉ lưu mốc chống trùng khi máy chủ trả thành công (lỗi mạng tạm thời vẫn được gửi lại).
 * - Tự tra cứu và lưu đệm tên người dùng trên máy để hiển thị trực tiếp lên màn hình TV.
 */
class PollAttendanceUseCase(
    private val deviceGateway: FkDeviceGateway,
    private val attendanceRepository: AttendanceRepository,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    pendingStorage: PendingCheckInStorage = PendingCheckInStorage.NoOp,
) {
    private val lastAcceptedByCard = mutableMapOf<String, Long>()
    private val deviceNameCache = ConcurrentHashMap<String, String>()
    private val pendingQueue = PendingCheckInQueue(clockMillis, pendingStorage)

    val pendingCount: Int get() = pendingQueue.size

    fun clearDedupCache() {
        synchronized(lastAcceptedByCard) { lastAcceptedByCard.clear() }
    }

    fun clearNameCache() {
        deviceNameCache.clear()
    }

    fun clearPendingQueue() = pendingQueue.clear()

    /** Xả hàng đợi các lượt chấm công tạm lưu lúc mất mạng lên máy chủ khi Internet có lại. */
    suspend fun flushPendingCheckIns(
        apiDomain: String,
        accountId: String,
        dedupSeconds: Long,
    ): FlushPendingOutcome {
        val dedupWindowMs = dedupSeconds.coerceAtLeast(0L) * 1_000L
        return pendingQueue.flush(apiDomain, accountId, attendanceRepository) { cardId ->
            recordAcceptedTap(cardId, dedupWindowMs)
        }
    }

    suspend operator fun invoke(
        handle: Int,
        device: DeviceConfig,
        apiDomain: String,
        accountId: String,
        dedupSeconds: Long,
    ): PollDeviceOutcome {
        val disableRes = deviceGateway.enableDevice(handle, enable = false)
        if (disableRes.isCommError) {
            return PollDeviceOutcome(device.key, needsReconnect = true, error = disableRes.message)
        }

        val logs: List<AttendanceLog>
        var commFailed = false
        try {
            val loadRes = deviceGateway.loadGeneralLogData(handle, readMark = true)
            if (loadRes.isCommError) {
                commFailed = true
                return PollDeviceOutcome(device.key, needsReconnect = true, error = loadRes.message)
            }
            if (loadRes.code == FkErrorCode.ERR_LOG_END) return PollDeviceOutcome(device.key)
            if (!loadRes.isSuccess) return PollDeviceOutcome(device.key, error = loadRes.message)

            // Khớp PollOne() trong AppMayTinh: chỉ đánh dấu đã đọc (readMark = true -> CMD 0xA2),
            // không xoá flash sau từng lượt quét để tránh treo máy chấm công và giữ lịch sử dự phòng.
            logs = loadRes.data.orEmpty()
        } finally {
            if (!commFailed) deviceGateway.enableDevice(handle, enable = true)
        }

        if (logs.isEmpty()) return PollDeviceOutcome(device.key)

        val events = mutableListOf<PolledEventItem>()
        var authError = false
        val dedupWindowMs = dedupSeconds.coerceAtLeast(0L) * 1_000L

        for (log in logs) {
            val cardId = log.cardIdString
            val nowMs = clockMillis()

            val isDuplicate = synchronized(lastAcceptedByCard) {
                val prev = lastAcceptedByCard[cardId]
                prev != null && (nowMs - prev) < dedupWindowMs
            }
            if (isDuplicate) {
                val userName = lookupCachedUserName(handle, device.key, log.enrollNumber)
                events.add(
                    PolledEventItem(
                        timeText = log.formattedDeviceTime(),
                        deviceName = device.displayName,
                        cardId = cardId,
                        userName = userName,
                        message = "Bỏ qua quẹt trùng trong ${dedupSeconds}s",
                        severity = EventSeverity.WARNING,
                    )
                )
                continue
            }

            // Khớp MainForm.cs: gửi bản tin chấm công lên máy chủ TRƯỚC rồi mới tra tên trên máy,
            // đảm bảo việc đọc tên không bao giờ làm trễ hoặc cản trở gửi bản tin chấm công.
            val serverRes = attendanceRepository.submitCheckInOut(
                apiDomain = apiDomain,
                accountId = accountId,
                studentCardId = cardId,
                device = device,
            )
            val userName = lookupCachedUserName(handle, device.key, log.enrollNumber)

            if (serverRes.success) {
                recordAcceptedTap(cardId, dedupWindowMs)
                events.add(
                    PolledEventItem(
                        timeText = serverRes.serverTimeFormatted,
                        deviceName = device.displayName,
                        cardId = cardId,
                        userName = userName,
                        message = serverRes.message.ifBlank { "Điểm danh thành công" },
                        severity = EventSeverity.SUCCESS,
                    )
                )
            } else if (serverRes.isRetryableNetworkError) {
                pendingQueue.enqueue(cardId, device, userName, dedupWindowMs)
                events.add(
                    PolledEventItem(
                        timeText = log.formattedDeviceTime(),
                        deviceName = device.displayName,
                        cardId = cardId,
                        userName = userName,
                        message = "Tạm lưu đợi mạng (${pendingQueue.size} lượt chờ đẩy web)",
                        severity = EventSeverity.WARNING,
                    )
                )
            } else {
                if (serverRes.isAuthError) authError = true
                events.add(
                    PolledEventItem(
                        timeText = serverRes.serverTimeFormatted,
                        deviceName = device.displayName,
                        cardId = cardId,
                        userName = userName,
                        message = serverRes.message.ifBlank { "Gửi điểm danh thất bại" },
                        severity = EventSeverity.ERROR,
                    )
                )
            }
        }

        return PollDeviceOutcome(
            deviceKey = device.key,
            authError = authError,
            events = events,
            recordsRead = logs.size,
        )
    }

    private suspend fun lookupCachedUserName(handle: Int, deviceKey: String, enrollNo: Int): String {
        val cacheKey = "$deviceKey|$enrollNo"
        deviceNameCache[cacheKey]?.let { return it }
        val fetched = deviceGateway.getUserName(handle, enrollNo).data?.trim().orEmpty()
        if (deviceNameCache.size > 4_000) deviceNameCache.clear()
        deviceNameCache[cacheKey] = fetched
        return fetched
    }

    private fun recordAcceptedTap(cardId: String, dedupWindowMs: Long) {
        synchronized(lastAcceptedByCard) {
            lastAcceptedByCard[cardId] = clockMillis()
            if (lastAcceptedByCard.size > 10_000) {
                val cutoff = clockMillis() - (dedupWindowMs * 2).coerceAtLeast(300_000L)
                lastAcceptedByCard.entries.removeAll { it.value < cutoff }
            }
        }
    }
}

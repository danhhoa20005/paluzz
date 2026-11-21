package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.PendingCheckInStorage
import com.edusing.chamcongtvbox.domain.repository.StoredPendingCheckIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Kết quả xả hàng đợi đẩy bù điểm danh sau khi Internet phục hồi. */
data class FlushPendingOutcome(
    val events: List<PolledEventItem> = emptyList(),
    val authError: Boolean = false,
)

/**
 * Hàng đợi lưu đệm ngoại tuyến (100% pure Kotlin + bền vững qua mất điện nhờ [PendingCheckInStorage]):
 * - Giữ an toàn tối đa 500 lượt chấm công khi rớt Internet tạm thời (chống trùng theo `cardId`).
 * - Tự động khôi phục từ bộ nhớ flash nếu TV Box sập điện giữa lúc rớt mạng WAN.
 * - Tự đẩy bù theo thứ tự FIFO ngay khi Internet có lại; khoá [Mutex] chống đẩy kép đa luồng.
 */
internal class PendingCheckInQueue(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val storage: PendingCheckInStorage = PendingCheckInStorage.NoOp,
) {
    private val items = ArrayDeque<StoredPendingCheckIn>().apply {
        val loaded = runCatching { storage.loadAll() }.getOrDefault(emptyList())
        val now = clockMillis()
        loaded.filter { (now - it.queuedAtMs) <= MAX_AGE_MS }.takeLast(MAX_QUEUE_SIZE).forEach(::addLast)
    }
    private val flushMutex = Mutex()

    val size: Int get() = synchronized(items) { items.size }

    fun clear() {
        synchronized(items) {
            items.clear()
            persistLocked()
        }
    }

    fun enqueue(cardId: String, device: DeviceConfig, userName: String, dedupWindowMs: Long): Boolean {
        val now = clockMillis()
        synchronized(items) {
            pruneExpiredLocked(now)
            val dup = items.any { it.cardId == cardId && (now - it.queuedAtMs) < dedupWindowMs }
            if (dup) return false
            if (items.size >= MAX_QUEUE_SIZE) items.removeFirst()
            items.addLast(StoredPendingCheckIn(cardId, device, userName, now))
            persistLocked()
            return true
        }
    }

    suspend fun flush(
        apiDomain: String,
        accountId: String,
        attendanceRepository: AttendanceRepository,
        onAccepted: (cardId: String) -> Unit,
    ): FlushPendingOutcome = flushMutex.withLock {
        val snapshot = synchronized(items) {
            val before = items.size
            pruneExpiredLocked(clockMillis())
            if (items.size != before) persistLocked()
            items.toList()
        }
        if (snapshot.isEmpty()) return@withLock FlushPendingOutcome()

        val events = mutableListOf<PolledEventItem>()
        var authError = false

        for (rec in snapshot) {
            val res = attendanceRepository.submitCheckInOut(apiDomain, accountId, rec.cardId, rec.device)
            if (res.success) {
                synchronized(items) {
                    items.remove(rec)
                    persistLocked()
                }
                onAccepted(rec.cardId)
                events.add(
                    PolledEventItem(
                        timeText = res.serverTimeFormatted,
                        deviceName = rec.device.displayName,
                        cardId = rec.cardId,
                        userName = rec.userName,
                        message = "Đẩy bù thành công: ${res.message.ifBlank { "Điểm danh OK" }}",
                        severity = EventSeverity.SUCCESS,
                    )
                )
            } else if (res.isRetryableNetworkError) {
                break
            } else {
                synchronized(items) {
                    items.remove(rec)
                    persistLocked()
                }
                if (res.isAuthError) authError = true
                events.add(
                    PolledEventItem(
                        timeText = res.serverTimeFormatted,
                        deviceName = rec.device.displayName,
                        cardId = rec.cardId,
                        userName = rec.userName,
                        message = res.message.ifBlank { "Đẩy bù thất bại" },
                        severity = EventSeverity.ERROR,
                    )
                )
            }
        }
        FlushPendingOutcome(events = events, authError = authError)
    }

    private fun pruneExpiredLocked(nowMs: Long) {
        while (items.isNotEmpty() && (nowMs - items.first().queuedAtMs) > MAX_AGE_MS) {
            items.removeFirst()
        }
    }

    private fun persistLocked() {
        runCatching { storage.saveAll(items.toList()) }
    }

    companion object {
        private const val MAX_QUEUE_SIZE = 500
        private const val MAX_AGE_MS = 24 * 60 * 60_000L
    }
}

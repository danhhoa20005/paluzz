package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.data.remote.ServerTimeParser
import com.edusing.chamcongtvbox.domain.model.AutoPushScheduleConfig
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.AutoPushMembersUseCase
import com.edusing.chamcongtvbox.domain.usecase.ClearDeviceLogsUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectedDeviceHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bộ lập lịch tự động đẩy danh sách nhân sự/học sinh xuống tất cả máy chấm công theo giờ cố định:
 * - Cố định hằng ngày: **06:00**, **12:00**, **20:00** (giờ Việt Nam UTC+7 lấy từ máy chủ).
 * - Cộng thêm **1 khung giờ tuỳ chọn** do người dùng cài đặt trong tab Cấu hình.
 * - Dùng [deviceIoMutex] đồng bộ với vòng quét chấm công 24/7 để không bao giờ đụng gói tin FK.
 * - Tự bù trong cửa sổ 15 phút nếu TV Box vừa cúp điện đúng giờ và tự dọn khoá ngày cũ chống rò bộ nhớ.
 */
internal class AutoPushScheduler(
    private val scope: CoroutineScope,
    private val configRepository: ConfigRepository,
    private val attendanceRepository: AttendanceRepository,
    private val autoPushMembersUseCase: AutoPushMembersUseCase?,
    private val clearDeviceLogsUseCase: ClearDeviceLogsUseCase,
    private val stateStore: EngineStateStore,
    private val deviceIoMutex: Mutex,
    private val getConnectedHandles: () -> List<ConnectedDeviceHandle>,
    private val getPendingCheckInCount: () -> Int,
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
) {
    private var schedulerJob: Job? = null
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)
    private val runMutex = Mutex()
    private val executedSlotKeys = mutableSetOf<String>()

    fun ensureStarted() {
        if (autoPushMembersUseCase == null) return
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            while (isActive) {
                try {
                    checkAndRunDueSlot()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    stateStore.appendSystemEvent(
                        "Đẩy tự động",
                        "-",
                        "Lỗi kiểm tra lịch đẩy: ${t.message ?: t.javaClass.simpleName}",
                        EventSeverity.WARNING,
                    )
                }
                withTimeoutOrNull(checkIntervalMs) { wakeChannel.receive() }
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    fun wakeNow() {
        wakeChannel.trySend(Unit)
    }

    fun resetExecutedForModeSwitch() {
        synchronized(executedSlotKeys) { executedSlotKeys.clear() }
        stateStore.update { it.copy(lastAutoPushText = "") }
        wakeNow()
    }

    /** Cho phép người dùng bấm "Đẩy tự động ngay" trong Cấu hình để đồng bộ tức thì các nhân sự mới. */
    suspend fun runNow(triggerLabel: String = "Thủ công"): Boolean {
        return executeSync(slotLabel = triggerLabel, executionKey = null)
    }

    private suspend fun checkAndRunDueSlot() {
        val cfg = configRepository.getConfig()
        if (!cfg.isLoggedIn) return
        val snap = stateStore.current
        if (!snap.isInternetOnline || snap.isDiscovering || snap.isBusy) return

        val nowMs = currentVietnamEpochMs()
        pruneOldKeys(nowMs)
        val snapshotKeys = synchronized(executedSlotKeys) { executedSlotKeys.toSet() }

        // 1. Bảo trì định kỳ 02:00 sáng Chủ Nhật: dọn log cũ tránh tràn Flash máy chấm công (chỉ chạy khi hàng đợi bù = 0)
        val weeklyKey = AutoPushScheduleConfig.findWeeklyMaintenanceKey(nowMs, snapshotKeys)
        if (weeklyKey != null && getPendingCheckInCount() == 0 && getConnectedHandles().isNotEmpty()) {
            runWeeklyFlashMaintenance(weeklyKey)
        }

        // 2. Đẩy nhân sự tự động theo khung giờ cố định (06:00, 12:00, 20:00 + khung phụ)
        if (!cfg.autoPushEnabled) return
        val due = cfg.autoPushSchedule.findDueSlot(nowMs, snapshotKeys) ?: return
        if (getConnectedHandles().isEmpty()) return

        executeSync(slotLabel = due.slot.timeText, executionKey = due.executionKey)
    }

    private suspend fun runWeeklyFlashMaintenance(weeklyKey: String) {
        runMutex.withLock {
            val targets = getConnectedHandles()
            if (targets.isEmpty() || getPendingCheckInCount() > 0) return@withLock
            var cleared = 0
            deviceIoMutex.withLock {
                for (t in targets) {
                    if (clearDeviceLogsUseCase(t.handle).isSuccess) cleared++
                }
            }
            if (cleared > 0) {
                synchronized(executedSlotKeys) { executedSlotKeys.add(weeklyKey) }
                stateStore.appendSystemEvent(
                    "Bảo trì",
                    "-",
                    "Bảo trì Chủ Nhật 02:00: đã dọn sạch bộ nhớ log trên $cleared/${targets.size} máy chấm công.",
                    EventSeverity.SUCCESS,
                )
            }
        }
    }

    private suspend fun executeSync(slotLabel: String, executionKey: String?): Boolean {
        val useCase = autoPushMembersUseCase ?: return false
        return runMutex.withLock {
            val targets = getConnectedHandles()
            if (targets.isEmpty()) {
                if (executionKey == null) {
                    stateStore.appendSystemEvent("Đẩy tự động", "-", "Chưa có máy chấm công nào kết nối để đẩy.", EventSeverity.WARNING)
                }
                return@withLock false
            }

            val personLower = configRepository.getBranding().personLower
            stateStore.update {
                it.copy(
                    isAutoPushing = true,
                    statusHint = "Đang tự động đồng bộ $personLower ($slotLabel)...",
                )
            }
            try {
                val outcome = deviceIoMutex.withLock { useCase(targets) }
                val timeShort = ServerTimeParser.formatVietnamTime(currentVietnamEpochMs()).substringBefore(" ")
                if (outcome.success) {
                    if (executionKey != null) synchronized(executedSlotKeys) { executedSlotKeys.add(executionKey) }
                    val sum = outcome.summary
                    val added = sum?.succeeded ?: 0
                    val skipped = sum?.skippedExisting ?: 0
                    val msg = when {
                        outcome.totalMembersOnServer == 0 -> "Lịch $slotLabel: chưa có $personLower nào trên web."
                        added > 0 -> "Lịch $slotLabel: đẩy mới +$added $personLower (đã có $skipped) xuống ${targets.size} máy."
                        else -> "Lịch $slotLabel: đã đồng bộ đủ ${outcome.totalMembersOnServer} $personLower trên ${targets.size} máy."
                    }
                    val badge = if (added > 0) "$timeShort • +$added mới" else "$timeShort • Đã đủ (${outcome.totalMembersOnServer})"
                    stateStore.update { it.copy(lastAutoPushText = badge) }
                    stateStore.appendSystemEvent("Đẩy tự động", "-", msg, EventSeverity.SUCCESS)
                    true
                } else {
                    val err = outcome.error ?: "Lỗi đồng bộ không xác định"
                    stateStore.appendSystemEvent("Đẩy tự động", "-", "Lịch $slotLabel chưa hoàn tất: $err", EventSeverity.WARNING)
                    false
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                stateStore.appendSystemEvent("Đẩy tự động", "-", "Lỗi đồng bộ ($slotLabel): ${t.message ?: t.javaClass.simpleName}", EventSeverity.ERROR)
                false
            } finally {
                stateStore.update { it.copy(isAutoPushing = false, statusHint = if (it.statusHint.startsWith("Đang tự động đồng bộ")) "" else it.statusHint) }
            }
        }
    }

    private fun currentVietnamEpochMs(): Long =
        attendanceRepository.lastKnownServerTimeMillis() ?: System.currentTimeMillis()

    private fun pruneOldKeys(nowMs: Long) {
        val today = AutoPushScheduleConfig.vietnamDateKey(nowMs)
        val yesterday = AutoPushScheduleConfig.vietnamDateKey(nowMs - 24L * 60 * 60_000L)
        synchronized(executedSlotKeys) {
            executedSlotKeys.removeAll { key -> !key.contains(today) && !key.contains(yesterday) }
        }
    }

    companion object {
        const val DEFAULT_CHECK_INTERVAL_MS = 30_000L
    }
}

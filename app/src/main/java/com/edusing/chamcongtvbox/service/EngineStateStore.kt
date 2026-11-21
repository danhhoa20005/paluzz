package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.data.remote.ServerTimeParser
import com.edusing.chamcongtvbox.domain.model.AutoPushScheduleConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceRuntimeStatus
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice
import com.edusing.chamcongtvbox.domain.model.LiveEventEntry
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.InternetStatus
import com.edusing.chamcongtvbox.domain.usecase.PolledEventItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Trạng thái tổng hợp thời gian thực chia sẻ giữa Service nền, Web quản trị LAN và giao diện MVVM. */
data class EngineSnapshot(
    val isRunning: Boolean = false,
    val isBusy: Boolean = false,
    val isDiscovering: Boolean = false,
    val isAutoPushing: Boolean = false,
    val statusHint: String = "",
    val lastAutoPushText: String = "",
    val webConsoleUrl: String = "",
    val pendingCheckInCount: Int = 0,
    val internetStatus: InternetStatus = InternetStatus.CHECKING,
    val internetDetail: String = "",
    val hasLanOrWifi: Boolean = true,
    val wifiHardwareDisabled: Boolean = false,
    val devices: List<DeviceRuntimeStatus> = emptyList(),
    val unpairedHikvisionDevices: List<HikvisionSadpDevice> = emptyList(),
    val events: List<LiveEventEntry> = emptyList(),
    val lastPollTimeText: String = "",
    val totalCheckInsToday: Int = 0,
) {
    val connectedCount: Int get() = devices.count { it.isConnected }
    val totalDeviceCount: Int get() = devices.size
    val isInternetOnline: Boolean
        get() = internetStatus == InternetStatus.ONLINE || internetStatus == InternetStatus.RECOVERING
}

/**
 * Kho giữ trạng thái [EngineSnapshot] và hàng đợi nhật ký trực tiếp cho [AttendanceEngine].
 * Tự động reset bộ đếm `totalCheckInsToday` về 0 khi bước sang 00:00 ngày mới (giờ Việt Nam UTC+7).
 */
internal class EngineStateStore(
    private val attendanceRepository: AttendanceRepository,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val eventIdGen = AtomicLong(1L)
    private val _state = MutableStateFlow(EngineSnapshot())
    val state: StateFlow<EngineSnapshot> = _state.asStateFlow()

    @Volatile
    private var activeVietnamDateKey: String = AutoPushScheduleConfig.vietnamDateKey(currentNowMs())

    val current: EngineSnapshot get() = _state.value

    fun update(transform: (EngineSnapshot) -> EngineSnapshot) {
        _state.update(transform)
    }

    /** Kiểm tra chuyển ngày 00:00 giờ VN; nếu sang ngày mới tự reset bộ đếm hôm nay và trả `true`. */
    fun ensureCurrentDay(nowMs: Long = currentNowMs()): Boolean {
        val todayKey = AutoPushScheduleConfig.vietnamDateKey(nowMs)
        if (todayKey == activeVietnamDateKey) return false
        activeVietnamDateKey = todayKey
        _state.update { it.copy(totalCheckInsToday = 0) }
        return true
    }

    fun syncConfiguredDevices(configured: List<DeviceConfig>) {
        _state.update { snap ->
            val oldByKey = snap.devices.associateBy { it.config.key }
            val merged = configured.map { cfg ->
                oldByKey[cfg.key]?.copy(config = cfg) ?: DeviceRuntimeStatus(config = cfg)
            }
            val configuredIps = configured.map { it.ip }.toSet()
            snap.copy(
                devices = merged,
                unpairedHikvisionDevices = snap.unpairedHikvisionDevices.filterNot { it.ip in configuredIps },
            )
        }
    }

    fun updateDevice(deviceKey: String, transform: (DeviceRuntimeStatus) -> DeviceRuntimeStatus) {
        _state.update { snap ->
            snap.copy(
                devices = snap.devices.map { status ->
                    if (status.config.key == deviceKey) transform(status) else status
                }
            )
        }
    }

    fun appendSystemEvent(deviceName: String, cardId: String, message: String, severity: EventSeverity) {
        val nowMs = currentNowMs()
        ensureCurrentDay(nowMs)
        val entry = LiveEventEntry(
            id = eventIdGen.getAndIncrement(),
            timeText = ServerTimeParser.formatVietnamTime(nowMs),
            deviceName = deviceName,
            cardId = cardId,
            message = message,
            severity = severity,
        )
        _state.update { snap ->
            snap.copy(events = (listOf(entry) + snap.events).take(MAX_LOG_ENTRIES))
        }
    }

    fun appendPolledEvents(items: List<PolledEventItem>) {
        if (items.isEmpty()) return
        ensureCurrentDay()
        val successAdd = items.count { it.severity == EventSeverity.SUCCESS }
        val mapped = items.map { ev ->
            LiveEventEntry(
                id = eventIdGen.getAndIncrement(),
                timeText = ev.timeText,
                deviceName = ev.deviceName,
                cardId = ev.cardId,
                userName = ev.userName,
                message = ev.message,
                severity = ev.severity,
            )
        }
        _state.update { snap ->
            snap.copy(
                events = (mapped.reversed() + snap.events).take(MAX_LOG_ENTRIES),
                totalCheckInsToday = snap.totalCheckInsToday + successAdd,
            )
        }
    }

    fun clearEvents() {
        _state.update { it.copy(events = emptyList()) }
    }

    private fun currentNowMs(): Long = attendanceRepository.lastKnownServerTimeMillis() ?: clockMillis()

    companion object {
        private const val MAX_LOG_ENTRIES = 300
    }
}

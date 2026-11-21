package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.data.remote.ServerTimeParser
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.ClearDeviceLogsUseCase
import com.edusing.chamcongtvbox.domain.usecase.CredentialCheckStatus
import com.edusing.chamcongtvbox.domain.usecase.PollAttendanceUseCase
import com.edusing.chamcongtvbox.domain.usecase.SendHeartbeatUseCase
import com.edusing.chamcongtvbox.domain.usecase.ValidateCredentialsUseCase
import com.edusing.chamcongtvbox.service.web.LocalWebConsoleServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Quản lý các vòng lặp nền 24/7 tự phục hồi cho TV Box chạy không màn hình:
 * 1. Quét điểm danh định kỳ + tự xả hàng đợi ngoại tuyến (đồng bộ khoá `deviceIoMutex`).
 * 2. Tự nối máy offline (giãn nhịp backoff), phát hiện treo vòng quét > 3 phút & reset bộ đệm lúc 00:00.
 * 3. Nhịp báo sống Heartbeat đính kèm chẩn đoán (URL Web quản trị LAN, hàng đợi bù, lịch đẩy gần nhất).
 * 4. Dọn log tồn khi dừng + tự phát hiện socket chết (`-3`) & kiểm tra phiên đăng nhập mỗi 5 phút.
 */
internal class AttendanceLoopRunner(
    private val scope: CoroutineScope,
    private val configRepository: ConfigRepository,
    private val attendanceRepository: AttendanceRepository,
    private val stateStore: EngineStateStore,
    private val clearDeviceLogsUseCase: ClearDeviceLogsUseCase,
    private val pollAttendanceUseCase: PollAttendanceUseCase,
    private val sendHeartbeatUseCase: SendHeartbeatUseCase,
    private val validateCredentialsUseCase: ValidateCredentialsUseCase,
    private val deviceIoMutex: Mutex,
    private val getHandle: (String) -> Int?,
    private val reconnectOfflineDevice: suspend (DeviceConfig, Int, Boolean) -> Boolean,
    private val reconnectAfterCommError: suspend (DeviceConfig) -> Boolean,
    private val onAutoResumeNeeded: suspend () -> Unit,
    private val onAutoDiscoverNeeded: suspend () -> Unit,
    private val onPollFreezeDetected: suspend () -> Unit,
    private val onAuthInvalid: suspend (String) -> Unit,
    private val onNewDayTasks: suspend () -> Unit = {},
) {
    private var pollJob: Job? = null
    private var autoReconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var idleCleanJob: Job? = null
    private var authMonitorJob: Job? = null
    private var zeroDeviceCycles = 0
    private var runningOfflineCycles = 0
    private val lastPollHeartbeatMs = AtomicLong(0L)
    private val offlineBackoffByKey = mutableMapOf<String, Int>()

    fun resetOfflineBackoff() = synchronized(offlineBackoffByKey) { offlineBackoffByKey.clear() }

    suspend fun flushPendingQueueNow() {
        val cfg = configRepository.getConfig()
        stateStore.update { it.copy(pendingCheckInCount = pollAttendanceUseCase.pendingCount) }
        if (!cfg.isLoggedIn || pollAttendanceUseCase.pendingCount == 0 || !stateStore.current.isInternetOnline) return
        val domain = configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)
        val outcome = pollAttendanceUseCase.flushPendingCheckIns(domain, cfg.accountId, cfg.dedupSeconds)
        stateStore.update { it.copy(pendingCheckInCount = pollAttendanceUseCase.pendingCount) }
        stateStore.appendPolledEvents(outcome.events)
        if (outcome.authError) onAuthInvalid("Phiên đăng nhập hết hiệu lực (401/403). Vui lòng đăng nhập lại.")
    }

    fun ensureBackgroundMonitorsStarted() {
        if (heartbeatJob?.isActive != true) {
            heartbeatJob = launchPeriodic(false, { configRepository.getConfig().heartbeatIntervalMs.coerceAtLeast(3_000L) }) { sendHeartbeatNow() }
        }
        if (autoReconnectJob?.isActive != true) {
            autoReconnectJob = launchPeriodic(true, { AUTO_RECONNECT_INTERVAL_MS }) {
                if (stateStore.ensureCurrentDay()) {
                    pollAttendanceUseCase.clearNameCache(); runSafe { onNewDayTasks() }
                    stateStore.appendSystemEvent("Hệ thống", "-", "Bước sang ngày mới — đã làm mới bộ đếm & đồng bộ giờ máy.", EventSeverity.INFO)
                }
                val snap = stateStore.current
                val cfg = configRepository.getConfig()
                if (cfg.isLoggedIn && snap.isRunning && snap.connectedCount > 0 && !snap.isAutoPushing && !snap.isBusy) {
                    val lastTick = lastPollHeartbeatMs.get()
                    if (lastTick > 0L && (System.currentTimeMillis() - lastTick) > POLL_FREEZE_THRESHOLD_MS) {
                        lastPollHeartbeatMs.set(System.currentTimeMillis())
                        stateStore.appendSystemEvent("Hệ thống", "-", "Phát hiện vòng quét treo > 3 phút — tự nối lại máy chấm công...", EventSeverity.WARNING)
                        onPollFreezeDetected()
                        return@launchPeriodic
                    }
                }
                if (cfg.isLoggedIn && !snap.isRunning && !snap.isDiscovering && !snap.isBusy) {
                    recoverIdleDevicesOrDiscover(cfg.devices, cfg.autoStartRunning)
                }
            }
        }
        if (idleCleanJob?.isActive != true) {
            idleCleanJob = launchPeriodic(true, { configRepository.getConfig().idleCleanIntervalMs.coerceAtLeast(30_000L) }) {
                val snap = stateStore.current
                if (!snap.isRunning && !snap.isBusy && !snap.isDiscovering && configRepository.getConfig().isLoggedIn) drainLogsWhileStopped()
            }
        }
        if (authMonitorJob?.isActive != true) {
            authMonitorJob = launchPeriodic(true, { AUTH_MONITOR_INTERVAL_MS }) { checkCredentialsNow() }
        }
    }

    fun startPollLoop() {
        pollJob?.cancel()
        lastPollHeartbeatMs.set(System.currentTimeMillis())
        pollJob = scope.launch {
            while (isActive && stateStore.current.isRunning) {
                lastPollHeartbeatMs.set(System.currentTimeMillis())
                runSafe { runOnePollCycle() }
                lastPollHeartbeatMs.set(System.currentTimeMillis())
                delay(configRepository.getConfig().pollIntervalMs.coerceAtLeast(3_000L))
            }
        }
    }

    fun stopPollLoop() { pollJob?.cancel(); pollJob = null; lastPollHeartbeatMs.set(0L) }
    fun triggerHeartbeatAsync() { scope.launch { runSafe { sendHeartbeatNow() } } }
    fun refreshCredentialsSilentlyAsync() { scope.launch { runSafe { checkCredentialsNow() } } }

    private suspend fun checkCredentialsNow() {
        if (configRepository.getConfig().isLoggedIn && stateStore.current.isInternetOnline &&
            validateCredentialsUseCase() == CredentialCheckStatus.INVALID_CREDENTIALS
        ) {
            onAuthInvalid("Tài khoản hoặc mật khẩu đã thay đổi trên hệ thống. Vui lòng đăng nhập lại.")
        }
    }

    private fun launchPeriodic(delayFirst: Boolean, intervalMs: () -> Long, action: suspend () -> Unit): Job = scope.launch {
        while (isActive) {
            if (delayFirst) delay(intervalMs())
            runSafe { action() }
            if (!delayFirst) delay(intervalMs())
        }
    }

    private suspend fun sendHeartbeatNow() {
        val cfg = configRepository.getConfig(); val snap = stateStore.current
        if (!cfg.isLoggedIn || !snap.isInternetOnline) return
        val parts = buildList {
            if (snap.webConsoleUrl.isNotBlank()) {
                val lanIp = LocalWebConsoleServer.detectLanIpv4(); val host = snap.webConsoleUrl.removePrefix("http://")
                add(if (lanIp.isNotBlank()) "Web:$host ($lanIp)" else "Web:$host")
            }
            if (pollAttendanceUseCase.pendingCount > 0) add("Chờ bù:${pollAttendanceUseCase.pendingCount}")
            if (snap.lastAutoPushText.isNotBlank()) add("Đẩy:${snap.lastAutoPushText}")
        }
        sendHeartbeatUseCase(configRepository.getBranding().normalizeApiDomain(cfg.apiDomain), cfg.accountId, snap.isRunning, snap.devices, parts.joinToString(" | "))
    }

    private suspend fun recoverIdleDevicesOrDiscover(devices: List<DeviceConfig>, autoResume: Boolean) {
        if (devices.isNotEmpty()) {
            var anyReconnected = false
            for (device in devices) {
                val snap = stateStore.current
                if (snap.isRunning || snap.isDiscovering || snap.isBusy) return
                if (getHandle(device.key) != null) continue
                if (reconnectOfflineDevice(device, 1, true)) anyReconnected = true
            }
            if (anyReconnected) {
                zeroDeviceCycles = 0; sendHeartbeatNow()
                if (autoResume && !stateStore.current.isRunning && stateStore.current.connectedCount > 0) onAutoResumeNeeded()
                return
            }
        }
        val snap = stateStore.current
        if (autoResume && snap.isInternetOnline && snap.hasLanOrWifi && snap.connectedCount == 0) {
            if (++zeroDeviceCycles >= 2) { zeroDeviceCycles = 0; onAutoDiscoverNeeded() }
        } else {
            zeroDeviceCycles = 0
        }
    }

    private suspend fun runOnePollCycle() {
        val cfg = configRepository.getConfig()
        if (!cfg.isLoggedIn) return
        val domain = configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)

        if (pollAttendanceUseCase.pendingCount > 0 && stateStore.current.isInternetOnline) flushPendingQueueNow()

        for (device in cfg.devices) {
            if (!stateStore.current.isRunning) break
            val handle = getHandle(device.key)
            if (handle == null) { tryReconnectOfflineWithBackoff(device); continue }
            synchronized(offlineBackoffByKey) { offlineBackoffByKey.remove(device.key) }
            val outcome = deviceIoMutex.withLock { pollAttendanceUseCase(handle, device, domain, cfg.accountId, cfg.dedupSeconds) }
            stateStore.update { it.copy(pendingCheckInCount = pollAttendanceUseCase.pendingCount) }
            if (outcome.needsReconnect) {
                stateStore.appendSystemEvent(device.displayName, "-", "Mất tín hiệu (-3), đang nối lại...", EventSeverity.WARNING)
                reconnectAfterCommError(device); sendHeartbeatNow(); continue
            }
            if (!outcome.error.isNullOrBlank() && outcome.events.isEmpty()) {
                stateStore.appendSystemEvent(device.displayName, "-", "Tải log lỗi: ${outcome.error}", EventSeverity.WARNING)
            }
            stateStore.appendPolledEvents(outcome.events)
            val nowMs = attendanceRepository.lastKnownServerTimeMillis() ?: System.currentTimeMillis()
            stateStore.updateDevice(device.key) { it.copy(lastPollAtMs = nowMs) }
            stateStore.update { it.copy(lastPollTimeText = ServerTimeParser.formatVietnamTime(nowMs)) }
            if (outcome.authError) { onAuthInvalid("Phiên đăng nhập hết hiệu lực (401/403). Vui lòng đăng nhập lại."); return }
        }

        val snap = stateStore.current
        if (snap.isRunning && snap.connectedCount == 0 && snap.isInternetOnline && snap.hasLanOrWifi) {
            if (++runningOfflineCycles >= 2) { runningOfflineCycles = 0; onAutoDiscoverNeeded() }
        } else {
            runningOfflineCycles = 0
        }
    }

    private suspend fun tryReconnectOfflineWithBackoff(device: DeviceConfig) {
        val skipLeft = synchronized(offlineBackoffByKey) { offlineBackoffByKey[device.key] ?: 0 }
        if (skipLeft > 0 && stateStore.current.connectedCount > 0) {
            synchronized(offlineBackoffByKey) { offlineBackoffByKey[device.key] = skipLeft - 1 }; return
        }
        if (reconnectOfflineDevice(device, 1, true)) {
            synchronized(offlineBackoffByKey) { offlineBackoffByKey.remove(device.key) }; sendHeartbeatNow()
        } else {
            synchronized(offlineBackoffByKey) { offlineBackoffByKey[device.key] = OFFLINE_BACKOFF_CYCLES }
        }
    }

    private suspend fun drainLogsWhileStopped() {
        for (device in configRepository.getConfig().devices) {
            val snap = stateStore.current
            if (snap.isRunning || snap.isDiscovering || snap.isBusy) return
            val handle = getHandle(device.key) ?: continue
            if (clearDeviceLogsUseCase(handle).isCommError) {
                stateStore.appendSystemEvent(device.displayName, "-", "Mất tín hiệu (-3), đang tự nối lại...", EventSeverity.WARNING)
                reconnectAfterCommError(device); sendHeartbeatNow()
            }
        }
    }

    private suspend inline fun runSafe(block: () -> Unit) {
        try { block() } catch (ce: CancellationException) { throw ce } catch (t: Throwable) {
            stateStore.appendSystemEvent("Hệ thống", "-", "Lỗi vòng nền: ${t.message ?: t.javaClass.simpleName}", EventSeverity.ERROR)
        }
    }
    companion object {
        private const val AUTO_RECONNECT_INTERVAL_MS = 8_000L; private const val AUTH_MONITOR_INTERVAL_MS = 5 * 60_000L
        private const val POLL_FREEZE_THRESHOLD_MS = 180_000L; private const val OFFLINE_BACKOFF_CYCLES = 4
    }
}

package com.edusing.chamcongtvbox.service

import android.util.Log
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.InternetCheckResult
import com.edusing.chamcongtvbox.domain.repository.InternetProbe
import com.edusing.chamcongtvbox.domain.usecase.AutoPushMembersUseCase
import com.edusing.chamcongtvbox.domain.usecase.ClearDeviceLogsUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectDeviceUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectedDeviceHandle
import com.edusing.chamcongtvbox.domain.usecase.DiscoverDevicesUseCase
import com.edusing.chamcongtvbox.domain.usecase.PollAttendanceUseCase
import com.edusing.chamcongtvbox.domain.usecase.SendHeartbeatUseCase
import com.edusing.chamcongtvbox.domain.usecase.ValidateCredentialsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bộ điều phối chấm công tự động 24/7 dùng chung giữa [AttendanceForegroundService] và tầng ViewModel:
 * - Giám sát Internet & Wi-Fi/LAN liên tục; tự nối lại mạng; tự xả hàng đợi ngoại tuyến; tự quét LAN → nối máy → chờ quét 24/7.
 * - Tích hợp lịch tự động đẩy nhân sự xuống máy (06:00, 12:00, 20:00 + 1 khung giờ tuỳ chọn) đồng bộ khoá `deviceIoMutex`.
 */
class AttendanceEngine(
    private val configRepository: ConfigRepository,
    private val attendanceRepository: AttendanceRepository,
    deviceGateway: FkDeviceGateway,
    connectDeviceUseCase: ConnectDeviceUseCase,
    private val clearDeviceLogsUseCase: ClearDeviceLogsUseCase,
    private val pollAttendanceUseCase: PollAttendanceUseCase,
    discoverDevicesUseCase: DiscoverDevicesUseCase,
    sendHeartbeatUseCase: SendHeartbeatUseCase,
    validateCredentialsUseCase: ValidateCredentialsUseCase,
    internetProbe: InternetProbe = AlwaysOnlineProbe,
    autoPushMembersUseCase: AutoPushMembersUseCase? = null,
    onlineCheckIntervalMs: Long = InternetWatchdog.DEFAULT_ONLINE_INTERVAL_MS,
    offlineCheckIntervalMs: Long = InternetWatchdog.DEFAULT_OFFLINE_INTERVAL_MS,
    dhcpSettleDelayMs: Long = DeviceDiscoveryCoordinator.DEFAULT_DHCP_SETTLE_MS,
    private val onNewDaySync: suspend (List<DeviceConfig>) -> Unit = {},
) {
    private val stateStore = EngineStateStore(attendanceRepository)
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        runCatching { Log.e(TAG, "Lỗi không lường trước trong AttendanceEngine", t) }
        stateStore.appendSystemEvent("Hệ thống", "-", "Lỗi hệ thống: ${t.message ?: t.javaClass.simpleName}", EventSeverity.ERROR)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val connManager = DeviceConnectionManager(configRepository, deviceGateway, connectDeviceUseCase, clearDeviceLogsUseCase, stateStore)
    private val startMutex = Mutex()
    private val deviceIoMutex = Mutex()

    val state: StateFlow<EngineSnapshot> = stateStore.state
    private val _forceRelogin = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val forceRelogin: SharedFlow<String> = _forceRelogin.asSharedFlow()

    private val loopRunner: AttendanceLoopRunner = AttendanceLoopRunner(
        scope, configRepository, attendanceRepository, stateStore,
        clearDeviceLogsUseCase, pollAttendanceUseCase, sendHeartbeatUseCase, validateCredentialsUseCase, deviceIoMutex,
        getHandle = connManager::getHandle,
        reconnectOfflineDevice = { dev, tries, silent -> connManager.connectIfOffline(dev, tries, silent) },
        reconnectAfterCommError = { dev -> connManager.reconnect(dev, maxAttempts = 2, waitMs = 1_200L) },
        onAutoResumeNeeded = { startAttendance() },
        onAutoDiscoverNeeded = { autoDiscoverConnectAndReady(fromInternetRecovery = false) },
        onPollFreezeDetected = { recoverFromPollFreeze() },
        onAuthInvalid = ::handleForceRelogin,
        onNewDayTasks = { onNewDaySync(configRepository.getConfig().devices) },
        onDeviceAuthLocked = { dev, msg -> connManager.markAuthLocked(dev, msg) },
    )

    private val discoveryCoordinator: DeviceDiscoveryCoordinator = DeviceDiscoveryCoordinator(
        configRepository, discoverDevicesUseCase, internetProbe, connManager, stateStore,
        onTriggerHeartbeat = { loopRunner.triggerHeartbeatAsync() },
        onPausePollingForRecovery = { loopRunner.stopPollLoop(); stateStore.update { it.copy(isRunning = false) } },
        onConnectAll = { autoResume -> connectAllDevices(autoResume) }, onStartAttendance = { startAttendance() }, dhcpSettleDelayMs = dhcpSettleDelayMs,
    )
    private val autoPushScheduler: AutoPushScheduler = AutoPushScheduler(
        scope, configRepository, attendanceRepository, autoPushMembersUseCase, clearDeviceLogsUseCase, stateStore, deviceIoMutex,
        getConnectedHandles = connManager::getConnectedHandles, getPendingCheckInCount = { pollAttendanceUseCase.pendingCount },
    )

    private val internetWatchdog: InternetWatchdog = InternetWatchdog(
        scope, configRepository, internetProbe, stateStore,
        onLanDropped = { handleLanDropped() },
        onInternetReady = { fromOutage ->
            attendanceRepository.resetNetworkConnections(); loopRunner.resetOfflineBackoff()
            loopRunner.refreshCredentialsSilentlyAsync(); loopRunner.flushPendingQueueNow()
            autoDiscoverConnectAndReady(fromInternetRecovery = fromOutage)
            autoPushScheduler.wakeNow()
        },
        onlineCheckIntervalMs = onlineCheckIntervalMs,
        offlineCheckIntervalMs = offlineCheckIntervalMs,
    )

    init {
        stateStore.syncConfiguredDevices(configRepository.getConfig().devices)
        scope.launch { configRepository.configFlow.collect { stateStore.syncConfiguredDevices(it.devices) } }
    }
    fun startBackgroundMonitors() { loopRunner.ensureBackgroundMonitorsStarted(); internetWatchdog.ensureStarted(); autoPushScheduler.ensureStarted() }
    fun getConnectedHandles(): List<ConnectedDeviceHandle> = connManager.getConnectedHandles()
    fun currentServerTimeMillis(): Long = attendanceRepository.lastKnownServerTimeMillis() ?: System.currentTimeMillis()
    suspend fun <T> withDeviceIoLock(block: suspend () -> T): T = deviceIoMutex.withLock { block() }
    suspend fun triggerAutoPushNow(): Boolean = withContext(Dispatchers.IO) { autoPushScheduler.runNow("Thủ công") }
    fun notifyAutoPushScheduleChanged() = autoPushScheduler.wakeNow()
    fun wakePollLoopNow() = loopRunner.wakePollNow()
    fun updateWebConsoleUrl(url: String) { stateStore.update { it.copy(webConsoleUrl = url) } }
    fun onAppResumed() { startBackgroundMonitors(); internetWatchdog.wakeNow(); autoPushScheduler.wakeNow(); triggerGentleReconnectIfOffline() }

    suspend fun connectAllDevices(autoResumeIfConfigured: Boolean = false): Unit = withContext(Dispatchers.IO) {
        connManager.connectAll { loopRunner.triggerHeartbeatAsync() }
        if (autoResumeIfConfigured && configRepository.getConfig().autoStartRunning && state.value.connectedCount > 0 && !state.value.isRunning) startAttendance()
    }
    suspend fun autoDiscoverConnectAndReady(fromInternetRecovery: Boolean = false): Unit = withContext(Dispatchers.IO) { discoveryCoordinator.autoDiscoverConnectAndReady(fromInternetRecovery) }
    suspend fun discoverDevicesInLan(autoResumeIfConfigured: Boolean = false): Unit = withContext(Dispatchers.IO) { discoveryCoordinator.discoverDevicesInLan(autoResumeIfConfigured) }

    suspend fun switchAppMode(targetMode: AppMode): AppConfig = withContext(Dispatchers.IO) {
        startMutex.withLock {
            if (configRepository.getConfig().mode == targetMode) return@withLock configRepository.getConfig()
            stopAttendanceInternal(persistFlag = false); connManager.disconnectAll()
            pollAttendanceUseCase.apply { clearDedupCache(); clearNameCache(); clearPendingQueue() }; stateStore.clearEvents()
            val next = configRepository.switchMode(targetMode); stateStore.syncConfiguredDevices(next.devices)
            internetWatchdog.resetInitialAutoReady(); autoPushScheduler.resetExecutedForModeSwitch()
            appendLog("Chế độ", "-", "Đã chuyển sang chế độ ${targetMode.shortLabel} (${next.apiDomain})", EventSeverity.INFO)
            next
        }
    }

    suspend fun testConnectDevice(device: DeviceConfig): Pair<Boolean, String> = withContext(Dispatchers.IO) { connManager.probeConnect(device) }
    suspend fun reconnectDevice(deviceKey: String): Unit = withContext(Dispatchers.IO) {
        val dev = configRepository.getConfig().devices.firstOrNull { it.key == deviceKey } ?: return@withContext
        val ok = connManager.reconnect(dev, maxAttempts = 3); loopRunner.triggerHeartbeatAsync()
        if (ok && configRepository.getConfig().autoStartRunning && !state.value.isRunning) startAttendance()
    }
    suspend fun addAndConnectDevice(device: DeviceConfig): Boolean = withContext(Dispatchers.IO) {
        val current = configRepository.getConfig().devices
        if (current.any { it.ip.equals(device.ip, ignoreCase = true) && it.port == device.port }) return@withContext false
        configRepository.updateDevices(current + device)
        appendLog(device.displayName, "-", "Đã thêm máy ${device.ip}:${device.port}", EventSeverity.INFO)
        val ok = connManager.connectIfOffline(device, maxAttempts = 3); loopRunner.triggerHeartbeatAsync()
        if (ok && configRepository.getConfig().autoStartRunning && !state.value.isRunning) startAttendance()
        true
    }
    suspend fun renameDevice(deviceKey: String, newName: String) {
        val cur = configRepository.getConfig().devices.firstOrNull { it.key == deviceKey } ?: return
        updateDevice(deviceKey, cur.copy(name = newName.trim()))
    }
    suspend fun updateDevice(oldKey: String, updated: DeviceConfig) = withContext(Dispatchers.IO) {
        connManager.disconnectSingle(oldKey); connManager.clearAuthLock(updated.key)
        configRepository.updateDevices(configRepository.getConfig().devices.map { if (it.key == oldKey) updated else it })
        val ok = connManager.connectIfOffline(updated, maxAttempts = 2); loopRunner.triggerHeartbeatAsync()
        if (ok && configRepository.getConfig().autoStartRunning && !state.value.isRunning) startAttendance()
    }
    suspend fun removeDevice(deviceKey: String) {
        connManager.disconnectSingle(deviceKey)
        val remaining = configRepository.getConfig().devices.filterNot { it.key == deviceKey }
        configRepository.updateDevices(remaining)
        if (remaining.isEmpty() && state.value.isRunning) stopAttendanceInternal(persistFlag = true)
        loopRunner.triggerHeartbeatAsync()
    }

    suspend fun startAttendance(): Boolean = withContext(Dispatchers.IO) {
        startMutex.withLock {
            if (state.value.isRunning) return@withLock true
            val cfg = configRepository.getConfig(); if (!cfg.isLoggedIn) return@withLock false
            stateStore.update { it.copy(isBusy = true, statusHint = "Đang kiểm tra kết nối & dọn bộ đệm...") }
            try {
                if (state.value.connectedCount == 0) connManager.connectAll { loopRunner.triggerHeartbeatAsync() }
                if (state.value.connectedCount == 0) {
                    appendLog("Hệ thống", "-", "Chưa kết nối được máy chấm công. Ứng dụng sẽ tự dò & nối lại nền.", EventSeverity.WARNING)
                    return@withLock false
                }
                connManager.clearLogsOrReconnectAll(cfg.devices); if (state.value.connectedCount == 0) return@withLock false
                pollAttendanceUseCase.clearDedupCache(); configRepository.setAutoStartRunning(true)
                stateStore.update { it.copy(isRunning = true) }
                appendLog("Hệ thống", "-", "BẮT ĐẦU CHẤM CÔNG — sẵn sàng chờ quét 24/7 (mỗi ${cfg.pollIntervalMs / 1000}s)", EventSeverity.SUCCESS)
                loopRunner.startPollLoop(); loopRunner.triggerHeartbeatAsync(); autoPushScheduler.wakeNow()
                true
            } catch (ce: CancellationException) { throw ce } catch (t: Throwable) {
                runCatching { Log.e(TAG, "Lỗi khi bắt đầu chấm công", t) }
                appendLog("Hệ thống", "-", "Không thể bắt đầu chấm công: ${t.message ?: t.javaClass.simpleName}", EventSeverity.ERROR)
                false
            } finally { stateStore.update { it.copy(isBusy = false, statusHint = "") } }
        }
    }

    suspend fun stopAttendance() { stopAttendanceInternal(persistFlag = true); loopRunner.triggerHeartbeatAsync() }
    suspend fun disconnectAllAndStop() { autoPushScheduler.stop(); internetWatchdog.stop(); stopAttendanceInternal(persistFlag = false); connManager.disconnectAll() }
    fun onSystemNetworkLost() = internetWatchdog.notifySystemNetworkLost()
    fun onNetworkRestored() { attendanceRepository.resetNetworkConnections(); internetWatchdog.notifySystemNetworkAvailable() }
    fun onLanTransportLost() = internetWatchdog.notifyLanTransportLost()
    fun onLanTransportRestored() { attendanceRepository.resetNetworkConnections(); loopRunner.resetOfflineBackoff(); internetWatchdog.notifyLanTransportRestored() }
    fun appendLog(deviceName: String, cardId: String, message: String, severity: EventSeverity) = stateStore.appendSystemEvent(deviceName, cardId, message, severity)
    fun clearLiveLogView() = stateStore.clearEvents()

    fun triggerGentleReconnectIfOffline(delayMs: Long = 300L, reason: String? = null) {
        scope.launch {
            val cfg = configRepository.getConfig()
            if (!cfg.isLoggedIn || cfg.devices.isEmpty()) return@launch
            if (delayMs > 0) delay(delayMs)
            val snap = state.value
            if (snap.isDiscovering || snap.isBusy) return@launch
            val offlineCount = cfg.devices.count { connManager.getHandle(it.key) == null }
            if (offlineCount == 0) return@launch
            if (reason != null) appendLog("Mạng LAN", "-", "$reason $offlineCount máy...", EventSeverity.INFO)
            if (connManager.reconnectAllOffline(cfg.devices, maxAttempts = 2, silentFail = reason == null)) {
                loopRunner.triggerHeartbeatAsync()
                if (cfg.autoStartRunning && !state.value.isRunning) startAttendance()
            }
        }
    }

    private suspend fun stopAttendanceInternal(persistFlag: Boolean) {
        loopRunner.stopPollLoop(); if (persistFlag) configRepository.setAutoStartRunning(false)
        val wasRunning = state.value.isRunning; stateStore.update { it.copy(isRunning = false) }
        if (wasRunning) appendLog("Hệ thống", "-", "ĐÃ DỪNG CHẤM CÔNG", EventSeverity.WARNING)
    }
    private suspend fun handleLanDropped() {
        loopRunner.stopPollLoop(); if (state.value.isRunning) stateStore.update { it.copy(isRunning = false) }
        connManager.markAllLostOnNetworkDrop("Mất Wi-Fi/LAN — chờ tự nối lại...")
        appendLog("Mạng LAN", "-", "Mất kết nối Wi-Fi/LAN — đã ngắt phiên cũ, sẽ tự dò & nối lại khi có Wi-Fi.", EventSeverity.WARNING)
    }
    private suspend fun recoverFromPollFreeze() {
        loopRunner.stopPollLoop(); stateStore.update { it.copy(isRunning = false) }
        connManager.disconnectAll(); connManager.connectAll { loopRunner.triggerHeartbeatAsync() }
        if (configRepository.getConfig().autoStartRunning && state.value.connectedCount > 0) startAttendance()
    }
    private suspend fun handleForceRelogin(reason: String) {
        stopAttendanceInternal(persistFlag = true); appendLog("Tài khoản", "-", reason, EventSeverity.ERROR); _forceRelogin.tryEmit(reason)
    }
    private object AlwaysOnlineProbe : InternetProbe {
        override suspend fun checkInternet(apiDomain: String) = InternetCheckResult(online = true, latencyMs = 1L, detail = "OK")
    }
    companion object { private const val TAG = "AttendanceEngine" }
}

package com.edusing.chamcongtvbox.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.model.HikvisionDoorCommand
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.BackfillHikvisionEventsUseCase
import com.edusing.chamcongtvbox.domain.usecase.LogoutUseCase
import com.edusing.chamcongtvbox.service.AttendanceEngine
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class MainDialogState(
    val showStopConfirm: Boolean = false, val showLogoutConfirm: Boolean = false, val showModePicker: Boolean = false,
    val renameTarget: DeviceConfig? = null, val deleteTarget: DeviceConfig? = null,
    val isPairingHik: Boolean = false, val pairHikError: String? = null,
)

/** ViewModel điều khiển màn hình chính: dò máy, kết nối, bật/tắt chấm công, chuyển chế độ và quản lý máy. */
class MainViewModel(
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val logoutUseCase: LogoutUseCase,
    private val hikControl: HikvisionControlGateway? = null,
    private val backfillUseCase: BackfillHikvisionEventsUseCase? = null,
) : ViewModel() {

    private val dialogs = MutableStateFlow(MainDialogState())
    private var lastInitializedSessionKey: String? = null

    val uiState: StateFlow<MainUiState> = combine(
        configRepository.configFlow,
        attendanceEngine.state,
        dialogs,
    ) { cfg, engine, dlg ->
        val branding = configRepository.getBranding()
        MainUiState(
            activeMode = cfg.mode,
            appTitle = branding.appTitle,
            orgLabel = branding.org,
            isOffice = branding.isOffice,
            orgName = cfg.orgName.ifBlank { cfg.username },
            accountId = cfg.accountId,
            apiDomain = cfg.apiDomain,
            isRunning = engine.isRunning,
            isBusy = engine.isBusy,
            isDiscovering = engine.isDiscovering,
            statusHint = engine.statusHint,
            internetStatus = engine.internetStatus,
            internetDetail = engine.internetDetail,
            hasLanOrWifi = engine.hasLanOrWifi,
            wifiHardwareDisabled = engine.wifiHardwareDisabled,
            devices = engine.devices,
            unpairedHikvisionDevices = engine.unpairedHikvisionDevices,
            isPairingHikvision = dlg.isPairingHik,
            pairHikvisionError = dlg.pairHikError,
            events = engine.events,
            lastPollTimeText = engine.lastPollTimeText,
            totalCheckInsToday = engine.totalCheckInsToday,
            pendingCheckInCount = engine.pendingCheckInCount,
            showStopConfirmDialog = dlg.showStopConfirm,
            showLogoutConfirmDialog = dlg.showLogoutConfirm,
            showModePickerDialog = dlg.showModePicker,
            renameTarget = dlg.renameTarget,
            deleteTarget = dlg.deleteTarget,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = run {
            val branding = configRepository.getBranding()
            MainUiState(
                activeMode = branding.mode,
                appTitle = branding.appTitle,
                orgLabel = branding.org,
                isOffice = branding.isOffice,
            )
        },
    )

    /** Khởi động giám sát Internet + chu trình tự nhận diện máy quét mặt → kết nối → sẵn sàng chờ quét 24/7. */
    fun onEnterMainScreen() {
        attendanceEngine.startBackgroundMonitors()
        val cfg = configRepository.getConfig()
        val sessionKey = "${cfg.mode.code}|${cfg.accountId}"
        if (lastInitializedSessionKey == sessionKey) return
        lastInitializedSessionKey = sessionKey
        viewModelScope.launch {
            attendanceEngine.autoDiscoverConnectAndReady(fromInternetRecovery = false)
        }
    }

    fun onStartOrStopClicked() {
        if (uiState.value.isBusy) return
        if (uiState.value.isRunning) {
            dialogs.value = dialogs.value.copy(showStopConfirm = true)
        } else {
            viewModelScope.launch { attendanceEngine.startAttendance() }
        }
    }

    fun confirmStopAttendance() {
        dialogs.value = dialogs.value.copy(showStopConfirm = false)
        viewModelScope.launch { attendanceEngine.stopAttendance() }
    }

    fun dismissStopDialog() { dialogs.value = dialogs.value.copy(showStopConfirm = false) }

    fun onDiscoverDevicesClicked() {
        if (uiState.value.isRunning || uiState.value.isBusy) return
        viewModelScope.launch { attendanceEngine.discoverDevicesInLan(autoResumeIfConfigured = true) }
    }

    fun onConnectAllClicked() { viewModelScope.launch { attendanceEngine.connectAllDevices(autoResumeIfConfigured = true) } }
    fun onReconnectDevice(deviceKey: String) { viewModelScope.launch { attendanceEngine.reconnectDevice(deviceKey) } }

    /** Ghép nối đồng loạt các máy Hikvision vừa tự dò thấy bằng 1 lần nhập mật khẩu thiết bị. */
    fun pairDiscoveredHikvision(username: String, password: String) {
        val targets = uiState.value.unpairedHikvisionDevices
        if (targets.isEmpty() || password.isBlank() || dialogs.value.isPairingHik) return
        val cleanUser = username.trim().ifBlank { DeviceConfig.DEFAULT_HIK_USERNAME }
        dialogs.value = dialogs.value.copy(isPairingHik = true, pairHikError = null)
        viewModelScope.launch {
            var connected = 0
            var lastErr: String? = null
            for (d in targets) {
                val cfg = DeviceConfig(
                    ip = d.ip, port = d.httpPort, machineNo = DeviceConfig.DEFAULT_MACHINE_NO,
                    name = d.model.ifBlank { "Hikvision" }, protocol = DeviceProtocol.HIKVISION_ISAPI,
                    username = cleanUser, accessPassword = password, useHttps = d.httpPort == 443, serialNumber = d.serialNumber,
                )
                val (ok, msg) = attendanceEngine.testConnectDevice(cfg)
                if (ok) { attendanceEngine.addAndConnectDevice(cfg); connected++ } else lastErr = "${d.ip}: $msg"
            }
            dialogs.value = dialogs.value.copy(
                isPairingHik = false,
                pairHikError = if (connected == 0) (lastErr ?: "Sai mật khẩu hoặc không nối được máy Hikvision.") else null,
            )
        }
    }

    fun onOpenHikvisionDoor(device: DeviceConfig) {
        val ctrl = hikControl ?: return
        viewModelScope.launch {
            ctrl.controlDoor(device, HikvisionDoorCommand.OPEN).fold(
                onSuccess = { msg -> attendanceEngine.appendLog(device.displayName, "-", msg, EventSeverity.SUCCESS) },
                onFailure = { err -> attendanceEngine.appendLog(device.displayName, "-", err.message ?: "Mở cửa lỗi", EventSeverity.ERROR) },
            )
        }
    }

    /** Đồng bộ bù toàn bộ lượt chấm công hôm nay còn trên máy Hikvision lên máy chủ tổng (1 chạm). */
    fun onBackfillHikvisionToday(device: DeviceConfig) {
        val uc = backfillUseCase ?: return
        val cfg = configRepository.getConfig()
        if (!cfg.isLoggedIn) return
        viewModelScope.launch {
            attendanceEngine.appendLog(device.displayName, "-", "Đang đồng bộ bù lượt chấm hôm nay...", EventSeverity.INFO)
            val nowMs = attendanceEngine.currentServerTimeMillis()
            val sinceMs = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh")).apply {
                timeInMillis = nowMs; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val domain = configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)
            attendanceEngine.withDeviceIoLock { uc(device, domain, cfg.accountId, sinceMs, nowMs + 60_000L, cfg.dedupSeconds) }.fold(
                onSuccess = { s -> attendanceEngine.appendLog(device.displayName, "-", "Bù lượt hôm nay: ${s.message}", EventSeverity.SUCCESS) },
                onFailure = { e -> attendanceEngine.appendLog(device.displayName, "-", e.message ?: "Đồng bộ bù lỗi", EventSeverity.ERROR) },
            )
        }
    }

    fun requestRenameDevice(device: DeviceConfig) {
        if (!uiState.value.isRunning) dialogs.value = dialogs.value.copy(renameTarget = device)
    }

    fun confirmRenameDevice(newName: String) {
        val target = dialogs.value.renameTarget ?: return
        dialogs.value = dialogs.value.copy(renameTarget = null)
        viewModelScope.launch { attendanceEngine.renameDevice(target.key, newName) }
    }

    fun confirmEditDevice(updated: DeviceConfig) {
        val target = dialogs.value.renameTarget ?: return
        dialogs.value = dialogs.value.copy(renameTarget = null)
        viewModelScope.launch { attendanceEngine.updateDevice(target.key, updated) }
    }

    fun dismissRenameDialog() { dialogs.value = dialogs.value.copy(renameTarget = null) }

    fun requestDeleteDevice(device: DeviceConfig) {
        if (!uiState.value.isRunning) dialogs.value = dialogs.value.copy(deleteTarget = device)
    }

    fun confirmDeleteDevice() {
        val target = dialogs.value.deleteTarget ?: return
        dialogs.value = dialogs.value.copy(deleteTarget = null)
        viewModelScope.launch { attendanceEngine.removeDevice(target.key) }
    }

    fun dismissDeleteDialog() { dialogs.value = dialogs.value.copy(deleteTarget = null) }
    fun onClearLogsClicked() = attendanceEngine.clearLiveLogView()
    fun requestLogout() { dialogs.value = dialogs.value.copy(showLogoutConfirm = true) }
    fun dismissLogoutDialog() { dialogs.value = dialogs.value.copy(showLogoutConfirm = false) }
    fun openModePicker() { dialogs.value = dialogs.value.copy(showModePicker = true) }
    fun dismissModePicker() { dialogs.value = dialogs.value.copy(showModePicker = false) }

    fun switchMode(targetMode: AppMode, onRequireLogin: () -> Unit) {
        dialogs.value = dialogs.value.copy(showModePicker = false)
        viewModelScope.launch {
            val next = attendanceEngine.switchAppMode(targetMode)
            if (next.isLoggedIn) {
                lastInitializedSessionKey = "${next.mode.code}|${next.accountId}"
                attendanceEngine.autoDiscoverConnectAndReady(fromInternetRecovery = false)
            } else {
                lastInitializedSessionKey = null
                onRequireLogin()
            }
        }
    }

    fun confirmLogout(onLoggedOut: () -> Unit) {
        dialogs.value = dialogs.value.copy(showLogoutConfirm = false)
        lastInitializedSessionKey = null
        viewModelScope.launch {
            attendanceEngine.disconnectAllAndStop()
            logoutUseCase()
            onLoggedOut()
        }
    }
}

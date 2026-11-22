package com.edusing.chamcongtvbox.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.AutoPushScheduleConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.UpdateStateSnapshot
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.service.AppUpdateCoordinator
import com.edusing.chamcongtvbox.service.AttendanceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Trạng thái UI màn hình Cài đặt kết nối, lịch tự động đẩy nhân sự và tối ưu 24/7. */
data class SettingsUiState(
    val activeMode: AppMode = AppMode.PALUZZ,
    val personLower: String = "nhân viên",
    val apiDomain: String = "",
    val udpMode: Boolean = false,
    val defaultPort: String = DeviceConfig.DEFAULT_PORT.toString(),
    val defaultMachineNo: String = DeviceConfig.DEFAULT_MACHINE_NO.toString(),
    val defaultPassword: String = DeviceConfig.DEFAULT_PASSWORD.toString(),
    val defaultLicense: String = DeviceConfig.DEFAULT_LICENSE.toString(),
    val connectTimeoutMs: String = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS.toString(),
    val pollIntervalSec: String = (AppConfig.DEFAULT_POLL_INTERVAL_MS / 1000).toString(),
    val dedupSeconds: String = AppConfig.DEFAULT_DEDUP_SECONDS.toString(),
    val heartbeatIntervalSec: String = (AppConfig.DEFAULT_HEARTBEAT_INTERVAL_MS / 1000).toString(),
    val maxNameBytes: String = AppConfig.DEFAULT_MAX_NAME_BYTES.toString(),
    val autoStartOnBoot: Boolean = true,
    val voiceGreetingEnabled: Boolean = false,
    val autoPushEnabled: Boolean = true,
    val autoPushCustomEnabled: Boolean = false,
    val autoPushCustomHour: Int = AutoPushScheduleConfig.DEFAULT_CUSTOM_HOUR,
    val autoPushCustomMinute: Int = AutoPushScheduleConfig.DEFAULT_CUSTOM_MINUTE,
    val isAutoPushing: Boolean = false,
    val lastAutoPushText: String = "",
    val nextAutoPushText: String = "06:00",
    val webConsoleUrl: String = "",
    val connectedDeviceCount: Int = 0,
    val updateState: UpdateStateSnapshot = UpdateStateSnapshot(),
    val savedBanner: String? = null,
    val errorMessage: String? = null,
)

/**
 * ViewModel cấu hình kỹ thuật + lịch tự động đẩy nhân sự xuống máy chấm công (06:00, 12:00, 20:00 + 1 giờ tuỳ chọn).
 * Thay đổi lịch đẩy được lưu đồng bộ ngay lập tức và kích bộ lập lịch chạy nền kiểm tra tức thì.
 */
class SettingsViewModel(
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val appUpdateCoordinator: AppUpdateCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(fromConfig(configRepository.getConfig()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            configRepository.configFlow.collect { cfg ->
                if (_uiState.value.activeMode != cfg.mode) _uiState.value = fromConfig(cfg)
            }
        }
        viewModelScope.launch {
            attendanceEngine.state.collect { snap ->
                _uiState.update { s ->
                    val sched = AutoPushScheduleConfig(s.autoPushEnabled, s.autoPushCustomEnabled, s.autoPushCustomHour, s.autoPushCustomMinute)
                    s.copy(
                        isAutoPushing = snap.isAutoPushing, lastAutoPushText = snap.lastAutoPushText,
                        webConsoleUrl = snap.webConsoleUrl, connectedDeviceCount = snap.connectedCount,
                        nextAutoPushText = sched.nextSlotText(attendanceEngine.currentServerTimeMillis()),
                    )
                }
            }
        }
        viewModelScope.launch {
            appUpdateCoordinator.state.collect { upd -> _uiState.update { it.copy(updateState = upd) } }
        }
    }

    private fun fromConfig(cfg: AppConfig): SettingsUiState {
        val snap = attendanceEngine.state.value
        val branding = configRepository.getBranding()
        return SettingsUiState(
            activeMode = cfg.mode,
            personLower = branding.personLower,
            apiDomain = branding.normalizeApiDomain(cfg.apiDomain),
            udpMode = cfg.udpMode,
            defaultPort = cfg.defaultPort.toString(),
            defaultMachineNo = cfg.defaultMachineNo.toString(),
            defaultPassword = cfg.defaultPassword.toString(),
            defaultLicense = cfg.defaultLicense.toString(),
            connectTimeoutMs = cfg.connectTimeoutMs.toString(),
            pollIntervalSec = (cfg.pollIntervalMs / 1000L).toString(),
            dedupSeconds = cfg.dedupSeconds.toString(),
            heartbeatIntervalSec = (cfg.heartbeatIntervalMs / 1000L).toString(),
            maxNameBytes = cfg.maxDeviceNameBytes.toString(),
            autoStartOnBoot = cfg.autoStartOnBoot,
            voiceGreetingEnabled = cfg.voiceGreetingEnabled,
            autoPushEnabled = cfg.autoPushEnabled,
            autoPushCustomEnabled = cfg.autoPushCustomEnabled,
            autoPushCustomHour = cfg.autoPushCustomHour.coerceIn(0, 23),
            autoPushCustomMinute = cfg.autoPushCustomMinute.coerceIn(0, 59),
            isAutoPushing = snap.isAutoPushing,
            lastAutoPushText = snap.lastAutoPushText,
            webConsoleUrl = snap.webConsoleUrl,
            nextAutoPushText = cfg.autoPushSchedule.nextSlotText(attendanceEngine.currentServerTimeMillis()),
            connectedDeviceCount = snap.connectedCount,
            updateState = appUpdateCoordinator.state.value,
        )
    }

    fun switchMode(targetMode: AppMode, onRequireLogin: () -> Unit) {
        if (_uiState.value.activeMode == targetMode) return
        viewModelScope.launch {
            val next = attendanceEngine.switchAppMode(targetMode)
            _uiState.value = fromConfig(next).copy(savedBanner = "Đã chuyển sang chế độ ${targetMode.shortLabel}.")
            if (next.isLoggedIn) {
                if (next.devices.isNotEmpty()) attendanceEngine.connectAllDevices(autoResumeIfConfigured = true)
            } else {
                onRequireLogin()
            }
        }
    }

    fun onApiDomainChange(v: String) = _uiState.update { it.copy(apiDomain = v, savedBanner = null, errorMessage = null) }
    fun onUdpModeChange(v: Boolean) = _uiState.update { it.copy(udpMode = v, savedBanner = null) }
    fun onPortChange(v: String) = _uiState.update { it.copy(defaultPort = v.filter(Char::isDigit), savedBanner = null) }
    fun onMachineNoChange(v: String) = _uiState.update { it.copy(defaultMachineNo = v.filter(Char::isDigit), savedBanner = null) }
    fun onPasswordChange(v: String) = _uiState.update { it.copy(defaultPassword = v.filter(Char::isDigit), savedBanner = null) }
    fun onLicenseChange(v: String) = _uiState.update { it.copy(defaultLicense = v.filter(Char::isDigit), savedBanner = null) }
    fun onTimeoutChange(v: String) = _uiState.update { it.copy(connectTimeoutMs = v.filter(Char::isDigit), savedBanner = null) }
    fun onPollSecChange(v: String) = _uiState.update { it.copy(pollIntervalSec = v.filter(Char::isDigit), savedBanner = null) }
    fun onDedupSecChange(v: String) = _uiState.update { it.copy(dedupSeconds = v.filter(Char::isDigit), savedBanner = null) }
    fun onHeartbeatSecChange(v: String) = _uiState.update { it.copy(heartbeatIntervalSec = v.filter(Char::isDigit), savedBanner = null) }
    fun onMaxNameBytesChange(v: String) = _uiState.update { it.copy(maxNameBytes = v.filter(Char::isDigit), savedBanner = null) }
    fun onAutoStartOnBootChange(v: Boolean) {
        _uiState.update { it.copy(autoStartOnBoot = v, savedBanner = null) }
        viewModelScope.launch { configRepository.saveConfig(configRepository.getConfig().copy(autoStartOnBoot = v)) }
    }
    fun onVoiceGreetingChange(v: Boolean) {
        _uiState.update { it.copy(voiceGreetingEnabled = v, savedBanner = null) }
        viewModelScope.launch { configRepository.saveConfig(configRepository.getConfig().copy(voiceGreetingEnabled = v)) }
    }

    fun onAutoPushEnabledChange(enabled: Boolean) = updateAutoPushSchedule { it.copy(autoPushEnabled = enabled) }
    fun onAutoPushCustomEnabledChange(enabled: Boolean) = updateAutoPushSchedule { it.copy(autoPushCustomEnabled = enabled) }
    fun adjustCustomHour(delta: Int) = updateAutoPushSchedule { it.copy(autoPushCustomHour = Math.floorMod(it.autoPushCustomHour + delta, 24)) }
    fun adjustCustomMinute(delta: Int) = updateAutoPushSchedule { it.copy(autoPushCustomMinute = Math.floorMod(it.autoPushCustomMinute + delta, 60)) }
    fun setCustomMinute(minute: Int) = updateAutoPushSchedule { it.copy(autoPushCustomMinute = minute.coerceIn(0, 59)) }

    private fun updateAutoPushSchedule(transform: (SettingsUiState) -> SettingsUiState) {
        var latestState = _uiState.value
        _uiState.update { prev ->
            val next = transform(prev)
            val sched = AutoPushScheduleConfig(next.autoPushEnabled, next.autoPushCustomEnabled, next.autoPushCustomHour, next.autoPushCustomMinute)
            next.copy(
                nextAutoPushText = sched.nextSlotText(attendanceEngine.currentServerTimeMillis()),
                savedBanner = null,
                errorMessage = null,
            ).also { latestState = it }
        }
        viewModelScope.launch {
            val current = configRepository.getConfig()
            configRepository.saveConfig(
                current.copy(
                    autoPushEnabled = latestState.autoPushEnabled,
                    autoPushCustomEnabled = latestState.autoPushCustomEnabled,
                    autoPushCustomHour = latestState.autoPushCustomHour,
                    autoPushCustomMinute = latestState.autoPushCustomMinute,
                )
            )
            attendanceEngine.notifyAutoPushScheduleChanged()
        }
    }

    fun onAutoUpdateEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            configRepository.saveConfig(configRepository.getConfig().copy(autoUpdateEnabled = enabled))
        }
    }

    fun checkAppUpdateNow() { viewModelScope.launch { appUpdateCoordinator.checkNow() } }
    fun installAppUpdateNow() { viewModelScope.launch { appUpdateCoordinator.downloadAndInstallNow(allowInteractiveFallback = true) } }

    fun triggerAutoPushNow() {
        if (_uiState.value.isAutoPushing) return
        viewModelScope.launch {
            val ok = attendanceEngine.triggerAutoPushNow()
            _uiState.update {
                if (ok) it.copy(savedBanner = "Đã đồng bộ xong danh sách ${it.personLower} xuống máy chấm công.", errorMessage = null)
                else it.copy(errorMessage = "Chưa thể đẩy tự động — hãy kiểm tra kết nối máy chấm công & mạng Internet.")
            }
        }
    }

    fun saveSettings() {
        val s = _uiState.value
        val port = s.defaultPort.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return showError("Cổng phải nằm trong khoảng 1–65535 (mặc định 5005).")
        val machineNo = s.defaultMachineNo.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val password = s.defaultPassword.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val license = s.defaultLicense.toIntOrNull()?.coerceAtLeast(0) ?: DeviceConfig.DEFAULT_LICENSE
        val timeout = s.connectTimeoutMs.toIntOrNull()?.coerceIn(1_500, 30_000) ?: 5_000
        val pollSec = s.pollIntervalSec.toLongOrNull()?.coerceIn(3L, 60L) ?: 10L
        val dedupSec = s.dedupSeconds.toLongOrNull()?.coerceIn(0L, 3600L) ?: 60L
        val hbSec = s.heartbeatIntervalSec.toLongOrNull()?.coerceIn(3L, 300L) ?: 3L
        val maxBytes = s.maxNameBytes.toIntOrNull()?.coerceIn(4, 16) ?: 6

        viewModelScope.launch {
            val current = configRepository.getConfig()
            val updated = current.copy(
                apiDomain = configRepository.getBranding().normalizeApiDomain(s.apiDomain),
                udpMode = s.udpMode,
                defaultPort = port,
                defaultMachineNo = machineNo,
                defaultPassword = password,
                defaultLicense = license,
                connectTimeoutMs = timeout,
                pollIntervalMs = pollSec * 1_000L,
                dedupSeconds = dedupSec,
                heartbeatIntervalMs = hbSec * 1_000L,
                maxDeviceNameBytes = maxBytes,
                autoStartOnBoot = s.autoStartOnBoot,
                voiceGreetingEnabled = s.voiceGreetingEnabled,
                autoPushEnabled = s.autoPushEnabled,
                autoPushCustomEnabled = s.autoPushCustomEnabled,
                autoPushCustomHour = s.autoPushCustomHour,
                autoPushCustomMinute = s.autoPushCustomMinute,
            )
            configRepository.saveConfig(updated)
            attendanceEngine.notifyAutoPushScheduleChanged()
            _uiState.value = fromConfig(updated).copy(savedBanner = "Đã lưu cấu hình thành công.")
        }
    }

    private fun showError(msg: String) = _uiState.update { it.copy(errorMessage = msg, savedBanner = null) }
}

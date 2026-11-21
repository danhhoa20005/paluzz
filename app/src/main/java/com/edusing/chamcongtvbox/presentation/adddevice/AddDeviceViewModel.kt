package com.edusing.chamcongtvbox.presentation.adddevice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.ScanLanDevicesUseCase
import com.edusing.chamcongtvbox.domain.usecase.SuggestFreeIpUseCase
import com.edusing.chamcongtvbox.service.AttendanceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel màn hình Thêm & Nhận máy:
 * - Tự động quét SADP + LAN ngay khi mở để hiện các máy chấm công trong ~1.5 giây.
 * - Cho phép **Nhận máy 1 chạm** ngay trên thẻ tìm thấy (tự dùng mật khẩu Hikvision đã nhớ hoặc mở hộp nhập nhanh 1 ô).
 */
class AddDeviceViewModel(
    private val configRepository: ConfigRepository,
    private val scanLanDevicesUseCase: ScanLanDevicesUseCase,
    private val suggestFreeIpUseCase: SuggestFreeIpUseCase,
    private val attendanceEngine: AttendanceEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<AddDeviceUiState> = _uiState.asStateFlow()

    private val _savedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val savedEvent: SharedFlow<Unit> = _savedEvent.asSharedFlow()

    private val suggestedHistory = LinkedHashSet<String>()
    private var scanJob: Job? = null

    init {
        prepareSubnetsAndAutoScan()
    }

    private fun buildInitialState(): AddDeviceUiState {
        val cfg = configRepository.getConfig()
        val hasHik = cfg.resolvedHikPassword.isNotBlank() || cfg.devices.any { it.isHikvision }
        val defaultProto = if (hasHik || cfg.devices.isEmpty()) DeviceProtocol.HIKVISION_ISAPI else DeviceProtocol.FK
        return AddDeviceUiState(
            protocol = defaultProto,
            port = if (defaultProto == DeviceProtocol.HIKVISION_ISAPI) DeviceConfig.DEFAULT_HIK_PORT.toString() else cfg.defaultPort.toString(),
            machineNo = cfg.defaultMachineNo.toString(),
            commPassword = cfg.defaultPassword.toString(),
            useUdp = cfg.udpMode,
            hikUsername = cfg.resolvedHikUsername,
            hikPassword = cfg.resolvedHikPassword,
            hasSavedHikPassword = cfg.resolvedHikPassword.isNotBlank(),
        )
    }

    private fun prepareSubnetsAndAutoScan() {
        viewModelScope.launch(Dispatchers.IO) {
            val configured = configRepository.getConfig().devices.map { it.ip }.toSet()
            val localSubnets = scanLanDevicesUseCase.detectSubnets(configured)
            val subnet = localSubnets.firstOrNull()
            val extraPrefixes = scanLanDevicesUseCase.detectRoutableExtraPrefixes(localSubnets, configured)
            val allPrefixes = (localSubnets.map { it.prefix } + extraPrefixes).distinct()
            _uiState.update {
                it.copy(
                    subnetInfo = subnet,
                    scanPrefix = it.scanPrefix.ifBlank { subnet?.prefix ?: allPrefixes.firstOrNull().orEmpty() },
                    quickPrefixes = allPrefixes,
                )
            }
            startLanScan()
            suggestNextFreeIp()
        }
    }

    fun suggestNextFreeIp() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingSuggestion = true) }
            val configured = configRepository.getConfig().devices.map { it.ip }.toSet()
            val port = _uiState.value.port.toIntOrNull()?.takeIf { it in 1..65535 } ?: DeviceConfig.DEFAULT_PORT
            val suggestion = suggestFreeIpUseCase(port, excludeIps = configured + suggestedHistory)
            if (suggestion != null) suggestedHistory.add(suggestion.suggestedIp)
            _uiState.update { it.copy(ipSuggestion = suggestion, isLoadingSuggestion = false, ip = it.ip.ifBlank { suggestion?.suggestedIp.orEmpty() }) }
        }
    }

    fun toggleAdvanced() = _uiState.update { it.copy(showAdvanced = !it.showAdvanced) }
    fun toggleOtherHosts() = _uiState.update { it.copy(showOtherHosts = !it.showOtherHosts) }

    fun onProtocolChange(protocol: DeviceProtocol) {
        val defaultPort = if (protocol == DeviceProtocol.HIKVISION_ISAPI) {
            if (_uiState.value.useHttps) DeviceConfig.DEFAULT_HIK_HTTPS_PORT else DeviceConfig.DEFAULT_HIK_PORT
        } else configRepository.getConfig().defaultPort
        _uiState.update { it.copy(protocol = protocol, port = defaultPort.toString(), errorMessage = null, infoMessage = null) }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, errorMessage = null) }
    fun onIpChange(value: String) {
        val parsed = AddDeviceFormValidator.parsePastedUrl(value)
        if (parsed != null) {
            _uiState.update {
                it.copy(protocol = DeviceProtocol.HIKVISION_ISAPI, ip = parsed.host, port = parsed.port, useHttps = parsed.useHttps, errorMessage = null)
            }
        } else _uiState.update { it.copy(ip = value.trim(), errorMessage = null) }
    }
    fun onPortChange(value: String) = _uiState.update { it.copy(port = value.filter(Char::isDigit), errorMessage = null) }
    fun onMachineNoChange(value: String) = _uiState.update { it.copy(machineNo = value.filter(Char::isDigit), errorMessage = null) }
    fun onCommPasswordChange(value: String) = _uiState.update { it.copy(commPassword = value.filter(Char::isDigit), errorMessage = null) }
    fun onUseUdpChange(useUdp: Boolean) = _uiState.update { it.copy(useUdp = useUdp) }
    fun onHikUsernameChange(value: String) = _uiState.update { it.copy(hikUsername = value.trim(), errorMessage = null) }
    fun onHikPasswordChange(value: String) = _uiState.update { it.copy(hikPassword = value, errorMessage = null) }
    fun onUseHttpsChange(useHttps: Boolean) = _uiState.update {
        it.copy(useHttps = useHttps, port = (if (useHttps) DeviceConfig.DEFAULT_HIK_HTTPS_PORT else DeviceConfig.DEFAULT_HIK_PORT).toString(), errorMessage = null)
    }
    fun onScanPrefixChange(value: String) = _uiState.update { it.copy(scanPrefix = value.trim(), errorMessage = null) }

    /** Đưa thiết bị tìm thấy sang biểu mẫu nhập tay để đổi tên/cổng trước khi lưu. */
    fun selectDiscoveredHost(host: LanHostInfo) {
        val nextProto = if (host.isHikvisionTerminal) DeviceProtocol.HIKVISION_ISAPI else if (host.isFkTerminal) DeviceProtocol.FK else _uiState.value.protocol
        val nextPort = if (host.isDetectedTerminal) host.port else _uiState.value.port.toIntOrNull() ?: nextProto.defaultPort
        _uiState.update {
            it.copy(
                protocol = nextProto,
                name = host.modelHint.ifBlank { it.name },
                ip = host.ip,
                port = nextPort.toString(),
                useHttps = nextPort == DeviceConfig.DEFAULT_HIK_HTTPS_PORT,
                errorMessage = null,
                infoMessage = if (host.alreadyConfigured) "IP ${host.ip} đã có trong danh sách." else "Đã điền ${host.ip}:$nextPort — bấm Kết nối & Nhận máy.",
            )
        }
    }

    /** Nhận máy 1 chạm ngay trên thẻ tìm thấy: FK hoặc Hikvision đã nhớ mật khẩu sẽ nối thẳng; Hikvision mới mở hộp nhập MK gọn. */
    fun onQuickAcceptClicked(host: LanHostInfo) {
        val s = _uiState.value
        if (s.isSaving || s.savingHostIp != null || host.alreadyConfigured) return
        val cfg = configRepository.getConfig()
        val savedPass = s.hikPassword.ifBlank { cfg.resolvedHikPassword }
        if (host.isHikvisionTerminal && savedPass.isBlank()) {
            _uiState.update {
                it.copy(quickAcceptHost = host, quickAcceptUsername = cfg.resolvedHikUsername, quickAcceptPassword = "", quickAcceptError = null)
            }
            return
        }
        val device = AddDeviceFormValidator.buildFromHost(host, cfg, cfg.resolvedHikUsername, savedPass)
        connectAndAcceptHost(host, device, fromDialog = false)
    }

    fun onQuickAcceptUsernameChange(v: String) = _uiState.update { it.copy(quickAcceptUsername = v.trim(), quickAcceptError = null) }
    fun onQuickAcceptPasswordChange(v: String) = _uiState.update { it.copy(quickAcceptPassword = v, quickAcceptError = null) }
    fun dismissQuickAcceptDialog() = _uiState.update { it.copy(quickAcceptHost = null, quickAcceptError = null) }

    fun confirmQuickAcceptDialog() {
        val s = _uiState.value
        val host = s.quickAcceptHost ?: return
        if (s.quickAcceptPassword.isBlank()) {
            _uiState.update { it.copy(quickAcceptError = "Vui lòng nhập mật khẩu thiết bị Hikvision.") }
            return
        }
        val device = AddDeviceFormValidator.buildFromHost(host, configRepository.getConfig(), s.quickAcceptUsername, s.quickAcceptPassword)
        connectAndAcceptHost(host, device, fromDialog = true)
    }

    private fun connectAndAcceptHost(host: LanHostInfo, device: DeviceConfig, fromDialog: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(savingHostIp = host.ip, isSaving = true, errorMessage = null, quickAcceptError = null) }
            val (ok, msg) = attendanceEngine.testConnectDevice(device)
            if (!ok) {
                _uiState.update {
                    if (host.isHikvisionTerminal) {
                        it.copy(
                            savingHostIp = null, isSaving = false, quickAcceptHost = host, quickAcceptUsername = device.username,
                            quickAcceptPassword = if (fromDialog) it.quickAcceptPassword else "",
                            quickAcceptError = "Không nối được ($msg). Kiểm tra lại mật khẩu thiết bị.",
                        )
                    } else it.copy(savingHostIp = null, isSaving = false, errorMessage = "Chưa nối được ${host.ip} ($msg).")
                }
                return@launch
            }
            val added = attendanceEngine.addAndConnectDevice(device)
            _uiState.update {
                it.copy(
                    savingHostIp = null, isSaving = false, quickAcceptHost = null,
                    hikPassword = if (device.isHikvision) device.accessPassword else it.hikPassword,
                    hasSavedHikPassword = it.hasSavedHikPassword || (device.isHikvision && device.accessPassword.isNotBlank()),
                    errorMessage = if (!added) "Máy ${device.ip}:${device.port} đã có trong danh sách." else null,
                )
            }
            if (added) _savedEvent.tryEmit(Unit)
        }
    }

    fun startLanScan(overridePrefix: String? = null) {
        if (_uiState.value.isScanning) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val cfg = configRepository.getConfig()
            val configuredIps = cfg.devices.map { it.ip }.toSet()
            val targetPrefix = (overridePrefix ?: _uiState.value.scanPrefix).trim().ifBlank { null }
            if (overridePrefix != null) _uiState.update { it.copy(scanPrefix = overridePrefix.trim()) }
            _uiState.update { it.copy(isScanning = true, scanCompleted = 0, scanTotal = 254, errorMessage = null) }
            val hosts = scanLanDevicesUseCase(
                configuredIps = configuredIps, port = cfg.defaultPort, customPrefix = targetPrefix,
                machineNo = cfg.defaultMachineNo, password = cfg.defaultPassword, license = cfg.defaultLicense,
                useUdp = cfg.udpMode, hikvisionPort = DeviceConfig.DEFAULT_HIK_PORT,
                onQuickResults = { quick -> _uiState.update { cur -> if (cur.discoveredHosts.isEmpty()) cur.copy(discoveredHosts = quick) else cur } },
                onProgress = { done, total -> _uiState.update { it.copy(scanCompleted = done, scanTotal = total) } },
            )
            _uiState.update { it.copy(isScanning = false, discoveredHosts = hosts) }
        }
    }

    fun saveDevice(verifyFirst: Boolean) {
        val s = _uiState.value
        if (s.isSaving) return
        val (device, err) = AddDeviceFormValidator.validateForm(s, configRepository.getConfig())
        if (device == null) { _uiState.update { it.copy(errorMessage = err) }; return }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, infoMessage = "Đang kết nối tới ${device.ip}:${device.port}...") }
            if (verifyFirst) {
                val (ok, msg) = attendanceEngine.testConnectDevice(device)
                if (!ok) {
                    _uiState.update { it.copy(isSaving = false, errorMessage = "Chưa nối được ($msg). Kiểm tra IP, mật khẩu hoặc bấm \"Lưu luôn\".", infoMessage = null) }
                    return@launch
                }
            }
            val added = attendanceEngine.addAndConnectDevice(device)
            _uiState.update { it.copy(isSaving = false, errorMessage = if (!added) "Máy ${device.ip}:${device.port} đã tồn tại." else null) }
            if (added) _savedEvent.tryEmit(Unit)
        }
    }

    override fun onCleared() { scanJob?.cancel(); super.onCleared() }
}

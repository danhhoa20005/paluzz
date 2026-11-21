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

/** ViewModel màn hình Thêm máy: hỗ trợ song song FK (5005) & Hikvision DS-K1T320MX (80/443), gợi ý IP và quét LAN. */
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
        suggestNextFreeIp()
    }

    private fun buildInitialState(): AddDeviceUiState {
        val cfg = configRepository.getConfig()
        return AddDeviceUiState(
            port = cfg.defaultPort.toString(),
            machineNo = cfg.defaultMachineNo.toString(),
            commPassword = cfg.defaultPassword.toString(),
            useUdp = cfg.udpMode,
        )
    }

    fun suggestNextFreeIp() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingSuggestion = true, errorMessage = null) }
            val configured = configRepository.getConfig().devices.map { it.ip }.toSet()
            val localSubnets = scanLanDevicesUseCase.detectSubnets(configured)
            val subnet = localSubnets.firstOrNull()
            val extraPrefixes = scanLanDevicesUseCase.detectRoutableExtraPrefixes(localSubnets, configured)
            val allPrefixes = (localSubnets.map { it.prefix } + extraPrefixes).distinct()
            val port = _uiState.value.port.toIntOrNull()?.takeIf { it in 1..65535 } ?: DeviceConfig.DEFAULT_PORT
            val suggestion = suggestFreeIpUseCase(port, excludeIps = configured + suggestedHistory)
            if (suggestion != null) suggestedHistory.add(suggestion.suggestedIp)
            _uiState.update {
                it.copy(
                    subnetInfo = subnet,
                    scanPrefix = it.scanPrefix.ifBlank { subnet?.prefix ?: allPrefixes.firstOrNull().orEmpty() },
                    quickPrefixes = allPrefixes,
                    ipSuggestion = suggestion,
                    isLoadingSuggestion = false,
                    ip = suggestion?.suggestedIp ?: it.ip,
                    infoMessage = suggestion?.let { s ->
                        "Gợi ý IP tĩnh trống: ${s.suggestedIp}. Nhập vào Menu → Cài đặt mạng trên máy chấm công."
                    },
                )
            }
        }
    }

    fun onProtocolChange(protocol: DeviceProtocol) {
        val defaultPort = if (protocol == DeviceProtocol.HIKVISION_ISAPI) {
            if (_uiState.value.useHttps) DeviceConfig.DEFAULT_HIK_HTTPS_PORT else DeviceConfig.DEFAULT_HIK_PORT
        } else configRepository.getConfig().defaultPort
        val hint = if (protocol == DeviceProtocol.HIKVISION_ISAPI) "Đã chọn cổng Hikvision ISAPI (mọi dòng máy). Bấm Quét LAN (SADP) hoặc nhập IP, admin & mật khẩu thiết bị." else null
        _uiState.update { it.copy(protocol = protocol, port = defaultPort.toString(), errorMessage = null, infoMessage = hint) }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, errorMessage = null) }
    fun onIpChange(value: String) {
        val raw = value.trim()
        if (raw.contains("://") || raw.contains('/')) {
            val isHttpsUrl = raw.startsWith("https://", ignoreCase = true)
            val authority = raw.substringAfter("://", raw).substringBefore('/').substringBefore('#').substringBefore('?')
            val host = authority.substringBefore(':').trim()
            val nextPort = authority.substringAfter(':', "").filter(Char::isDigit).ifBlank { if (isHttpsUrl) "443" else "80" }
            _uiState.update { it.copy(protocol = DeviceProtocol.HIKVISION_ISAPI, ip = host, port = nextPort, useHttps = isHttpsUrl || nextPort == "443", errorMessage = null) }
        } else _uiState.update { it.copy(ip = raw, errorMessage = null) }
    }
    fun onPortChange(value: String) = _uiState.update { it.copy(port = value.filter(Char::isDigit), errorMessage = null) }
    fun onMachineNoChange(value: String) = _uiState.update { it.copy(machineNo = value.filter(Char::isDigit), errorMessage = null) }
    fun onCommPasswordChange(value: String) = _uiState.update { it.copy(commPassword = value.filter(Char::isDigit), errorMessage = null) }
    fun onUseUdpChange(useUdp: Boolean) = _uiState.update { it.copy(useUdp = useUdp) }
    fun onHikUsernameChange(value: String) = _uiState.update { it.copy(hikUsername = value.trim(), errorMessage = null) }
    fun onHikPasswordChange(value: String) = _uiState.update { it.copy(hikPassword = value, errorMessage = null) }
    fun onUseHttpsChange(useHttps: Boolean) = _uiState.update {
        val nextPort = if (useHttps) DeviceConfig.DEFAULT_HIK_HTTPS_PORT else DeviceConfig.DEFAULT_HIK_PORT
        it.copy(useHttps = useHttps, port = nextPort.toString(), errorMessage = null)
    }
    fun onScanPrefixChange(value: String) = _uiState.update { it.copy(scanPrefix = value.trim(), errorMessage = null) }

    fun selectDiscoveredHost(host: LanHostInfo) {
        val nextProto = if (host.isHikvisionTerminal) DeviceProtocol.HIKVISION_ISAPI else if (host.isFkTerminal) DeviceProtocol.FK else _uiState.value.protocol
        val nextPort = if (host.isDetectedTerminal) host.port else _uiState.value.port.toIntOrNull() ?: nextProto.defaultPort
        val suggestedName = if (_uiState.value.name.isBlank() && host.modelHint.isNotBlank()) host.modelHint else _uiState.value.name
        _uiState.update {
            it.copy(
                protocol = nextProto,
                name = suggestedName,
                ip = host.ip,
                port = nextPort.toString(),
                useHttps = nextPort == DeviceConfig.DEFAULT_HIK_HTTPS_PORT,
                errorMessage = null,
                infoMessage = when {
                    host.alreadyConfigured -> "IP ${host.ip} đã có trong danh sách máy."
                    host.isHikvisionTerminal -> "Đã chọn ${host.modelHint.ifBlank { "Hikvision" }} (${host.ip}:$nextPort) — nhập mật khẩu thiết bị rồi bấm Kết nối & Lưu."
                    else -> "Đã chọn IP ${host.ip}:$nextPort."
                },
            )
        }
    }

    fun startLanScan(overridePrefix: String? = null) {
        if (_uiState.value.isScanning) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val cfg = configRepository.getConfig()
            val configuredIps = cfg.devices.map { it.ip }.toSet()
            val stateNow = _uiState.value
            val targetPrefix = (overridePrefix ?: stateNow.scanPrefix).trim().ifBlank { null }
            if (overridePrefix != null) _uiState.update { it.copy(scanPrefix = overridePrefix.trim()) }
            val fkPort = if (stateNow.protocol == DeviceProtocol.FK) {
                stateNow.port.toIntOrNull()?.takeIf { it in 1..65535 } ?: cfg.defaultPort
            } else cfg.defaultPort
            val hikPort = if (stateNow.protocol == DeviceProtocol.HIKVISION_ISAPI) {
                stateNow.port.toIntOrNull()?.takeIf { it in 1..65535 } ?: DeviceConfig.DEFAULT_HIK_PORT
            } else DeviceConfig.DEFAULT_HIK_PORT
            val machineNo = stateNow.machineNo.toIntOrNull()?.takeIf { it >= 1 } ?: cfg.defaultMachineNo
            val password = stateNow.commPassword.toIntOrNull()?.takeIf { it >= 0 } ?: cfg.defaultPassword
            val label = targetPrefix?.let { "$it.x" } ?: "mạng LAN"
            _uiState.update {
                it.copy(
                    isScanning = true, scanCompleted = 0, scanTotal = 254, discoveredHosts = emptyList(),
                    errorMessage = null, infoMessage = "Đang quét $label tìm cả máy FK (5005) và Hikvision ($hikPort)...",
                )
            }
            val hosts = scanLanDevicesUseCase(
                configuredIps = configuredIps, port = fkPort, customPrefix = targetPrefix,
                machineNo = machineNo, password = password, license = cfg.defaultLicense,
                useUdp = stateNow.useUdp, hikvisionPort = hikPort,
            ) { scanned, total ->
                _uiState.update { it.copy(scanCompleted = scanned, scanTotal = total) }
            }
            val fkCount = hosts.count { it.isFkTerminal }
            val hikCount = hosts.count { it.isHikvisionTerminal }
            _uiState.update {
                it.copy(
                    isScanning = false,
                    discoveredHosts = hosts,
                    infoMessage = when {
                        fkCount + hikCount > 0 -> "Phát hiện $hikCount Hikvision + $fkCount máy FK trong ${hosts.size} thiết bị ($label)."
                        hosts.isNotEmpty() -> "Thấy ${hosts.size} thiết bị ở $label nhưng chưa máy nào mở cổng FK/Hikvision."
                        else -> "Không thấy thiết bị nào đáp Ping ở $label. Thử chọn dải mạng khác hoặc nhập IP thủ công."
                    },
                )
            }
        }
    }

    fun saveDevice(verifyFirst: Boolean) {
        val s = _uiState.value
        if (s.isSaving) return
        val device = buildValidatedDevice(s) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, infoMessage = "Đang kết nối thử tới ${device.ip}:${device.port}...") }
            if (verifyFirst) {
                val (ok, msg) = attendanceEngine.testConnectDevice(device)
                if (!ok) {
                    val hint = if (device.isHikvision) {
                        "Kiểm tra đúng IP, cổng 80/443 và mật khẩu admin kích hoạt trên DS-K1T320MX (hoặc bấm \"Lưu luôn\")."
                    } else {
                        "Kiểm tra tắt phần mềm chấm công trên PC, đúng IP/cổng 5005 hoặc bấm \"Lưu luôn\"."
                    }
                    _uiState.update { it.copy(isSaving = false, errorMessage = "Chưa nối được ($msg). $hint", infoMessage = null) }
                    return@launch
                }
            }
            val added = attendanceEngine.addAndConnectDevice(device)
            if (!added) {
                _uiState.update { it.copy(isSaving = false, errorMessage = "Máy ${device.ip}:${device.port} đã tồn tại trong danh sách.", infoMessage = null) }
            } else {
                _uiState.update { it.copy(isSaving = false) }
                _savedEvent.tryEmit(Unit)
            }
        }
    }

    private fun buildValidatedDevice(s: AddDeviceUiState): DeviceConfig? {
        val parts = s.ip.trim().split('.')
        if (parts.size != 4 || !parts.all { it.toIntOrNull() in 0..255 }) {
            _uiState.update { it.copy(errorMessage = "Địa chỉ IP không hợp lệ (ví dụ: 192.168.1.64).") }
            return null
        }
        val portNum = s.port.toIntOrNull()?.takeIf { it in 1..65535 }
        if (portNum == null) {
            _uiState.update { it.copy(errorMessage = "Cổng không hợp lệ (FK: 5005, Hikvision: 80/443).") }
            return null
        }
        if (s.protocol == DeviceProtocol.HIKVISION_ISAPI && s.hikPassword.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Vui lòng nhập mật khẩu quản trị máy Hikvision DS-K1T320MX.") }
            return null
        }
        val cfg = configRepository.getConfig()
        return DeviceConfig(
            name = s.name.trim(),
            ip = s.ip.trim(),
            port = portNum,
            machineNo = s.machineNo.toIntOrNull()?.takeIf { it >= 1 } ?: cfg.defaultMachineNo,
            password = s.commPassword.toIntOrNull()?.takeIf { it >= 0 } ?: cfg.defaultPassword,
            license = cfg.defaultLicense,
            useUdp = s.useUdp,
            protocol = s.protocol,
            username = s.hikUsername.trim().ifBlank { DeviceConfig.DEFAULT_HIK_USERNAME },
            accessPassword = s.hikPassword,
            useHttps = s.useHttps,
        )
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}

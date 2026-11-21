package com.edusing.chamcongtvbox.presentation.adddevice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.IpSuggestion
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.domain.model.SubnetInfo
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

/** Trạng thái UI màn hình Thêm máy chấm công mới. */
data class AddDeviceUiState(
    val name: String = "",
    val ip: String = "",
    val port: String = DeviceConfig.DEFAULT_PORT.toString(),
    val machineNo: String = DeviceConfig.DEFAULT_MACHINE_NO.toString(),
    val commPassword: String = DeviceConfig.DEFAULT_PASSWORD.toString(),
    val useUdp: Boolean = false,
    val subnetInfo: SubnetInfo? = null,
    val scanPrefix: String = "",
    val quickPrefixes: List<String> = emptyList(),
    val ipSuggestion: IpSuggestion? = null,
    val isLoadingSuggestion: Boolean = false,
    val isScanning: Boolean = false,
    val scanCompleted: Int = 0,
    val scanTotal: Int = 254,
    val discoveredHosts: List<LanHostInfo> = emptyList(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
)

/** ViewModel màn hình Thêm máy: gợi ý IP trống không trùng, quét Ping an toàn và kiểm tra nối thử. */
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

    /** Tìm IP trống tiếp theo trong dải .240..200 và tự nhận diện cả dải LAN thượng nguồn thông tuyến. */
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

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, errorMessage = null) }
    fun onIpChange(value: String) = _uiState.update { it.copy(ip = value.trim(), errorMessage = null) }
    fun onPortChange(value: String) = _uiState.update { it.copy(port = value.filter(Char::isDigit), errorMessage = null) }
    fun onMachineNoChange(value: String) = _uiState.update { it.copy(machineNo = value.filter(Char::isDigit), errorMessage = null) }
    fun onCommPasswordChange(value: String) = _uiState.update { it.copy(commPassword = value.filter(Char::isDigit), errorMessage = null) }
    fun onUseUdpChange(useUdp: Boolean) = _uiState.update { it.copy(useUdp = useUdp) }
    fun onScanPrefixChange(value: String) = _uiState.update { it.copy(scanPrefix = value.trim(), errorMessage = null) }

    fun selectDiscoveredHost(host: LanHostInfo) {
        _uiState.update {
            it.copy(
                ip = host.ip,
                port = host.port.toString(),
                errorMessage = null,
                infoMessage = if (host.alreadyConfigured) "IP ${host.ip} đã có trong danh sách máy." else "Đã chọn IP ${host.ip}.",
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
            val port = stateNow.port.toIntOrNull()?.takeIf { it in 1..65535 } ?: cfg.defaultPort
            val machineNo = stateNow.machineNo.toIntOrNull()?.takeIf { it >= 1 } ?: cfg.defaultMachineNo
            val password = stateNow.commPassword.toIntOrNull()?.takeIf { it >= 0 } ?: cfg.defaultPassword
            val label = targetPrefix?.let { "$it.x" } ?: "mạng LAN"
            _uiState.update {
                it.copy(
                    isScanning = true,
                    scanCompleted = 0,
                    scanTotal = 254,
                    discoveredHosts = emptyList(),
                    errorMessage = null,
                    infoMessage = "Đang quét $label bằng Ping an toàn + xác nhận máy chấm công FK...",
                )
            }
            val hosts = scanLanDevicesUseCase(
                configuredIps = configuredIps,
                port = port,
                customPrefix = targetPrefix,
                machineNo = machineNo,
                password = password,
                license = cfg.defaultLicense,
                useUdp = stateNow.useUdp,
            ) { scanned, total ->
                _uiState.update { it.copy(scanCompleted = scanned, scanTotal = total) }
            }
            val fkCount = hosts.count { it.isFkTerminal }
            _uiState.update {
                it.copy(
                    isScanning = false,
                    discoveredHosts = hosts,
                    infoMessage = when {
                        fkCount > 0 -> "Phát hiện $fkCount máy chấm công FK trong ${hosts.size} thiết bị ($label)."
                        hosts.isNotEmpty() -> "Thấy ${hosts.size} thiết bị ở $label nhưng chưa máy nào mở cổng $port (kiểm tra app PC có đang giữ kết nối không)."
                        else -> "Không thấy thiết bị nào đáp Ping ở $label. Thử chọn dải mạng khác hoặc nhập IP thủ công."
                    },
                )
            }
        }
    }

    /** Kiểm tra kết nối thật (nếu `verifyFirst = true`) rồi lưu và nối máy vào danh sách chính. */
    fun saveDevice(verifyFirst: Boolean) {
        val s = _uiState.value
        if (s.isSaving) return
        val device = buildValidatedDevice(s) ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, infoMessage = "Đang kết nối thử tới ${device.ip}:${device.port}...") }
            if (verifyFirst) {
                val (ok, msg) = attendanceEngine.testConnectDevice(device)
                if (!ok) {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = "Chưa nối được ($msg). Kiểm tra: (1) Tắt phần mềm chấm công trên máy tính nếu đang giữ kết nối, (2) Đúng IP/cổng 5005, hoặc bấm \"Lưu luôn\" để nối sau.",
                            infoMessage = null,
                        )
                    }
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
        val validIp = parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        if (!validIp) {
            _uiState.update { it.copy(errorMessage = "Địa chỉ IP không hợp lệ (ví dụ: 192.168.1.201).") }
            return null
        }
        val portNum = s.port.toIntOrNull()?.takeIf { it in 1..65535 }
        if (portNum == null) {
            _uiState.update { it.copy(errorMessage = "Cổng không hợp lệ (mặc định 5005).") }
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
        )
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}

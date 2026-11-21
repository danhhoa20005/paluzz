package com.edusing.chamcongtvbox.presentation.adddevice

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.IpSuggestion
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.domain.model.SubnetInfo

/** Trạng thái UI màn hình Thêm & Nhận máy chấm công (hỗ trợ quét tức thì 1 chạm cho Hikvision & FK). */
data class AddDeviceUiState(
    val protocol: DeviceProtocol = DeviceProtocol.HIKVISION_ISAPI,
    val name: String = "",
    val ip: String = "",
    val port: String = DeviceConfig.DEFAULT_HIK_PORT.toString(),
    val machineNo: String = DeviceConfig.DEFAULT_MACHINE_NO.toString(),
    val commPassword: String = DeviceConfig.DEFAULT_PASSWORD.toString(),
    val useUdp: Boolean = false,
    val hikUsername: String = DeviceConfig.DEFAULT_HIK_USERNAME,
    val hikPassword: String = "",
    val hasSavedHikPassword: Boolean = false,
    val useHttps: Boolean = false,
    val showAdvanced: Boolean = false,
    val showOtherHosts: Boolean = false,
    val subnetInfo: SubnetInfo? = null,
    val scanPrefix: String = "",
    val quickPrefixes: List<String> = emptyList(),
    val ipSuggestion: IpSuggestion? = null,
    val isLoadingSuggestion: Boolean = false,
    val isScanning: Boolean = false,
    val scanCompleted: Int = 0,
    val scanTotal: Int = 254,
    val discoveredHosts: List<LanHostInfo> = emptyList(),
    val savingHostIp: String? = null,
    val quickAcceptHost: LanHostInfo? = null,
    val quickAcceptUsername: String = DeviceConfig.DEFAULT_HIK_USERNAME,
    val quickAcceptPassword: String = "",
    val quickAcceptError: String? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
) {
    /** Danh sách chỉ gồm các máy chấm công thật sự phát hiện được (Hikvision + FK). */
    val detectedTerminals: List<LanHostInfo> get() = discoveredHosts.filter { it.isDetectedTerminal }

    /** Các thiết bị mạng khác (router, PC, điện thoại...) xếp gọn vào mục mở rộng. */
    val otherLanHosts: List<LanHostInfo> get() = discoveredHosts.filterNot { it.isDetectedTerminal }
}

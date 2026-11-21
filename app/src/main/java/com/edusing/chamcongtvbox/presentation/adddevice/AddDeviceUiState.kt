package com.edusing.chamcongtvbox.presentation.adddevice

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.IpSuggestion
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.domain.model.SubnetInfo

/** Trạng thái UI màn hình Thêm máy chấm công mới (hỗ trợ cả FK cổng 5005 và Hikvision DS-K1T320MX cổng 80/443). */
data class AddDeviceUiState(
    val protocol: DeviceProtocol = DeviceProtocol.FK,
    val name: String = "",
    val ip: String = "",
    val port: String = DeviceConfig.DEFAULT_PORT.toString(),
    val machineNo: String = DeviceConfig.DEFAULT_MACHINE_NO.toString(),
    val commPassword: String = DeviceConfig.DEFAULT_PASSWORD.toString(),
    val useUdp: Boolean = false,
    val hikUsername: String = DeviceConfig.DEFAULT_HIK_USERNAME,
    val hikPassword: String = "",
    val useHttps: Boolean = false,
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

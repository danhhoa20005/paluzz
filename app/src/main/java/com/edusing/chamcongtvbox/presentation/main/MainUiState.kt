package com.edusing.chamcongtvbox.presentation.main

import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceRuntimeStatus
import com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice
import com.edusing.chamcongtvbox.domain.model.LiveEventEntry
import com.edusing.chamcongtvbox.domain.repository.InternetStatus

/** Trạng thái UI của màn hình Trạm trực (TV Box & Điện thoại). */
data class MainUiState(
    val activeMode: AppMode = AppMode.PALUZZ,
    val appTitle: String = "",
    val orgLabel: String = "Trường",
    val isOffice: Boolean = false,
    val orgName: String = "",
    val accountId: String = "",
    val apiDomain: String = "",
    val isRunning: Boolean = false,
    val isBusy: Boolean = false,
    val isDiscovering: Boolean = false,
    val statusHint: String = "",
    val internetStatus: InternetStatus = InternetStatus.CHECKING,
    val internetDetail: String = "",
    val hasLanOrWifi: Boolean = true,
    val wifiHardwareDisabled: Boolean = false,
    val devices: List<DeviceRuntimeStatus> = emptyList(),
    val unpairedHikvisionDevices: List<HikvisionSadpDevice> = emptyList(),
    val isPairingHikvision: Boolean = false,
    val pairHikvisionError: String? = null,
    val events: List<LiveEventEntry> = emptyList(),
    val lastPollTimeText: String = "",
    val totalCheckInsToday: Int = 0,
    val pendingCheckInCount: Int = 0,
    val showStopConfirmDialog: Boolean = false,
    val showLogoutConfirmDialog: Boolean = false,
    val showModePickerDialog: Boolean = false,
    val renameTarget: DeviceConfig? = null,
    val deleteTarget: DeviceConfig? = null,
) {
    val connectedCount: Int get() = devices.count { it.isConnected }
    val totalDeviceCount: Int get() = devices.size
}

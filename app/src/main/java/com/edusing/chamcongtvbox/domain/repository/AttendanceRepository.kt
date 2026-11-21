package com.edusing.chamcongtvbox.domain.repository

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem
import com.edusing.chamcongtvbox.domain.model.ServerCheckInResult

interface AttendanceRepository {
    suspend fun submitCheckInOut(
        apiDomain: String,
        accountId: String,
        studentCardId: String,
        device: DeviceConfig,
    ): ServerCheckInResult

    suspend fun sendHeartbeat(
        apiDomain: String,
        accountId: String,
        primaryMachineNo: Int,
        running: Boolean,
        devices: List<DeviceHeartbeatItem>,
    )

    fun lastKnownServerTimeMillis(): Long?

    /** Xoá bể kết nối HTTP cũ bị treo sau khi đổi Wi-Fi/LAN để request mới thông ngay lập tức. */
    fun resetNetworkConnections() = Unit
}

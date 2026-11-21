package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem
import com.edusing.chamcongtvbox.domain.model.DeviceRuntimeStatus
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository

/**
 * Gửi nhịp báo sống (Heartbeat) về máy chủ để trang giám sát biết TV Box còn hoạt động,
 * đang chạy hay đang dừng và trạng thái kết nối của từng máy chấm công.
 * Mọi lỗi mạng đều được nuốt để tuyệt đối không ảnh hưởng tới luồng chấm công chính.
 */
class SendHeartbeatUseCase(
    private val attendanceRepository: AttendanceRepository,
) {
    suspend operator fun invoke(
        apiDomain: String,
        accountId: String,
        running: Boolean,
        devices: List<DeviceRuntimeStatus>,
        diagnosticSuffix: String = "",
    ) {
        if (accountId.isBlank() || apiDomain.isBlank()) return
        val primaryMachineNo = devices.firstOrNull()?.config?.machineNo ?: 1
        val cleanDiag = diagnosticSuffix.trim()
        val payload = devices.map { runtime ->
            val combinedNote = if (cleanDiag.isBlank()) {
                runtime.statusText
            } else {
                "${runtime.statusText} • $cleanDiag"
            }
            DeviceHeartbeatItem(
                deviceName = runtime.config.displayName,
                deviceIp = runtime.config.ip,
                devicePort = runtime.config.port,
                deviceNo = runtime.config.machineNo,
                connected = runtime.isConnected,
                running = running && runtime.isConnected,
                note = combinedNote,
            )
        }
        runCatching {
            attendanceRepository.sendHeartbeat(
                apiDomain = apiDomain,
                accountId = accountId,
                primaryMachineNo = primaryMachineNo,
                running = running,
                devices = payload,
            )
        }
    }
}

package com.edusing.chamcongtvbox.domain.protocol

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceUser
import com.edusing.chamcongtvbox.domain.model.HikvisionDoorCommand
import com.edusing.chamcongtvbox.domain.model.HikvisionHealthStatus
import com.edusing.chamcongtvbox.domain.model.HikvisionHistoryItem
import com.edusing.chamcongtvbox.domain.model.HikvisionWebhookConfig

/**
 * Cổng điều khiển chuyên sâu cho máy chấm công khuôn mặt Hikvision `DS-K1T320MX` tầng Domain (100% thuần Kotlin):
 * sức khoẻ phần cứng, mở cửa, khoá IP tĩnh, nạp ảnh khuôn mặt AI (`FDLib`), chụp Camera, đồng bộ bù & tự tìm lại khi đổi IP.
 */
interface HikvisionControlGateway {
    suspend fun fetchHealth(device: DeviceConfig): Result<HikvisionHealthStatus>
    suspend fun controlDoor(device: DeviceConfig, command: HikvisionDoorCommand = HikvisionDoorCommand.OPEN, doorNo: Int = 1): Result<String>
    suspend fun syncVietnamTime(device: DeviceConfig): Result<String>
    suspend fun lockStaticIp(device: DeviceConfig): Result<String>
    suspend fun rebootDevice(device: DeviceConfig): Result<String>
    suspend fun captureSnapshot(device: DeviceConfig): Result<ByteArray>
    suspend fun uploadFacePhoto(device: DeviceConfig, employeeNo: String, jpegBytes: ByteArray): Result<String>
    suspend fun downloadFacePhoto(device: DeviceConfig, employeeNo: String): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("Chưa hỗ trợ tải khuôn mặt"))
    suspend fun fetchUserCards(device: DeviceConfig, employeeNo: String): List<String> = emptyList()
    suspend fun upsertUserWithOptionalFace(
        device: DeviceConfig,
        employeeNo: String,
        fullName: String,
        cardNo: String = "",
        jpegBytes: ByteArray? = null,
    ): Result<String>
    suspend fun fetchDetailedUsers(device: DeviceConfig): Result<List<DeviceUser>>
    suspend fun fetchHistoryEvents(device: DeviceConfig, sinceMs: Long, untilMs: Long): Result<List<HikvisionHistoryItem>>
    suspend fun deleteUserByEmployeeNo(device: DeviceConfig, employeeNo: String): Result<Unit>
    suspend fun fetchSerialNumber(device: DeviceConfig, timeoutMs: Int = 2_500): String?
    suspend fun downloadAvatarBytes(url: String): ByteArray?
    suspend fun fetchWebhookConfig(device: DeviceConfig): Result<HikvisionWebhookConfig>
    suspend fun configureWebhook(device: DeviceConfig, config: HikvisionWebhookConfig): Result<String>
    suspend fun clearWebhook(device: DeviceConfig): Result<String>
}

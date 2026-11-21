package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.BatchProgress
import com.edusing.chamcongtvbox.domain.model.DeviceUser
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.MemberRepository

/**
 * Đọc danh sách toàn bộ người dùng hiện có trên một máy chấm công
 * và tự đối chiếu họ tên đầy đủ + lớp/phòng ban từ máy chủ (nếu có mạng).
 */
class ReadDeviceUsersUseCase(
    private val deviceGateway: FkDeviceGateway,
    private val memberRepository: MemberRepository,
    private val configRepository: ConfigRepository,
) {
    suspend operator fun invoke(handle: Int): Result<List<DeviceUser>> {
        val idsRes = deviceGateway.readAllUserIds(handle)
        if (idsRes.code == FkErrorCode.ERR_LOG_END || idsRes.code == FkErrorCode.ERR_NO_MORE_DATA) {
            return Result.success(emptyList())
        }
        if (!idsRes.isSuccess) {
            return Result.failure(IllegalStateException("Đọc danh sách người lỗi: ${idsRes.message}"))
        }

        val uniqueIds = idsRes.data.orEmpty().map { it.enrollNumber }.distinct().sorted()

        // Tra cứu bổ sung họ tên có dấu và lớp từ server (không chặn nếu mất mạng)
        val serverMap = HashMap<Int, Pair<String, String>>()
        val cfg = configRepository.getConfig()
        if (cfg.token.isNotBlank()) {
            val domain = configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)
            val classMap = memberRepository.getClasses(domain, cfg.token)
                .getOrNull()?.associate { it.id to it.name }.orEmpty()
            val members = memberRepository.getStudents(domain, cfg.token).getOrNull().orEmpty()
            for (m in members) {
                val id = m.cardIdInt ?: continue
                serverMap[id] = m.name to (classMap[m.classId] ?: "")
            }
        }

        val users = uniqueIds.map { enrollNo ->
            val deviceName = deviceGateway.getUserName(handle, enrollNo).data?.trim().orEmpty()
            val serverInfo = serverMap[enrollNo]
            DeviceUser(
                enrollNumber = enrollNo,
                deviceName = deviceName,
                serverFullName = serverInfo?.first,
                className = serverInfo?.second?.ifBlank { null },
            )
        }
        return Result.success(users)
    }
}

/**
 * Xoá một hoặc nhiều người dùng khỏi máy chấm công (xoá toàn bộ backup 0..12 rồi lưu Flash).
 */
class DeleteDeviceUsersUseCase(
    private val deviceGateway: FkDeviceGateway,
) {
    suspend operator fun invoke(
        handle: Int,
        enrollNumbers: List<Int>,
        onProgress: (BatchProgress) -> Unit = {},
    ): Result<Int> {
        if (enrollNumbers.isEmpty()) return Result.success(0)
        val disableRes = deviceGateway.enableDevice(handle, enable = false)
        if (disableRes.isCommError) {
            return Result.failure(IllegalStateException(disableRes.message))
        }

        var deletedCount = 0
        try {
            for ((idx, enrollNo) in enrollNumbers.withIndex()) {
                var anyDeleted = false
                for (bk in 0..FkErrorCode.BACKUP_FACE) {
                    val res = deviceGateway.deleteEnrollData(handle, enrollNo, bk)
                    if (res.isSuccess) anyDeleted = true
                    if (res.isCommError) return Result.failure(IllegalStateException(res.message))
                }
                if (anyDeleted) deletedCount++
                onProgress(BatchProgress(idx + 1, enrollNumbers.size, "Đang xoá mã $enrollNo..."))
            }
            if (deletedCount > 0) deviceGateway.saveEnrollData(handle)
        } finally {
            deviceGateway.enableDevice(handle, enable = true)
        }
        return Result.success(deletedCount)
    }
}

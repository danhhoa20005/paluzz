package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.BatchProgress
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceUser
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.MemberRepository

/**
 * Đọc danh sách toàn bộ người dùng hiện có trên một máy chấm công (FK & Hikvision DS-K1T320MX)
 * và tự đối chiếu họ tên đầy đủ + lớp/phòng ban từ máy chủ (kèm số khuôn mặt/thẻ trên Hikvision).
 */
class ReadDeviceUsersUseCase(
    private val deviceGateway: FkDeviceGateway,
    private val memberRepository: MemberRepository,
    private val configRepository: ConfigRepository,
    private val hikControl: HikvisionControlGateway? = null,
) {
    suspend operator fun invoke(handle: Int, device: DeviceConfig? = null): Result<List<DeviceUser>> {
        // Tra cứu bổ sung họ tên có dấu và lớp từ server (không chặn nếu mất mạng)
        val serverMap = HashMap<String, Pair<String, String>>()
        val cfg = configRepository.getConfig()
        if (cfg.token.isNotBlank()) {
            val domain = configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)
            val classMap = memberRepository.getClasses(domain, cfg.token)
                .getOrNull()?.associate { it.id to it.name }.orEmpty()
            val members = memberRepository.getStudents(domain, cfg.token).getOrNull().orEmpty()
            for (m in members) {
                val info = m.name to (classMap[m.classId] ?: "")
                if (m.cardId.isNotBlank()) serverMap[m.cardId] = info
                if (m.rawCard.isNotBlank()) serverMap[m.rawCard] = info
            }
        }

        // Nhánh Hikvision: đọc trực tiếp nguyên mã gốc ("001" tách biệt "1") + số ảnh mặt + số thẻ
        if (device != null && device.isHikvision && hikControl != null) {
            val rawUsers = hikControl.fetchDetailedUsers(device).getOrElse { return Result.failure(it) }
            val enriched = rawUsers.map { u ->
                val serverInfo = serverMap[u.displayCardId] ?: serverMap[u.enrollNumber.toString()]
                u.copy(
                    serverFullName = serverInfo?.first,
                    className = serverInfo?.second?.ifBlank { null },
                )
            }
            return Result.success(enriched)
        }

        val idsRes = deviceGateway.readAllUserIds(handle)
        if (idsRes.code == FkErrorCode.ERR_LOG_END || idsRes.code == FkErrorCode.ERR_NO_MORE_DATA) {
            return Result.success(emptyList())
        }
        if (!idsRes.isSuccess) {
            return Result.failure(IllegalStateException("Đọc danh sách người lỗi: ${idsRes.message}"))
        }

        val uniqueIds = idsRes.data.orEmpty().map { it.enrollNumber }.distinct().sorted()
        val users = uniqueIds.map { enrollNo ->
            val deviceName = deviceGateway.getUserName(handle, enrollNo).data?.trim().orEmpty()
            val serverInfo = serverMap[enrollNo.toString()]
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
 * Xoá một hoặc nhiều người dùng khỏi máy chấm công (xoá toàn bộ backup 0..12 trên FK hoặc xoá theo `employeeNo` gốc trên Hikvision).
 */
class DeleteDeviceUsersUseCase(
    private val deviceGateway: FkDeviceGateway,
    private val hikControl: HikvisionControlGateway? = null,
) {
    suspend fun deleteEmployeeNos(
        device: DeviceConfig,
        employeeNos: List<String>,
        onProgress: (BatchProgress) -> Unit = {},
    ): Result<Int> {
        if (employeeNos.isEmpty()) return Result.success(0)
        val ctrl = hikControl ?: return Result.failure(IllegalStateException("Chưa hỗ trợ điều khiển Hikvision"))
        var deleted = 0
        for ((idx, empNo) in employeeNos.withIndex()) {
            val res = ctrl.deleteUserByEmployeeNo(device, empNo)
            if (res.isSuccess) deleted++ else return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Xoá mã $empNo thất bại"))
            onProgress(BatchProgress(idx + 1, employeeNos.size, "Đang xoá mã $empNo..."))
        }
        return Result.success(deleted)
    }

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

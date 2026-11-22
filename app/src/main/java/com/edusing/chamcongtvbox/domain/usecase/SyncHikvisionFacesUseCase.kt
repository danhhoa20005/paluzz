package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.BatchProgress
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.FaceSyncSummary
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway

/**
 * Nhân bản hồ sơ nhân sự + mã thẻ Mifare + ảnh khuôn mặt AI (`FDLib`) từ một máy Hikvision nguồn
 * sang một hoặc nhiều máy Hikvision đích trong cùng mạng LAN:
 * - Nhân viên chỉ cần đăng ký khuôn mặt **1 lần ở 1 máy**, hệ thống tự chép sang mọi máy còn lại.
 * - Chỉ tải ảnh mặt từ máy nguồn 1 lần/nhân sự và tự bỏ qua người đã có khuôn mặt trên máy đích.
 */
class SyncHikvisionFacesUseCase(
    private val hikControl: HikvisionControlGateway,
) {
    suspend operator fun invoke(
        source: DeviceConfig,
        targets: List<DeviceConfig>,
        skipExistingFace: Boolean = true,
        onProgress: (BatchProgress) -> Unit = {},
    ): Result<FaceSyncSummary> {
        if (!source.isHikvision) {
            return Result.failure(IllegalArgumentException("Máy nguồn phải là thiết bị Hikvision."))
        }
        val validTargets = targets.filter { it.isHikvision && it.key != source.key }
        if (validTargets.isEmpty()) {
            return Result.failure(IllegalArgumentException("Cần có ít nhất 2 máy Hikvision để đồng bộ khuôn mặt chéo."))
        }

        val sourceUsers = hikControl.fetchDetailedUsers(source)
            .getOrElse { return Result.failure(it) }
            .filter { it.faceCount > 0 }
        if (sourceUsers.isEmpty()) {
            return Result.success(
                FaceSyncSummary(0, 0, 0, 0, "Máy ${source.displayName} chưa có nhân sự nào đăng ký khuôn mặt."),
            )
        }

        val targetPlans = mutableListOf<Pair<DeviceConfig, List<com.edusing.chamcongtvbox.domain.model.DeviceUser>>>()
        var skippedTotal = 0
        var failedTotal = 0
        var firstError: String? = null

        for (target in validTargets) {
            val destUsersRes = hikControl.fetchDetailedUsers(target)
            if (destUsersRes.isFailure) {
                failedTotal += sourceUsers.size
                if (firstError == null) firstError = "${target.displayName}: ${destUsersRes.exceptionOrNull()?.message}"
                continue
            }
            val existingFaces = destUsersRes.getOrNull().orEmpty()
                .filter { it.faceCount > 0 }
                .map { it.displayCardId }
                .toHashSet()
            val needed = if (skipExistingFace) {
                sourceUsers.filter { it.displayCardId !in existingFaces }
            } else {
                sourceUsers
            }
            skippedTotal += (sourceUsers.size - needed.size)
            if (needed.isNotEmpty()) targetPlans.add(target to needed)
        }

        val totalWork = targetPlans.sumOf { it.second.size }
        if (totalWork == 0) {
            val msg = if (failedTotal > 0) {
                "Không nối được máy đích ($firstError)"
            } else {
                "Tất cả ${sourceUsers.size} khuôn mặt đã có đủ trên ${validTargets.size} máy Hikvision đích."
            }
            return Result.success(FaceSyncSummary(sourceUsers.size, 0, skippedTotal, failedTotal, msg))
        }

        val faceCache = HashMap<String, ByteArray>()
        val cardCache = HashMap<String, String>()
        var done = 0
        var syncedTotal = 0

        for ((target, needed) in targetPlans) {
            for (user in needed) {
                val empNo = user.displayCardId
                val name = user.deviceName.ifBlank { user.serverFullName.orEmpty() }.ifBlank { "NV $empNo" }
                onProgress(BatchProgress(done, totalWork, "${target.displayName}: $name (#$empNo)"))

                val faceBytes = faceCache[empNo] ?: hikControl.downloadFacePhoto(source, empNo).getOrNull()?.also {
                    faceCache[empNo] = it
                }
                if (faceBytes == null) {
                    failedTotal++
                    done++
                    continue
                }
                val cardNo = cardCache.getOrPut(empNo) {
                    hikControl.fetchUserCards(source, empNo).firstOrNull()
                        ?: if (empNo.all(Char::isDigit)) empNo else ""
                }
                val pushRes = hikControl.upsertUserWithOptionalFace(target, empNo, name, cardNo, faceBytes)
                if (pushRes.isSuccess) syncedTotal++ else {
                    failedTotal++
                    if (firstError == null) firstError = pushRes.exceptionOrNull()?.message
                }
                done++
                onProgress(BatchProgress(done, totalWork, "${target.displayName}: $name (#$empNo)"))
            }
        }

        val msg = buildString {
            append("Nguồn ${source.displayName} (${sourceUsers.size} mặt) → ${validTargets.size} máy: ")
            append("đã chép $syncedTotal")
            if (skippedTotal > 0) append(" • đã có sẵn $skippedTotal")
            if (failedTotal > 0) append(" • lỗi $failedTotal")
        }
        return Result.success(FaceSyncSummary(sourceUsers.size, syncedTotal, skippedTotal, failedTotal, msg))
    }
}

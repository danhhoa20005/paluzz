package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.BatchProgress
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DevicePushOutcome
import com.edusing.chamcongtvbox.domain.model.Member
import com.edusing.chamcongtvbox.domain.model.PushResultSummary
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway

/** Cặp thông tin cấu hình máy và handle kết nối đang mở. */
data class ConnectedDeviceHandle(
    val device: DeviceConfig,
    val handle: Int,
)

/**
 * Đẩy danh sách nhân sự/học sinh xuống 1 hoặc nhiều máy chấm công (FK & mọi dòng Hikvision):
 * - **Delta Fast-Path**: bỏ qua ngay khi mọi người đã có trên máy (0 khoá máy, 0 ghi Flash).
 * - **Máy Hikvision**: giữ nguyên mã `studentCardId` gốc (`"001"`, `"NV01"`, thẻ 10 số), nạp tên tiếng Việt UTF-8
 *   đầy đủ, gắn thẻ Mifare (nếu thuần số) và tự đẩy ảnh khuôn mặt AI (`FDLib`) nếu máy chủ có ảnh đại diện.
 */
class PushMembersToDevicesUseCase(
    private val deviceGateway: FkDeviceGateway,
    private val abbreviateNamesUseCase: AbbreviateNamesUseCase,
    private val hikControl: HikvisionControlGateway? = null,
) {
    suspend operator fun invoke(
        targets: List<ConnectedDeviceHandle>,
        members: List<Member>,
        skipExistingOnDevice: Boolean = true,
        maxNameBytes: Int = AbbreviateNamesUseCase.MAX_NAME_BYTES,
        onProgress: (BatchProgress) -> Unit = {},
    ): PushResultSummary {
        val totalOps = targets.sumOf { t ->
            members.count { it.isPushableFor(t.device.isHikvision && hikControl != null) }
        }.coerceAtLeast(1)
        var completed = 0
        var totalOk = 0
        var totalSkipped = 0
        var totalFail = 0
        val outcomes = mutableListOf<DevicePushOutcome>()

        for (target in targets) {
            val ctrl = hikControl
            if (target.device.isHikvision && ctrl != null) {
                val (outcome, doneDelta) = pushToHikvisionExact(target, ctrl, members, skipExistingOnDevice, completed, totalOps, onProgress)
                outcomes.add(outcome)
                totalOk += outcome.succeeded; totalSkipped += outcome.skippedExisting; totalFail += outcome.failed
                completed += doneDelta
                continue
            }

            val pushable = members.filter { it.cardIdInt != null }
            val existingRes = deviceGateway.readAllUserIds(target.handle)
            if (existingRes.isCommError) {
                outcomes.add(DevicePushOutcome(target.device.displayName, target.device.ip, 0, 0, pushable.size, false, existingRes.message))
                totalFail += pushable.size; completed += pushable.size
                onProgress(BatchProgress(completed, totalOps, target.device.displayName))
                continue
            }

            val existingIds = existingRes.data.orEmpty().map { it.enrollNumber }.distinct()
            val existingCards = existingIds.toHashSet()
            val toPush = if (skipExistingOnDevice) pushable.filter { it.cardIdInt !in existingCards } else pushable
            val skippedNow = pushable.size - toPush.size

            if (toPush.isEmpty()) {
                totalSkipped += skippedNow; completed += pushable.size
                onProgress(BatchProgress(completed, totalOps, "${target.device.displayName}: Đã đồng bộ đủ"))
                outcomes.add(DevicePushOutcome(target.device.displayName, target.device.ip, 0, skippedNow, 0, true))
                continue
            }

            val isHik = target.device.isHikvision
            val shortNames = if (isHik) {
                toPush.map { it.name.trim().take(32) }
            } else {
                val existingNames = HashSet<String>(existingIds.size)
                for (id in existingIds) {
                    val name = deviceGateway.getUserName(target.handle, id).data?.trim().orEmpty()
                    if (name.isNotEmpty()) existingNames.add(name)
                }
                abbreviateNamesUseCase.abbreviateBatch(toPush.map { it.name }, existingNames, maxNameBytes)
            }

            val disableRes = deviceGateway.enableDevice(target.handle, enable = false)
            if (disableRes.isCommError) {
                outcomes.add(DevicePushOutcome(target.device.displayName, target.device.ip, 0, skippedNow, toPush.size, false, disableRes.message))
                totalSkipped += skippedNow; totalFail += toPush.size; completed += pushable.size
                onProgress(BatchProgress(completed, totalOps, target.device.displayName))
                continue
            }

            var devOk = 0; var devFail = 0; var savedOk = false; var devError: String? = null
            totalSkipped += skippedNow; completed += skippedNow
            try {
                for ((idx, member) in toPush.withIndex()) {
                    val cardInt = member.cardIdInt ?: continue
                    val shortName = shortNames[idx]
                    val putRes = deviceGateway.putCardEnrollData(target.handle, cardInt, cardInt, privilege = 0)
                    val finalRes = if (putRes.isSuccess && shortName.isNotEmpty()) {
                        val nameRes = deviceGateway.setUserName(target.handle, cardInt, shortName)
                        if (isHik) nameRes else putRes
                    } else putRes
                    if (finalRes.isSuccess) {
                        existingCards.add(cardInt); devOk++; totalOk++
                    } else {
                        devFail++; totalFail++
                        if (devError == null) devError = finalRes.message
                        if (finalRes.isCommError) break
                    }
                    completed++
                    onProgress(BatchProgress(completed, totalOps, "${target.device.displayName}: ${member.name} ($shortName)"))
                }
                if (devOk > 0) {
                    val saveRes = deviceGateway.saveEnrollData(target.handle)
                    savedOk = saveRes.isSuccess
                    if (!savedOk) devError = saveRes.message
                } else savedOk = true
            } finally {
                deviceGateway.enableDevice(target.handle, enable = true)
            }
            outcomes.add(DevicePushOutcome(target.device.displayName, target.device.ip, devOk, skippedNow, devFail, savedOk, devError))
        }

        val totalRequested = outcomes.sumOf { it.succeeded + it.skippedExisting + it.failed }
        return PushResultSummary(totalRequested, totalOk, totalSkipped, totalFail, outcomes)
    }

    /** Đẩy chính xác nguyên mã gốc (`"001"`, `"NV01"`, thẻ 10 số) + họ tên UTF-8 + ảnh mặt AI xuống máy Hikvision. */
    private suspend fun pushToHikvisionExact(
        target: ConnectedDeviceHandle,
        ctrl: HikvisionControlGateway,
        members: List<Member>,
        skipExistingOnDevice: Boolean,
        completedStart: Int,
        totalOps: Int,
        onProgress: (BatchProgress) -> Unit,
    ): Pair<DevicePushOutcome, Int> {
        val pushable = members.filter { it.isPushableToHikvision }
        val existingRes = ctrl.fetchDetailedUsers(target.device)
        if (existingRes.isFailure) {
            val err = existingRes.exceptionOrNull()?.message ?: "Mất kết nối Hikvision"
            onProgress(BatchProgress(completedStart + pushable.size, totalOps, target.device.displayName))
            return DevicePushOutcome(target.device.displayName, target.device.ip, 0, 0, pushable.size, false, err) to pushable.size
        }

        val existingEmpNos = existingRes.getOrNull().orEmpty().map { it.displayCardId }.toHashSet()
        val toPush = if (skipExistingOnDevice) pushable.filter { it.hikvisionEmployeeNo !in existingEmpNos } else pushable
        val skippedNow = pushable.size - toPush.size
        if (toPush.isEmpty()) {
            onProgress(BatchProgress(completedStart + pushable.size, totalOps, "${target.device.displayName}: Đã đồng bộ đủ"))
            return DevicePushOutcome(target.device.displayName, target.device.ip, 0, skippedNow, 0, true) to pushable.size
        }

        var done = skippedNow; var ok = 0; var fail = 0; var firstErr: String? = null
        for (member in toPush) {
            val empNo = member.hikvisionEmployeeNo
            val fullName = member.name.trim().take(32)
            val cardNo = if (empNo.all(Char::isDigit)) empNo else ""
            val avatar = if (member.avatarUrl.isNotBlank()) runCatching { ctrl.downloadAvatarBytes(member.avatarUrl) }.getOrNull() else null
            // Nạp hồ sơ trước; nếu ảnh avatar không rõ mặt vẫn đảm bảo nạp thành công hồ sơ nhân viên
            val res = ctrl.upsertUserWithOptionalFace(target.device, empNo, fullName, cardNo, jpegBytes = null)
            if (res.isSuccess) {
                if (avatar != null) runCatching { ctrl.uploadFacePhoto(target.device, empNo, avatar) }
                ok++
            } else {
                fail++
                if (firstErr == null) firstErr = res.exceptionOrNull()?.message
            }
            done++
            onProgress(BatchProgress(completedStart + done, totalOps, "${target.device.displayName}: $fullName (#$empNo)"))
        }
        return DevicePushOutcome(target.device.displayName, target.device.ip, ok, skippedNow, fail, savedOk = fail == 0, error = firstErr) to pushable.size
    }
}

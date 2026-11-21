package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.BatchProgress
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DevicePushOutcome
import com.edusing.chamcongtvbox.domain.model.Member
import com.edusing.chamcongtvbox.domain.model.PushResultSummary
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway

/** Cặp thông tin cấu hình máy và handle kết nối đang mở. */
data class ConnectedDeviceHandle(
    val device: DeviceConfig,
    val handle: Int,
)

/**
 * Đẩy danh sách người dùng xuống 1 hoặc nhiều máy chấm công (tối ưu Delta Fast-Path):
 * 1. Đọc danh sách mã thẻ hiện có trên máy trước (không khoá máy).
 * 2. Nếu mọi thẻ cần đẩy đều đã có trên máy và `skipExistingOnDevice = true` -> bỏ qua ngay
 *    (không đọc hàng trăm tên, không khoá màn hình máy quét, không ghi mòn Flash).
 * 3. Chỉ khi thực sự có người mới mới đọc tên chống trùng -> khoá máy -> ghi thẻ + tên -> lưu Flash -> mở máy.
 */
class PushMembersToDevicesUseCase(
    private val deviceGateway: FkDeviceGateway,
    private val abbreviateNamesUseCase: AbbreviateNamesUseCase,
) {
    suspend operator fun invoke(
        targets: List<ConnectedDeviceHandle>,
        members: List<Member>,
        skipExistingOnDevice: Boolean = true,
        maxNameBytes: Int = AbbreviateNamesUseCase.MAX_NAME_BYTES,
        onProgress: (BatchProgress) -> Unit = {},
    ): PushResultSummary {
        val pushable = members.filter { it.cardIdInt != null }
        val totalOps = (targets.size * pushable.size).coerceAtLeast(1)
        var completed = 0
        var totalOk = 0
        var totalSkipped = 0
        var totalFail = 0
        val outcomes = mutableListOf<DevicePushOutcome>()

        for (target in targets) {
            val existingRes = deviceGateway.readAllUserIds(target.handle)
            if (existingRes.isCommError) {
                outcomes.add(
                    DevicePushOutcome(
                        deviceName = target.device.displayName,
                        deviceIp = target.device.ip,
                        succeeded = 0,
                        skippedExisting = 0,
                        failed = pushable.size,
                        savedOk = false,
                        error = existingRes.message,
                    )
                )
                totalFail += pushable.size
                completed += pushable.size
                onProgress(BatchProgress(completed, totalOps, target.device.displayName))
                continue
            }

            val existingIds = existingRes.data.orEmpty().map { it.enrollNumber }.distinct()
            val existingCards = existingIds.toHashSet()
            val toPush = if (skipExistingOnDevice) {
                pushable.filter { it.cardIdInt !in existingCards }
            } else {
                pushable
            }
            val skippedNow = pushable.size - toPush.size

            // Đường tắt Delta Fast-Path: không có người mới -> 0 khóa máy, 0 đọc tên, 0 ghi Flash
            if (toPush.isEmpty()) {
                totalSkipped += skippedNow
                completed += pushable.size
                onProgress(BatchProgress(completed, totalOps, "${target.device.displayName}: Đã đồng bộ đủ"))
                outcomes.add(
                    DevicePushOutcome(
                        deviceName = target.device.displayName,
                        deviceIp = target.device.ip,
                        succeeded = 0,
                        skippedExisting = skippedNow,
                        failed = 0,
                        savedOk = true,
                    )
                )
                continue
            }

            val existingNames = HashSet<String>(existingIds.size)
            for (id in existingIds) {
                val name = deviceGateway.getUserName(target.handle, id).data?.trim().orEmpty()
                if (name.isNotEmpty()) existingNames.add(name)
            }

            val shortNames = abbreviateNamesUseCase.abbreviateBatch(
                fullNames = toPush.map { it.name },
                existingNamesOnDevice = existingNames,
                maxNameBytes = maxNameBytes,
            )

            val disableRes = deviceGateway.enableDevice(target.handle, enable = false)
            if (disableRes.isCommError) {
                outcomes.add(
                    DevicePushOutcome(
                        deviceName = target.device.displayName,
                        deviceIp = target.device.ip,
                        succeeded = 0,
                        skippedExisting = skippedNow,
                        failed = toPush.size,
                        savedOk = false,
                        error = disableRes.message,
                    )
                )
                totalSkipped += skippedNow
                totalFail += toPush.size
                completed += pushable.size
                onProgress(BatchProgress(completed, totalOps, target.device.displayName))
                continue
            }

            var devOk = 0
            var devFail = 0
            var savedOk = false
            var devError: String? = null
            totalSkipped += skippedNow
            completed += skippedNow

            try {
                for ((idx, member) in toPush.withIndex()) {
                    val cardInt = member.cardIdInt ?: continue
                    val shortName = shortNames[idx]
                    val putRes = deviceGateway.putCardEnrollData(target.handle, cardInt, cardInt, privilege = 0)
                    if (putRes.isSuccess) {
                        if (shortName.isNotEmpty()) {
                            deviceGateway.setUserName(target.handle, cardInt, shortName)
                        }
                        existingCards.add(cardInt)
                        devOk++
                        totalOk++
                    } else {
                        devFail++
                        totalFail++
                        if (devError == null) devError = putRes.message
                        if (putRes.isCommError) break
                    }

                    completed++
                    onProgress(
                        BatchProgress(
                            completed = completed,
                            total = totalOps,
                            currentLabel = "${target.device.displayName}: ${member.name} ($shortName)",
                        )
                    )
                }

                if (devOk > 0) {
                    val saveRes = deviceGateway.saveEnrollData(target.handle)
                    savedOk = saveRes.isSuccess
                    if (!savedOk) devError = saveRes.message
                } else {
                    savedOk = true
                }
            } finally {
                deviceGateway.enableDevice(target.handle, enable = true)
            }

            outcomes.add(
                DevicePushOutcome(
                    deviceName = target.device.displayName,
                    deviceIp = target.device.ip,
                    succeeded = devOk,
                    skippedExisting = skippedNow,
                    failed = devFail,
                    savedOk = savedOk,
                    error = devError,
                )
            )
        }

        return PushResultSummary(
            totalRequested = pushable.size * targets.size,
            succeeded = totalOk,
            skippedExisting = totalSkipped,
            failed = totalFail,
            perDeviceResults = outcomes,
        )
    }
}

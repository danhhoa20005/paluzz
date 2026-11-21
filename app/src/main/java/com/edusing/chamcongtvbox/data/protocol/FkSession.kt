package com.edusing.chamcongtvbox.data.protocol

import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phiên làm việc an toàn đa luồng với một máy chấm công FK.
 * Dùng [Mutex] tuần tự hoá mọi lệnh vì phần cứng máy chấm chỉ xử lý từng lệnh một.
 */
class FkSession(
    val info: FkConnectionInfo,
    private val transport: FkTransport,
    private val legacyLogType: Int,
) {
    private val mutex = Mutex()
    private val machineNo: Int get() = info.machineNo
    private val profile: FkFirmwareProfile get() = info.profile

    suspend fun enableDevice(enable: Boolean): FkResult<Unit> = mutex.withLock {
        val resp = transport.sendCommand(
            machineNo = machineNo,
            cmd = FkCommands.ENABLE_DEVICE,
            param = if (enable) 1 else 0,
            timeoutMs = FAST_CMD_TIMEOUT_MS,
        )
        if (resp.isSuccess) FkResult.ok(Unit) else FkResult.fail(resp.resultCode)
    }

    suspend fun emptyGeneralLogData(): FkResult<Unit> = mutex.withLock {
        val resp = transport.sendCommand(
            machineNo = machineNo,
            cmd = FkCommands.EMPTY_GENERAL_LOG,
            param = 0,
            timeoutMs = 26_000,
        )
        if (resp.isSuccess) FkResult.ok(Unit) else FkResult.fail(resp.resultCode)
    }

    suspend fun loadGeneralLogData(readMark: Boolean): FkResult<List<AttendanceLog>> = mutex.withLock {
        val statusIdx = if (readMark) FkCommands.STATUS_UNREAD_GLOG else FkCommands.STATUS_TOTAL_GLOG
        val countRes = getDeviceStatusLocked(statusIdx)
        if (!countRes.isSuccess) return FkResult.fail(countRes.code)
        val count = countRes.data ?: 0
        if (count <= 0) return FkResult.fail(FkErrorCode.ERR_LOG_END)

        val recordSize = FkAttendanceLogParser.recordSize(profile, legacyLogType).coerceAtLeast(1)
        val totalBytesLong = count.toLong() * recordSize.toLong()
        if (count > MAX_SAFE_RECORD_COUNT || totalBytesLong !in 1L..MAX_SAFE_READ_BYTES) {
            return FkResult.fail(FkErrorCode.ERR_INVALID_DATA, "Số bản ghi chấm công bất thường ($count)")
        }
        val cmd = if (readMark) FkCommands.READ_UNREAD_GLOG else FkCommands.READ_ALL_GLOG
        val readResp = transport.readData(machineNo, cmd, totalLength = totalBytesLong.toInt(), timeoutMs = 30_000)
        if (!readResp.isSuccess) return FkResult.fail(readResp.resultCode)

        val parsed = FkAttendanceLogParser.parseLogs(profile, readResp.data, count, legacyLogType)
        if (parsed.isEmpty()) {
            val preview = readResp.data.take(16).joinToString("") { "%02X".format(it) }
            return FkResult.fail(
                FkErrorCode.ERR_INVALID_DATA,
                "Đọc $count bản ghi (${profile.displayName}) nhưng giải mã 0 lượt [hex=$preview]",
            )
        }
        if (readMark) {
            val markResp = transport.sendCommand(machineNo, FkCommands.MARK_GLOG_READ, param = 2, timeoutMs = 60_000)
            if (!markResp.isSuccess && markResp.resultCode == FkErrorCode.ERR_COMM) {
                return FkResult.fail(markResp.resultCode)
            }
        }
        FkResult.ok(parsed)
    }

    suspend fun readAllUserIds(): FkResult<List<FkUserCredential>> = mutex.withLock {
        val totalCount = when (profile) {
            FkFirmwareProfile.CIF10, FkFirmwareProfile.CIF10_1 -> sumStatusesLocked(intArrayOf(3, 4, 9, 10, 40))
            FkFirmwareProfile.GEN, FkFirmwareProfile.PS, FkFirmwareProfile.PS_EXCEL -> sumStatusesLocked(intArrayOf(3, 4, 9))
            else -> sumStatusesLocked(intArrayOf(2, 1)) // User (2) + Manager (1)
        }
        if (!totalCount.isSuccess) return FkResult.fail(totalCount.code)
        val count = totalCount.data ?: 0
        if (count <= 0) return FkResult.ok(emptyList())

        val recordSize = if (profile == FkFirmwareProfile.CIF15 || profile == FkFirmwareProfile.CIF15_1) 10 else 8
        val totalBytesLong = count.toLong() * recordSize.toLong()
        if (count > MAX_SAFE_RECORD_COUNT || totalBytesLong !in 1L..MAX_SAFE_READ_BYTES) {
            return FkResult.fail(FkErrorCode.ERR_INVALID_DATA, "Số lượng người dùng bất thường ($count)")
        }
        val totalBytes = totalBytesLong.toInt()
        val readResp = transport.readData(
            machineNo = machineNo,
            cmd = FkCommands.READ_ALL_USER_ID,
            totalLength = totalBytes,
            param = totalBytes,
            timeoutMs = 30_000,
        )
        if (!readResp.isSuccess) return FkResult.fail(readResp.resultCode)
        FkResult.ok(FkUserEnrollParser.parseUserIds(profile, readResp.data, count))
    }

    suspend fun putCardEnrollData(enrollNumber: Int, cardNumber: Int, privilege: Int): FkResult<Unit> = mutex.withLock {
        val primaryResp = sendPutCardLocked(enrollNumber, cardNumber, privilege, includeEncryptedCardData = true)
        if (primaryResp.isSuccess) return FkResult.ok(Unit)

        // Dự phòng cho một số dòng firmware từ chối ô dữ liệu thẻ mã hoá
        if (primaryResp.resultCode == FkErrorCode.ERR_DEVICE_FAIL) {
            val fallbackResp = sendPutCardLocked(enrollNumber, cardNumber, privilege, includeEncryptedCardData = false)
            return if (fallbackResp.isSuccess) FkResult.ok(Unit) else FkResult.fail(fallbackResp.resultCode)
        }
        FkResult.fail(primaryResp.resultCode)
    }

    suspend fun saveEnrollData(): FkResult<Unit> = mutex.withLock {
        val resp = transport.sendCommand(machineNo, FkCommands.SAVE_ENROLL_DATA, param = 1, timeoutMs = 60_000)
        if (resp.isSuccess) FkResult.ok(Unit) else FkResult.fail(resp.resultCode)
    }

    suspend fun deleteEnrollData(enrollNumber: Int, backupNumber: Int): FkResult<Unit> = mutex.withLock {
        val resp = transport.sendCommand(
            machineNo = machineNo,
            cmd = FkCommands.DELETE_ENROLL_DATA,
            param = enrollNumber,
            inParam = backupNumber,
            timeoutMs = 6_000,
        )
        if (resp.isSuccess) FkResult.ok(Unit) else FkResult.fail(resp.resultCode)
    }

    suspend fun getUserName(enrollNumber: Int): FkResult<String> = mutex.withLock {
        FkUserNameCodec.readUserName(transport, machineNo, profile, enrollNumber)
    }

    suspend fun setUserName(enrollNumber: Int, name: String): FkResult<Unit> = mutex.withLock {
        FkUserNameCodec.writeUserName(transport, machineNo, profile, enrollNumber, name)
    }

    suspend fun close() = mutex.withLock { transport.close() }

    private fun getDeviceStatusLocked(statusIndex: Int): FkResult<Int> {
        val wireIdx = if (profile == FkFirmwareProfile.LFW) {
            FkDeviceInfoParser.mapLfwStatusIndex(statusIndex)
        } else {
            statusIndex
        }
        val resp = transport.sendCommand(machineNo, FkCommands.GET_DEVICE_STATUS, param = wireIdx, timeoutMs = FAST_CMD_TIMEOUT_MS)
        return if (resp.isSuccess) FkResult.ok(resp.outParam) else FkResult.fail(resp.resultCode)
    }

    private fun sumStatusesLocked(indices: IntArray): FkResult<Int> {
        var total = 0
        for (idx in indices) {
            val res = getDeviceStatusLocked(idx)
            if (!res.isSuccess) return res
            total += (res.data ?: 0).coerceAtLeast(0)
        }
        return FkResult.ok(total)
    }

    private fun sendPutCardLocked(
        enrollNumber: Int,
        cardNumber: Int,
        privilege: Int,
        includeEncryptedCardData: Boolean,
    ): FkTransportResponse {
        val payload = FkUserEnrollParser.buildCardEnrollPayload(
            profile = profile,
            enrollNumber = enrollNumber,
            cardNumber = cardNumber,
            privilege = privilege,
            includeEncryptedCardData = includeEncryptedCardData,
        )
        return if (profile == FkFirmwareProfile.LFW) {
            transport.writeData(machineNo, FkCommands.LFW_PUT_ENROLL_DATA, payload, param = enrollNumber, inParam = FkErrorCode.BACKUP_CARD, timeoutMs = 15_000)
        } else {
            transport.writeData(machineNo, FkCommands.PUT_ENROLL_DATA, payload, param = FkErrorCode.BACKUP_CARD, inParam = 0, timeoutMs = 15_000)
        }
    }

    companion object {
        private const val FAST_CMD_TIMEOUT_MS = 4_000
        private const val MAX_SAFE_RECORD_COUNT = 200_000
        private const val MAX_SAFE_READ_BYTES = 8L * 1024L * 1024L
    }
}

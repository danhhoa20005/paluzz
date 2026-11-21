package com.edusing.chamcongtvbox.data.protocol

import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import java.util.Calendar

/**
 * Đọc và ghi tên người dùng theo đúng định dạng bộ đệm của từng dòng firmware FK
 * (`UTF-16LE` 14B/32B/128B hoặc `ASCII` trên các dòng PS/GEN đời cũ).
 */
internal object FkUserNameCodec {

    fun readUserName(
        transport: FkTransport,
        machineNo: Int,
        profile: FkFirmwareProfile,
        enrollNumber: Int,
    ): FkResult<String> {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1

        return when (profile) {
            FkFirmwareProfile.CIF11_1, FkFirmwareProfile.CIF11_2, FkFirmwareProfile.CIF15 -> {
                val resp = transport.readData(machineNo, FkCommands.GET_USER_NAME, totalLength = 14, param = enrollNumber)
                if (!resp.isSuccess) FkResult.fail(resp.resultCode)
                else FkResult.ok(BinaryUtils.decodeUtf16LeNullTerminated(resp.data, 0, 14))
            }

            FkFirmwareProfile.CIF15_1 -> {
                val resp = transport.readData(machineNo, FkCommands.GET_USER_NAME, totalLength = 128, param = enrollNumber)
                if (!resp.isSuccess) FkResult.fail(resp.resultCode)
                else FkResult.ok(BinaryUtils.decodeUtf16LeNullTerminated(resp.data, 0, 128))
            }

            FkFirmwareProfile.CIF11 -> {
                val inParam = FkUserEnrollParser.packGetUserInfoInParam(year, month)
                val resp = transport.readData(machineNo, FkCommands.GET_USER_INFO, totalLength = 0x40, param = enrollNumber, inParam = inParam)
                if (!resp.isSuccess) FkResult.fail(resp.resultCode)
                else FkResult.ok(BinaryUtils.decodeUtf16LeNullTerminated(resp.data, 17, 14))
            }

            FkFirmwareProfile.CIF12, FkFirmwareProfile.CIF12_1, FkFirmwareProfile.CIF12_2,
            FkFirmwareProfile.CIF13 -> {
                val inParam = FkUserEnrollParser.packGetUserInfoInParam(year, month)
                val resp = transport.readData(machineNo, FkCommands.GET_USER_INFO, totalLength = 0xB0, param = enrollNumber, inParam = inParam)
                if (!resp.isSuccess) FkResult.fail(resp.resultCode)
                else FkResult.ok(BinaryUtils.decodeUtf16LeNullTerminated(resp.data, 8, 128))
            }

            FkFirmwareProfile.CIF10, FkFirmwareProfile.CIF10_1 -> {
                val resp = transport.readData(machineNo, FkCommands.GET_USER_INFO, totalLength = 0x204, param = enrollNumber, inParam = 0x14)
                if (!resp.isSuccess) FkResult.fail(resp.resultCode)
                else FkResult.ok(BinaryUtils.decodeUtf16LeNullTerminated(resp.data, 4, 32))
            }

            FkFirmwareProfile.LFW -> {
                val resp = transport.readData(machineNo, FkCommands.LFW_GET_USER_INFO, totalLength = 0xCC, param = enrollNumber)
                if (!resp.isSuccess) FkResult.fail(resp.resultCode)
                else FkResult.ok(BinaryUtils.decodeUtf16LeNullTerminated(resp.data, 0x44, 32))
            }

            FkFirmwareProfile.PS, FkFirmwareProfile.PS_EXCEL -> {
                val resp = transport.readData(machineNo, FkCommands.GET_USER_NAME, totalLength = 0x2C, param = enrollNumber)
                if (!resp.isSuccess) FkResult.fail(resp.resultCode)
                else if (resp.data.size >= 0x2C && BinaryUtils.u8(resp.data, 2) == 1) {
                    FkResult.ok(BinaryUtils.decodeAsciiNullTerminated(resp.data, 16, 28))
                } else {
                    FkResult.ok("")
                }
            }

            FkFirmwareProfile.CIF14, FkFirmwareProfile.CIF14_1, FkFirmwareProfile.GEN -> {
                val resp = transport.readData(machineNo, FkCommands.GET_USER_NAME, totalLength = 20, param = enrollNumber)
                if (!resp.isSuccess) FkResult.fail(resp.resultCode)
                else FkResult.ok(BinaryUtils.decodeAsciiNullTerminated(resp.data, 0, 20))
            }
        }
    }

    fun writeUserName(
        transport: FkTransport,
        machineNo: Int,
        profile: FkFirmwareProfile,
        enrollNumber: Int,
        name: String,
    ): FkResult<Unit> {
        if (name.isEmpty()) return FkResult.fail(FkErrorCode.ERR_BAD_PARAM)

        val resp = when (profile) {
            FkFirmwareProfile.CIF11_1, FkFirmwareProfile.CIF11_2, FkFirmwareProfile.CIF15 -> {
                transport.writeData(machineNo, FkCommands.SET_USER_NAME, BinaryUtils.encodeUtf16LeFixed(name, 14), param = enrollNumber)
            }

            FkFirmwareProfile.CIF15_1 -> {
                transport.writeData(machineNo, FkCommands.SET_USER_NAME, BinaryUtils.encodeUtf16LeFixed(name, 128), param = enrollNumber)
            }

            FkFirmwareProfile.CIF11 -> {
                val buf = ByteArray(0x40)
                BinaryUtils.putU32LE(buf, 4, enrollNumber)
                System.arraycopy(BinaryUtils.encodeUtf16LeFixed(name, 14), 0, buf, 17, 14)
                transport.writeData(machineNo, FkCommands.SET_USER_INFO, buf, param = 0x15)
            }

            FkFirmwareProfile.CIF12, FkFirmwareProfile.CIF12_1, FkFirmwareProfile.CIF12_2,
            FkFirmwareProfile.CIF13 -> {
                val buf = ByteArray(0xB0)
                BinaryUtils.putU32LE(buf, 0, enrollNumber)
                System.arraycopy(BinaryUtils.encodeUtf16LeFixed(name, 128), 0, buf, 8, 128)
                transport.writeData(machineNo, FkCommands.SET_USER_INFO, buf, param = 0x15)
            }

            FkFirmwareProfile.CIF10, FkFirmwareProfile.CIF10_1 -> {
                val buf = ByteArray(0x204)
                BinaryUtils.putU32LE(buf, 0, enrollNumber)
                System.arraycopy(BinaryUtils.encodeUtf16LeFixed(name, 32), 0, buf, 4, 32)
                transport.writeData(machineNo, FkCommands.SET_USER_INFO, buf, param = 0x15)
            }

            FkFirmwareProfile.LFW -> {
                // Đọc lại bản ghi 204B hiện có trước khi sửa tên để không mất các trường khác
                val existing = transport.readData(machineNo, FkCommands.LFW_GET_USER_INFO, totalLength = 0xCC, param = enrollNumber)
                val buf = if (existing.isSuccess && existing.data.size >= 0xCC) {
                    existing.data.copyOf(0xCC)
                } else {
                    ByteArray(0xCC).also { BinaryUtils.putU32LE(it, 0, enrollNumber) }
                }
                System.arraycopy(BinaryUtils.encodeUtf16LeFixed(name, 32), 0, buf, 0x44, 32)
                transport.writeData(machineNo, FkCommands.LFW_SET_USER_INFO, buf, param = enrollNumber)
            }

            FkFirmwareProfile.CIF14, FkFirmwareProfile.CIF14_1,
            FkFirmwareProfile.GEN, FkFirmwareProfile.PS, FkFirmwareProfile.PS_EXCEL -> {
                transport.writeData(machineNo, FkCommands.SET_USER_NAME, BinaryUtils.encodeUtf16LeFixed(name, 14), param = enrollNumber)
            }
        }
        return if (resp.isSuccess) FkResult.ok(Unit) else FkResult.fail(resp.resultCode)
    }
}

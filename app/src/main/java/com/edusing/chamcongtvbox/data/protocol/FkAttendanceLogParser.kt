package com.edusing.chamcongtvbox.data.protocol

import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile

/**
 * Bộ giải mã bản ghi chấm công nhị phân (`CMD 0xA1` / `0xA4`) cho mọi dòng firmware FK:
 * - 12 byte nén (`CIF11`, `CIF11_1`, `CIF12`, `CIF14`, `LFW`)
 * - 20/24 byte mở rộng (`CIF11_2`, `CIF12_1`, `CIF12_2`, `CIF14_1`, `CIF15`, `CIF15_1`)
 * - 12 byte cổ điển (`CIF10`, `GEN`, `PS`) và 8 byte rút gọn (`GEN/PS` khi `logType == 1`)
 */
object FkAttendanceLogParser {

    fun recordSize(profile: FkFirmwareProfile, legacyLogType: Int = 0): Int = when (profile) {
        FkFirmwareProfile.GEN, FkFirmwareProfile.PS, FkFirmwareProfile.PS_EXCEL ->
            if (legacyLogType == 1) 8 else 12
        else -> profile.logRecordSize
    }

    fun parseLogs(
        profile: FkFirmwareProfile,
        raw: ByteArray,
        count: Int,
        legacyLogType: Int = 0,
    ): List<AttendanceLog> {
        val recSize = recordSize(profile, legacyLogType)
        if (recSize <= 0 || count <= 0 || raw.isEmpty()) return emptyList()
        val maxRecords = (raw.size / recSize).coerceAtMost(count)
        val out = ArrayList<AttendanceLog>(maxRecords)

        for (i in 0 until maxRecords) {
            val base = i * recSize
            val parsed = when (profile) {
                FkFirmwareProfile.CIF11,
                FkFirmwareProfile.CIF11_1,
                FkFirmwareProfile.CIF12,
                FkFirmwareProfile.CIF14,
                FkFirmwareProfile.LFW -> parsePacked12Byte(raw, base)

                FkFirmwareProfile.CIF10_1,
                FkFirmwareProfile.CIF11_2,
                FkFirmwareProfile.CIF12_1,
                FkFirmwareProfile.CIF12_2,
                FkFirmwareProfile.CIF14_1,
                FkFirmwareProfile.CIF15,
                FkFirmwareProfile.CIF15_1 -> parsePacked20Or24Byte(raw, base)

                FkFirmwareProfile.CIF13 -> parseCif13(raw, base)

                FkFirmwareProfile.CIF10 -> parseCif10(raw, base)

                FkFirmwareProfile.GEN,
                FkFirmwareProfile.PS,
                FkFirmwareProfile.PS_EXCEL -> parseGenOrPs(raw, base, legacyLogType)
            }
            if (parsed != null && parsed.enrollNumber > 0) out.add(parsed)
        }
        return out
    }

    private fun parsePacked12Byte(buf: ByteArray, off: Int): AttendanceLog? {
        val enrollNo = BinaryUtils.u32LE(buf, off)
        val inOutMode = BinaryUtils.u8(buf, off + 5)
        val verifyMode = BinaryUtils.u8(buf, off + 6)
        val second = BinaryUtils.u8(buf, off + 7)
        val dt = BinaryUtils.u32LE(buf, off + 8)
        return decodePackedDateTime(enrollNo, verifyMode, inOutMode, second, dt)
    }

    private fun parsePacked20Or24Byte(buf: ByteArray, off: Int): AttendanceLog? {
        val enrollNo = BinaryUtils.u32LE(buf, off)
        val second = BinaryUtils.u8(buf, off + 7)
        val dt = BinaryUtils.u32LE(buf, off + 8)
        val rawInOut = BinaryUtils.u32LE(buf, off + 12)
        val inOutMode = (rawInOut shl 8) or BinaryUtils.u8(buf, off + 15)
        val verifyMode = BinaryUtils.u32LE(buf, off + 16)
        return decodePackedDateTime(enrollNo, verifyMode, inOutMode, second, dt)
    }

    /** Bản ghi 32B của `CIF13` (StringID 16B tại `0..15`, giây `+19`, ngày giờ nén `+20`, IO `+27`, verify `+28`). */
    private fun parseCif13(buf: ByteArray, off: Int): AttendanceLog? {
        val rawId = BinaryUtils.decodeAsciiNullTerminated(buf, off, 16).trim()
        val enrollNo = rawId.toIntOrNull() ?: return null
        val second = BinaryUtils.u8(buf, off + 19)
        val dt = BinaryUtils.u32LE(buf, off + 20)
        val inOutMode = BinaryUtils.u8(buf, off + 27)
        val verifyMode = BinaryUtils.u32LE(buf, off + 28)
        return decodePackedDateTime(enrollNo, verifyMode, inOutMode, second, dt)
    }

    /** Giải mã ngày giờ nén 32-bit: `[năm-1900:10b][tháng:4b][ngày:5b][giờ:5b][phút:6b]`. */
    private fun decodePackedDateTime(
        enrollNo: Int,
        verifyMode: Int,
        inOutMode: Int,
        second: Int,
        packedDt: Int,
    ): AttendanceLog? {
        val year = ((packedDt ushr 2) and 0x3FF) + 1900
        val month = (packedDt ushr 12) and 0x0F
        val day = (packedDt ushr 16) and 0x1F
        val hour = (packedDt ushr 21) and 0x1F
        val minute = (packedDt ushr 26) and 0x3F
        if (!isValidDate(year, month, day, hour, minute, second)) return null
        return AttendanceLog(enrollNo, verifyMode, inOutMode, year, month, day, hour, minute, second)
    }

    private fun parseCif10(buf: ByteArray, off: Int): AttendanceLog? {
        val second = BinaryUtils.u8(buf, off + 3)
        val enrollNo = BinaryUtils.u32LE(buf, off + 4)
        val w8 = BinaryUtils.u16LE(buf, off + 8)
        val w10 = BinaryUtils.u16LE(buf, off + 10)
        val year = w8 and 0x0FFF
        val month = (w8 ushr 12) and 0x0F
        val day = w10 and 0x1F
        val hour = (w10 ushr 5) and 0x1F
        val minute = (w10 ushr 10) and 0x3F
        if (!isValidDate(year, month, day, hour, minute, second)) return null
        val b1 = BinaryUtils.u8(buf, off + 1)
        return AttendanceLog(enrollNo, b1 and 0x3F, b1 ushr 6, year, month, day, hour, minute, second)
    }

    private fun parseGenOrPs(buf: ByteArray, off: Int, logType: Int): AttendanceLog? {
        if (logType == 1) {
            val w0 = BinaryUtils.u16LE(buf, off)
            val w2 = BinaryUtils.u16LE(buf, off + 2)
            val enrollNo = BinaryUtils.u32LE(buf, off + 4)
            val year = ((w0 ushr 5) and 0x7F) + 2000
            val month = (w0 ushr 12) and 0x0F
            val day = w2 and 0x1F
            val hour = (w2 ushr 5) and 0x1F
            val minute = (w2 ushr 10) and 0x3F
            if (!isValidDate(year, month, day, hour, minute, 0)) return null
            return AttendanceLog(enrollNo, verifyMode = 1, inOutMode = 0, year, month, day, hour, minute, 0)
        }
        val second = BinaryUtils.u8(buf, off + 3)
        val enrollNo = BinaryUtils.u32LE(buf, off + 4)
        val w8 = BinaryUtils.u16LE(buf, off + 8)
        val w10 = BinaryUtils.u16LE(buf, off + 10)
        val year = w8 and 0x0FFF
        val month = (w8 ushr 12) and 0x0F
        val day = w10 and 0x1F
        val hour = (w10 ushr 5) and 0x1F
        val minute = (w10 ushr 10) and 0x3F
        if (!isValidDate(year, month, day, hour, minute, second)) return null
        val flags = BinaryUtils.u32LE(buf, off)
        return AttendanceLog(
            enrollNumber = enrollNo,
            verifyMode = (flags ushr 8) and 0x3F,
            inOutMode = (flags ushr 14) and 0x1F,
            year = year,
            month = month,
            day = day,
            hour = hour,
            minute = minute,
            second = second,
        )
    }

    private fun isValidDate(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Boolean =
        y in 1990..2150 && mo in 1..12 && d in 1..31 && h in 0..23 && mi in 0..59 && s in 0..59
}

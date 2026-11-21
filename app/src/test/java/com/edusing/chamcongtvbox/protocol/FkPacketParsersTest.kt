package com.edusing.chamcongtvbox.protocol

import com.edusing.chamcongtvbox.data.protocol.BinaryUtils
import com.edusing.chamcongtvbox.data.protocol.FkAttendanceLogParser
import com.edusing.chamcongtvbox.data.protocol.FkDeviceInfoParser
import com.edusing.chamcongtvbox.data.protocol.FkPwdCipher
import com.edusing.chamcongtvbox.data.protocol.FkUserEnrollParser
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử bộ giải mã nhị phân DeviceInfo, Log chấm công, danh sách User và nạp thẻ. */
class FkPacketParsersTest {

    @Test
    fun parseDeviceInfo48_andResolveProfileAcrossAllFamilies() {
        val buf = ByteArray(48)
        BinaryUtils.putU16LE(buf, 0, 0x0104)
        BinaryUtils.putU16LE(buf, 4, 0x0003)
        BinaryUtils.putU32LE(buf, 8, 5912)
        BinaryUtils.putU16LE(buf, 12, 0x0103)
        buf[15] = 0
        buf[16] = 2
        BinaryUtils.putU16LE(buf, 46, 64)

        val info = FkDeviceInfoParser.parseFk48(buf)
        assertEquals(5912, info.license)
        assertTrue(info.prefersSmallBlockSize)
        assertTrue(info.hasExtendedInfo)

        assertEquals(FkFirmwareProfile.CIF11_1, FkDeviceInfoParser.resolveProfile(info, """{"CommunicationInterfaceId":"11_1"}"""))
        assertEquals(FkFirmwareProfile.CIF15_1, FkDeviceInfoParser.resolveProfile(info, """{"CommunicationInterfaceId":"15_1"}"""))
        assertEquals(
            FkFirmwareProfile.CIF11,
            FkDeviceInfoParser.resolveProfile(
                info,
                "{CommunicationInterfaceId:11,CodePage:UTF-16,UDiskBackupFileFormatId:3,SupportSetupImages:OK,EnrollDataType:{pwd,idcard,fp,face,palm}}",
            ),
        )
        assertEquals(FkFirmwareProfile.CIF10_1, FkDeviceInfoParser.resolveProfile(info, "{CommunicationInterfaceId:10_1,CodePage:UTF-16}"))
        assertEquals(FkFirmwareProfile.CIF12_1, FkDeviceInfoParser.resolveProfile(info, "{CommunicationInterfaceId:12_1,CodePage:UTF-16}"))
        assertEquals(FkFirmwareProfile.CIF13, FkDeviceInfoParser.resolveProfile(info, "{CommunicationInterfaceId:13,CodePage:UTF-16}"))
        assertEquals(FkFirmwareProfile.PS_EXCEL, FkDeviceInfoParser.resolveProfile(info, null))
    }

    @Test
    fun parseGeneralLogs_cif10_1_20Bytes_and_cif13_32BytesStringId() {
        val packedDt = ((2026 - 1900) shl 2) or (11 shl 12) or (20 shl 16) or (14 shl 21) or (25 shl 26)

        val buf20 = ByteArray(20)
        BinaryUtils.putU32LE(buf20, 0, 445566)
        buf20[7] = 19
        BinaryUtils.putU32LE(buf20, 8, packedDt)
        BinaryUtils.putU32LE(buf20, 16, 1)
        val logs20 = FkAttendanceLogParser.parseLogs(FkFirmwareProfile.CIF10_1, buf20, 1)
        assertEquals(445566, logs20.single().enrollNumber)
        assertEquals("14:25:19 20/11/2026", logs20.single().formattedDeviceTime())

        val buf32 = ByteArray(32)
        "908070".toByteArray(Charsets.US_ASCII).copyInto(buf32, 0)
        buf32[19] = 42
        BinaryUtils.putU32LE(buf32, 20, packedDt)
        buf32[27] = 1
        BinaryUtils.putU32LE(buf32, 28, 3)
        val logs32 = FkAttendanceLogParser.parseLogs(FkFirmwareProfile.CIF13, buf32, 1)
        assertEquals(908070, logs32.single().enrollNumber)
        assertEquals("14:25:42 20/11/2026", logs32.single().formattedDeviceTime())
    }

    @Test
    fun mapLfwStatusIndex_matchesVerifiedJumpTable() {
        assertEquals(2, FkDeviceInfoParser.mapLfwStatusIndex(1))
        assertEquals(3, FkDeviceInfoParser.mapLfwStatusIndex(2))
        assertEquals(8, FkDeviceInfoParser.mapLfwStatusIndex(6))
        assertEquals(10, FkDeviceInfoParser.mapLfwStatusIndex(8))
        assertEquals(5, FkDeviceInfoParser.mapLfwStatusIndex(10))
    }

    @Test
    fun parseGeneralLogs_cif11Packed12BytesAndCif15Packed24Bytes() {
        val packedDt = ((2026 - 1900) shl 2) or (11 shl 12) or (19 shl 16) or (7 shl 21) or (45 shl 26)

        val buf12 = ByteArray(12)
        BinaryUtils.putU32LE(buf12, 0, 102030)
        buf12[5] = 1
        buf12[6] = 3
        buf12[7] = 32
        BinaryUtils.putU32LE(buf12, 8, packedDt)

        val logs12 = FkAttendanceLogParser.parseLogs(FkFirmwareProfile.CIF11_1, buf12, 1)
        assertEquals(1, logs12.size)
        assertEquals(102030, logs12[0].enrollNumber)
        assertEquals("07:45:32 19/11/2026", logs12[0].formattedDeviceTime())

        val buf24 = ByteArray(24)
        BinaryUtils.putU32LE(buf24, 0, 888999)
        buf24[7] = 15
        BinaryUtils.putU32LE(buf24, 8, packedDt)
        buf24[15] = 2
        BinaryUtils.putU32LE(buf24, 16, 4)

        val logs24 = FkAttendanceLogParser.parseLogs(FkFirmwareProfile.CIF15_1, buf24, 1)
        assertEquals(1, logs24.size)
        assertEquals(888999, logs24[0].enrollNumber)
        assertEquals(4, logs24[0].verifyMode)
        assertEquals(15, logs24[0].second)
    }

    @Test
    fun parseGeneralLogs_cif10AndLegacy8ByteGen() {
        val cif10 = ByteArray(12)
        cif10[1] = ((2 shl 6) or 3).toByte()
        cif10[3] = 5
        BinaryUtils.putU32LE(cif10, 4, 555)
        BinaryUtils.putU16LE(cif10, 8, (5 shl 12) or 2026)
        BinaryUtils.putU16LE(cif10, 10, (20 shl 10) or (16 shl 5) or 9)

        val parsed10 = FkAttendanceLogParser.parseLogs(FkFirmwareProfile.CIF10, cif10, 1)
        assertEquals(555, parsed10.single().enrollNumber)
        assertEquals("16:20:05 09/05/2026", parsed10.single().formattedDeviceTime())

        val gen8 = ByteArray(8)
        BinaryUtils.putU16LE(gen8, 0, (8 shl 12) or (26 shl 5))
        BinaryUtils.putU16LE(gen8, 2, (30 shl 10) or (8 shl 5) or 14)
        BinaryUtils.putU32LE(gen8, 4, 777)

        val parsedGen = FkAttendanceLogParser.parseLogs(FkFirmwareProfile.GEN, gen8, 1, legacyLogType = 1)
        assertEquals(777, parsedGen.single().enrollNumber)
        assertEquals("08:30:00 14/08/2026", parsedGen.single().formattedDeviceTime())
    }

    @Test
    fun parseUserIdRecords_expandsBitmasksAcrossCif11Cif15AndLfw() {
        val cif11 = ByteArray(8)
        BinaryUtils.putU32LE(cif11, 0, 1001)
        cif11[5] = 1
        BinaryUtils.putU16LE(cif11, 6, 0x02 or 0x04 or (2 shl 3))
        val backups11 = FkUserEnrollParser.parseUserIds(FkFirmwareProfile.CIF11_1, cif11, 1).map { it.backupNumber }.toSet()
        assertEquals(setOf(0, 1, FkErrorCode.BACKUP_CARD, FkErrorCode.BACKUP_FACE), backups11)

        val cif15 = ByteArray(10)
        BinaryUtils.putU32LE(cif15, 0, 2002)
        cif15[4] = 1
        cif15[5] = 1
        BinaryUtils.putU32LE(cif15, 6, 0x02 or 0x10000)
        val backups15 = FkUserEnrollParser.parseUserIds(FkFirmwareProfile.CIF15_1, cif15, 1).map { it.backupNumber }.toSet()
        assertEquals(setOf(FkErrorCode.BACKUP_CARD, 30), backups15)

        val lfw = ByteArray(8)
        BinaryUtils.putU32LE(lfw, 0, 3003)
        lfw[5] = 1
        BinaryUtils.putU16LE(lfw, 6, 0x02 or (0b1001 shl 3))
        val backupsLfw = FkUserEnrollParser.parseUserIds(FkFirmwareProfile.LFW, lfw, 1).map { it.backupNumber }.toSet()
        assertEquals(setOf(0, 3, FkErrorCode.BACKUP_CARD), backupsLfw)
    }

    @Test
    fun buildCardEnrollPayload_matchesExactStructsAndEncryptsCard() {
        val p56 = FkUserEnrollParser.buildCardEnrollPayload(FkFirmwareProfile.CIF11_1, 12345, 12345, privilege = 0)
        assertEquals(0x38, p56.size)
        val decCard = FkPwdCipher.decryptHs3760(p56.copyOfRange(0x18, 0x38))
            .takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
        assertEquals("12345", decCard)

        val p88 = FkUserEnrollParser.buildCardEnrollPayload(FkFirmwareProfile.CIF10, 54321, 54321, privilege = 1)
        assertEquals(0x58, p88.size)
        assertEquals(1, BinaryUtils.u8(p88, 0x28))

        val pLfw = FkUserEnrollParser.buildCardEnrollPayload(FkFirmwareProfile.LFW, 777, 888)
        assertEquals(12, pLfw.size)
        assertEquals(888, BinaryUtils.u32LE(pLfw, 4))
    }
}

package com.edusing.chamcongtvbox.data.protocol

import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential

/**
 * Bộ giải mã danh sách người dùng (`CMD 0x97`) và đóng gói struct nạp thẻ (`CMD 0x98` / LFW `0x9B`).
 */
object FkUserEnrollParser {

    /** Giải mã mảng bản ghi người dùng (8B hoặc 10B tuỳ firmware) thành danh sách credential. */
    fun parseUserIds(
        profile: FkFirmwareProfile,
        raw: ByteArray,
        count: Int,
    ): List<FkUserCredential> {
        if (count <= 0 || raw.isEmpty()) return emptyList()
        val out = ArrayList<FkUserCredential>(count)

        when (profile) {
            FkFirmwareProfile.CIF10, FkFirmwareProfile.CIF10_1 -> {
                val max = (raw.size / 8).coerceAtMost(count)
                for (i in 0 until max) {
                    val off = i * 8
                    val flags = BinaryUtils.u32LE(raw, off)
                    val enrollNo = BinaryUtils.u32LE(raw, off + 4)
                    if (enrollNo > 0) {
                        out.add(FkUserCredential(enrollNo, (flags ushr 16) and 0xFF, (flags ushr 8) and 0xFF, (flags and 0xFF) != 0))
                    }
                }
            }

            FkFirmwareProfile.GEN, FkFirmwareProfile.PS, FkFirmwareProfile.PS_EXCEL -> {
                val max = (raw.size / 8).coerceAtMost(count)
                for (i in 0 until max) {
                    val off = i * 8
                    val flags = BinaryUtils.u32LE(raw, off)
                    val enrollNo = BinaryUtils.u32LE(raw, off + 4)
                    if (enrollNo > 0) {
                        out.add(FkUserCredential(enrollNo, (flags ushr 24) and 0xFF, (flags ushr 8) and 0xFF, (flags and 0xFF) != 0))
                    }
                }
            }

            FkFirmwareProfile.LFW -> parseLfwUserIds(raw, count, out)

            FkFirmwareProfile.CIF15, FkFirmwareProfile.CIF15_1 -> {
                val max = (raw.size / 10).coerceAtMost(count)
                for (i in 0 until max) {
                    val off = i * 10
                    val enrollNo = BinaryUtils.u32LE(raw, off)
                    if (enrollNo <= 0) continue
                    expandCifBitmask(
                        out = out,
                        enrollNo = enrollNo,
                        privilege = BinaryUtils.u8(raw, off + 4),
                        enabled = BinaryUtils.u8(raw, off + 5) != 0,
                        mask = BinaryUtils.u32LE(raw, off + 6),
                        includeExtended = true,
                    )
                }
            }

            else -> {
                // CIF11, CIF11_1, CIF11_2, CIF12, CIF12_1, CIF12_2, CIF14, CIF14_1
                val max = (raw.size / 8).coerceAtMost(count)
                for (i in 0 until max) {
                    val off = i * 8
                    val enrollNo = BinaryUtils.u32LE(raw, off)
                    if (enrollNo <= 0) continue
                    expandCifBitmask(
                        out = out,
                        enrollNo = enrollNo,
                        privilege = BinaryUtils.u8(raw, off + 4),
                        enabled = BinaryUtils.u8(raw, off + 5) != 0,
                        mask = BinaryUtils.u16LE(raw, off + 6),
                        includeExtended = false,
                    )
                }
            }
        }
        return out
    }

    private fun parseLfwUserIds(raw: ByteArray, count: Int, out: MutableList<FkUserCredential>) {
        val max = (raw.size / 8).coerceAtMost(count)
        for (i in 0 until max) {
            val off = i * 8
            val enrollNo = BinaryUtils.u32LE(raw, off)
            if (enrollNo <= 0) continue
            val privilege = BinaryUtils.u8(raw, off + 4)
            val enabled = BinaryUtils.u8(raw, off + 5) != 0
            val mask = BinaryUtils.u16LE(raw, off + 6)
            var added = false
            val fpBits = (mask ushr 3) and 0x3FF
            for (fp in 0..9) {
                if ((fpBits and (1 shl fp)) != 0) {
                    out.add(FkUserCredential(enrollNo, fp, privilege, enabled))
                    added = true
                }
            }
            if ((mask and 0x04) != 0) { out.add(FkUserCredential(enrollNo, FkErrorCode.BACKUP_FACE, privilege, enabled)); added = true }
            if ((mask and 0x01) != 0) { out.add(FkUserCredential(enrollNo, FkErrorCode.BACKUP_PASSWORD, privilege, enabled)); added = true }
            if ((mask and 0x02) != 0) { out.add(FkUserCredential(enrollNo, FkErrorCode.BACKUP_CARD, privilege, enabled)); added = true }
            if (!added) out.add(FkUserCredential(enrollNo, FkErrorCode.BACKUP_CARD, privilege, enabled))
        }
    }

    private fun expandCifBitmask(
        out: MutableList<FkUserCredential>,
        enrollNo: Int,
        privilege: Int,
        enabled: Boolean,
        mask: Int,
        includeExtended: Boolean,
    ) {
        var added = false
        for (k in 0 until ((mask ushr 12) and 0x0F)) { out.add(FkUserCredential(enrollNo, 20 + k, privilege, enabled)); added = true }
        for (k in 0 until ((mask ushr 7) and 0x07)) { out.add(FkUserCredential(enrollNo, 13 + k, privilege, enabled)); added = true }
        for (k in 0 until ((mask ushr 10) and 0x03)) { out.add(FkUserCredential(enrollNo, 17 + k, privilege, enabled)); added = true }
        for (k in 0 until ((mask ushr 3) and 0x0F)) { out.add(FkUserCredential(enrollNo, k, privilege, enabled)); added = true }
        if ((mask and 0x04) != 0) { out.add(FkUserCredential(enrollNo, FkErrorCode.BACKUP_FACE, privilege, enabled)); added = true }
        if ((mask and 0x01) != 0) { out.add(FkUserCredential(enrollNo, FkErrorCode.BACKUP_PASSWORD, privilege, enabled)); added = true }
        if ((mask and 0x02) != 0) { out.add(FkUserCredential(enrollNo, FkErrorCode.BACKUP_CARD, privilege, enabled)); added = true }
        if (includeExtended) {
            if ((mask and 0x10000) != 0) { out.add(FkUserCredential(enrollNo, 30, privilege, enabled)); added = true }
            if ((mask and 0x20000) != 0) { out.add(FkUserCredential(enrollNo, 50, privilege, enabled)); added = true }
        }
        if (!added) out.add(FkUserCredential(enrollNo, FkErrorCode.BACKUP_CARD, privilege, enabled))
    }

    /** Đóng gói struct nạp thẻ (`backup = 11`) đúng kích thước từng dòng máy (12B/16B/56B/64B/88B). */
    fun buildCardEnrollPayload(
        profile: FkFirmwareProfile,
        enrollNumber: Int,
        cardNumber: Int,
        privilege: Int = 0,
        includeEncryptedCardData: Boolean = true,
    ): ByteArray = when (profile) {
        FkFirmwareProfile.CIF10, FkFirmwareProfile.CIF10_1 -> ByteArray(0x58).also { buf ->
            BinaryUtils.putU32LE(buf, 0, enrollNumber)
            buf[4] = FkErrorCode.BACKUP_CARD.toByte()
            buf[0x28] = (privilege and 0xFF).toByte()
            if (includeEncryptedCardData && cardNumber > 0) {
                System.arraycopy(FkPwdCipher.encryptCardStringFixed(cardNumber.toString(), 24), 0, buf, 0x40, 24)
            }
        }

        FkFirmwareProfile.CIF11, FkFirmwareProfile.CIF11_1, FkFirmwareProfile.CIF11_2,
        FkFirmwareProfile.CIF12, FkFirmwareProfile.CIF12_1, FkFirmwareProfile.CIF12_2,
        FkFirmwareProfile.CIF13,
        FkFirmwareProfile.CIF14, FkFirmwareProfile.CIF14_1,
        FkFirmwareProfile.CIF15, FkFirmwareProfile.CIF15_1 -> ByteArray(0x38).also { buf ->
            BinaryUtils.putU32LE(buf, 0, enrollNumber)
            buf[4] = FkErrorCode.BACKUP_CARD.toByte()
            buf[0x16] = (privilege and 0xFF).toByte()
            if (includeEncryptedCardData && cardNumber > 0) {
                System.arraycopy(FkPwdCipher.encryptCardStringFixed(cardNumber.toString(), 32), 0, buf, 0x18, 32)
            }
        }

        FkFirmwareProfile.GEN, FkFirmwareProfile.PS -> ByteArray(0x10).also { buf ->
            buf[0] = 1
            buf[1] = (privilege and 0xFF).toByte()
            buf[3] = FkErrorCode.BACKUP_CARD.toByte()
            BinaryUtils.putU32LE(buf, 4, enrollNumber)
            BinaryUtils.putU32LE(buf, 8, if (includeEncryptedCardData) cardNumber else 0)
        }

        FkFirmwareProfile.PS_EXCEL -> ByteArray(0x40).also { buf ->
            buf[0] = 2
            buf[1] = (privilege and 0xFF).toByte()
            BinaryUtils.putU32LE(buf, 4, enrollNumber)
            BinaryUtils.putU32LE(buf, 12, if (includeEncryptedCardData) cardNumber else 0)
        }

        FkFirmwareProfile.LFW -> ByteArray(0x0C).also { buf ->
            BinaryUtils.putU32LE(buf, 0, enrollNumber)
            BinaryUtils.putU32LE(buf, 4, if (includeEncryptedCardData) cardNumber else 0)
            buf[8] = 1
        }
    }

    /** Đóng gói tham số 16-bit `(năm, tháng)` cho lệnh `CMD 0xE8` trên CIF11/CIF12. */
    fun packGetUserInfoInParam(year: Int, month: Int): Int {
        val yPart = (((year + 0x30) shl 9) and 0xFE14)
        val mPart = ((month and 0x0F) shl 5)
        return (yPart or mPart or 0x14) and 0xFFFF
    }
}

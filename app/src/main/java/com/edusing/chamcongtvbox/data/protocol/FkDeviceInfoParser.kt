package com.edusing.chamcongtvbox.data.protocol

import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile

/**
 * Thông tin phần cứng đọc từ lệnh `CMD 0x13` (48 byte ở `FKViaDev`, 54 byte ở `LFWViaDev`).
 */
data class FkDeviceInfo(
    val diType: Int,
    val modelFlag: Int,
    val license: Int,
    val commVersion: Int,
    val logType: Int,
    val subType: Int,
    val extInfoLen: Int,
) {
    /** Firmware có `commVersion == 0x103` yêu cầu giảm kích thước khối xuống 512 byte. */
    val prefersSmallBlockSize: Boolean get() = commVersion == 0x103

    /** Máy đời mới (`diType >= 0x104`) cung cấp thêm chuỗi JSON mô tả `CommunicationInterfaceId`. */
    val hasExtendedInfo: Boolean get() = diType >= 0x104 && extInfoLen in 1..2048
}

/**
 * Bộ giải mã `DeviceInfo` và chọn đúng hồ sơ firmware trong 18 lớp của `FKAttend.dll`.
 */
object FkDeviceInfoParser {

    fun parseFk48(buf: ByteArray): FkDeviceInfo {
        require(buf.size >= 48) { "DeviceInfo chuẩn FK phải có ít nhất 48 byte" }
        return FkDeviceInfo(
            diType = BinaryUtils.u16LE(buf, 0),
            modelFlag = BinaryUtils.u16LE(buf, 4) and 0x0F,
            license = BinaryUtils.u32LE(buf, 8),
            commVersion = BinaryUtils.u16LE(buf, 12),
            logType = BinaryUtils.u8(buf, 15),
            subType = BinaryUtils.u8(buf, 16),
            extInfoLen = BinaryUtils.u16LE(buf, 46),
        )
    }

    fun parseLfw54(buf: ByteArray): FkDeviceInfo {
        require(buf.size >= 54) { "DeviceInfo chuẩn LFW phải có ít nhất 54 byte" }
        return FkDeviceInfo(
            diType = BinaryUtils.u16LE(buf, 0),
            modelFlag = BinaryUtils.u16LE(buf, 4) and 0x0F,
            license = BinaryUtils.u32LE(buf, 8),
            commVersion = BinaryUtils.u16LE(buf, 12),
            logType = BinaryUtils.u8(buf, 15),
            subType = BinaryUtils.u8(buf, 16),
            extInfoLen = 0,
        )
    }

    /** Chọn hồ sơ firmware khớp logic nhà máy tại `0x10032b50` của `FKAttend.dll`. */
    fun resolveProfile(deviceInfo: FkDeviceInfo, extInfoAscii: String?): FkFirmwareProfile {
        if (!extInfoAscii.isNullOrBlank()) {
            when (extractJsonField(extInfoAscii, "CommunicationInterfaceId")) {
                "10" -> return FkFirmwareProfile.CIF10
                "10_1" -> return FkFirmwareProfile.CIF10_1
                "11" -> return FkFirmwareProfile.CIF11
                "11_1" -> return FkFirmwareProfile.CIF11_1
                "11_2" -> return FkFirmwareProfile.CIF11_2
                "12" -> return FkFirmwareProfile.CIF12
                "12_1" -> return FkFirmwareProfile.CIF12_1
                "12_2" -> return FkFirmwareProfile.CIF12_2
                "13" -> return FkFirmwareProfile.CIF13
                "14" -> return FkFirmwareProfile.CIF14
                "14_1" -> return FkFirmwareProfile.CIF14_1
                "15" -> return FkFirmwareProfile.CIF15
                "15_1" -> return FkFirmwareProfile.CIF15_1
            }
        }
        return if (deviceInfo.modelFlag == 3 || deviceInfo.modelFlag == 4) {
            if (deviceInfo.subType == 2) FkFirmwareProfile.PS_EXCEL else FkFirmwareProfile.PS
        } else {
            FkFirmwareProfile.GEN
        }
    }

    /** Ánh xạ chỉ số `GetDeviceStatus` trên máy LFW (bảng nhảy tại `0x100256c8` trong DLL). */
    fun mapLfwStatusIndex(statusIndex: Int): Int = when (statusIndex) {
        1 -> 2
        2 -> 3
        3 -> 4
        4 -> 6
        5 -> 9
        6 -> 8   // Số log chấm công chưa đọc
        7 -> 11
        8 -> 10  // Tổng số log chấm công
        9 -> 7
        10 -> 5
        else -> statusIndex
    }

    /**
     * Trích xuất giá trị theo khóa từ chuỗi mở rộng `CMD 0x13 (param=1)`.
     * Khớp hàm `0x10032350` trong `FKAttend.dll`: máy thật trả dạng KHÔNG có ngoặc kép
     * (vd `{CommunicationInterfaceId:11,CodePage:UTF-16,...}`), đồng thời hỗ trợ cả JSON có ngoặc kép.
     */
    internal fun extractJsonField(text: String, key: String): String? {
        val pattern = Regex("""(?:^|[{,\s])"?${Regex.escape(key)}"?\s*:\s*"?([^",}\r\n]+)"?""")
        return pattern.find(text)?.groupValues?.getOrNull(1)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
    }
}

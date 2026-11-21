package com.edusing.chamcongtvbox.domain.protocol

/**
 * Hồ sơ firmware được nhận diện tự động lúc bắt tay (`CMD 0x13`).
 * Tương ứng đầy đủ 18 lớp RTTI trong `FKAttend.dll`.
 */
enum class FkFirmwareProfile(
    val displayName: String,
    val logRecordSize: Int,
) {
    CIF10("CIF10", 12),
    CIF10_1("CIF10_1", 20),
    CIF11("CIF11", 12),
    CIF11_1("CIF11_1", 12),
    CIF11_2("CIF11_2", 20),
    CIF12("CIF12", 12),
    CIF12_1("CIF12_1", 20),
    CIF12_2("CIF12_2", 20),
    CIF13("CIF13", 32),
    CIF14("CIF14", 12),
    CIF14_1("CIF14_1", 20),
    CIF15("CIF15", 20),
    CIF15_1("CIF15_1", 24),
    GEN("GEN", 12),
    PS("PS", 12),
    PS_EXCEL("PS_EXCEL", 12),
    LFW("LFW", 12),
    HIKVISION_ISAPI("Hikvision ISAPI", 0),
}

/**
 * Bảng mã trạng thái và chỉ số backup chuẩn của giao thức FKAttend.
 */
object FkErrorCode {
    const val SUCCESS = 1
    const val FAIL = 0
    const val ERR_INVALID_PARAM = -1
    const val ERR_NOT_OPEN = -2
    const val ERR_COMM = -3
    const val ERR_INVALID_DATA = -4
    const val ERR_BAD_PARAM = -5
    const val ERR_DEVICE_FAIL = -6
    const val ERR_MEMORY = -7
    const val ERR_LOG_END = -8
    const val ERR_NO_MORE_DATA = -9
    const val ERR_LICENSE = -10

    const val BACKUP_PASSWORD = 10
    const val BACKUP_CARD = 11
    const val BACKUP_FACE = 12

    /** Chuyển mã lỗi FK sang mô tả tiếng Việt dễ hiểu cho người vận hành. */
    fun describe(code: Int): String = when (code) {
        SUCCESS -> "Thành công"
        ERR_NOT_OPEN -> "Chưa kết nối (-2)"
        ERR_COMM -> "Mất kết nối mạng / timeout (-3)"
        ERR_INVALID_DATA -> "Dữ liệu không hợp lệ (-4)"
        ERR_BAD_PARAM -> "Tham số không hợp lệ (-5)"
        ERR_DEVICE_FAIL -> "Máy chấm công từ chối thao tác (-6)"
        ERR_MEMORY -> "Lỗi bộ nhớ / đọc dữ liệu (-7)"
        ERR_LOG_END -> "Không có dữ liệu chấm công mới (-8)"
        ERR_NO_MORE_DATA -> "Hết danh sách (-9)"
        ERR_LICENSE -> "Sai mã bản quyền máy chấm (-10)"
        else -> "Mã lỗi FK: $code"
    }
}

/** Kết quả bọc mã trạng thái và dữ liệu trả về từ [FkDeviceGateway]. */
data class FkResult<T>(
    val code: Int,
    val data: T? = null,
    val message: String = FkErrorCode.describe(code),
) {
    val isSuccess: Boolean get() = code == FkErrorCode.SUCCESS
    val isCommError: Boolean get() = code == FkErrorCode.ERR_COMM || code == FkErrorCode.ERR_NOT_OPEN

    companion object {
        fun <T> ok(data: T): FkResult<T> = FkResult(FkErrorCode.SUCCESS, data, "Thành công")
        fun <T> fail(code: Int, detail: String? = null): FkResult<T> =
            FkResult(code, null, detail ?: FkErrorCode.describe(code))
    }
}

/** Thông tin phiên kết nối đang hoạt động với một máy chấm công. */
data class FkConnectionInfo(
    val handle: Int,
    val ip: String,
    val port: Int,
    val machineNo: Int,
    val profile: FkFirmwareProfile,
    val transportName: String,
    val maxBlockSize: Int,
)

/** Một thông tin xác thực (vân tay/thẻ/mặt) đọc được từ lệnh `ReadAllUserID`. */
data class FkUserCredential(
    val enrollNumber: Int,
    val backupNumber: Int,
    val privilege: Int,
    val enabled: Boolean,
)

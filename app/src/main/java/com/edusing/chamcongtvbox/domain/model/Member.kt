package com.edusing.chamcongtvbox.domain.model

/**
 * Thông tin một học sinh (bản trường) hoặc nhân viên (bản PALUZZ) lấy từ máy chủ.
 */
data class Member(
    val cardId: String,     // Mã thẻ số nguyên dương 32-bit (1..2.147.483.647), rỗng nếu không nạp được
    val rawCard: String,    // Mã thẻ gốc hiển thị cho người dùng kể cả khi vượt giới hạn
    val name: String,
    val classId: Int,       // 0 = chưa xếp lớp / phòng ban
) {
    val isPushable: Boolean get() = cardId.isNotEmpty()
    val cardIdInt: Int? get() = cardId.toIntOrNull()?.takeIf { it > 0 }
}

/** Một lớp học hoặc phòng ban trên máy chủ. */
data class ClassGroup(
    val id: Int,
    val name: String,
)

/** Một người dùng hiện đang được lưu trong bộ nhớ máy chấm công. */
data class DeviceUser(
    val enrollNumber: Int,
    val deviceName: String,
    val serverFullName: String? = null,
    val className: String? = null,
)

/** Dữ liệu tổng hợp cho màn hình Đẩy danh sách xuống máy. */
data class MembersTreeData(
    val classes: List<ClassGroup>,
    val members: List<Member>,
    val existingCardIdsOnTarget: Set<Int>,
    val existingNamesOnTarget: Set<String>,
)

/** Tiến độ xử lý theo lô khi đẩy danh sách hoặc xoá hàng loạt. */
data class BatchProgress(
    val completed: Int,
    val total: Int,
    val currentLabel: String,
)

/** Tổng hợp kết quả sau khi đẩy danh sách xuống một hoặc nhiều máy chấm công. */
data class PushResultSummary(
    val totalRequested: Int,
    val succeeded: Int,
    val skippedExisting: Int = 0,
    val failed: Int,
    val perDeviceResults: List<DevicePushOutcome>,
)

/** Kết quả đẩy danh sách chi tiết trên từng máy chấm công. */
data class DevicePushOutcome(
    val deviceName: String,
    val deviceIp: String,
    val succeeded: Int,
    val skippedExisting: Int = 0,
    val failed: Int,
    val savedOk: Boolean,
    val error: String? = null,
)

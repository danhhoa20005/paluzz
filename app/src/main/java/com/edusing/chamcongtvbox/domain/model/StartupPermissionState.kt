package com.edusing.chamcongtvbox.domain.model

/** Các quyền hệ thống bắt buộc cần cấp ngay khi mới mở ứng dụng (trước cả màn hình Đăng nhập). */
enum class StartupPermissionType {
    /** Tự động bật ứng dụng khi khởi động / mất điện có lại (Hiển thị trên app khác hoặc Màn hình chính TV). */
    AUTO_LAUNCH_ON_BOOT,
    /** Bỏ giới hạn pin để dịch vụ chấm công chạy nền 24/7 không bị hệ thống tắt ngầm. */
    BATTERY_UNRESTRICTED,
    /** Hiển thị thông báo trạng thái kết nối 24/7 & cảnh báo lỗi (Android 13+). */
    NOTIFICATIONS,
    /** Cho phép tự cài đặt bản cập nhật OTA mới nhất (Android 8+). */
    INSTALL_UPDATES,
}

/** Trạng thái chi tiết của một quyền hệ thống trong cổng cấp quyền bắt buộc lúc khởi động. */
data class StartupPermissionItem(
    val type: StartupPermissionType,
    val title: String,
    val description: String,
    val actionLabel: String,
    val granted: Boolean,
    val required: Boolean = true,
)

/**
 * Ảnh chụp toàn bộ quyền hệ thống tại thời điểm mở app để cổng bắt buộc quyết định:
 * - Chặn tại màn hình cấp quyền (không cho bỏ qua / bấm Back) cho tới khi [allMandatoryGranted] == `true`.
 * - Ngay khi đủ 100% quyền bắt buộc → tự động mở thẳng vào màn hình Đăng nhập hoặc Trạm trực.
 */
data class StartupPermissionsSnapshot(
    val isTvDevice: Boolean,
    val isDefaultHomeLauncher: Boolean,
    val canSetHomeLauncher: Boolean,
    val items: List<StartupPermissionItem>,
) {
    val mandatoryItems: List<StartupPermissionItem> get() = items.filter { it.required }
    val grantedMandatoryCount: Int get() = mandatoryItems.count { it.granted }
    val totalMandatoryCount: Int get() = mandatoryItems.size
    val nextPending: StartupPermissionItem? get() = mandatoryItems.firstOrNull { !it.granted }
    val allMandatoryGranted: Boolean get() = mandatoryItems.all { it.granted }
}

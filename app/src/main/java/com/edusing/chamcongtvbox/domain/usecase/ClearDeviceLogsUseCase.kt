package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkResult

/**
 * Quy trình 5 bước dọn sạch bộ nhớ chấm công (chuyển thể từ `DonSachMayCham` trong `MainForm.cs`):
 * 1. Khoá máy tạm thời (`enableDevice(false)`) tránh ghi đè lúc đang dọn.
 * 2. Gọi `emptyGeneralLogData`.
 * 3. Đọc hết lượt còn sót (`loadGeneralLogData(readMark = true)`) để đẩy con trỏ đọc.
 * 4. Gọi `emptyGeneralLogData` lần 2 cho các dòng firmware chỉ xoá thật sau khi đánh dấu đã đọc.
 * 5. Luôn mở lại máy (`enableDevice(true)`) trong khối `finally`.
 */
class ClearDeviceLogsUseCase(
    private val deviceGateway: FkDeviceGateway,
) {
    suspend operator fun invoke(handle: Int): FkResult<Int> {
        val disableRes = deviceGateway.enableDevice(handle, enable = false)
        if (disableRes.isCommError) return FkResult.fail(disableRes.code, disableRes.message)
        var commFailed = false
        return try {
            val firstEmpty = deviceGateway.emptyGeneralLogData(handle)
            if (firstEmpty.isCommError) {
                commFailed = true
                return FkResult.fail(firstEmpty.code, firstEmpty.message)
            }
            val loadRes = deviceGateway.loadGeneralLogData(handle, readMark = true)
            if (loadRes.isCommError) {
                commFailed = true
                return FkResult.fail(loadRes.code, loadRes.message)
            }
            val drained = loadRes.data?.size ?: 0
            val finalEmpty = deviceGateway.emptyGeneralLogData(handle)
            if (finalEmpty.isCommError) {
                commFailed = true
                FkResult.fail(finalEmpty.code, finalEmpty.message)
            } else {
                FkResult.ok(drained)
            }
        } finally {
            // Không cố gọi enableDevice(true) khi socket đã chết (-3) để tránh treo thêm nhiều giây
            if (!commFailed) deviceGateway.enableDevice(handle, enable = true)
        }
    }
}

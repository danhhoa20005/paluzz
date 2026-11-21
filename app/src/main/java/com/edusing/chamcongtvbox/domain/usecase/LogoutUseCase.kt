package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository

/**
 * Đăng xuất phiên hiện tại: ngắt toàn bộ máy chấm công, tắt cờ tự chạy và xoá phiên làm việc.
 * Danh sách tài khoản đã lưu (chọn nhanh) vẫn được giữ nguyên để đăng nhập lại thuận tiện.
 */
class LogoutUseCase(
    private val configRepository: ConfigRepository,
    private val deviceGateway: FkDeviceGateway,
) {
    suspend operator fun invoke() {
        deviceGateway.disconnectAll()
        configRepository.setAutoStartRunning(false)
        configRepository.clearActiveCredentials()
    }
}

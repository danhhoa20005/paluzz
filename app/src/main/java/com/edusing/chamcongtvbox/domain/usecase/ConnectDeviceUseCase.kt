package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import kotlinx.coroutines.delay

/**
 * Kết nối tới một máy chấm công có tự thử lại kèm thời gian chờ.
 * Lưu ý phần cứng: máy chấm công FK chỉ nhận 1 phiên tại một thời điểm và cần ~1–2 giây
 * để giải phóng socket cũ trước khi nhận phiên mới.
 */
class ConnectDeviceUseCase(
    private val deviceGateway: FkDeviceGateway,
    private val clearDeviceLogsUseCase: ClearDeviceLogsUseCase,
) {
    suspend operator fun invoke(
        device: DeviceConfig,
        timeoutMs: Int = 5_000,
        maxAttempts: Int = 5,
        retryDelayMs: Long = 1_800L,
        clearStaleLogsOnConnect: Boolean = true,
    ): FkResult<FkConnectionInfo> {
        var lastResult: FkResult<FkConnectionInfo> = FkResult.fail(FkErrorCode.ERR_COMM)
        for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
            val res = deviceGateway.connect(device = device, timeoutMs = timeoutMs)
            if (res.isSuccess && res.data != null) {
                if (clearStaleLogsOnConnect) {
                    clearDeviceLogsUseCase(res.data.handle)
                }
                return res
            }
            lastResult = res
            // Dừng ngay khi sai bản quyền FK (-10) hoặc sai tài khoản/mật khẩu Hikvision (-5) để tránh khoá máy 30 phút
            if (res.code == FkErrorCode.ERR_LICENSE || res.code == FkErrorCode.ERR_BAD_PARAM) return res
            if (attempt < maxAttempts) delay(retryDelayMs)
        }
        return lastResult
    }
}

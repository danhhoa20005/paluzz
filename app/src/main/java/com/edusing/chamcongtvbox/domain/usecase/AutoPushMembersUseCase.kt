package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.PushResultSummary
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.MemberRepository

/** Kết quả một lần tự động đồng bộ danh sách nhân sự/học sinh xuống máy chấm công. */
data class AutoPushRunOutcome(
    val success: Boolean,
    val summary: PushResultSummary? = null,
    val totalMembersOnServer: Int = 0,
    val error: String? = null,
)

/**
 * Nghiệp vụ đẩy nhân sự tự động theo khung giờ (100% pure Kotlin):
 * - Chỉ gọi 1 API lấy danh sách nhân sự/học sinh (tự làm mới token 1 lần nếu hết hạn).
 * - Tận dụng đường tắt Delta Fast-Path trong [PushMembersToDevicesUseCase] để chỉ đẩy người mới,
 *   không khoá máy quét và không ghi mòn Flash nếu toàn bộ nhân sự đã có sẵn trên máy.
 */
class AutoPushMembersUseCase(
    private val configRepository: ConfigRepository,
    private val memberRepository: MemberRepository,
    private val validateCredentialsUseCase: ValidateCredentialsUseCase,
    private val pushMembersToDevicesUseCase: PushMembersToDevicesUseCase,
) {
    suspend operator fun invoke(targets: List<ConnectedDeviceHandle>): AutoPushRunOutcome {
        if (targets.isEmpty()) {
            return AutoPushRunOutcome(success = false, error = "Chưa có máy chấm công nào đang kết nối.")
        }

        var cfg = configRepository.getConfig()
        if (!cfg.isLoggedIn) {
            return AutoPushRunOutcome(success = false, error = "Chưa đăng nhập tài khoản.")
        }

        val domain = configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)
        if (cfg.token.isBlank() && cfg.password.isNotBlank()) {
            validateCredentialsUseCase()
            cfg = configRepository.getConfig()
        }

        var studentsRes = memberRepository.getStudents(domain, cfg.token)
        if (studentsRes.isFailure && cfg.password.isNotBlank()) {
            if (validateCredentialsUseCase() == CredentialCheckStatus.VALID) {
                cfg = configRepository.getConfig()
                studentsRes = memberRepository.getStudents(domain, cfg.token)
            }
        }

        val members = studentsRes.getOrElse { err ->
            return AutoPushRunOutcome(success = false, error = err.message ?: "Không tải được danh sách nhân sự từ web.")
        }
        val hasHikTarget = targets.any { it.device.isHikvision }
        val pushable = members.filter { it.isPushableFor(hasHikTarget) }
        if (pushable.isEmpty()) {
            return AutoPushRunOutcome(
                success = true,
                summary = PushResultSummary(0, 0, 0, 0, emptyList()),
                totalMembersOnServer = 0,
            )
        }

        val summary = pushMembersToDevicesUseCase(
            targets = targets,
            members = pushable,
            skipExistingOnDevice = true,
            maxNameBytes = cfg.maxDeviceNameBytes,
        )
        val allSaved = summary.perDeviceResults.all { it.savedOk } && summary.failed == 0
        val firstErr = summary.perDeviceResults.firstOrNull { !it.error.isNullOrBlank() }?.error
        return AutoPushRunOutcome(
            success = allSaved,
            summary = summary,
            totalMembersOnServer = pushable.size,
            error = firstErr,
        )
    }
}

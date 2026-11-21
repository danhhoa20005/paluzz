package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.repository.AuthRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository

/** Kết quả kiểm tra lại thông tin tài khoản định kỳ trong nền. */
enum class CredentialCheckStatus {
    VALID,
    INVALID_CREDENTIALS,
    NETWORK_ERROR,
    SKIPPED,
}

/**
 * Làm mới token âm thầm và phát hiện tài khoản đã đổi mật khẩu trên hệ thống.
 * Chỉ báo [CredentialCheckStatus.INVALID_CREDENTIALS] khi máy chủ từ chối tài khoản (401/403);
 * lỗi mất mạng tạm thời trả [CredentialCheckStatus.NETWORK_ERROR] để không gián đoạn chấm công 24/7.
 */
class ValidateCredentialsUseCase(
    private val authRepository: AuthRepository,
    private val configRepository: ConfigRepository,
) {
    suspend operator fun invoke(): CredentialCheckStatus {
        val cfg = configRepository.getConfig()
        if (cfg.username.isBlank() || cfg.password.isBlank()) return CredentialCheckStatus.SKIPPED

        val domain = configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)
        val result = authRepository.login(domain, cfg.username, cfg.password)
        return when {
            result.success -> {
                val latest = configRepository.getConfig()
                if (latest.mode == cfg.mode && latest.username == cfg.username) {
                    configRepository.saveConfig(
                        latest.copy(
                            apiDomain = domain,
                            accountId = result.accountId,
                            orgName = result.orgName.ifBlank { latest.orgName },
                            token = result.token.ifBlank { latest.token },
                        )
                    )
                }
                CredentialCheckStatus.VALID
            }
            result.isInvalidCredentials -> CredentialCheckStatus.INVALID_CREDENTIALS
            else -> CredentialCheckStatus.NETWORK_ERROR
        }
    }
}

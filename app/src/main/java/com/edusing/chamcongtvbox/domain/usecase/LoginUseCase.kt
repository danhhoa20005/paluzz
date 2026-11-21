package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.LoginResult
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import com.edusing.chamcongtvbox.domain.repository.AuthRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository

/**
 * Xử lý đăng nhập tài khoản trường/đơn vị, lưu phiên làm việc và cập nhật danh sách chọn nhanh.
 */
class LoginUseCase(
    private val authRepository: AuthRepository,
    private val configRepository: ConfigRepository,
) {
    suspend operator fun invoke(
        username: String,
        password: String,
        rememberAccount: Boolean,
        customApiDomain: String? = null,
    ): LoginResult {
        val cleanUser = username.trim()
        if (cleanUser.isEmpty() || password.isEmpty()) {
            return LoginResult(
                success = false,
                error = "Vui lòng nhập đầy đủ tên đăng nhập và mật khẩu.",
            )
        }

        val current = configRepository.getConfig()
        val domain = configRepository.getBranding().normalizeApiDomain(customApiDomain ?: current.apiDomain)
        val result = authRepository.login(domain, cleanUser, password)
        if (!result.success) return result

        val updated = current.copy(
            apiDomain = domain,
            accountId = result.accountId,
            orgName = result.orgName,
            username = cleanUser,
            password = if (rememberAccount) password else "",
            rememberAccount = rememberAccount,
            token = result.token,
            autoStartRunning = true,
        )
        configRepository.saveConfig(updated)

        if (rememberAccount) {
            configRepository.upsertSavedAccount(
                SavedAccount(
                    username = cleanUser,
                    password = password,
                    orgName = result.orgName,
                    accountId = result.accountId,
                )
            )
        } else {
            configRepository.removeSavedAccount(cleanUser)
        }
        return result
    }

    /** Kiểm tra nhanh kết nối mạng + chứng chỉ HTTPS tới máy chủ hiện tại. */
    suspend fun testServerConnection(customApiDomain: String? = null): String {
        val current = configRepository.getConfig()
        val domain = configRepository.getBranding().normalizeApiDomain(customApiDomain ?: current.apiDomain)
        return authRepository.testServerConnection(domain)
    }
}

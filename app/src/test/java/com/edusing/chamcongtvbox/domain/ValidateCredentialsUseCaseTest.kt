package com.edusing.chamcongtvbox.domain

import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.LoginResult
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import com.edusing.chamcongtvbox.domain.repository.AuthRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.CredentialCheckStatus
import com.edusing.chamcongtvbox.domain.usecase.ValidateCredentialsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiểm thử tự làm mới phiên bằng tài khoản/mật khẩu đã lưu mà không ghi đè danh sách máy vừa dò LAN. */
class ValidateCredentialsUseCaseTest {

    @Test
    fun silentRefresh_preservesConcurrentlyDiscoveredDevices() = runBlocking {
        val initial = AppConfig(
            apiDomain = "https://api.paluzz.com",
            accountId = "11366",
            username = "admin",
            password = "secret",
            token = "old-token",
            devices = emptyList(),
        )
        val cfgRepo = InMemoryConfigRepo(initial)
        val discovered = DeviceConfig(ip = "192.168.1.224", port = 5005, machineNo = 1)
        val authRepo = object : AuthRepository {
            override suspend fun login(apiDomain: String, username: String, password: String): LoginResult {
                // Mô phỏng lúc đang gọi API đăng nhập thì tiến trình dò LAN thêm xong máy mới
                cfgRepo.updateDevices(listOf(discovered))
                return LoginResult(success = true, accountId = "11366", orgName = "Công ty A", token = "fresh-token")
            }
            override suspend fun testServerConnection(apiDomain: String) = "OK"
        }

        val status = ValidateCredentialsUseCase(authRepo, cfgRepo)()

        assertEquals(CredentialCheckStatus.VALID, status)
        assertEquals("fresh-token", cfgRepo.getConfig().token)
        assertEquals("Không được ghi đè máy chấm công vừa dò song song", listOf(discovered), cfgRepo.getConfig().devices)
    }

    @Test
    fun networkOutage_doesNotInvalidateSavedSession() = runBlocking {
        val initial = AppConfig(
            apiDomain = "https://api.paluzz.com",
            accountId = "11366",
            username = "admin",
            password = "secret",
            token = "cached-token",
        )
        val cfgRepo = InMemoryConfigRepo(initial)
        val authRepo = object : AuthRepository {
            override suspend fun login(apiDomain: String, username: String, password: String) =
                LoginResult(success = false, error = "Timeout", isInvalidCredentials = false)
            override suspend fun testServerConnection(apiDomain: String) = "Timeout"
        }

        val status = ValidateCredentialsUseCase(authRepo, cfgRepo)()
        assertEquals(CredentialCheckStatus.NETWORK_ERROR, status)
        assertEquals("cached-token", cfgRepo.getConfig().token)
    }

    private class InMemoryConfigRepo(initial: AppConfig) : ConfigRepository {
        private val flow = MutableStateFlow(initial)
        override val configFlow: StateFlow<AppConfig> = flow
        override fun getConfig(): AppConfig = flow.value
        override fun getBranding(): BrandingConfig = BrandingConfig.forMode(flow.value.mode)
        override suspend fun switchMode(targetMode: AppMode) = flow.value
        override suspend fun saveConfig(config: AppConfig) { flow.value = config }
        override suspend fun upsertSavedAccount(account: SavedAccount) = Unit
        override suspend fun removeSavedAccount(username: String) = Unit
        override suspend fun updateDevices(devices: List<DeviceConfig>) { flow.value = flow.value.copy(devices = devices) }
        override suspend fun setAutoStartRunning(running: Boolean) { flow.value = flow.value.copy(autoStartRunning = running) }
        override suspend fun clearActiveCredentials() = Unit
    }
}

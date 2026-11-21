package com.edusing.chamcongtvbox.domain

import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.AppVersionComparator
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import com.edusing.chamcongtvbox.domain.model.UpdateInstallMode
import com.edusing.chamcongtvbox.domain.model.UpdateReleaseInfo
import com.edusing.chamcongtvbox.domain.repository.ApkInstallOutcome
import com.edusing.chamcongtvbox.domain.repository.AppUpdateRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.service.AppUpdateCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử so sánh phiên bản OTA, chuẩn hoá slug GitHub và cơ chế tự cài âm thầm vs chờ xác nhận. */
class AppUpdateLogicTest {

    @Test
    fun versionComparator_handlesVersionCodeSemVerAndGitHubTags() {
        assertTrue(AppVersionComparator.isNewer(8, "1.7.0", 7, "1.6.0"))
        assertFalse(AppVersionComparator.isNewer(7, "1.6.0", 7, "1.6.0"))
        assertTrue(AppVersionComparator.isNewer(0, "1.6.1", 7, "1.6.0"))
        assertTrue(AppVersionComparator.isNewer(0, "2.0.0", 7, "1.9.9"))
        assertFalse(AppVersionComparator.isNewer(0, "1.5.9", 7, "1.6.0"))

        val (v1, c1) = AppVersionComparator.parseGitHubTag("v1.7.0-8")
        assertEquals("1.7.0", v1)
        assertEquals(8, c1)

        val (v2, c2) = AppVersionComparator.parseGitHubTag("v1.8.0")
        assertEquals("1.8.0", v2)
        assertEquals(null, c2)
    }

    @Test
    fun sanitizeRepoSlug_normalizesFullUrlAndDefaultsSafely() {
        assertEquals("danhhoa20005/paluzz", AppConfig.sanitizeRepoSlug(""))
        assertEquals("danhhoa20005/paluzz", AppConfig.sanitizeRepoSlug("https://github.com/danhhoa20005/paluzz.git"))
        assertEquals("myorg/my-repo", AppConfig.sanitizeRepoSlug("myorg/my-repo/"))
    }

    @Test
    fun coordinator_autoInstallsSilentlyOnlyWhenSilentCapable() = runBlocking {
        val release = UpdateReleaseInfo(8, "1.7.0", "https://github.com/danhhoa20005/paluzz/releases/download/v1.7.0/app.apk")
        val cfgRepo = FakeCfg(AppConfig(apiDomain = "https://api.paluzz.com", autoUpdateEnabled = true))

        // 1. TV Box có Device Owner -> tự tải VÀ tự cài âm thầm luôn
        val silentRepo = FakeUpdateRepo(UpdateInstallMode.SILENT_DEVICE_OWNER, release)
        val silentCoord = AppUpdateCoordinator(cfgRepo, silentRepo, isInternetOnline = { true })
        silentCoord.checkAndPrepareUpdate(triggerSilentInstall = true, isManual = false)
        assertEquals(1, silentRepo.installCalls)
        assertFalse("Cài nền âm thầm không được bật hộp thoại tương tác", silentRepo.lastInteractiveAllowed)

        // 2. Máy thường -> chỉ tải sẵn, KHÔNG tự bật popup làm phiền
        val promptRepo = FakeUpdateRepo(UpdateInstallMode.PROMPT_USER, release)
        val promptCoord = AppUpdateCoordinator(cfgRepo, promptRepo, isInternetOnline = { true })
        promptCoord.checkAndPrepareUpdate(triggerSilentInstall = true, isManual = false)
        assertEquals(0, promptRepo.installCalls)
        assertEquals("1.7.0", promptCoord.state.value.availableRelease?.versionName)
        assertTrue(promptCoord.state.value.downloadedApkPath != null)
    }

    private class FakeUpdateRepo(
        private val mode: UpdateInstallMode,
        private val latest: UpdateReleaseInfo?,
    ) : AppUpdateRepository {
        var installCalls = 0
        var lastInteractiveAllowed = false
        override fun currentVersionCode() = 7
        override fun currentVersionName() = "1.6.0"
        override fun detectInstallMode() = mode
        override suspend fun fetchLatestRelease(repoSlug: String) = Result.success(latest)
        override suspend fun downloadAndVerifyApk(release: UpdateReleaseInfo) = Result.success("/tmp/v${release.versionName}.apk")
        override suspend fun installApk(apkPath: String, allowInteractiveFallback: Boolean): ApkInstallOutcome {
            installCalls++
            lastInteractiveAllowed = allowInteractiveFallback
            return ApkInstallOutcome(true, "OK", silentStarted = mode.isSilentCapable)
        }
    }

    private class FakeCfg(initial: AppConfig) : ConfigRepository {
        private val flow = MutableStateFlow(initial)
        override val configFlow: StateFlow<AppConfig> = flow
        override fun getConfig(): AppConfig = flow.value
        override fun getBranding(): BrandingConfig = BrandingConfig.forMode(flow.value.mode)
        override suspend fun switchMode(targetMode: AppMode) = flow.value
        override suspend fun saveConfig(config: AppConfig) { flow.value = config }
        override suspend fun upsertSavedAccount(account: SavedAccount) = Unit
        override suspend fun removeSavedAccount(username: String) = Unit
        override suspend fun updateDevices(devices: List<DeviceConfig>) = Unit
        override suspend fun setAutoStartRunning(running: Boolean) = Unit
        override suspend fun clearActiveCredentials() = Unit
    }
}

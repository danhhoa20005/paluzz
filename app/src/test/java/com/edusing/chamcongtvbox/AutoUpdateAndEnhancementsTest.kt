package com.edusing.chamcongtvbox

import com.edusing.chamcongtvbox.data.local.HikvisionSyncWatermarkStore
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import com.edusing.chamcongtvbox.domain.model.UpdateInstallMode
import com.edusing.chamcongtvbox.domain.model.UpdateReleaseInfo
import com.edusing.chamcongtvbox.domain.model.UpdateStateSnapshot
import com.edusing.chamcongtvbox.domain.repository.ApkInstallOutcome
import com.edusing.chamcongtvbox.domain.repository.AppUpdateRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.service.AppUpdateCoordinator
import com.edusing.chamcongtvbox.service.AttendanceVoiceAnnouncer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdateAndEnhancementsTest {

    @Test
    fun `UpdateStateSnapshot triggers auto-prompt once per version and respects dismiss`() {
        val rel = UpdateReleaseInfo(17, "2.4.0", "https://example.com/app.apk")
        val ready = UpdateStateSnapshot(
            currentVersionCode = 16, currentVersionName = "2.3.0",
            autoUpdateEnabled = true, installMode = UpdateInstallMode.PROMPT_USER,
            availableRelease = rel, downloadedApkPath = "/tmp/v2.4.0.apk",
        )
        assertTrue(ready.shouldAutoPromptNow)
        assertTrue(ready.shouldShowUpdateBanner)

        // Sau khi đã tự bật 1 lần cho bản 17 -> không tự bật lặp lại, nhưng vẫn giữ thanh cài trên đầu màn hình
        val prompted = ready.copy(autoPromptedVersionCode = 17)
        assertFalse(prompted.shouldAutoPromptNow)
        assertTrue(prompted.shouldShowUpdateBanner)

        // Khi người dùng bấm Để sau / Ẩn -> ẩn cả 2 cho bản 17, nhưng bản 18 mới ra sẽ tự hoạt động lại
        val dismissed = ready.copy(dismissedVersionCode = 17, autoPromptedVersionCode = 17)
        assertFalse(dismissed.shouldAutoPromptNow)
        assertFalse(dismissed.shouldShowUpdateBanner)
        assertTrue(dismissed.copy(availableRelease = rel.copy(versionCode = 18, versionName = "2.5.0")).shouldAutoPromptNow)
    }

    @Test
    fun `AppUpdateCoordinator debounces resume checks and skips re-downloading already downloaded APK`() = runBlocking {
        var now = 1_000_000L
        var fetchCalls = 0
        var downloadCalls = 0
        val rel = UpdateReleaseInfo(17, "2.4.0", "https://example.com/v2.4.0.apk")
        val repo = object : AppUpdateRepository {
            override fun currentVersionCode() = 16
            override fun currentVersionName() = "2.3.0"
            override fun detectInstallMode() = UpdateInstallMode.PROMPT_USER
            override suspend fun fetchLatestRelease(repoSlug: String): Result<UpdateReleaseInfo?> { fetchCalls++; return Result.success(rel) }
            override suspend fun downloadAndVerifyApk(release: UpdateReleaseInfo): Result<String> { downloadCalls++; return Result.success("/tmp/v2.4.0.apk") }
            override suspend fun installApk(apkPath: String, allowInteractiveFallback: Boolean) = ApkInstallOutcome(true, "OK")
        }
        val coord = AppUpdateCoordinator(FakeCfgRepo(), repo, isInternetOnline = { true }, clockMillis = { now })

        // Lần 1: kiểm tra & tải APK về sẵn
        coord.triggerBackgroundCheckIfDue()
        delay(120L)
        assertEquals(1, fetchCalls)
        assertEquals(1, downloadCalls)
        assertTrue(coord.state.value.shouldAutoPromptNow)

        // Trong vòng 10 phút quay lại app -> debounce không gọi mạng lặp
        now += 60_000L
        coord.triggerBackgroundCheckIfDue()
        delay(80L)
        assertEquals(1, fetchCalls)

        // Sau >10 phút -> kiểm tra lại GitHub nhưng KHÔNG tải lại file APK trùng versionCode
        now += 15 * 60_000L
        coord.triggerBackgroundCheckIfDue()
        delay(120L)
        assertEquals(2, fetchCalls)
        assertEquals(1, downloadCalls)
    }

    @Test
    fun `HikvisionSyncWatermarkStore computes backfill window only for genuine outages`() {
        val now = 1_760_000_000_000L
        // Chưa từng đồng bộ (lần đầu cài app) -> không đẩy lại lịch sử cũ
        assertNull(HikvisionSyncWatermarkStore.computeWindow(0L, now))
        // Vòng quét bình thường cách 10s -> không cần bù
        assertNull(HikvisionSyncWatermarkStore.computeWindow(now - 10_000L, now))
        // Mất điện / đứt mạng 20 phút -> tự tạo khung giờ bù chính xác (có 15s đệm chồng lặp)
        val win = HikvisionSyncWatermarkStore.computeWindow(now - 20 * 60_000L, now)
        assertNotNull(win)
        assertEquals(now - 20 * 60_000L - 15_000L, win!!.first)
        assertTrue(win.second >= now)
        // Mất điện 3 ngày -> giới hạn tối đa 12 giờ gần nhất tránh treo máy
        val capped = HikvisionSyncWatermarkStore.computeWindow(now - 72L * 3_600_000L, now)
        assertNotNull(capped)
        assertEquals(now - 12L * 3_600_000L, capped!!.first)
    }

    @Test
    fun `AttendanceVoiceAnnouncer formats clean Vietnamese greeting`() {
        assertEquals("Xin chào Nguyễn Văn Hùng", AttendanceVoiceAnnouncer.formatGreeting("  Nguyễn   Văn Hùng ", "001"))
        assertEquals("Chấm công thành công mã 009", AttendanceVoiceAnnouncer.formatGreeting("", "009"))
        assertEquals("Chấm công thành công", AttendanceVoiceAnnouncer.formatGreeting("", "-"))
    }

    private class FakeCfgRepo : ConfigRepository {
        private val flow = MutableStateFlow(AppConfig(apiDomain = "api.paluzz.com", autoUpdateEnabled = true))
        override val configFlow: StateFlow<AppConfig> = flow
        override fun getConfig() = flow.value
        override fun getBranding() = BrandingConfig.forMode(flow.value.mode)
        override suspend fun switchMode(targetMode: AppMode) = flow.value
        override suspend fun saveConfig(config: AppConfig) { flow.value = config }
        override suspend fun upsertSavedAccount(account: SavedAccount) = Unit
        override suspend fun removeSavedAccount(username: String) = Unit
        override suspend fun updateDevices(devices: List<DeviceConfig>) = Unit
        override suspend fun setAutoStartRunning(running: Boolean) = Unit
        override suspend fun clearActiveCredentials() = Unit
    }
}

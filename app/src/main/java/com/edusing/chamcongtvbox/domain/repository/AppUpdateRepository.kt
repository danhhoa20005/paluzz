package com.edusing.chamcongtvbox.domain.repository

import com.edusing.chamcongtvbox.domain.model.UpdateInstallMode
import com.edusing.chamcongtvbox.domain.model.UpdateReleaseInfo

/** Kết quả cài đặt tệp APK cục bộ. */
data class ApkInstallOutcome(
    val success: Boolean,
    val message: String,
    val silentStarted: Boolean = false,
)

/** Giao diện thuần Kotlin kiểm tra, tải và cài đặt bản cập nhật OTA từ GitHub. */
interface AppUpdateRepository {
    fun currentVersionCode(): Int
    fun currentVersionName(): String
    fun detectInstallMode(): UpdateInstallMode
    suspend fun fetchLatestRelease(repoSlug: String): Result<UpdateReleaseInfo?>
    suspend fun downloadAndVerifyApk(release: UpdateReleaseInfo): Result<String>
    suspend fun installApk(apkPath: String, allowInteractiveFallback: Boolean): ApkInstallOutcome
}

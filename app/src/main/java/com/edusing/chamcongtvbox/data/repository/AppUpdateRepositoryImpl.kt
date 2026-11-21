package com.edusing.chamcongtvbox.data.repository

import android.content.Context
import com.edusing.chamcongtvbox.BuildConfig
import com.edusing.chamcongtvbox.data.local.AndroidApkInstaller
import com.edusing.chamcongtvbox.data.remote.GitHubUpdateClient
import com.edusing.chamcongtvbox.domain.model.UpdateInstallMode
import com.edusing.chamcongtvbox.domain.model.UpdateReleaseInfo
import com.edusing.chamcongtvbox.domain.repository.ApkInstallOutcome
import com.edusing.chamcongtvbox.domain.repository.AppUpdateRepository
import java.io.File

/** Cài đặt kho tự động cập nhật OTA từ GitHub Releases + cài APK 3 tầng (Device Owner / Root / Chuẩn). */
class AppUpdateRepositoryImpl(
    context: Context,
    private val githubClient: GitHubUpdateClient = GitHubUpdateClient(),
    private val installer: AndroidApkInstaller = AndroidApkInstaller(context),
) : AppUpdateRepository {

    private val updatesDir = File(context.applicationContext.cacheDir, "updates")

    override fun currentVersionCode(): Int = BuildConfig.VERSION_CODE
    override fun currentVersionName(): String = BuildConfig.VERSION_NAME
    override fun detectInstallMode(): UpdateInstallMode = installer.detectInstallMode()

    override suspend fun fetchLatestRelease(repoSlug: String): Result<UpdateReleaseInfo?> =
        githubClient.fetchLatestRelease(repoSlug)

    override suspend fun downloadAndVerifyApk(release: UpdateReleaseInfo): Result<String> {
        val safeVer = release.versionName.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.ifBlank { "latest" }
        val target = File(updatesDir, "chamcong_v$safeVer.apk")
        return githubClient.downloadApk(release, target).map { it.absolutePath }
    }

    override suspend fun installApk(apkPath: String, allowInteractiveFallback: Boolean): ApkInstallOutcome =
        installer.installVerifiedApk(File(apkPath), allowInteractiveFallback)
}

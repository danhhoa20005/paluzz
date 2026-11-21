package com.edusing.chamcongtvbox.data.local

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.edusing.chamcongtvbox.domain.model.UpdateInstallMode
import com.edusing.chamcongtvbox.domain.repository.ApkInstallOutcome
import com.edusing.chamcongtvbox.service.BootReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Bộ cài đặt APK thông minh 3 tầng cho mọi dòng Android TV Box (7–15) & Điện thoại:
 * 1. **Device Owner (`PackageInstaller` API chuẩn Google)**: tự cài đè 100% âm thầm, không cần màn hình hay Root.
 * 2. **Root (`su -c pm install -r`)**: tự cài đè 100% âm thầm trên các dòng TV Box Trung Quốc có sẵn quyền `su`.
 * 3. **Hộp thoại chuẩn (`FileProvider`)**: bật sẵn màn hình xác nhận 1 chạm trên máy cá nhân / Box chưa cấp Device Owner.
 */
class AndroidApkInstaller(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun detectInstallMode(): UpdateInstallMode = when {
        isDeviceOwner() -> UpdateInstallMode.SILENT_DEVICE_OWNER
        hasRootSuBinary() -> UpdateInstallMode.SILENT_ROOT
        else -> UpdateInstallMode.PROMPT_USER
    }

    fun isDeviceOwner(): Boolean = runCatching {
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        dpm?.isDeviceOwnerApp(appContext.packageName) == true
    }.getOrDefault(false)

    suspend fun installVerifiedApk(apkFile: File, allowInteractiveFallback: Boolean): ApkInstallOutcome = withContext(Dispatchers.IO) {
        if (!apkFile.exists() || apkFile.length() < 100_000L) {
            return@withContext ApkInstallOutcome(false, "Tệp APK không tồn tại hoặc bị hỏng.")
        }
        val pkgError = validateArchiveMatchesSelf(apkFile)
        if (pkgError != null) return@withContext ApkInstallOutcome(false, pkgError)

        // Tầng 1: Tự cài âm thầm chuẩn Google qua Device Owner (PackageInstaller)
        if (isDeviceOwner()) {
            val ok = installViaPackageInstallerSession(apkFile)
            if (ok) return@withContext ApkInstallOutcome(true, "Đang tự cài bản cập nhật âm thầm (Device Owner)...", silentStarted = true)
        }

        // Tầng 2: Tự cài âm thầm qua Root `su` (TV Box AOSP)
        if (hasRootSuBinary()) {
            val rootOk = installViaRootSu(apkFile)
            if (rootOk) return@withContext ApkInstallOutcome(true, "Đã cài đè âm thầm thành công (Root) — ứng dụng sẽ tự mở lại.", silentStarted = true)
        }

        if (!allowInteractiveFallback) {
            return@withContext ApkInstallOutcome(
                false,
                "Đã tải sẵn bản mới — cần bấm Cài đặt trên máy hoặc cấp quyền Device Owner để tự cài âm thầm.",
            )
        }

        // Tầng 3: Mở hộp thoại cài đặt chuẩn qua FileProvider
        val launched = launchInteractiveInstallIntent(apkFile)
        if (launched) {
            ApkInstallOutcome(true, "Đã mở hộp thoại cài đặt bản mới — bấm 'Cài đặt' để hoàn tất.")
        } else {
            ApkInstallOutcome(false, "Không mở được trình cài đặt APK (hãy cấp quyền cài ứng dụng từ nguồn này).")
        }
    }

    @Suppress("DEPRECATION")
    private fun validateArchiveMatchesSelf(apkFile: File): String? = runCatching {
        val pm = appContext.packageManager
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: return "Không đọc được thông tin gói APK (tệp có thể bị lỗi)."
        if (info.packageName != appContext.packageName) {
            return "Sai gói ứng dụng (${info.packageName}) — từ chối cài đặt."
        }
        null
    }.getOrElse { "Lỗi kiểm tra APK: ${it.message}" }

    private fun installViaPackageInstallerSession(apkFile: File): Boolean = runCatching {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apkFile).use { input ->
                session.openWrite("base.apk", 0L, apkFile.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val callbackIntent = Intent(appContext, BootReceiver::class.java).apply {
                action = ACTION_PACKAGE_INSTALL_STATUS
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(appContext, sessionId, callbackIntent, flags)
            session.commit(pi.intentSender)
        }
        true
    }.getOrDefault(false)

    private fun installViaRootSu(apkFile: File): Boolean = runCatching {
        val safePath = apkFile.absolutePath.replace("'", "'\\''")
        val cmd = "cp '$safePath' /data/local/tmp/chamcong_ota.apk && pm install -r -g /data/local/tmp/chamcong_ota.apk && rm -f /data/local/tmp/chamcong_ota.apk"
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val exitCode = proc.waitFor()
        exitCode == 0
    }.getOrDefault(false)

    private fun launchInteractiveInstallIntent(apkFile: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun hasRootSuBinary(): Boolean =
        SU_PATHS.any { runCatching { File(it).exists() && File(it).canExecute() }.getOrDefault(false) }

    companion object {
        const val ACTION_PACKAGE_INSTALL_STATUS = "com.edusing.chamcongtvbox.ACTION_PACKAGE_INSTALL_STATUS"
        private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/su/bin/su")
    }
}

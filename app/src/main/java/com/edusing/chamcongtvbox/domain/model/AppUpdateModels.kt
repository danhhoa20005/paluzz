package com.edusing.chamcongtvbox.domain.model

/** Chế độ cài đặt bản cập nhật khả dụng trên thiết bị hiện tại. */
enum class UpdateInstallMode(val label: String, val isSilentCapable: Boolean) {
    SILENT_DEVICE_OWNER("Tự cài âm thầm (Device Owner)", true),
    SILENT_ROOT("Tự cài âm thầm (Root)", true),
    PROMPT_USER("Tự tải • Bấm Cài 1 chạm", false),
}

/** Thông tin phiên bản mới nhất lấy từ GitHub (`releases/version.json` hoặc GitHub Releases API). */
data class UpdateReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String = "",
    val notes: String = "",
    val source: String = "GitHub",
)

/** Trạng thái hiện tại của hệ thống tự động cập nhật OTA. */
data class UpdateStateSnapshot(
    val currentVersionCode: Int = 0,
    val currentVersionName: String = "",
    val repoSlug: String = AppConfig.DEFAULT_UPDATE_REPO,
    val autoUpdateEnabled: Boolean = true,
    val installMode: UpdateInstallMode = UpdateInstallMode.PROMPT_USER,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val isInstalling: Boolean = false,
    val availableRelease: UpdateReleaseInfo? = null,
    val downloadedApkPath: String? = null,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val autoPromptedVersionCode: Int = 0,
    val dismissedVersionCode: Int = 0,
) {
    val hasUpdateAvailable: Boolean get() = availableRelease != null
    val isDownloadedReady: Boolean get() = availableRelease != null && !downloadedApkPath.isNullOrBlank()
    val isBusy: Boolean get() = isChecking || isDownloading || isInstalling
    val shouldAutoPromptNow: Boolean
        get() {
            val code = availableRelease?.versionCode ?: 0
            return autoUpdateEnabled && isDownloadedReady && !installMode.isSilentCapable && !isInstalling &&
                code > 0 && code != autoPromptedVersionCode && code != dismissedVersionCode
        }
    val shouldShowUpdateBanner: Boolean
        get() {
            val code = availableRelease?.versionCode ?: 0
            return isDownloadedReady && code > 0 && code != dismissedVersionCode
        }
}

/**
 * Bộ so sánh phiên bản & phân tích tag GitHub thuần Kotlin (0 phụ thuộc Android):
 * hỗ trợ cả so sánh theo `versionCode` (ưu tiên) lẫn so sánh số phiên bản SemVer (`1.6.0 > 1.5.0`).
 */
object AppVersionComparator {

    fun isNewer(
        remoteVersionCode: Int,
        remoteVersionName: String,
        currentVersionCode: Int,
        currentVersionName: String,
    ): Boolean {
        if (remoteVersionCode > 0 && currentVersionCode > 0) {
            if (remoteVersionCode != currentVersionCode) return remoteVersionCode > currentVersionCode
        }
        return compareSemVer(remoteVersionName, currentVersionName) > 0
    }

    /** Tách `v1.6.0` hoặc `v1.6.0-7` thành `(versionName = "1.6.0", optionalVersionCode = 7)`. */
    fun parseGitHubTag(tagName: String): Pair<String, Int?> {
        val clean = tagName.trim().removePrefix("v").removePrefix("V")
        val dashParts = clean.split('-', limit = 2)
        val verName = dashParts[0].trim()
        val parsedCode = dashParts.getOrNull(1)?.filter(Char::isDigit)?.toIntOrNull()
        return verName to parsedCode
    }

    fun compareSemVer(a: String, b: String): Int {
        val pa = extractNumericParts(a)
        val pb = extractNumericParts(b)
        val maxLen = maxOf(pa.size, pb.size)
        for (i in 0 until maxLen) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun extractNumericParts(raw: String): List<Int> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }
}

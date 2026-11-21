package com.edusing.chamcongtvbox.data.remote

import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppVersionComparator
import com.edusing.chamcongtvbox.domain.model.UpdateReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Client kiểm tra & tải bản cập nhật APK từ GitHub (ưu tiên `raw.githubusercontent.com/releases/version.json`
 * không giới hạn lưu lượng, tự động dự phòng sang GitHub Releases API nếu người dùng kéo thả APK trực tiếp).
 */
class GitHubUpdateClient(
    private val httpClient: OkHttpClient = HttpClientFactory.createFallbackClient().newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    suspend fun fetchLatestRelease(repoSlug: String): Result<UpdateReleaseInfo?> = withContext(Dispatchers.IO) {
        val cleanSlug = AppConfig.sanitizeRepoSlug(repoSlug)
        // 1. Ưu tiên đọc releases/version.json trên nhánh main (không giới hạn 60 req/giờ của GitHub API)
        val rawManifest = fetchRawVersionJson(cleanSlug)
        if (rawManifest != null) return@withContext Result.success(rawManifest)

        // 2. Dự phòng: gọi thẳng GitHub Releases API (hỗ trợ kéo thả file .apk trực tiếp lên trang Releases)
        runCatching { fetchGitHubReleasesApi(cleanSlug) }
    }

    suspend fun downloadApk(release: UpdateReleaseInfo, destFile: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destFile.parentFile?.mkdirs()
            val tmpFile = File(destFile.parentFile, "${destFile.name}.part")
            val req = Request.Builder()
                .url(release.apkUrl)
                .header("Accept", "application/octet-stream, */*")
                .header("User-Agent", USER_AGENT)
                .build()

            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Tải APK lỗi HTTP ${resp.code}")
                val body = resp.body ?: error("Phản hồi tải APK rỗng")
                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buf = ByteArray(16_384)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            totalBytes += n
                            if (totalBytes > MAX_APK_BYTES) error("Tệp APK vượt quá giới hạn an toàn 60MB")
                            digest.update(buf, 0, n)
                            output.write(buf, 0, n)
                        }
                        output.flush()
                    }
                }
            }
            if (totalBytes < 100_000L) {
                tmpFile.delete()
                error("Tệp APK tải về quá nhỏ hoặc không hợp lệ")
            }

            val expectedHash = release.sha256.trim().lowercase()
            if (expectedHash.length == 64) {
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (actualHash != expectedHash) {
                    tmpFile.delete()
                    error("Sai mã kiểm tra SHA-256 — đã huỷ cài đặt để bảo vệ TV Box")
                }
            }

            if (destFile.exists()) destFile.delete()
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
            destFile
        }
    }

    private fun fetchRawVersionJson(slug: String): UpdateReleaseInfo? = runCatching {
        val url = "https://raw.githubusercontent.com/$slug/main/releases/version.json?t=${System.currentTimeMillis() / 60_000L}"
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val j = JSONObject(resp.body?.string().orEmpty())
            val apkUrl = j.optString("apkUrl", "").trim()
            val verName = j.optString("versionName", "").trim()
            if (apkUrl.isBlank() || verName.isBlank()) return@runCatching null
            UpdateReleaseInfo(
                versionCode = j.optInt("versionCode", 0),
                versionName = verName,
                apkUrl = apkUrl,
                sha256 = j.optString("sha256", "").trim(),
                notes = j.optString("notes", "").trim(),
                source = "GitHub manifest",
            )
        }
    }.getOrNull()

    private fun fetchGitHubReleasesApi(slug: String): UpdateReleaseInfo? {
        val url = "https://api.github.com/repos/$slug/releases/latest"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) error("Kiểm tra GitHub lỗi HTTP ${resp.code}")
            val j = JSONObject(resp.body?.string().orEmpty())
            val assets = j.optJSONArray("assets") ?: return null
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", "").trim()
                    if (apkUrl.isNotBlank()) break
                }
            }
            if (apkUrl.isBlank()) return null
            val (verName, tagCode) = AppVersionComparator.parseGitHubTag(j.optString("tag_name", ""))
            if (verName.isBlank()) return null
            return UpdateReleaseInfo(
                versionCode = tagCode ?: 0,
                versionName = verName,
                apkUrl = apkUrl,
                notes = j.optString("body", "").trim().take(240),
                source = "GitHub Releases",
            )
        }
    }

    companion object {
        private const val USER_AGENT = "ChamCongTVBox-Updater/1.0"
        private const val MAX_APK_BYTES = 60L * 1024 * 1024
    }
}

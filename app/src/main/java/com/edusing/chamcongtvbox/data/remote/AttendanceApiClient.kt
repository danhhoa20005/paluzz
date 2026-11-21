package com.edusing.chamcongtvbox.data.remote

import android.os.Build
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.ClassGroup
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem
import com.edusing.chamcongtvbox.domain.model.LoginResult
import com.edusing.chamcongtvbox.domain.model.Member
import com.edusing.chamcongtvbox.domain.model.ServerCheckInResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * Client HTTP REST giao tiếp với máy chủ `diemdanhhs.com` / `paluzz.com` / `dontrahs.com`.
 * - HTTP/1.1 + TLS 1.2 ưu tiên + DNS IPv4-first (khớp `ApiClient.cs`), tự thử lại bằng kết nối sạch khi lỗi mạng.
 * - Gửi đúng header `Content-Type: application/json`, `Accept: application/json` và `x-brand`.
 * - Tự đọc header `Date` đồng bộ giờ máy chủ và hỗ trợ xả pool socket cũ khi đổi Wi-Fi.
 */
class AttendanceApiClient(
    private val brandingProvider: () -> BrandingConfig,
    private val appVersion: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val primaryClient: OkHttpClient = HttpClientFactory.createPrimaryClient(),
    private val fallbackClient: OkHttpClient = HttpClientFactory.createFallbackClient(),
) {
    constructor(
        brandingConfig: BrandingConfig,
        appVersion: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        primaryClient: OkHttpClient = HttpClientFactory.createPrimaryClient(),
        fallbackClient: OkHttpClient = HttpClientFactory.createFallbackClient(),
    ) : this({ brandingConfig }, appVersion, ioDispatcher, primaryClient, fallbackClient)

    private val brandingConfig: BrandingConfig get() = brandingProvider()
    private val lastServerEpochMs = AtomicLong(0L)

    private val tvBoxMachineName: String
        get() = runCatching {
            val mfg = Build.MANUFACTURER?.trim().orEmpty()
            val model = Build.MODEL?.trim().orEmpty()
            when {
                model.isBlank() -> "AndroidTVBox"
                mfg.isNotBlank() && !model.startsWith(mfg, ignoreCase = true) -> "$mfg $model"
                else -> model
            }
        }.getOrDefault("AndroidTVBox")

    fun lastKnownServerTimeMillis(): Long? = lastServerEpochMs.get().takeIf { it > 0L }

    /** Đóng toàn bộ socket HTTP keep-alive cũ khi đổi Wi-Fi/mạng để tránh treo timeout 15 giây. */
    fun evictStaleConnections() {
        runCatching { primaryClient.connectionPool.evictAll() }
        runCatching { fallbackClient.connectionPool.evictAll() }
    }

    suspend fun login(apiDomain: String, username: String, password: String): LoginResult = withContext(ioDispatcher) {
        val base = brandingConfig.normalizeApiDomain(apiDomain)
        val url = "$base/api/auth/login"
        val payload = JSONObject().put("username", username).put("password", password).toString()
        try {
            val (code, body) = executeWithRetry(url, payload, bearer = null)
            ApiResponseParser.parseLoginResponse(code, body)
        } catch (e: Exception) {
            HttpClientFactory.logError("login($url)", e)
            LoginResult(success = false, error = HttpClientFactory.formatConnectionError(base, e))
        }
    }

    /** Kiểm tra nhanh DNS + HTTPS/TLS + phản hồi máy chủ để chẩn đoán kết nối trên điện thoại/TV Box. */
    suspend fun testConnection(apiDomain: String): String = withContext(ioDispatcher) {
        val base = brandingConfig.normalizeApiDomain(apiDomain)
        val t0 = System.currentTimeMillis()
        try {
            val host = URI(base).host ?: return@withContext "Địa chỉ máy chủ không hợp lệ: $base"
            val ips = HttpClientFactory.ipv4FirstDns.lookup(host).joinToString(", ") { it.hostAddress.orEmpty() }
            val req = buildRequest("$base/api/auth/login", """{"username":"","password":""}""", bearer = null, forceClose = true)
            primaryClient.newCall(req).execute().use { resp ->
                ServerTimeParser.parseHttpDateMillis(resp.header("Date"))?.let { lastServerEpochMs.set(it) }
                val elapsed = System.currentTimeMillis() - t0
                val tls = resp.handshake?.tlsVersion?.javaName ?: "HTTP"
                "Kết nối OK tới $host ($ips) • $tls • HTTP ${resp.code} (${elapsed}ms)"
            }
        } catch (e: Exception) {
            HttpClientFactory.logError("testConnection($base)", e)
            HttpClientFactory.formatConnectionError(base, e)
        }
    }

    suspend fun checkInOut(apiDomain: String, accountId: String, studentCardId: String, device: DeviceConfig): ServerCheckInResult =
        withContext(ioDispatcher) {
            val fallbackTimeMs = lastKnownServerTimeMillis() ?: System.currentTimeMillis()
            val base = brandingConfig.normalizeApiDomain(apiDomain)
            try {
                val url = "$base/api/check-in-out/$studentCardId/no-auth/$accountId"
                val body = JSONObject().apply {
                    put("deviceName", device.displayName)
                    put("deviceIp", device.ip)
                    put("devicePort", device.port)
                    put("deviceNo", device.machineNo)
                    put("appMachine", tvBoxMachineName)
                    put("appVersion", appVersion)
                }
                val (code, respBody) = executeWithRetry(url, body.toString(), bearer = null)
                val headerTimeMs = lastKnownServerTimeMillis() ?: fallbackTimeMs
                val msg = ApiResponseParser.extractMessage(respBody)
                if (code >= 400) {
                    // /no-auth/ chỉ đá đăng xuất khi HTTP 401/403; HTTP >= 500 được đánh dấu để hàng đợi tự đẩy bù
                    return@withContext ServerCheckInResult(
                        success = false,
                        serverTimeFormatted = ServerTimeParser.formatVietnamTime(headerTimeMs),
                        serverTimeMillis = headerTimeMs,
                        message = msg ?: "HTTP $code",
                        isAuthError = code == 401 || code == 403,
                        isRetryableNetworkError = code >= 500,
                    )
                }
                val parsedMsgTime = ServerTimeParser.extractFromMessage(msg)
                ServerCheckInResult(
                    success = true,
                    serverTimeFormatted = parsedMsgTime?.first ?: ServerTimeParser.formatVietnamTime(headerTimeMs),
                    serverTimeMillis = parsedMsgTime?.second ?: headerTimeMs,
                    message = msg ?: "Điểm danh thành công",
                )
            } catch (e: Exception) {
                HttpClientFactory.logError("checkInOut($studentCardId)", e)
                ServerCheckInResult(
                    success = false,
                    serverTimeFormatted = ServerTimeParser.formatVietnamTime(fallbackTimeMs),
                    serverTimeMillis = fallbackTimeMs,
                    message = e.message ?: "Không kết nối được máy chủ",
                    isRetryableNetworkError = true,
                )
            }
        }

    suspend fun sendHeartbeat(
        apiDomain: String,
        accountId: String,
        primaryMachineNo: Int,
        running: Boolean,
        devices: List<DeviceHeartbeatItem>,
    ) = withContext(ioDispatcher) {
        runCatching {
            val base = brandingConfig.normalizeApiDomain(apiDomain)
            val url = "$base/api/attendance-machines/heartbeat/no-auth/$accountId"
            val devArray = JSONArray().apply {
                devices.forEach { d ->
                    put(JSONObject().apply {
                        put("deviceName", d.deviceName); put("deviceIp", d.deviceIp); put("devicePort", d.devicePort)
                        put("deviceNo", d.deviceNo); put("connected", d.connected); put("running", d.running); put("note", d.note)
                    })
                }
            }
            val body = JSONObject().apply {
                put("appMachine", tvBoxMachineName); put("appVersion", appVersion)
                put("deviceNo", primaryMachineNo); put("running", running); put("devices", devArray)
            }
            executeRequest(primaryClient, url, body.toString(), bearer = null)
        }
    }

    suspend fun getClasses(apiDomain: String, bearerToken: String): Result<List<ClassGroup>> = withContext(ioDispatcher) {
        runCatching {
            val base = brandingConfig.normalizeApiDomain(apiDomain)
            val (code, body) = executeWithRetry("$base/api/classes/?limit=1000", null, bearerToken)
            ApiResponseParser.parseClassesResponse(code, body).getOrThrow()
        }
    }

    suspend fun getStudents(apiDomain: String, bearerToken: String): Result<List<Member>> = withContext(ioDispatcher) {
        runCatching {
            val base = brandingConfig.normalizeApiDomain(apiDomain)
            val (code, body) = executeWithRetry("$base/api/students/", null, bearerToken)
            ApiResponseParser.parseStudentsResponse(code, body, brandingConfig.personLower).getOrThrow()
        }
    }

    /** Thử qua client chính (TLS 1.2); nếu gặp lỗi socket/SSL thì thử lại 1 lần qua client dự phòng sạch. */
    private fun executeWithRetry(url: String, postJsonBody: String?, bearer: String?): Pair<Int, String> =
        try {
            executeRequest(primaryClient, url, postJsonBody, bearer)
        } catch (firstErr: IOException) {
            HttpClientFactory.logError("Primary request failed, retrying fallback ($url)", firstErr)
            executeRequest(fallbackClient, url, postJsonBody, bearer, forceClose = true)
        }

    private fun executeRequest(
        httpClient: OkHttpClient,
        url: String,
        postJsonBody: String?,
        bearer: String?,
        forceClose: Boolean = false,
    ): Pair<Int, String> {
        val req = buildRequest(url, postJsonBody, bearer, forceClose)
        httpClient.newCall(req).execute().use { resp ->
            ServerTimeParser.parseHttpDateMillis(resp.header("Date"))?.let { lastServerEpochMs.set(it) }
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    private fun buildRequest(url: String, postJsonBody: String?, bearer: String?, forceClose: Boolean): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("x-brand", brandingConfig.brandHeader)
        if (forceClose) builder.header("Connection", "close")
        if (!bearer.isNullOrBlank()) builder.header("Authorization", "Bearer $bearer")
        if (postJsonBody != null) {
            // Dùng ByteArray để gửi đúng `Content-Type: application/json` giống HttpWebRequest trong ApiClient.cs
            builder.post(postJsonBody.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.get()
        }
        return builder.build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

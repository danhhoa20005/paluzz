package com.edusing.chamcongtvbox.service.web

import android.content.Context
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.DeleteDeviceUsersUseCase
import com.edusing.chamcongtvbox.domain.usecase.LoginUseCase
import com.edusing.chamcongtvbox.domain.usecase.ReadDeviceUsersUseCase
import com.edusing.chamcongtvbox.service.AppUpdateCoordinator
import com.edusing.chamcongtvbox.service.AttendanceEngine
import org.json.JSONArray
import org.json.JSONObject

internal data class WebHttpResponse(
    val statusCode: Int,
    val contentType: String,
    val body: String,
)

/**
 * Bộ xử lý REST API JSON đầy đủ cho Web quản trị nội bộ (`http://<host>.local:8080`):
 * điều khiển chấm công, máy chấm công, đẩy nhân sự, lịch đẩy, tên miền `.local` đa Box,
 * thông số kỹ thuật, chẩn đoán kết nối máy chủ và xem/xoá người dùng trực tiếp trên máy chấm công.
 */
internal class WebConsoleApiHandler(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val loginUseCase: LoginUseCase,
    private val readDeviceUsersUseCase: ReadDeviceUsersUseCase,
    private val deleteDeviceUsersUseCase: DeleteDeviceUsersUseCase,
    private val appUpdateCoordinator: AppUpdateCoordinator,
    private val onLocalHostChanged: () -> Unit = {},
    private val auth: WebConsoleAuth = WebConsoleAuth(),
    private val startedAtMs: Long = System.currentTimeMillis(),
) {
    suspend fun handle(method: String, path: String, token: String?, rawBody: String): WebHttpResponse {
        if (path == "/api/console-login" && method == "POST") return handleConsoleLogin(rawBody)
        if (path == "/api/auth-check" && method == "GET") {
            val cfg = configRepository.getConfig()
            val body = JSONObject()
                .put("authenticated", auth.isTokenValid(token))
                .put("needsInitialSetup", !cfg.isLoggedIn && cfg.password.isBlank())
                .put("orgName", cfg.orgName.ifBlank { cfg.username })
                .put("mode", cfg.mode.shortLabel)
                .toString()
            return jsonOk(body)
        }
        if (!auth.isTokenValid(token)) {
            return WebHttpResponse(401, JSON_TYPE, """{"ok":false,"error":"Chưa đăng nhập quản trị"}""")
        }

        return try {
            when {
                path == "/api/status" && method == "GET" -> jsonOk(
                    WebConsoleStatusSerializer.buildStatusJson(
                        context = context,
                        cfg = configRepository.getConfig(),
                        snap = attendanceEngine.state.value,
                        updateSnap = appUpdateCoordinator.state.value,
                        nowMs = attendanceEngine.currentServerTimeMillis(),
                        startedAtMs = startedAtMs,
                    ).toString()
                )
                path == "/api/attendance/start" && method == "POST" -> {
                    val ok = attendanceEngine.startAttendance()
                    jsonOk(JSONObject().put("ok", ok).put("message", if (ok) "Đã bật chờ quét 24/7" else "Chưa kết nối được máy chấm công").toString())
                }
                path == "/api/attendance/stop" && method == "POST" -> {
                    attendanceEngine.stopAttendance()
                    jsonOk("""{"ok":true,"message":"Đã tạm dừng chấm công"}""")
                }
                path == "/api/devices/discover" && method == "POST" -> {
                    attendanceEngine.discoverDevicesInLan(autoResumeIfConfigured = true)
                    jsonOk("""{"ok":true,"message":"Đã hoàn tất quét máy chấm công trong mạng LAN"}""")
                }
                path == "/api/devices/connect-all" && method == "POST" -> {
                    attendanceEngine.connectAllDevices(autoResumeIfConfigured = true)
                    jsonOk("""{"ok":true,"message":"Đã chạy kết nối lại toàn bộ máy"}""")
                }
                path == "/api/devices/add" && method == "POST" -> handleAddDevice(rawBody)
                path == "/api/devices/reconnect" && method == "POST" -> {
                    val key = JSONObject(rawBody).optString("key", "")
                    if (key.isBlank()) return jsonBad("Thiếu mã máy")
                    attendanceEngine.reconnectDevice(key)
                    jsonOk("""{"ok":true,"message":"Đã gửi lệnh nối lại máy"}""")
                }
                path == "/api/devices/remove" && method == "POST" -> {
                    val key = JSONObject(rawBody).optString("key", "")
                    if (key.isBlank()) return jsonBad("Thiếu mã máy")
                    attendanceEngine.removeDevice(key)
                    jsonOk("""{"ok":true,"message":"Đã xoá máy khỏi danh sách"}""")
                }
                path == "/api/autopush/run" && method == "POST" -> {
                    val ok = attendanceEngine.triggerAutoPushNow()
                    jsonOk(JSONObject().put("ok", ok).put("message", if (ok) "Đã đồng bộ nhân sự thành công" else "Chưa đẩy được — kiểm tra máy & mạng").toString())
                }
                path == "/api/autopush/schedule" && method == "POST" -> handleUpdateSchedule(rawBody)
                path == "/api/settings/tech" && method == "POST" -> handleUpdateTechSettings(rawBody)
                path == "/api/diagnostics/test-server" && method == "POST" -> {
                    val msg = loginUseCase.testServerConnection()
                    jsonOk(JSONObject().put("ok", msg.startsWith("Kết nối OK")).put("message", msg).toString())
                }
                path == "/api/device-users/list" && method == "POST" -> handleReadDeviceUsers(rawBody)
                path == "/api/device-users/delete" && method == "POST" -> handleDeleteDeviceUser(rawBody)
                path == "/api/update/check" && method == "POST" -> {
                    val s = appUpdateCoordinator.checkNow()
                    val msg = s.errorMessage ?: s.statusMessage.ifBlank { "Đã kiểm tra xong." }
                    jsonOk(JSONObject().put("ok", s.errorMessage == null).put("message", msg).toString())
                }
                path == "/api/update/install" && method == "POST" -> {
                    val (ok, msg) = appUpdateCoordinator.downloadAndInstallNow(allowInteractiveFallback = true)
                    jsonOk(JSONObject().put("ok", ok).put("message", msg).toString())
                }
                path == "/api/mode" && method == "POST" -> handleSwitchMode(rawBody)
                path == "/api/auth/login" && method == "POST" -> handleRemoteAccountLogin(rawBody)
                else -> WebHttpResponse(404, JSON_TYPE, """{"ok":false,"error":"Không tìm thấy API"}""")
            }
        } catch (e: Exception) {
            WebHttpResponse(400, JSON_TYPE, JSONObject().put("ok", false).put("error", e.message ?: "Dữ liệu không hợp lệ").toString())
        }
    }

    private fun handleConsoleLogin(rawBody: String): WebHttpResponse {
        val json = runCatching { JSONObject(rawBody) }.getOrElse { return jsonBad("Thiếu mật khẩu") }
        val pw = json.optString("password", "")
        val cfg = configRepository.getConfig()
        if (!auth.verifyPassword(pw, cfg.password, cfg.isLoggedIn)) {
            return WebHttpResponse(401, JSON_TYPE, """{"ok":false,"error":"Sai mật khẩu quản trị"}""")
        }
        return jsonOk(JSONObject().put("ok", true).put("token", auth.issueToken()).toString())
    }

    private suspend fun handleAddDevice(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val ip = j.optString("ip", "").trim()
        if (ip.isBlank()) return jsonBad("Vui lòng nhập địa chỉ IP máy chấm công")
        val cfg = configRepository.getConfig()
        val dev = DeviceConfig(
            ip = ip,
            port = j.optInt("port", cfg.defaultPort).coerceIn(1, 65535),
            machineNo = j.optInt("machineNo", cfg.defaultMachineNo).coerceAtLeast(1),
            name = j.optString("name", "").trim(),
            password = cfg.defaultPassword,
            license = cfg.defaultLicense,
            useUdp = cfg.udpMode,
        )
        val added = attendanceEngine.addAndConnectDevice(dev)
        val msg = if (added) "Đã thêm và kết nối máy $ip" else "Máy $ip đã tồn tại trong danh sách"
        return jsonOk(JSONObject().put("ok", added).put("message", msg).toString())
    }

    private suspend fun handleUpdateSchedule(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val cur = configRepository.getConfig()
        val updated = cur.copy(
            autoPushEnabled = j.optBoolean("enabled", cur.autoPushEnabled),
            autoPushCustomEnabled = j.optBoolean("customEnabled", cur.autoPushCustomEnabled),
            autoPushCustomHour = j.optInt("customHour", cur.autoPushCustomHour).coerceIn(0, 23),
            autoPushCustomMinute = j.optInt("customMinute", cur.autoPushCustomMinute).coerceIn(0, 59),
        )
        configRepository.saveConfig(updated)
        attendanceEngine.notifyAutoPushScheduleChanged()
        return jsonOk("""{"ok":true,"message":"Đã lưu lịch tự động đẩy nhân sự"}""")
    }

    private suspend fun handleUpdateTechSettings(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val cur = configRepository.getConfig()
        val newHost = AppConfig.sanitizeLocalHost(j.optString("localHostName", cur.safeLocalHostName))
        val newRepo = AppConfig.sanitizeRepoSlug(j.optString("updateRepoSlug", cur.safeUpdateRepoSlug))
        val updated = cur.copy(
            localHostName = newHost,
            pollIntervalMs = j.optLong("pollIntervalSec", cur.pollIntervalMs / 1000L).coerceIn(3L, 60L) * 1000L,
            dedupSeconds = j.optLong("dedupSeconds", cur.dedupSeconds).coerceIn(0L, 3600L),
            udpMode = j.optBoolean("udpMode", cur.udpMode),
            defaultPort = j.optInt("defaultPort", cur.defaultPort).coerceIn(1, 65535),
            autoStartOnBoot = j.optBoolean("autoStartOnBoot", cur.autoStartOnBoot),
            autoUpdateEnabled = j.optBoolean("autoUpdateEnabled", cur.autoUpdateEnabled),
            updateRepoSlug = newRepo,
        )
        configRepository.saveConfig(updated)
        if (newHost != cur.safeLocalHostName) onLocalHostChanged()
        return jsonOk(JSONObject().put("ok", true).put("message", "Đã lưu cấu hình • http://$newHost.local:8080").toString())
    }

    private suspend fun handleReadDeviceUsers(rawBody: String): WebHttpResponse {
        val key = JSONObject(rawBody).optString("key", "")
        val target = attendanceEngine.getConnectedHandles().firstOrNull { it.device.key == key }
            ?: return jsonBad("Máy chấm công chưa kết nối")
        val res = attendanceEngine.withDeviceIoLock { readDeviceUsersUseCase(target.handle) }
        val users = res.getOrElse { return jsonBad(it.message ?: "Không đọc được danh sách người dùng") }
        val arr = JSONArray().apply {
            users.forEach { u ->
                put(
                    JSONObject()
                        .put("enrollNumber", u.enrollNumber)
                        .put("deviceName", u.deviceName)
                        .put("fullName", u.serverFullName.orEmpty())
                        .put("className", u.className.orEmpty())
                )
            }
        }
        return jsonOk(JSONObject().put("ok", true).put("users", arr).toString())
    }

    private suspend fun handleDeleteDeviceUser(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val key = j.optString("key", "")
        val enrollNo = j.optInt("enrollNumber", -1)
        if (key.isBlank() || enrollNo < 0) return jsonBad("Thiếu mã máy hoặc mã thẻ")
        val target = attendanceEngine.getConnectedHandles().firstOrNull { it.device.key == key }
            ?: return jsonBad("Máy chấm công chưa kết nối")
        val res = attendanceEngine.withDeviceIoLock { deleteDeviceUsersUseCase(target.handle, listOf(enrollNo)) }
        val deleted = res.getOrElse { return jsonBad(it.message ?: "Xoá người dùng thất bại") }
        return jsonOk(JSONObject().put("ok", deleted > 0).put("message", if (deleted > 0) "Đã xoá mã $enrollNo khỏi máy" else "Không tìm thấy mã $enrollNo trên máy").toString())
    }

    private suspend fun handleSwitchMode(rawBody: String): WebHttpResponse {
        val target = AppMode.fromCode(JSONObject(rawBody).optString("mode", ""))
        val next = attendanceEngine.switchAppMode(target)
        if (next.isLoggedIn) attendanceEngine.autoDiscoverConnectAndReady(fromInternetRecovery = false)
        return jsonOk(JSONObject().put("ok", true).put("message", "Đã chuyển chế độ ${target.shortLabel}").toString())
    }

    private suspend fun handleRemoteAccountLogin(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val user = j.optString("username", "").trim()
        val pass = j.optString("password", "")
        val domain = j.optString("apiDomain", "").trim().ifBlank { null }
        val res = loginUseCase(user, pass, rememberAccount = true, customApiDomain = domain)
        if (!res.success) return jsonBad(res.error ?: "Đăng nhập thất bại")
        attendanceEngine.autoDiscoverConnectAndReady(fromInternetRecovery = false)
        return jsonOk(JSONObject().put("ok", true).put("message", "Đã đăng nhập ${res.orgName.ifBlank { user }} & tự kết nối máy").toString())
    }

    private fun jsonOk(body: String) = WebHttpResponse(200, JSON_TYPE, body)
    private fun jsonBad(msg: String) = WebHttpResponse(400, JSON_TYPE, JSONObject().put("ok", false).put("error", msg).toString())

    companion object {
        private const val JSON_TYPE = "application/json; charset=utf-8"
    }
}

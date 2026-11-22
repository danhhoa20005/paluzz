package com.edusing.chamcongtvbox.service.web

import android.content.Context
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
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
    val binaryBody: ByteArray? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/** Bộ xử lý REST API JSON/Binary đầy đủ cho Web quản trị LAN (`http://<host>.local:8080`). */
internal class WebConsoleApiHandler(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val loginUseCase: LoginUseCase,
    private val readDeviceUsersUseCase: ReadDeviceUsersUseCase,
    private val deleteDeviceUsersUseCase: DeleteDeviceUsersUseCase,
    private val appUpdateCoordinator: AppUpdateCoordinator,
    private val hikHandler: WebConsoleHikvisionHandler? = null,
    private val backupAlertHelper: WebConsoleBackupAlertHelper? = null,
    private val onLocalHostChanged: () -> Unit = {},
    private val auth: WebConsoleAuth = WebConsoleAuth(persistentSalt = WebConsoleAuth.loadOrCreateDeviceSalt(context)),
    private val startedAtMs: Long = System.currentTimeMillis(),
) {
    suspend fun handle(method: String, path: String, query: String = "", token: String?, rawBody: String): WebHttpResponse {
        val effectiveToken = token?.takeIf { it.isNotBlank() } ?: WebConsoleHikvisionHandler.parseQuery(query)["token"]
        if (path == "/api/console-login" && method == "POST") return handleConsoleLogin(rawBody)
        val authCfg = configRepository.getConfig()
        val isAuthed = auth.isTokenValid(effectiveToken, authCfg.password, authCfg.isLoggedIn)
        if (path == "/api/auth-check" && method == "GET") {
            val body = JSONObject().put("authenticated", isAuthed)
                .put("needsInitialSetup", !authCfg.isLoggedIn && authCfg.password.isBlank())
                .put("orgName", authCfg.orgName.ifBlank { authCfg.username }).put("mode", authCfg.mode.shortLabel).toString()
            return jsonOk(body)
        }
        if (!isAuthed) {
            return WebHttpResponse(401, JSON_TYPE, """{"ok":false,"error":"Chưa đăng nhập quản trị"}""")
        }
        return try {
            backupAlertHelper?.handle(method, path, query, rawBody)?.let { return it }
            if (path.startsWith("/api/hikvision/") && hikHandler != null) {
                return hikHandler.handle(method, path, query, rawBody)
            }
            when {
                path == "/api/status" && method == "GET" -> jsonOk(
                    WebConsoleStatusSerializer.buildStatusJson(
                        context, configRepository.getConfig(), attendanceEngine.state.value,
                        appUpdateCoordinator.state.value, attendanceEngine.currentServerTimeMillis(), startedAtMs,
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
                    jsonOk(JSONObject().put("ok", s.errorMessage == null).put("message", s.errorMessage ?: s.statusMessage.ifBlank { "Đã kiểm tra xong." }).toString())
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
            jsonBad(e.message ?: "Dữ liệu không hợp lệ")
        }
    }

    private fun handleConsoleLogin(rawBody: String): WebHttpResponse {
        val json = runCatching { JSONObject(rawBody) }.getOrElse { return jsonBad("Thiếu mật khẩu") }
        val cfg = configRepository.getConfig()
        if (!auth.verifyPassword(json.optString("password", ""), cfg.password, cfg.isLoggedIn)) {
            return WebHttpResponse(401, JSON_TYPE, """{"ok":false,"error":"Sai mật khẩu quản trị"}""")
        }
        return jsonOk(JSONObject().put("ok", true).put("token", auth.issuePersistentToken(cfg.password, cfg.isLoggedIn)).toString())
    }

    private suspend fun handleAddDevice(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody); val rawIp = j.optString("ip", "").trim()
        val ip = rawIp.substringAfter("://", rawIp).substringBefore('/').substringBefore('#').substringBefore('?').substringBefore(':').trim()
        if (ip.isBlank()) return jsonBad("Vui lòng nhập địa chỉ IP máy chấm công")
        val cfg = configRepository.getConfig()
        val proto = DeviceProtocol.fromCode(j.optString("protocol", "FK"))
        val portNum = j.optInt("port", if (proto == DeviceProtocol.HIKVISION_ISAPI) DeviceConfig.DEFAULT_HIK_PORT else cfg.defaultPort).coerceIn(1, 65535)
        val useHttps = j.optBoolean("useHttps", false) || portNum == DeviceConfig.DEFAULT_HIK_HTTPS_PORT || rawIp.startsWith("https://", true)
        val accessPass = j.optString("accessPassword", "").ifBlank { if (proto == DeviceProtocol.HIKVISION_ISAPI) cfg.resolvedHikPassword else "" }
        if (proto == DeviceProtocol.HIKVISION_ISAPI && accessPass.isBlank()) return jsonBad("Thiếu mật khẩu thiết bị Hikvision")
        val dev = DeviceConfig(
            ip = ip, port = portNum, machineNo = j.optInt("machineNo", cfg.defaultMachineNo).coerceAtLeast(1),
            name = j.optString("name", "").trim(), password = cfg.defaultPassword, license = cfg.defaultLicense,
            useUdp = cfg.udpMode, protocol = proto, useHttps = useHttps, accessPassword = accessPass,
            username = j.optString("username", "").trim().ifBlank { cfg.resolvedHikUsername },
        )
        val added = attendanceEngine.addAndConnectDevice(dev)
        val msg = if (added) "Đã thêm và kết nối ${proto.shortLabel} $ip:${dev.port}" else "Máy $ip:${dev.port} đã tồn tại"
        return jsonOk(JSONObject().put("ok", added).put("message", msg).toString())
    }

    private suspend fun handleUpdateSchedule(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody); val cur = configRepository.getConfig()
        configRepository.saveConfig(
            cur.copy(
                autoPushEnabled = j.optBoolean("enabled", cur.autoPushEnabled),
                autoPushCustomEnabled = j.optBoolean("customEnabled", cur.autoPushCustomEnabled),
                autoPushCustomHour = j.optInt("customHour", cur.autoPushCustomHour).coerceIn(0, 23),
                autoPushCustomMinute = j.optInt("customMinute", cur.autoPushCustomMinute).coerceIn(0, 59),
            )
        )
        attendanceEngine.notifyAutoPushScheduleChanged()
        return jsonOk("""{"ok":true,"message":"Đã lưu lịch tự động đẩy nhân sự"}""")
    }

    private suspend fun handleUpdateTechSettings(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody); val cur = configRepository.getConfig()
        val newHost = AppConfig.sanitizeLocalHost(j.optString("localHostName", cur.safeLocalHostName))
        val newRepo = AppConfig.sanitizeRepoSlug(j.optString("updateRepoSlug", cur.safeUpdateRepoSlug))
        configRepository.saveConfig(
            cur.copy(
                localHostName = newHost,
                pollIntervalMs = j.optLong("pollIntervalSec", cur.pollIntervalMs / 1000L).coerceIn(3L, 60L) * 1000L,
                dedupSeconds = j.optLong("dedupSeconds", cur.dedupSeconds).coerceIn(0L, 3600L),
                udpMode = j.optBoolean("udpMode", cur.udpMode),
                defaultPort = j.optInt("defaultPort", cur.defaultPort).coerceIn(1, 65535),
                autoStartOnBoot = j.optBoolean("autoStartOnBoot", cur.autoStartOnBoot),
                voiceGreetingEnabled = j.optBoolean("voiceGreetingEnabled", cur.voiceGreetingEnabled),
                autoUpdateEnabled = j.optBoolean("autoUpdateEnabled", cur.autoUpdateEnabled),
                updateRepoSlug = newRepo,
            )
        )
        if (newHost != cur.safeLocalHostName) onLocalHostChanged()
        return jsonOk(JSONObject().put("ok", true).put("message", "Đã lưu cấu hình • http://$newHost.local:8080").toString())
    }

    private suspend fun handleReadDeviceUsers(rawBody: String): WebHttpResponse {
        val key = JSONObject(rawBody).optString("key", "")
        val target = attendanceEngine.getConnectedHandles().firstOrNull { it.device.key == key } ?: return jsonBad("Máy chấm công chưa kết nối")
        val users = attendanceEngine.withDeviceIoLock { readDeviceUsersUseCase(target.handle, target.device) }
            .getOrElse { return jsonBad(it.message ?: "Không đọc được danh sách người dùng") }
        val arr = JSONArray().apply {
            users.forEach { u ->
                put(JSONObject().put("enrollNumber", u.enrollNumber).put("employeeNo", u.displayCardId)
                    .put("deviceName", u.deviceName).put("fullName", u.serverFullName.orEmpty())
                    .put("className", u.className.orEmpty()).put("faceCount", u.faceCount).put("cardCount", u.cardCount))
            }
        }
        return jsonOk(JSONObject().put("ok", true).put("isHikvision", target.device.isHikvision).put("users", arr).toString())
    }

    private suspend fun handleDeleteDeviceUser(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody); val key = j.optString("key", "")
        val empNo = j.optString("employeeNo", "").trim()
        val enrollNo = j.optInt("enrollNumber", -1)
        if (key.isBlank() || (empNo.isBlank() && enrollNo < 0)) return jsonBad("Thiếu mã máy hoặc mã thẻ")
        val target = attendanceEngine.getConnectedHandles().firstOrNull { it.device.key == key } ?: return jsonBad("Máy chấm công chưa kết nối")
        val res = attendanceEngine.withDeviceIoLock {
            if (target.device.isHikvision && empNo.isNotBlank()) deleteDeviceUsersUseCase.deleteEmployeeNos(target.device, listOf(empNo))
            else deleteDeviceUsersUseCase(target.handle, listOf(enrollNo))
        }
        val deleted = res.getOrElse { return jsonBad(it.message ?: "Xoá người dùng thất bại") }
        val label = empNo.ifBlank { enrollNo.toString() }
        return jsonOk(JSONObject().put("ok", deleted > 0).put("message", if (deleted > 0) "Đã xoá mã $label khỏi máy" else "Không tìm thấy mã $label").toString())
    }

    private suspend fun handleSwitchMode(rawBody: String): WebHttpResponse {
        val target = AppMode.fromCode(JSONObject(rawBody).optString("mode", ""))
        val next = attendanceEngine.switchAppMode(target)
        if (next.isLoggedIn) attendanceEngine.autoDiscoverConnectAndReady(fromInternetRecovery = false)
        return jsonOk(JSONObject().put("ok", true).put("message", "Đã chuyển chế độ ${target.shortLabel}").toString())
    }

    private suspend fun handleRemoteAccountLogin(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val user = j.optString("username", "").trim(); val pass = j.optString("password", "")
        val domain = j.optString("apiDomain", "").trim().ifBlank { null }
        val res = loginUseCase(user, pass, rememberAccount = true, customApiDomain = domain)
        if (!res.success) return jsonBad(res.error ?: "Đăng nhập thất bại")
        attendanceEngine.autoDiscoverConnectAndReady(fromInternetRecovery = false)
        return jsonOk(JSONObject().put("ok", true).put("message", "Đã đăng nhập ${res.orgName.ifBlank { user }} & tự kết nối máy").toString())
    }

    private fun jsonOk(body: String) = WebHttpResponse(200, JSON_TYPE, body)
    private fun jsonBad(msg: String) = WebHttpResponse(400, JSON_TYPE, JSONObject().put("ok", false).put("error", msg).toString())

    companion object { private const val JSON_TYPE = "application/json; charset=utf-8" }
}

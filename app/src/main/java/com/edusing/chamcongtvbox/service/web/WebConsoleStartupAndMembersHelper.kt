package com.edusing.chamcongtvbox.service.web

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.StartupPermissionType
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.LoadMembersTreeUseCase
import com.edusing.chamcongtvbox.domain.usecase.LogoutUseCase
import com.edusing.chamcongtvbox.domain.usecase.PushMembersToDevicesUseCase
import com.edusing.chamcongtvbox.service.AttendanceEngine
import com.edusing.chamcongtvbox.service.StartupPermissionsHelper
import com.edusing.chamcongtvbox.service.TvAutoBootHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bộ mở rộng REST API cho Web quản trị LAN:
 * - Điều khiển toàn bộ cài đặt Khởi động 24/7, chế độ Màn hình chính TV Box & mở trang cấp quyền từ xa trên TV Box.
 * - Tải danh sách nhân sự/học sinh từ Web tổng, so sánh `Chưa có` / `Đã có` và đẩy chọn lọc xuống từng máy hoặc tất cả máy.
 * - Xoá nhật ký trực tiếp và Đăng xuất / đổi tài khoản từ xa.
 */
internal class WebConsoleStartupAndMembersHelper(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val loadMembersTreeUseCase: LoadMembersTreeUseCase,
    private val pushMembersToDevicesUseCase: PushMembersToDevicesUseCase,
    private val logoutUseCase: LogoutUseCase,
) {
    suspend fun handle(method: String, path: String, rawBody: String): WebHttpResponse? = when {
        path == "/api/startup/config" && method == "POST" -> handleSaveStartupConfig(rawBody)
        path == "/api/startup/tv-home" && method == "POST" -> handleToggleTvHome(rawBody)
        path == "/api/startup/open-permission" && method == "POST" -> handleOpenPermission(rawBody)
        path == "/api/startup/bring-to-front" && method == "POST" -> {
            val ok = TvAutoBootHelper.launchMainActivitySafe(context)
            jsonOk(JSONObject().put("ok", ok).put("message", if (ok) "Đã gọi ứng dụng lên màn hình TV Box" else "Không gọi được lên màn hình (kiểm tra quyền tự bật app)").toString())
        }
        path == "/api/members/diff" && method == "POST" -> handleMembersDiff(rawBody)
        path == "/api/members/push" && method == "POST" -> handleMembersPush(rawBody)
        path == "/api/logs/clear" && method == "POST" -> {
            attendanceEngine.clearLiveLogView()
            jsonOk("""{"ok":true,"message":"Đã xoá nhật ký hiển thị"}""")
        }
        path == "/api/auth/logout" && method == "POST" -> {
            attendanceEngine.disconnectAllAndStop()
            logoutUseCase()
            jsonOk("""{"ok":true,"message":"Đã đăng xuất tài khoản trên TV Box (mã quản trị LAN trở về mặc định 1234)"}""")
        }
        else -> null
    }

    private suspend fun handleSaveStartupConfig(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val cur = configRepository.getConfig()
        val updated = cur.copy(
            autoStartOnBoot = j.optBoolean("autoStartOnBoot", cur.autoStartOnBoot),
            autoStartRunning = j.optBoolean("autoStartRunning", cur.autoStartRunning),
            voiceGreetingEnabled = j.optBoolean("voiceGreetingEnabled", cur.voiceGreetingEnabled),
            autoUpdateEnabled = j.optBoolean("autoUpdateEnabled", cur.autoUpdateEnabled),
        )
        configRepository.saveConfig(updated)
        return jsonOk(JSONObject().put("ok", true).put("message", "Đã lưu cấu hình khởi động 24/7").toString())
    }

    private fun handleToggleTvHome(rawBody: String): WebHttpResponse {
        val enable = JSONObject(rawBody).optBoolean("enabled", true)
        return if (enable) {
            TvAutoBootHelper.enableTvHomeLauncherAndPrompt(context)
            jsonOk("""{"ok":true,"message":"Đã bật chế độ Màn hình chính TV Box & mở hộp chọn trên màn hình TV"}""")
        } else {
            TvAutoBootHelper.disableTvHomeLauncher(context)
            jsonOk("""{"ok":true,"message":"Đã tắt chế độ Màn hình chính TV Box (khôi phục màn hình Home gốc)"}""")
        }
    }

    private fun handleOpenPermission(rawBody: String): WebHttpResponse {
        val typeStr = JSONObject(rawBody).optString("type", "").trim()
        val msg = when (typeStr) {
            StartupPermissionType.AUTO_LAUNCH_ON_BOOT.name -> {
                StartupPermissionsHelper.openAutoLaunchSettings(context)
                "Đã mở trang cấp quyền Tự động bật app trên màn hình TV Box"
            }
            StartupPermissionType.BATTERY_UNRESTRICTED.name -> {
                StartupPermissionsHelper.openBatteryOptimizationSettings(context)
                "Đã mở trang Bỏ giới hạn pin trên màn hình TV Box"
            }
            StartupPermissionType.NOTIFICATIONS.name -> {
                StartupPermissionsHelper.openNotificationSettings(context)
                "Đã mở trang Cài đặt thông báo trên màn hình TV Box"
            }
            StartupPermissionType.INSTALL_UPDATES.name -> {
                StartupPermissionsHelper.openInstallUnknownAppsSettings(context)
                "Đã mở trang Cho phép tự cập nhật OTA trên màn hình TV Box"
            }
            "SYSTEM_SETTINGS" -> {
                TvAutoBootHelper.openSystemSettings(context)
                "Đã mở Cài đặt Android trên màn hình TV Box"
            }
            else -> return jsonBad("Loại quyền không hợp lệ")
        }
        return jsonOk(JSONObject().put("ok", true).put("message", msg).toString())
    }

    private suspend fun handleMembersDiff(rawBody: String): WebHttpResponse {
        val j = runCatching { JSONObject(rawBody) }.getOrDefault(JSONObject())
        val targetKey = j.optString("targetKey", "ALL").ifBlank { "ALL" }
        val connected = attendanceEngine.getConnectedHandles()
        val targets = if (targetKey == "ALL") connected else connected.filter { it.device.key == targetKey }
        if (targets.isEmpty()) return jsonBad("Chưa có máy chấm công online để đối chiếu")

        val tree = attendanceEngine.withDeviceIoLock { loadMembersTreeUseCase.loadForTargets(targets) }
            .getOrElse { return jsonBad(it.message ?: "Không tải được danh sách từ máy chủ") }
        val classMap = tree.classes.associate { it.id to it.name }
        val useHikRules = targets.all { it.device.isHikvision }
        val branding = configRepository.getBranding()

        val notOnArr = JSONArray()
        val onArr = JSONArray()
        tree.members.forEach { m ->
            val key = m.pushKeyFor(useHikRules)
            val pushable = m.isPushableFor(useHikRules)
            val onDev = if (useHikRules) key in tree.existingEmployeeNosOnTarget
            else (m.cardIdInt != null && m.cardIdInt in tree.existingCardIdsOnTarget)
            val obj = JSONObject()
                .put("pushKey", key)
                .put("rawCard", m.rawCard)
                .put("name", m.name)
                .put("groupName", classMap[m.classId].orEmpty())
                .put("pushable", pushable)
                .put("hasPhoto", m.avatarUrl.isNotBlank())
            if (onDev) onArr.put(obj) else notOnArr.put(obj)
        }
        return jsonOk(
            JSONObject()
                .put("ok", true)
                .put("personLabel", branding.person)
                .put("groupLabel", branding.group)
                .put("totalServer", tree.members.size)
                .put("notOnDeviceCount", notOnArr.length())
                .put("onDeviceCount", onArr.length())
                .put("notOnDevice", notOnArr)
                .put("onDevice", onArr)
                .toString()
        )
    }

    private suspend fun handleMembersPush(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val targetKey = j.optString("targetKey", "ALL").ifBlank { "ALL" }
        val pushAllMissing = j.optBoolean("pushAllMissing", false)
        val selectedKeys = HashSet<String>().apply {
            val arr = j.optJSONArray("pushKeys")
            if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i).trim())
        }
        val connected = attendanceEngine.getConnectedHandles()
        val targets = if (targetKey == "ALL") connected else connected.filter { it.device.key == targetKey }
        if (targets.isEmpty()) return jsonBad("Chưa có máy chấm công online")

        val tree = attendanceEngine.withDeviceIoLock { loadMembersTreeUseCase.loadForTargets(targets) }
            .getOrElse { return jsonBad(it.message ?: "Không tải được danh sách từ máy chủ") }
        val useHikRules = targets.all { it.device.isHikvision }
        val membersToPush = if (pushAllMissing || selectedKeys.isEmpty()) {
            tree.members
        } else {
            tree.members.filter { it.pushKeyFor(useHikRules) in selectedKeys }
        }
        if (membersToPush.isEmpty()) return jsonBad("Chưa chọn nhân sự hợp lệ để đẩy")

        val cfg = configRepository.getConfig()
        val summary = attendanceEngine.withDeviceIoLock {
            pushMembersToDevicesUseCase(
                targets = targets,
                members = membersToPush,
                skipExistingOnDevice = pushAllMissing,
                maxNameBytes = cfg.maxDeviceNameBytes,
            )
        }
        val msg = "Đẩy xong: Mới ${summary.succeeded} • Đã có ${summary.skippedExisting} • Lỗi ${summary.failed}"
        return jsonOk(
            JSONObject()
                .put("ok", summary.failed == 0)
                .put("message", msg)
                .put("succeeded", summary.succeeded)
                .put("skippedExisting", summary.skippedExisting)
                .put("failed", summary.failed)
                .toString()
        )
    }

    private fun jsonOk(body: String) = WebHttpResponse(200, JSON_TYPE, body)
    private fun jsonBad(msg: String) = WebHttpResponse(400, JSON_TYPE, JSONObject().put("ok", false).put("error", msg).toString())

    companion object {
        private const val JSON_TYPE = "application/json; charset=utf-8"

        /** Đóng gói trạng thái quyền khởi động 24/7 thật trên thiết bị vào JSON `/api/status`. */
        fun buildStartupJson(context: Context, cfg: AppConfig): JSONObject {
            val snap = runCatching { StartupPermissionsHelper.evaluate(context) }.getOrNull()
            val isDeviceOwner = runCatching {
                (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
                    ?.isDeviceOwnerApp(context.packageName) == true
            }.getOrDefault(false)
            val itemsArr = JSONArray().apply {
                snap?.items?.forEach { item ->
                    put(
                        JSONObject()
                            .put("type", item.type.name)
                            .put("title", item.title)
                            .put("actionLabel", item.actionLabel)
                            .put("granted", item.granted)
                            .put("required", item.required)
                    )
                }
            }
            return JSONObject()
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("androidRelease", Build.VERSION.RELEASE.orEmpty())
                .put("isTvDevice", snap?.isTvDevice ?: false)
                .put("autoStartOnBoot", cfg.autoStartOnBoot)
                .put("autoStartRunning", cfg.autoStartRunning)
                .put("isDefaultHomeLauncher", snap?.isDefaultHomeLauncher ?: false)
                .put("isTvHomeAliasEnabled", runCatching { TvAutoBootHelper.isTvHomeAliasEnabled(context) }.getOrDefault(false))
                .put("canSetHomeLauncher", snap?.canSetHomeLauncher ?: false)
                .put("isDeviceOwner", isDeviceOwner)
                .put("isAppInForeground", TvAutoBootHelper.isAppInForeground())
                .put("allMandatoryGranted", snap?.allMandatoryGranted ?: false)
                .put("grantedMandatoryCount", snap?.grantedMandatoryCount ?: 0)
                .put("totalMandatoryCount", snap?.totalMandatoryCount ?: 0)
                .put("items", itemsArr)
        }
    }
}

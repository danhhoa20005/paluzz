package com.edusing.chamcongtvbox.service.web

import android.util.Base64
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.model.HikvisionDoorCommand
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.BackfillHikvisionEventsUseCase
import com.edusing.chamcongtvbox.service.AttendanceEngine
import org.json.JSONObject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bộ xử lý toàn bộ API quản trị phần cứng Hikvision `DS-K1T320MX` cho Web LAN (`http://chamcong.local:8080`):
 * sức khoẻ phần cứng, mở cửa, đồng bộ giờ VN, khoá IP tĩnh, khởi động lại, xem Camera, nạp ảnh khuôn mặt AI,
 * tạo nhanh nhân sự, đồng bộ bù lịch sử & xuất báo cáo Excel CSV UTF-8 BOM.
 */
internal class WebConsoleHikvisionHandler(
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val hikControl: HikvisionControlGateway,
    private val backfillUseCase: BackfillHikvisionEventsUseCase,
) {
    suspend fun handle(method: String, path: String, query: String, rawBody: String): WebHttpResponse {
        val params = parseQuery(query)
        return when {
            path == "/api/hikvision/health" && method == "POST" -> handleHealth(rawBody)
            path == "/api/hikvision/door" && method == "POST" -> handleDoor(rawBody)
            path == "/api/hikvision/sync-time" && method == "POST" -> handleSyncTime(rawBody)
            path == "/api/hikvision/lock-static-ip" && method == "POST" -> handleLockStaticIp(rawBody)
            path == "/api/hikvision/reboot" && method == "POST" -> handleReboot(rawBody)
            path == "/api/hikvision/backfill" && method == "POST" -> handleBackfill(rawBody)
            path == "/api/hikvision/face-upload" && method == "POST" -> handleFaceUpload(rawBody)
            path == "/api/hikvision/quick-enroll" && method == "POST" -> handleQuickEnroll(rawBody)
            path == "/api/hikvision/snapshot" && method == "GET" -> handleSnapshot(params["key"].orEmpty())
            path == "/api/hikvision/export-csv" && method == "GET" -> handleExportCsv(params["key"].orEmpty(), params["hours"]?.toIntOrNull() ?: 24)
            else -> WebHttpResponse(404, JSON_TYPE, """{"ok":false,"error":"Không tìm thấy API Hikvision"}""")
        }
    }

    private suspend fun handleHealth(rawBody: String): WebHttpResponse {
        val dev = findHikDevice(JSONObject(rawBody).optString("key", "")) ?: return jsonBad("Không tìm thấy máy Hikvision")
        val h = hikControl.fetchHealth(dev).getOrElse { return jsonBad(it.message ?: "Lỗi đọc sức khoẻ máy") }
        val json = JSONObject().put("ok", true)
            .put("model", h.model).put("serialNumber", h.serialNumber).put("firmwareVersion", h.firmwareVersion)
            .put("userCount", h.userCount).put("faceCount", h.faceCount).put("cardCount", h.cardCount)
            .put("maxFaceCapacity", h.maxFaceCapacity).put("doorLocked", h.doorLocked)
            .put("antiDismantleAlert", h.antiDismantleAlert).put("isDhcp", h.isDhcp)
            .put("ipAddress", h.ipAddress).put("subnetMask", h.subnetMask).put("gateway", h.gateway)
            .put("deviceTimeText", h.deviceTimeText)
        return jsonOk(json.toString())
    }

    private suspend fun handleDoor(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val dev = findHikDevice(j.optString("key", "")) ?: return jsonBad("Không tìm thấy máy Hikvision")
        val cmd = HikvisionDoorCommand.fromCode(j.optString("cmd", "open"))
        val msg = hikControl.controlDoor(dev, cmd).getOrElse { return jsonBad(it.message ?: "Mở cửa thất bại") }
        attendanceEngine.appendLog(dev.displayName, "-", msg, EventSeverity.INFO)
        return jsonOk(JSONObject().put("ok", true).put("message", msg).toString())
    }

    private suspend fun handleSyncTime(rawBody: String): WebHttpResponse {
        val dev = findHikDevice(JSONObject(rawBody).optString("key", "")) ?: return jsonBad("Không tìm thấy máy Hikvision")
        val msg = hikControl.syncVietnamTime(dev).getOrElse { return jsonBad(it.message ?: "Đồng bộ giờ thất bại") }
        attendanceEngine.appendLog(dev.displayName, "-", msg, EventSeverity.SUCCESS)
        return jsonOk(JSONObject().put("ok", true).put("message", msg).toString())
    }

    private suspend fun handleLockStaticIp(rawBody: String): WebHttpResponse {
        val dev = findHikDevice(JSONObject(rawBody).optString("key", "")) ?: return jsonBad("Không tìm thấy máy Hikvision")
        val msg = hikControl.lockStaticIp(dev).getOrElse { return jsonBad(it.message ?: "Khoá IP tĩnh thất bại") }
        attendanceEngine.appendLog(dev.displayName, "-", msg, EventSeverity.SUCCESS)
        return jsonOk(JSONObject().put("ok", true).put("message", msg).toString())
    }

    private suspend fun handleReboot(rawBody: String): WebHttpResponse {
        val dev = findHikDevice(JSONObject(rawBody).optString("key", "")) ?: return jsonBad("Không tìm thấy máy Hikvision")
        val msg = hikControl.rebootDevice(dev).getOrElse { return jsonBad(it.message ?: "Khởi động lại thất bại") }
        attendanceEngine.appendLog(dev.displayName, "-", msg, EventSeverity.WARNING)
        return jsonOk(JSONObject().put("ok", true).put("message", msg).toString())
    }

    private suspend fun handleBackfill(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val dev = findHikDevice(j.optString("key", "")) ?: return jsonBad("Không tìm thấy máy Hikvision")
        val cfg = configRepository.getConfig()
        if (!cfg.isLoggedIn) return jsonBad("Chưa đăng nhập tài khoản web tổng")
        val hours = j.optInt("hours", 0)
        val nowMs = attendanceEngine.currentServerTimeMillis()
        val sinceMs = if (hours > 0) nowMs - hours * 3_600_000L else startOfVietnamDayMs(nowMs)
        val domain = configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)
        val summary = attendanceEngine.withDeviceIoLock {
            backfillUseCase(dev, domain, cfg.accountId, sinceMs, nowMs + 60_000L, cfg.dedupSeconds)
        }.getOrElse { return jsonBad(it.message ?: "Đồng bộ bù thất bại") }
        attendanceEngine.appendLog(dev.displayName, "-", "Đồng bộ bù: ${summary.message}", EventSeverity.SUCCESS)
        return jsonOk(JSONObject().put("ok", true).put("message", summary.message).toString())
    }

    private suspend fun handleFaceUpload(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val dev = findHikDevice(j.optString("key", "")) ?: return jsonBad("Không tìm thấy máy Hikvision")
        val empNo = j.optString("employeeNo", "").trim()
        val b64 = j.optString("imageBase64", "").substringAfter("base64,", j.optString("imageBase64", "")).trim()
        if (empNo.isEmpty() || b64.isEmpty()) return jsonBad("Thiếu mã nhân sự hoặc dữ liệu ảnh khuôn mặt")
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrElse { return jsonBad("Ảnh Base64 không hợp lệ") }
        val msg = attendanceEngine.withDeviceIoLock { hikControl.uploadFacePhoto(dev, empNo, bytes) }
            .getOrElse { return jsonBad(it.message ?: "Nạp ảnh khuôn mặt thất bại") }
        attendanceEngine.appendLog(dev.displayName, empNo, msg, EventSeverity.SUCCESS)
        return jsonOk(JSONObject().put("ok", true).put("message", msg).toString())
    }

    private suspend fun handleQuickEnroll(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val dev = findHikDevice(j.optString("key", "")) ?: return jsonBad("Không tìm thấy máy Hikvision")
        val empNo = j.optString("employeeNo", "").trim()
        val fullName = j.optString("fullName", "").trim()
        val cardNo = j.optString("cardNo", "").trim()
        val rawB64 = j.optString("imageBase64", "").trim()
        val bytes = if (rawB64.isNotEmpty()) {
            runCatching { Base64.decode(rawB64.substringAfter("base64,", rawB64), Base64.DEFAULT) }.getOrNull()
        } else null
        val msg = attendanceEngine.withDeviceIoLock {
            hikControl.upsertUserWithOptionalFace(dev, empNo, fullName, cardNo, bytes)
        }.getOrElse { return jsonBad(it.message ?: "Tạo nhân sự thất bại") }
        attendanceEngine.appendLog(dev.displayName, empNo, msg, EventSeverity.SUCCESS)
        return jsonOk(JSONObject().put("ok", true).put("message", msg).toString())
    }

    private suspend fun handleSnapshot(key: String): WebHttpResponse {
        val dev = findHikDevice(key) ?: return jsonBad("Không tìm thấy máy Hikvision")
        val bytes = hikControl.captureSnapshot(dev).getOrElse { return jsonBad(it.message ?: "Không chụp được Camera") }
        return WebHttpResponse(200, "image/jpeg", "", binaryBody = bytes)
    }

    private suspend fun handleExportCsv(key: String, hours: Int): WebHttpResponse {
        val dev = findHikDevice(key) ?: return jsonBad("Không tìm thấy máy Hikvision")
        val nowMs = attendanceEngine.currentServerTimeMillis()
        val sinceMs = if (hours in 1..168) nowMs - hours * 3_600_000L else startOfVietnamDayMs(nowMs)
        val rows = hikControl.fetchHistoryEvents(dev, sinceMs, nowMs + 60_000L)
            .getOrElse { return jsonBad(it.message ?: "Không xuất được lịch sử máy") }
        val sb = StringBuilder("\uFEFFThời gian,Mã nhân sự,Họ tên,Phương thức,Số thứ tự,Máy chấm công\r\n")
        rows.forEach { r ->
            sb.append(csvCell(r.timeText)).append(',').append(csvCell(r.employeeNo)).append(',')
                .append(csvCell(r.name)).append(',').append(csvCell(r.verifyMethod)).append(',')
                .append(r.serialNo).append(',').append(csvCell(dev.displayName)).append("\r\n")
        }
        val dayStamp = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = VN_TZ }.format(Date(nowMs))
        return WebHttpResponse(
            statusCode = 200,
            contentType = "text/csv; charset=utf-8",
            body = "",
            binaryBody = sb.toString().toByteArray(Charsets.UTF_8),
            extraHeaders = mapOf("Content-Disposition" to """attachment; filename="chamcong_${dayStamp}.csv""""),
        )
    }

    private fun findHikDevice(key: String): DeviceConfig? =
        configRepository.getConfig().devices.firstOrNull { it.isHikvision && (key.isBlank() || it.key == key) }

    private fun startOfVietnamDayMs(nowMs: Long): Long = Calendar.getInstance(VN_TZ).apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun csvCell(raw: String): String = "\"${raw.replace("\"", "\"\"")}\""
    private fun jsonOk(body: String) = WebHttpResponse(200, JSON_TYPE, body)
    private fun jsonBad(msg: String) = WebHttpResponse(400, JSON_TYPE, JSONObject().put("ok", false).put("error", msg).toString())

    companion object {
        private const val JSON_TYPE = "application/json; charset=utf-8"
        private val VN_TZ: TimeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

        fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            return query.split('&').mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else {
                    val k = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault(pair.substring(0, idx))
                    val v = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault(pair.substring(idx + 1))
                    k to v
                }
            }.toMap()
        }
    }
}

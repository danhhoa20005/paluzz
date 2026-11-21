package com.edusing.chamcongtvbox.service.web

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.model.HikvisionWebhookConfig
import com.edusing.chamcongtvbox.domain.model.HikvisionWebhookStats
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.service.AttendanceEngine
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bộ xử lý API cấu hình & theo dõi Webhook (`HTTP Listening`) trên Web quản trị LAN:
 * - Đọc trạng thái Webhook hiện tại trên máy Hikvision + bộ đếm gói tin TV Box vừa nhận.
 * - Gắn 1 chạm Webhook về chính TV Box (`http://<IP-Box>:8080/hikvision/event`) hoặc máy chủ tuỳ chỉnh (HTTP/HTTPS).
 * - Tắt Webhook trên máy chấm công khi cần chuyển sang hệ thống khác.
 */
internal class WebConsoleWebhookHelper(
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val hikControl: HikvisionControlGateway,
    private val activePortProvider: () -> Int,
    private val webhookStatsProvider: () -> HikvisionWebhookStats,
) {
    suspend fun handle(method: String, path: String, rawBody: String): WebHttpResponse? = when {
        path == "/api/hikvision/webhook/get" && method == "POST" -> handleGet(rawBody)
        path == "/api/hikvision/webhook/apply" && method == "POST" -> handleApply(rawBody)
        path == "/api/hikvision/webhook/clear" && method == "POST" -> handleClear(rawBody)
        else -> null
    }

    private suspend fun handleGet(rawBody: String): WebHttpResponse {
        val dev = findHikDevice(JSONObject(rawBody).optString("key", ""))
            ?: return jsonBad("Không tìm thấy máy Hikvision")
        val tvBoxIp = LocalWebConsoleServer.detectLanIpv4()
        val tvBoxPort = activePortProvider().takeIf { it > 0 } ?: 8080
        val tvBoxUrl = if (tvBoxIp.isNotBlank()) "http://$tvBoxIp:$tvBoxPort/hikvision/event" else ""
        val statsJson = buildStatsJson(webhookStatsProvider())

        val cfg = hikControl.fetchWebhookConfig(dev).getOrElse { err ->
            return WebHttpResponse(
                400, JSON_TYPE,
                JSONObject().put("ok", false).put("error", err.message ?: "Không đọc được Webhook")
                    .put("tvBoxIp", tvBoxIp).put("tvBoxPort", tvBoxPort).put("tvBoxUrl", tvBoxUrl)
                    .put("stats", statsJson).toString(),
            )
        }
        val pointsToBox = cfg.enabled &&
            cfg.hostOrIp.equals(tvBoxIp, ignoreCase = true) &&
            cfg.port == tvBoxPort &&
            cfg.url.trimEnd('/') == "/hikvision/event"

        val json = JSONObject().put("ok", true)
            .put("enabled", cfg.enabled).put("protocolType", cfg.protocolType)
            .put("addressingType", cfg.addressingType).put("hostOrIp", cfg.hostOrIp)
            .put("port", cfg.port).put("url", cfg.url).put("summaryUrl", cfg.summaryUrl)
            .put("tvBoxIp", tvBoxIp).put("tvBoxPort", tvBoxPort).put("tvBoxUrl", tvBoxUrl)
            .put("pointsToThisBox", pointsToBox).put("stats", statsJson)
        return jsonOk(json.toString())
    }

    private suspend fun handleApply(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val dev = findHikDevice(j.optString("key", "")) ?: return jsonBad("Không tìm thấy máy Hikvision")
        val mode = j.optString("mode", "tvbox").trim().lowercase()
        val cfg = if (mode == "tvbox") {
            val tvBoxIp = LocalWebConsoleServer.detectLanIpv4()
            val tvBoxPort = activePortProvider().takeIf { it > 0 } ?: 8080
            if (tvBoxIp.isBlank()) return jsonBad("TV Box chưa có IP mạng LAN — kiểm tra Wi-Fi/Ethernet")
            HikvisionWebhookConfig(true, "HTTP", "ipaddress", tvBoxIp, tvBoxPort, "/hikvision/event")
        } else {
            parseCustomConfig(j) ?: return jsonBad("Địa chỉ máy chủ hoặc cổng Webhook không hợp lệ")
        }
        val msg = hikControl.configureWebhook(dev, cfg)
            .getOrElse { return jsonBad(it.message ?: "Cài Webhook thất bại") }
        attendanceEngine.appendLog(dev.displayName, "-", msg, EventSeverity.SUCCESS)
        return jsonOk(JSONObject().put("ok", true).put("message", msg).toString())
    }

    private suspend fun handleClear(rawBody: String): WebHttpResponse {
        val dev = findHikDevice(JSONObject(rawBody).optString("key", ""))
            ?: return jsonBad("Không tìm thấy máy Hikvision")
        val msg = hikControl.clearWebhook(dev).getOrElse { return jsonBad(it.message ?: "Tắt Webhook thất bại") }
        attendanceEngine.appendLog(dev.displayName, "-", msg, EventSeverity.INFO)
        return jsonOk(JSONObject().put("ok", true).put("message", msg).toString())
    }

    private fun parseCustomConfig(j: JSONObject): HikvisionWebhookConfig? {
        val rawHost = j.optString("hostOrIp", "").trim()
        if (rawHost.isEmpty()) return null
        var proto = if (j.optString("protocolType", "HTTP").equals("HTTPS", true)) "HTTPS" else "HTTP"
        var host = rawHost
        var port = j.optInt("port", 0)
        var path = j.optString("url", "").trim()

        if (rawHost.startsWith("http://", true) || rawHost.startsWith("https://", true)) {
            val uri = runCatching { URI(rawHost) }.getOrNull() ?: return null
            proto = if (uri.scheme.equals("https", true)) "HTTPS" else "HTTP"
            host = uri.host?.trim().orEmpty()
            if (port <= 0) port = if (uri.port > 0) uri.port else if (proto == "HTTPS") 443 else 80
            if (path.isBlank()) path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/hikvision/event"
        }
        if (host.isBlank()) return null
        if (port !in 1..65535) port = if (proto == "HTTPS") 443 else 80
        val cleanPath = path.ifBlank { "/hikvision/event" }.let { if (it.startsWith("/")) it else "/$it" }
        return HikvisionWebhookConfig(true, proto, "ipaddress", host, port, cleanPath)
    }

    private fun buildStatsJson(stats: HikvisionWebhookStats): JSONObject {
        val timeText = if (stats.lastReceivedAtMs > 0L) {
            SimpleDateFormat("HH:mm:ss dd/MM", Locale.US).apply { timeZone = VN_TZ }.format(Date(stats.lastReceivedAtMs))
        } else ""
        return JSONObject()
            .put("eventCount", stats.eventCount)
            .put("heartbeatCount", stats.heartbeatCount)
            .put("lastTimeText", timeText)
            .put("lastSourceIp", stats.lastSourceIp)
            .put("lastSummary", stats.lastSummary)
    }

    private fun findHikDevice(key: String): DeviceConfig? =
        configRepository.getConfig().devices.firstOrNull { it.isHikvision && (key.isBlank() || it.key == key) }

    private fun jsonOk(body: String) = WebHttpResponse(200, JSON_TYPE, body)
    private fun jsonBad(msg: String) = WebHttpResponse(400, JSON_TYPE, JSONObject().put("ok", false).put("error", msg).toString())

    companion object {
        private const val JSON_TYPE = "application/json; charset=utf-8"
        private val VN_TZ: TimeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    }
}

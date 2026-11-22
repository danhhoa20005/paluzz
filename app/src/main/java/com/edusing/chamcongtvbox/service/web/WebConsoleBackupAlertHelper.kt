package com.edusing.chamcongtvbox.service.web

import com.edusing.chamcongtvbox.data.local.AlertConfig
import com.edusing.chamcongtvbox.data.local.ConfigBackupCodec
import com.edusing.chamcongtvbox.data.local.SecureConfigStore
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.service.AlertNotifier
import com.edusing.chamcongtvbox.service.AttendanceEngine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bộ xử lý REST API mở rộng cho Web quản trị LAN:
 * - Sửa thông số máy chấm công trực tiếp (`POST /api/devices/update`).
 * - Cấu hình & gửi thử cảnh báo Telegram / Webhook (`GET/POST /api/alerts/...`).
 * - Xuất / Khôi phục tệp sao lưu mã hoá AES-256-GCM toàn bộ cấu hình (`GET /api/backup/export`, `POST /api/backup/import`).
 */
internal class WebConsoleBackupAlertHelper(
    private val configStore: SecureConfigStore,
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val alertNotifier: AlertNotifier,
) {
    suspend fun handle(method: String, path: String, query: String, rawBody: String): WebHttpResponse? = when {
        path == "/api/devices/update" && method == "POST" -> handleUpdateDevice(rawBody)
        path == "/api/alerts/get" && method == "GET" -> handleGetAlerts()
        path == "/api/alerts/save" && method == "POST" -> handleSaveAlerts(rawBody)
        path == "/api/alerts/test" && method == "POST" -> handleTestAlerts()
        path == "/api/backup/export" && method == "GET" -> handleExportBackup(WebConsoleHikvisionHandler.parseQuery(query)["pass"].orEmpty())
        path == "/api/backup/import" && method == "POST" -> handleImportBackup(rawBody)
        else -> null
    }

    private suspend fun handleUpdateDevice(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val oldKey = j.optString("oldKey", "").trim()
        val cur = configRepository.getConfig().devices.firstOrNull { it.key == oldKey }
            ?: return jsonBad("Không tìm thấy máy chấm công cần sửa")
        val ip = j.optString("ip", cur.ip).trim()
        val port = j.optInt("port", cur.port).coerceIn(1, 65535)
        val newPass = j.optString("accessPassword", "")
        val updated = cur.copy(
            name = j.optString("name", cur.name).trim(),
            ip = ip.ifBlank { cur.ip },
            port = port,
            username = j.optString("username", cur.username).trim().ifBlank { cur.username },
            accessPassword = if (newPass.isNotEmpty()) newPass else cur.accessPassword,
            useHttps = j.optBoolean("useHttps", cur.useHttps),
            machineNo = j.optInt("machineNo", cur.machineNo).coerceAtLeast(1),
            useUdp = j.optBoolean("useUdp", cur.useUdp),
        )
        attendanceEngine.updateDevice(oldKey, updated)
        return jsonOk(JSONObject().put("ok", true).put("message", "Đã cập nhật & nối lại ${updated.displayName} (${updated.ip}:${updated.port})").toString())
    }

    private fun handleGetAlerts(): WebHttpResponse {
        val c = alertNotifier.getConfig()
        val j = JSONObject().put("ok", true).put("enabled", c.enabled)
            .put("telegramBotToken", c.telegramBotToken).put("telegramChatId", c.telegramChatId)
            .put("webhookUrl", c.webhookUrl).put("offlineThresholdMin", c.offlineThresholdMin)
        return jsonOk(j.toString())
    }

    private fun handleSaveAlerts(rawBody: String): WebHttpResponse {
        val j = JSONObject(rawBody)
        val cur = alertNotifier.getConfig()
        val next = AlertConfig(
            enabled = j.optBoolean("enabled", cur.enabled),
            telegramBotToken = j.optString("telegramBotToken", cur.telegramBotToken).trim(),
            telegramChatId = j.optString("telegramChatId", cur.telegramChatId).trim(),
            webhookUrl = j.optString("webhookUrl", cur.webhookUrl).trim(),
            offlineThresholdMin = j.optInt("offlineThresholdMin", cur.offlineThresholdMin).coerceIn(1, 120),
        )
        alertNotifier.saveConfig(next)
        return jsonOk("""{"ok":true,"message":"Đã lưu cấu hình cảnh báo sự cố"}""")
    }

    private suspend fun handleTestAlerts(): WebHttpResponse {
        val org = configRepository.getConfig().orgName
        val msg = alertNotifier.sendTestAlert(org).getOrElse { return jsonBad(it.message ?: "Gửi thử cảnh báo thất bại") }
        return jsonOk(JSONObject().put("ok", true).put("message", msg).toString())
    }

    private fun handleExportBackup(passphrase: String): WebHttpResponse {
        val cfg = configRepository.getConfig()
        val effectivePass = passphrase.ifBlank { cfg.password.ifBlank { WebConsoleAuth.DEFAULT_SETUP_PIN } }
        val bundle = JSONObject().put("activeMode", cfg.mode.code)
        val modesObj = JSONObject()
        AppMode.entries.forEach { m ->
            val mc = configStore.load(m)
            modesObj.put(m.code, serializeAppConfig(mc))
        }
        bundle.put("modes", modesObj)
        val ac = alertNotifier.getConfig()
        bundle.put(
            "alerts",
            JSONObject().put("enabled", ac.enabled).put("telegramBotToken", ac.telegramBotToken)
                .put("telegramChatId", ac.telegramChatId).put("webhookUrl", ac.webhookUrl)
                .put("offlineThresholdMin", ac.offlineThresholdMin),
        )
        val encrypted = ConfigBackupCodec.encrypt(bundle.toString(), effectivePass)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh") }.format(Date())
        return WebHttpResponse(
            statusCode = 200,
            contentType = "application/json; charset=utf-8",
            body = encrypted,
            extraHeaders = mapOf("Content-Disposition" to """attachment; filename="chamcong_backup_${stamp}.json""""),
        )
    }

    private suspend fun handleImportBackup(rawBody: String): WebHttpResponse {
        val req = JSONObject(rawBody)
        val envelope = req.optString("backupText", "")
        val cfgNow = configRepository.getConfig()
        val pass = req.optString("passphrase", "").ifBlank { cfgNow.password.ifBlank { WebConsoleAuth.DEFAULT_SETUP_PIN } }
        if (envelope.isBlank()) return jsonBad("Thiếu nội dung tệp sao lưu")
        val plain = ConfigBackupCodec.decrypt(envelope, pass).getOrElse { return jsonBad(it.message ?: "Giải mã thất bại") }
        val bundle = JSONObject(plain)
        val modesObj = bundle.optJSONObject("modes") ?: return jsonBad("Tệp sao lưu thiếu dữ liệu cấu hình")
        var restoredCount = 0
        AppMode.entries.forEach { m ->
            val mo = modesObj.optJSONObject(m.code) ?: return@forEach
            val parsed = deserializeAppConfig(m, mo)
            configStore.save(parsed)
            restoredCount++
        }
        bundle.optJSONObject("alerts")?.let { a ->
            alertNotifier.saveConfig(
                AlertConfig(
                    a.optBoolean("enabled", false), a.optString("telegramBotToken", ""),
                    a.optString("telegramChatId", ""), a.optString("webhookUrl", ""),
                    a.optInt("offlineThresholdMin", 5).coerceIn(1, 120),
                )
            )
        }
        val activeMode = AppMode.fromCode(bundle.optString("activeMode", cfgNow.mode.code))
        val activeCfg = configStore.load(activeMode)
        configRepository.saveConfig(activeCfg)
        attendanceEngine.appendLog("Sao lưu", "-", "Đã khôi phục cấu hình ($restoredCount chế độ, ${activeCfg.devices.size} máy)", EventSeverity.SUCCESS)
        if (activeCfg.isLoggedIn) attendanceEngine.autoDiscoverConnectAndReady(fromInternetRecovery = false)
        return jsonOk(JSONObject().put("ok", true).put("message", "Đã khôi phục thành công (${activeCfg.devices.size} máy) & đang tự kết nối!").toString())
    }

    private fun serializeAppConfig(c: com.edusing.chamcongtvbox.domain.model.AppConfig): JSONObject {
        val devs = JSONArray().apply {
            c.devices.forEach { d ->
                put(JSONObject().put("ip", d.ip).put("port", d.port).put("machineNo", d.machineNo).put("name", d.name)
                    .put("password", d.password).put("license", d.license).put("useUdp", d.useUdp)
                    .put("protocol", d.protocol.code).put("username", d.username).put("accessPassword", d.accessPassword)
                    .put("useHttps", d.useHttps).put("serialNumber", d.serialNumber))
            }
        }
        return JSONObject().put("apiDomain", c.apiDomain).put("accountId", c.accountId).put("orgName", c.orgName)
            .put("username", c.username).put("password", c.password).put("token", c.token)
            .put("defaultHikUsername", c.resolvedHikUsername).put("defaultHikPassword", c.resolvedHikPassword)
            .put("voiceGreetingEnabled", c.voiceGreetingEnabled).put("localHostName", c.safeLocalHostName)
            .put("pollIntervalMs", c.pollIntervalMs).put("dedupSeconds", c.dedupSeconds)
            .put("autoPushEnabled", c.autoPushEnabled).put("autoPushCustomEnabled", c.autoPushCustomEnabled)
            .put("autoPushCustomHour", c.autoPushCustomHour).put("autoPushCustomMinute", c.autoPushCustomMinute)
            .put("devices", devs)
    }

    private fun deserializeAppConfig(m: AppMode, o: JSONObject): com.edusing.chamcongtvbox.domain.model.AppConfig {
        val base = configStore.load(m)
        val arr = o.optJSONArray("devices")
        val devs = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val ip = d.optString("ip", "").trim(); if (ip.isEmpty()) continue
                val proto = com.edusing.chamcongtvbox.domain.model.DeviceProtocol.fromCode(d.optString("protocol", "FK"))
                add(DeviceConfig(ip, d.optInt("port", proto.defaultPort), d.optInt("machineNo", 1), d.optString("name", ""),
                    d.optInt("password", 0), d.optInt("license", DeviceConfig.DEFAULT_LICENSE), d.optBoolean("useUdp", false),
                    proto, d.optString("username", DeviceConfig.DEFAULT_HIK_USERNAME), d.optString("accessPassword", ""),
                    d.optBoolean("useHttps", false), d.optString("serialNumber", "")))
            }
        }
        return base.copy(
            apiDomain = o.optString("apiDomain", base.apiDomain), accountId = o.optString("accountId", base.accountId),
            orgName = o.optString("orgName", base.orgName), username = o.optString("username", base.username),
            password = o.optString("password", base.password), token = o.optString("token", base.token),
            defaultHikUsername = o.optString("defaultHikUsername", base.defaultHikUsername),
            defaultHikPassword = o.optString("defaultHikPassword", base.defaultHikPassword),
            voiceGreetingEnabled = o.optBoolean("voiceGreetingEnabled", base.voiceGreetingEnabled),
            localHostName = o.optString("localHostName", base.localHostName),
            pollIntervalMs = o.optLong("pollIntervalMs", base.pollIntervalMs).coerceIn(3_000L, 60_000L),
            dedupSeconds = o.optLong("dedupSeconds", base.dedupSeconds).coerceIn(0L, 3600L),
            autoPushEnabled = o.optBoolean("autoPushEnabled", base.autoPushEnabled),
            autoPushCustomEnabled = o.optBoolean("autoPushCustomEnabled", base.autoPushCustomEnabled),
            autoPushCustomHour = o.optInt("autoPushCustomHour", base.autoPushCustomHour).coerceIn(0, 23),
            autoPushCustomMinute = o.optInt("autoPushCustomMinute", base.autoPushCustomMinute).coerceIn(0, 59),
            devices = devs,
        )
    }

    private fun jsonOk(body: String) = WebHttpResponse(200, JSON_TYPE, body)
    private fun jsonBad(msg: String) = WebHttpResponse(400, JSON_TYPE, JSONObject().put("ok", false).put("error", msg).toString())

    companion object { private const val JSON_TYPE = "application/json; charset=utf-8" }
}

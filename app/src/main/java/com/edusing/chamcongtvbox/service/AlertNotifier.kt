package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.data.local.AlertConfig
import com.edusing.chamcongtvbox.data.local.AlertConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Bộ gửi cảnh báo sự cố chủ động (Telegram Bot API & Custom Webhook) khi máy chấm công mất kết nối lâu
 * hoặc có cảnh báo phần cứng — chạy nền độc lập, không ảnh hưởng vòng quét 24/7.
 */
internal class AlertNotifier(
    private val store: AlertConfigStore,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build(),
) {
    fun getConfig(): AlertConfig = store.load()
    fun saveConfig(cfg: AlertConfig) = store.save(cfg)

    suspend fun notifyAlert(orgName: String, title: String, message: String, deviceName: String = "", severity: String = "WARNING") {
        val cfg = store.load()
        if (!cfg.enabled || !cfg.hasAnyChannel) return
        dispatch(cfg, orgName, title, message, deviceName, severity)
    }

    suspend fun sendTestAlert(orgName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = store.load()
            require(cfg.hasAnyChannel) { "Chưa cấu hình Telegram Bot hoặc Webhook cảnh báo" }
            val results = dispatch(cfg, orgName, "Kiểm tra kết nối cảnh báo", "Cảnh báo từ TV Box / Chấm công hoạt động bình thường ✓", "Hệ thống", "INFO")
            require(results.any { it.second }) {
                "Gửi thất bại: ${results.joinToString("; ") { "${it.first}: ${it.third}" }}"
            }
            "Đã gửi thử thành công qua: ${results.filter { it.second }.joinToString(", ") { it.first }}"
        }
    }

    private suspend fun dispatch(
        cfg: AlertConfig, orgName: String, title: String, message: String, deviceName: String, severity: String,
    ): List<Triple<String, Boolean, String>> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Triple<String, Boolean, String>>()
        val timeText = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.US).apply { timeZone = VN_TZ }.format(Date())
        val text = formatAlertText(orgName, title, message, deviceName, timeText)

        if (cfg.telegramBotToken.isNotBlank() && cfg.telegramChatId.isNotBlank()) {
            val res = runCatching {
                val url = "https://api.telegram.org/bot${cfg.telegramBotToken}/sendMessage"
                val payload = JSONObject().put("chat_id", cfg.telegramChatId).put("text", text).toString()
                val req = Request.Builder().url(url).post(payload.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA)).build()
                httpClient.newCall(req).execute().use { r ->
                    if (r.isSuccessful) Triple("Telegram", true, "OK")
                    else Triple("Telegram", false, "HTTP ${r.code}")
                }
            }.getOrElse { Triple("Telegram", false, it.message ?: "Lỗi mạng") }
            out.add(res)
        }

        if (cfg.webhookUrl.isNotBlank()) {
            val res = runCatching {
                val payload = JSONObject()
                    .put("orgName", orgName).put("title", title).put("message", message)
                    .put("device", deviceName).put("severity", severity).put("time", timeText).put("text", text)
                    .toString()
                val req = Request.Builder().url(cfg.webhookUrl).post(payload.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA)).build()
                httpClient.newCall(req).execute().use { r ->
                    if (r.isSuccessful) Triple("Webhook", true, "OK")
                    else Triple("Webhook", false, "HTTP ${r.code}")
                }
            }.getOrElse { Triple("Webhook", false, it.message ?: "Lỗi mạng") }
            out.add(res)
        }
        out
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val VN_TZ: TimeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

        fun formatAlertText(orgName: String, title: String, message: String, deviceName: String, timeText: String): String =
            buildString {
                append("[").append(orgName.ifBlank { "Chấm công TV Box" }).append("] ").append(title).append('\n')
                if (deviceName.isNotBlank()) append("Thiết bị: ").append(deviceName).append('\n')
                append(message).append('\n')
                append("Lúc: ").append(timeText)
            }
    }
}

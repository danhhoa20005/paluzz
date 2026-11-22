package com.edusing.chamcongtvbox.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Cấu hình cảnh báo sự cố chủ động (Telegram Bot & Webhook cảnh báo ngoài). */
data class AlertConfig(
    val enabled: Boolean = false,
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val webhookUrl: String = "",
    val offlineThresholdMin: Int = 5,
) {
    val hasAnyChannel: Boolean
        get() = (telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()) || webhookUrl.isNotBlank()
}

/** Lưu trữ mã hoá AES-256 riêng cho cấu hình cảnh báo Telegram / Webhook sự cố. */
internal class AlertConfigStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val mk = MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context.applicationContext, PREFS_ENC, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { context.applicationContext.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE) }

    fun load(): AlertConfig = runCatching {
        AlertConfig(
            enabled = prefs.getBoolean("enabled", false),
            telegramBotToken = prefs.getString("tg_token", "").orEmpty().trim(),
            telegramChatId = prefs.getString("tg_chat", "").orEmpty().trim(),
            webhookUrl = prefs.getString("wh_url", "").orEmpty().trim(),
            offlineThresholdMin = prefs.getInt("off_min", 5).coerceIn(1, 120),
        )
    }.getOrDefault(AlertConfig())

    fun save(cfg: AlertConfig) {
        runCatching {
            prefs.edit()
                .putBoolean("enabled", cfg.enabled)
                .putString("tg_token", cfg.telegramBotToken.trim())
                .putString("tg_chat", cfg.telegramChatId.trim())
                .putString("wh_url", cfg.webhookUrl.trim())
                .putInt("off_min", cfg.offlineThresholdMin.coerceIn(1, 120))
                .commit()
        }
    }

    companion object {
        private const val PREFS_ENC = "chamcong_alert_secure_v1"
        private const val PREFS_FALLBACK = "chamcong_alert_v1"
    }
}

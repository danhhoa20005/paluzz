package com.edusing.chamcongtvbox.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.AutoPushScheduleConfig
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lưu trữ cấu hình mã hoá AES-256 tách riêng theo từng chế độ ([AppMode]):
 * - `DIEMDANH` (`api.diemdanhhs.com`), `PALUZZ` (`api.paluzz.com`), `DONTRA` (`api.dontrahs.com`).
 * - Chuyển chế độ giữ nguyên tài khoản & danh sách đã lưu của từng chế độ, không ghi đè lẫn nhau.
 */
class SecureConfigStore(
    context: Context,
    private val defaultMode: AppMode = AppMode.PALUZZ,
) {
    private val backupPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE)
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    private fun createPrefs(appContext: Context): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME_ENCRYPTED,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { backupPrefs }

    fun brandingFor(mode: AppMode): BrandingConfig = BrandingConfig.forMode(mode)

    fun loadActiveMode(): AppMode {
        val raw = readRawString(KEY_ACTIVE_MODE)
        return if (raw.isNullOrBlank()) defaultMode else AppMode.fromCode(raw)
    }

    fun load(targetMode: AppMode = loadActiveMode()): AppConfig {
        val branding = brandingFor(targetMode)
        val raw = readRawString(modeKey(targetMode))
            ?: if (targetMode == defaultMode) readRawString(KEY_LEGACY_CONFIG) else null
        if (raw.isNullOrBlank()) {
            return AppConfig(mode = targetMode, apiDomain = branding.defaultApiDomain)
        }
        return runCatching {
            val root = JSONObject(raw)
            AppConfig(
                mode = targetMode,
                apiDomain = branding.normalizeApiDomain(root.optString("apiDomain")),
                accountId = root.optString("accountId", ""),
                orgName = root.optString("orgName", ""),
                username = root.optString("username", ""),
                password = root.optString("password", ""),
                rememberAccount = root.optBoolean("rememberAccount", true),
                token = root.optString("token", ""),
                devices = parseDevices(root.optJSONArray("devices")),
                savedAccounts = parseSavedAccounts(root.optJSONArray("savedAccounts")),
                autoStartRunning = root.optBoolean("autoStartRunning", true),
                pollIntervalMs = root.optLong("pollIntervalMs", AppConfig.DEFAULT_POLL_INTERVAL_MS).coerceIn(3_000L, 60_000L),
                dedupSeconds = root.optLong("dedupSeconds", AppConfig.DEFAULT_DEDUP_SECONDS).coerceIn(0L, 3600L),
                heartbeatIntervalMs = root.optLong("heartbeatIntervalMs", AppConfig.DEFAULT_HEARTBEAT_INTERVAL_MS).coerceIn(3_000L, 300_000L),
                idleCleanIntervalMs = root.optLong("idleCleanIntervalMs", AppConfig.DEFAULT_IDLE_CLEAN_INTERVAL_MS).coerceIn(15_000L, 600_000L),
                autoStartOnBoot = root.optBoolean("autoStartOnBoot", true),
                udpMode = root.optBoolean("udpMode", false),
                connectTimeoutMs = root.optInt("connectTimeoutMs", AppConfig.DEFAULT_CONNECT_TIMEOUT_MS).coerceIn(1_500, 30_000),
                defaultPort = root.optInt("defaultPort", DeviceConfig.DEFAULT_PORT).coerceIn(1, 65535),
                defaultMachineNo = root.optInt("defaultMachineNo", DeviceConfig.DEFAULT_MACHINE_NO).coerceAtLeast(1),
                defaultPassword = root.optInt("defaultPassword", DeviceConfig.DEFAULT_PASSWORD).coerceAtLeast(0),
                defaultLicense = root.optInt("defaultLicense", DeviceConfig.DEFAULT_LICENSE).coerceAtLeast(0),
                maxDeviceNameBytes = root.optInt("maxDeviceNameBytes", AppConfig.DEFAULT_MAX_NAME_BYTES).coerceIn(4, 16),
                autoPushEnabled = root.optBoolean("autoPushEnabled", true),
                autoPushCustomEnabled = root.optBoolean("autoPushCustomEnabled", false),
                autoPushCustomHour = root.optInt("autoPushCustomHour", AutoPushScheduleConfig.DEFAULT_CUSTOM_HOUR).coerceIn(0, 23),
                autoPushCustomMinute = root.optInt("autoPushCustomMinute", AutoPushScheduleConfig.DEFAULT_CUSTOM_MINUTE).coerceIn(0, 59),
                localHostName = AppConfig.sanitizeLocalHost(root.optString("localHostName", AppConfig.DEFAULT_LOCAL_HOST)),
                autoUpdateEnabled = root.optBoolean("autoUpdateEnabled", true),
                updateRepoSlug = AppConfig.sanitizeRepoSlug(root.optString("updateRepoSlug", AppConfig.DEFAULT_UPDATE_REPO)),
                defaultHikUsername = root.optString("defaultHikUsername", DeviceConfig.DEFAULT_HIK_USERNAME).ifBlank { DeviceConfig.DEFAULT_HIK_USERNAME },
                defaultHikPassword = root.optString("defaultHikPassword", ""),
                voiceGreetingEnabled = root.optBoolean("voiceGreetingEnabled", false),
            )
        }.getOrElse {
            AppConfig(mode = targetMode, apiDomain = branding.defaultApiDomain)
        }
    }

    fun save(config: AppConfig) {
        val branding = brandingFor(config.mode)
        val devArr = JSONArray().apply {
            config.devices.forEach { d ->
                put(JSONObject().apply {
                    put("ip", d.ip); put("port", d.port); put("machineNo", d.machineNo); put("name", d.name)
                    put("password", d.password); put("license", d.license); put("useUdp", d.useUdp)
                    put("protocol", d.protocol.code); put("username", d.username)
                    put("accessPassword", d.accessPassword); put("useHttps", d.useHttps); put("serialNumber", d.serialNumber)
                })
            }
        }
        val accArr = JSONArray().apply {
            config.savedAccounts.forEach { a ->
                put(
                    JSONObject().apply {
                        put("username", a.username)
                        put("password", a.password)
                        put("orgName", a.orgName)
                        put("accountId", a.accountId)
                    }
                )
            }
        }
        val root = JSONObject().apply {
            put("mode", config.mode.code)
            put("apiDomain", branding.normalizeApiDomain(config.apiDomain))
            put("accountId", config.accountId)
            put("orgName", config.orgName)
            put("username", config.username)
            put("password", config.password)
            put("rememberAccount", config.rememberAccount)
            put("token", config.token)
            put("devices", devArr)
            put("savedAccounts", accArr)
            put("autoStartRunning", config.autoStartRunning)
            put("pollIntervalMs", config.pollIntervalMs)
            put("dedupSeconds", config.dedupSeconds)
            put("heartbeatIntervalMs", config.heartbeatIntervalMs)
            put("idleCleanIntervalMs", config.idleCleanIntervalMs)
            put("autoStartOnBoot", config.autoStartOnBoot)
            put("udpMode", config.udpMode)
            put("connectTimeoutMs", config.connectTimeoutMs)
            put("defaultPort", config.defaultPort)
            put("defaultMachineNo", config.defaultMachineNo)
            put("defaultPassword", config.defaultPassword)
            put("defaultLicense", config.defaultLicense)
            put("maxDeviceNameBytes", config.maxDeviceNameBytes)
            put("autoPushEnabled", config.autoPushEnabled)
            put("autoPushCustomEnabled", config.autoPushCustomEnabled)
            put("autoPushCustomHour", config.autoPushCustomHour.coerceIn(0, 23))
            put("autoPushCustomMinute", config.autoPushCustomMinute.coerceIn(0, 59))
            put("localHostName", config.safeLocalHostName)
            put("autoUpdateEnabled", config.autoUpdateEnabled)
            put("updateRepoSlug", config.safeUpdateRepoSlug)
            put("defaultHikUsername", config.resolvedHikUsername)
            put("defaultHikPassword", config.resolvedHikPassword)
            put("voiceGreetingEnabled", config.voiceGreetingEnabled)
        }
        val json = root.toString()
        val key = modeKey(config.mode)
        // Dùng commit() ghi đồng bộ xuống bộ nhớ flash và sao lưu dự phòng chống mất tài khoản khi TV Box sập điện đột ngột
        runCatching {
            prefs.edit()
                .putString(KEY_ACTIVE_MODE, config.mode.code)
                .putString(key, json)
                .commit()
        }
        if (prefs !== backupPrefs) {
            runCatching {
                val encoded = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                backupPrefs.edit()
                    .putString(KEY_ACTIVE_MODE, config.mode.code)
                    .putString("${key}_b64", encoded)
                    .commit()
            }
        }
    }

    private fun readRawString(key: String): String? {
        val primary = runCatching { prefs.getString(key, null) }.getOrNull()
        if (!primary.isNullOrBlank()) return primary
        if (key == KEY_ACTIVE_MODE) return runCatching { backupPrefs.getString(key, null) }.getOrNull()
        val b64 = runCatching { backupPrefs.getString("${key}_b64", null) }.getOrNull()
        if (!b64.isNullOrBlank()) {
            return runCatching { String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
        }
        return runCatching { backupPrefs.getString(key, null) }.getOrNull()
    }

    private fun modeKey(mode: AppMode): String = "app_config_mode_${mode.code}"

    private fun parseDevices(arr: JSONArray?): List<DeviceConfig> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ip = o.optString("ip", "").trim()
                if (ip.isEmpty()) continue
                val protocol = DeviceProtocol.fromCode(o.optString("protocol", "FK"))
                add(
                    DeviceConfig(
                        ip = ip,
                        port = o.optInt("port", protocol.defaultPort),
                        machineNo = o.optInt("machineNo", DeviceConfig.DEFAULT_MACHINE_NO),
                        name = o.optString("name", ""),
                        password = o.optInt("password", DeviceConfig.DEFAULT_PASSWORD),
                        license = o.optInt("license", DeviceConfig.DEFAULT_LICENSE),
                        useUdp = o.optBoolean("useUdp", false),
                        protocol = protocol,
                        username = o.optString("username", DeviceConfig.DEFAULT_HIK_USERNAME).ifBlank { DeviceConfig.DEFAULT_HIK_USERNAME },
                        accessPassword = o.optString("accessPassword", ""),
                        useHttps = o.optBoolean("useHttps", false),
                        serialNumber = o.optString("serialNumber", ""),
                    )
                )
            }
        }
    }

    private fun parseSavedAccounts(arr: JSONArray?): List<SavedAccount> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val user = o.optString("username", "").trim()
                if (user.isEmpty()) continue
                add(
                    SavedAccount(
                        username = user,
                        password = o.optString("password", ""),
                        orgName = o.optString("orgName", ""),
                        accountId = o.optString("accountId", ""),
                    )
                )
            }
        }
    }

    companion object {
        private const val PREFS_NAME_ENCRYPTED = "chamcong_tvbox_secure_prefs"
        private const val PREFS_NAME_FALLBACK = "chamcong_tvbox_prefs"
        private const val KEY_ACTIVE_MODE = "active_app_mode_v1"
        private const val KEY_LEGACY_CONFIG = "app_config_v1"
    }
}

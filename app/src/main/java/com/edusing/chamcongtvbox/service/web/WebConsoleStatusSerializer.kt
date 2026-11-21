package com.edusing.chamcongtvbox.service.web

import android.content.Context
import android.os.Build
import com.edusing.chamcongtvbox.BuildConfig
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.UpdateStateSnapshot
import com.edusing.chamcongtvbox.service.CrashRecoveryHandler
import com.edusing.chamcongtvbox.service.EngineSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Bộ đóng gói JSON trạng thái thời gian thực cho Web quản trị LAN (`http://<host>.local:8080`). */
internal object WebConsoleStatusSerializer {

    fun buildStatusJson(
        context: Context,
        cfg: AppConfig,
        snap: EngineSnapshot,
        updateSnap: UpdateStateSnapshot,
        nowMs: Long,
        startedAtMs: Long,
    ): JSONObject {
        val devicesArr = JSONArray().apply {
            snap.devices.forEach { d ->
                put(
                    JSONObject()
                        .put("key", d.config.key)
                        .put("name", d.config.displayName)
                        .put("ip", d.config.ip)
                        .put("port", d.config.port)
                        .put("protocol", d.config.protocol.code)
                        .put("connected", d.isConnected)
                        .put("statusText", d.statusText)
                )
            }
        }
        val eventsArr = JSONArray().apply {
            snap.events.take(50).forEach { e ->
                put(
                    JSONObject()
                        .put("time", e.timeText)
                        .put("device", e.deviceName)
                        .put("cardId", e.cardId)
                        .put("user", e.userName)
                        .put("message", e.message)
                        .put("severity", e.severity.name)
                )
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("lanIp", LocalWebConsoleServer.detectLanIpv4())
            .put("localHostName", cfg.safeLocalHostName)
            .put("mode", cfg.mode.code)
            .put("modeLabel", cfg.mode.shortLabel)
            .put("isLoggedIn", cfg.isLoggedIn)
            .put("orgName", cfg.orgName.ifBlank { cfg.username })
            .put("username", cfg.username)
            .put("apiDomain", cfg.apiDomain)
            .put("isRunning", snap.isRunning)
            .put("isBusy", snap.isBusy || snap.isDiscovering || snap.isAutoPushing)
            .put("statusHint", snap.statusHint)
            .put("internetStatus", snap.internetStatus.name)
            .put("internetDetail", snap.internetDetail)
            .put("hasLanOrWifi", snap.hasLanOrWifi)
            .put("connectedCount", snap.connectedCount)
            .put("totalDeviceCount", snap.totalDeviceCount)
            .put("totalCheckInsToday", snap.totalCheckInsToday)
            .put("pendingCheckInCount", snap.pendingCheckInCount)
            .put("lastPollTimeText", snap.lastPollTimeText)
            .put("lastAutoPushText", snap.lastAutoPushText)
            .put("nextAutoPushText", cfg.autoPushSchedule.nextSlotText(nowMs))
            .put("webConsoleUrl", snap.webConsoleUrl)
            .put("lastCrashSummary", CrashRecoveryHandler.readLastCrashSummary(context))
            .put("uptimeSec", ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L))
            .put("autoPushEnabled", cfg.autoPushEnabled)
            .put("autoPushCustomEnabled", cfg.autoPushCustomEnabled)
            .put("autoPushCustomHour", cfg.autoPushCustomHour)
            .put("autoPushCustomMinute", cfg.autoPushCustomMinute)
            .put("pollIntervalSec", (cfg.pollIntervalMs / 1000L).coerceIn(3L, 60L))
            .put("dedupSeconds", cfg.dedupSeconds)
            .put("udpMode", cfg.udpMode)
            .put("defaultPort", cfg.defaultPort)
            .put("autoStartOnBoot", cfg.autoStartOnBoot)
            .put("autoUpdateEnabled", cfg.autoUpdateEnabled)
            .put("updateRepoSlug", cfg.safeUpdateRepoSlug)
            .put("updateInstallMode", updateSnap.installMode.label)
            .put("updateAvailableVersion", updateSnap.availableRelease?.versionName.orEmpty())
            .put("updateStatus", updateSnap.statusMessage)
            .put("updateError", updateSnap.errorMessage.orEmpty())
            .put("devices", devicesArr)
            .put("events", eventsArr)
    }
}

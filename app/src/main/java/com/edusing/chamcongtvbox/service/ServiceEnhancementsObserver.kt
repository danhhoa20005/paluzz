package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.di.AppContainer
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.repository.InternetStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Bộ quan sát sự kiện nền gọn nhẹ gắn vào [AttendanceForegroundService] (không làm phình [AttendanceEngine]):
 * 1. Tự kiểm tra cập nhật OTA ngay khi mạng Internet vừa phục hồi.
 * 2. Tự đồng bộ bù lượt quét Hikvision còn tồn khi máy chấm công vừa nối lại sau mất điện / đứt mạng.
 * 3. Đọc giọng chào tiếng Việt (Android TTS offline) khi bật tuỳ chọn loa thông báo.
 * 4. Cảnh báo chủ động qua Telegram / Webhook khi máy chấm công offline quá ngưỡng (mặc định 5 phút) và báo khi nối lại.
 */
internal object ServiceEnhancementsObserver {

    fun attach(scope: CoroutineScope, container: AppContainer) {
        val offlineSinceByKey = ConcurrentHashMap<String, Long>()
        val alertedOfflineKeys = ConcurrentHashMap.newKeySet<String>()

        scope.launch(Dispatchers.IO) {
            var prevOnline = false
            val prevConnectedKeys = mutableSetOf<String>()
            var lastSpokenEventId = -1L

            container.attendanceEngine.state.collect { snap ->
                val nowOnline = snap.internetStatus == InternetStatus.ONLINE
                if (nowOnline && !prevOnline) container.appUpdateCoordinator.triggerBackgroundCheckIfDue()
                prevOnline = nowOnline

                val now = System.currentTimeMillis()
                val curConnected = snap.devices.filter { it.isConnected }.map { it.config.key }.toSet()
                for (dev in snap.devices) {
                    val key = dev.config.key
                    if (dev.isConnected) {
                        offlineSinceByKey.remove(key)
                        if (alertedOfflineKeys.remove(key) && nowOnline) {
                            val org = container.configRepository.getConfig().orgName
                            scope.launch(Dispatchers.IO) {
                                container.alertNotifier.notifyAlert(org, "Máy chấm công đã kết nối lại ✓", "Máy ${dev.config.displayName} (${dev.config.ip}) đã hoạt động bình thường.", dev.config.displayName, "INFO")
                            }
                        }
                        if (dev.config.isHikvision && key !in prevConnectedKeys) {
                            scope.launch(Dispatchers.IO) { runCatching { container.autoBackfillHikvisionIfOutage(dev.config) } }
                        }
                    } else {
                        offlineSinceByKey.putIfAbsent(key, now)
                    }
                }
                val validKeys = snap.devices.map { it.config.key }.toSet()
                offlineSinceByKey.keys.retainAll(validKeys)
                alertedOfflineKeys.retainAll(validKeys)
                prevConnectedKeys.clear()
                prevConnectedKeys.addAll(curConnected)

                val latest = snap.events.firstOrNull()
                if (latest != null && latest.id != lastSpokenEventId) {
                    val isInitialSeed = lastSpokenEventId == -1L
                    lastSpokenEventId = latest.id
                    if (!isInitialSeed && latest.severity == EventSeverity.SUCCESS &&
                        latest.cardId.isNotBlank() && latest.cardId != "-" &&
                        container.configRepository.getConfig().voiceGreetingEnabled
                    ) {
                        container.voiceAnnouncer.speakCheckIn(latest.userName, latest.cardId)
                    }
                }
            }
        }

        // Vòng kiểm tra mỗi 30s phát cảnh báo khi máy chấm công mất kết nối kéo dài quá ngưỡng phút đã cấu hình
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000L)
                runCatching {
                    val alertCfg = container.alertNotifier.getConfig()
                    val snap = container.attendanceEngine.state.value
                    val appCfg = container.configRepository.getConfig()
                    if (!alertCfg.enabled || !alertCfg.hasAnyChannel || !appCfg.isLoggedIn || !snap.isInternetOnline) return@runCatching
                    val thresholdMs = alertCfg.offlineThresholdMin * 60_000L
                    val now = System.currentTimeMillis()
                    for (dev in snap.devices) {
                        if (dev.isConnected) continue
                        val since = offlineSinceByKey[dev.config.key] ?: continue
                        if ((now - since) >= thresholdMs && alertedOfflineKeys.add(dev.config.key)) {
                            val mins = ((now - since) / 60_000L).coerceAtLeast(1L)
                            container.alertNotifier.notifyAlert(
                                appCfg.orgName,
                                "Cảnh báo mất kết nối máy chấm công!",
                                "Máy ${dev.config.displayName} (${dev.config.ip}:${dev.config.port}) đã offline $mins phút (${dev.statusText}).",
                                dev.config.displayName,
                                "ERROR",
                            )
                        }
                    }
                }
            }
        }
    }
}

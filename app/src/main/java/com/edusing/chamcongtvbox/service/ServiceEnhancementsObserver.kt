package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.di.AppContainer
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.repository.InternetStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bộ quan sát sự kiện nền gọn nhẹ gắn vào [AttendanceForegroundService] (không làm phình [AttendanceEngine]):
 * 1. Tự kiểm tra cập nhật OTA ngay khi mạng Internet vừa phục hồi.
 * 2. Tự đồng bộ bù lượt quét Hikvision còn tồn khi máy chấm công vừa nối lại sau mất điện / đứt mạng.
 * 3. Đọc giọng chào tiếng Việt (Android TTS offline) khi bật tuỳ chọn loa thông báo.
 */
internal object ServiceEnhancementsObserver {

    fun attach(scope: CoroutineScope, container: AppContainer) {
        scope.launch(Dispatchers.IO) {
            var prevOnline = false
            val prevConnectedKeys = mutableSetOf<String>()
            var lastSpokenEventId = -1L

            container.attendanceEngine.state.collect { snap ->
                val nowOnline = snap.internetStatus == InternetStatus.ONLINE
                if (nowOnline && !prevOnline) {
                    container.appUpdateCoordinator.triggerBackgroundCheckIfDue()
                }
                prevOnline = nowOnline

                val curConnected = snap.devices.filter { it.isConnected }.map { it.config.key }.toSet()
                for (dev in snap.devices) {
                    if (dev.isConnected && dev.config.isHikvision && dev.config.key !in prevConnectedKeys) {
                        scope.launch(Dispatchers.IO) { runCatching { container.autoBackfillHikvisionIfOutage(dev.config) } }
                    }
                }
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
    }
}

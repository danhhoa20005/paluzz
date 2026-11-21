package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.InternetCheckResult
import com.edusing.chamcongtvbox.domain.repository.InternetProbe
import com.edusing.chamcongtvbox.domain.repository.InternetStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bộ giám sát Internet & Wi-Fi/LAN chủ động 24/7 xử lý triệt để mọi tình huống mất Wi-Fi / đứt mạng:
 * - Phân biệt và theo dõi đồng thời Internet WAN lẫn hạ tầng Wi-Fi/LAN (kể cả khi điện thoại có 4G).
 * - Khi rớt Wi-Fi/LAN: đóng ngay socket chết (`onLanDropped`), bật cờ `lanNeedsRecovery` và kích nối lại Wi-Fi.
 * - Ngay khi Wi-Fi có lại IPv4 & Internet ổn định: tự động chạy chu trình quét LAN → nối máy → chờ quét 24/7.
 */
internal class InternetWatchdog(
    private val scope: CoroutineScope,
    private val configRepository: ConfigRepository,
    private val internetProbe: InternetProbe,
    private val stateStore: EngineStateStore,
    private val onLanDropped: suspend () -> Unit,
    private val onInternetReady: suspend (fromOutage: Boolean) -> Unit,
    private val onlineCheckIntervalMs: Long = DEFAULT_ONLINE_INTERVAL_MS,
    private val offlineCheckIntervalMs: Long = DEFAULT_OFFLINE_INTERVAL_MS,
) {
    private var monitorJob: Job? = null
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)
    private val immediateOfflineFlag = AtomicBoolean(false)
    private val lanDroppedPending = AtomicBoolean(false)
    private val lanNeedsRecovery = AtomicBoolean(false)
    private val initialAutoReadyDone = AtomicBoolean(false)

    private var consecutiveFailures = 0
    private var lastHasLanOrWifi = true

    fun ensureStarted() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    runOneCheckCycle()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    stateStore.appendSystemEvent(
                        "Internet",
                        "-",
                        "Lỗi kiểm tra mạng: ${t.message ?: t.javaClass.simpleName}",
                        EventSeverity.WARNING,
                    )
                }
                val snap = stateStore.current
                val waitMs = if (snap.internetStatus == InternetStatus.OFFLINE || !snap.hasLanOrWifi || lanNeedsRecovery.get()) {
                    offlineCheckIntervalMs
                } else {
                    onlineCheckIntervalMs
                }
                withTimeoutOrNull(waitMs) { wakeChannel.receive() }
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun resetInitialAutoReady() {
        initialAutoReadyDone.set(false)
        wakeChannel.trySend(Unit)
    }

    /** Đánh thức vòng kiểm tra mạng ngay lập tức (ví dụ khi người dùng quay lại ứng dụng sau khi bật Wi-Fi). */
    fun wakeNow() {
        wakeChannel.trySend(Unit)
    }

    /** Mất kết nối Internet mức hệ thống (`onLost`). */
    fun notifySystemNetworkLost() {
        immediateOfflineFlag.set(true)
        wakeChannel.trySend(Unit)
    }

    /** Có lại kết nối Internet mức hệ thống (`onAvailable` / `VALIDATED`). */
    fun notifySystemNetworkAvailable() {
        immediateOfflineFlag.set(false)
        wakeChannel.trySend(Unit)
    }

    /** Mất Wi-Fi hoặc dây LAN (`TRANSPORT_WIFI`/`ETHERNET onLost`) — kể cả khi máy còn 4G. */
    fun notifyLanTransportLost() {
        lanDroppedPending.set(true)
        lanNeedsRecovery.set(true)
        wakeChannel.trySend(Unit)
    }

    /** Wi-Fi/LAN vừa nối lại (`onAvailable`) hoặc vừa nhận xong IPv4 DHCP (`onLinkPropertiesChanged`). */
    fun notifyLanTransportRestored() {
        val snap = stateStore.current
        if (snap.totalDeviceCount == 0 || snap.connectedCount < snap.totalDeviceCount || !snap.isRunning) {
            lanNeedsRecovery.set(true)
        }
        immediateOfflineFlag.set(false)
        wakeChannel.trySend(Unit)
    }

    private suspend fun runOneCheckCycle() {
        if (lanDroppedPending.getAndSet(false)) {
            lastHasLanOrWifi = false
            onLanDropped()
        }

        val cfg = configRepository.getConfig()
        val domain = configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)
        val result = internetProbe.checkInternet(domain)

        // Dự phòng cho TV Box AOSP không phát sự kiện onLost: tự phát hiện khi mất IP Wi-Fi/LAN
        if (lastHasLanOrWifi && !result.hasLanOrWifi) {
            lanNeedsRecovery.set(true)
            onLanDropped()
        }
        lastHasLanOrWifi = result.hasLanOrWifi

        if (result.online) {
            handleOnline(result, cfg.isLoggedIn)
        } else {
            handleOffline(result)
        }
    }

    private suspend fun handleOnline(result: InternetCheckResult, isLoggedIn: Boolean) {
        val wasOffline = stateStore.current.internetStatus == InternetStatus.OFFLINE
        val needLanRecover = result.hasLanOrWifi && lanNeedsRecovery.getAndSet(false)
        consecutiveFailures = 0
        immediateOfflineFlag.set(false)

        if (wasOffline || needLanRecover) {
            stateStore.update {
                it.copy(
                    internetStatus = InternetStatus.RECOVERING,
                    internetDetail = result.detail,
                    hasLanOrWifi = result.hasLanOrWifi,
                    wifiHardwareDisabled = result.wifiHardwareDisabled,
                    statusHint = "Đã có lại Wi-Fi & Internet — đang tự quét & kết nối máy chấm công...",
                )
            }
            val reason = if (needLanRecover && !wasOffline) {
                "Wi-Fi/LAN đã kết nối lại — tự động dò & nối máy quét mặt!"
            } else {
                "Đã có lại mạng Internet (${result.detail}) — tự động quét máy chấm công & đưa vào trạng thái sẵn sàng!"
            }
            stateStore.appendSystemEvent("Mạng", "-", reason, EventSeverity.SUCCESS)
            if (isLoggedIn) {
                initialAutoReadyDone.set(true)
                onInternetReady(true)
            }
            stateStore.update {
                it.copy(
                    internetStatus = InternetStatus.ONLINE,
                    internetDetail = result.detail,
                    hasLanOrWifi = result.hasLanOrWifi,
                    wifiHardwareDisabled = result.wifiHardwareDisabled,
                )
            }
            return
        }

        stateStore.update {
            it.copy(
                internetStatus = InternetStatus.ONLINE,
                internetDetail = result.detail,
                hasLanOrWifi = result.hasLanOrWifi,
                wifiHardwareDisabled = result.wifiHardwareDisabled,
            )
        }
        if (isLoggedIn && result.hasLanOrWifi && initialAutoReadyDone.compareAndSet(false, true)) {
            onInternetReady(false)
        }
    }

    private fun handleOffline(result: InternetCheckResult) {
        consecutiveFailures++
        val forceNow = immediateOfflineFlag.getAndSet(false) || !result.hasLanOrWifi || result.wifiHardwareDisabled
        val alreadyOffline = stateStore.current.internetStatus == InternetStatus.OFFLINE

        if (forceNow || consecutiveFailures >= OFFLINE_CONFIRM_THRESHOLD || alreadyOffline) {
            if (!alreadyOffline) {
                stateStore.appendSystemEvent(
                    "Mạng",
                    "-",
                    "${result.detail.ifBlank { "Mất kết nối Internet" }} — đang kiểm tra mỗi ${offlineCheckIntervalMs / 1_000L}s & tự kích nối lại...",
                    EventSeverity.ERROR,
                )
            }
            stateStore.update {
                it.copy(
                    internetStatus = InternetStatus.OFFLINE,
                    internetDetail = result.detail.ifBlank { "Mất mạng Internet" },
                    hasLanOrWifi = result.hasLanOrWifi,
                    wifiHardwareDisabled = result.wifiHardwareDisabled,
                )
            }
            runCatching { internetProbe.requestReconnectNetwork() }
        }
    }

    companion object {
        const val DEFAULT_ONLINE_INTERVAL_MS = 8_000L
        const val DEFAULT_OFFLINE_INTERVAL_MS = 3_500L
        private const val OFFLINE_CONFIRM_THRESHOLD = 2
    }
}

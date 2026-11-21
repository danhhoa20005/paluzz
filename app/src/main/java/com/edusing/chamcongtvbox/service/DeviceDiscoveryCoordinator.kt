package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.InternetProbe
import com.edusing.chamcongtvbox.domain.usecase.DiscoverDevicesUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bộ điều phối tự động dò máy chấm công / quét mặt trong mạng LAN và phục hồi không chạm sau khi mất Wi-Fi/Internet:
 * - Chờ IPv4 DHCP trên Wi-Fi/LAN ổn định trước khi nối máy hoặc quét LAN (tránh lỗi `onAvailable` sớm trước DHCP).
 * - Chu trình chuẩn: Có Wi-Fi & Internet → Kiểm tra/nối máy đã lưu → Nếu thiếu/đổi IP thì tự quét LAN →
 *   Kết nối máy quét mặt → Tự đưa vào trạng thái sẵn sàng chờ nhân viên/học sinh quét 24/7.
 */
internal class DeviceDiscoveryCoordinator(
    private val configRepository: ConfigRepository,
    private val discoverDevicesUseCase: DiscoverDevicesUseCase,
    private val internetProbe: InternetProbe,
    private val connManager: DeviceConnectionManager,
    private val stateStore: EngineStateStore,
    private val onTriggerHeartbeat: () -> Unit,
    private val onPausePollingForRecovery: () -> Unit,
    private val onConnectAll: suspend (Boolean) -> Unit,
    private val onStartAttendance: suspend () -> Boolean,
    private val dhcpSettleDelayMs: Long = DEFAULT_DHCP_SETTLE_MS,
) {
    private val recoveryMutex = Mutex()

    suspend fun autoDiscoverConnectAndReady(fromInternetRecovery: Boolean = false) {
        recoveryMutex.withLock {
            val cfg = configRepository.getConfig()
            if (!cfg.isLoggedIn) return@withLock

            if (fromInternetRecovery) {
                waitForLanIpv4Ready()
            }

            if (fromInternetRecovery && stateStore.current.connectedCount > 0) {
                connManager.clearLogsOrReconnectAll(cfg.devices)
            }
            // Kiểm tra nhanh 1 lần với IP đã lưu để không bị chậm nếu router vừa đổi IP máy chấm công
            if (cfg.devices.isNotEmpty() && stateStore.current.connectedCount < cfg.devices.size) {
                connManager.connectAll(maxAttempts = 1) { onTriggerHeartbeat() }
            }

            val latestDevices = configRepository.getConfig().devices
            val needLanScan = latestDevices.isEmpty() ||
                stateStore.current.connectedCount == 0 ||
                (fromInternetRecovery && stateStore.current.connectedCount < latestDevices.size)

            if (needLanScan) {
                if (stateStore.current.isRunning) onPausePollingForRecovery()
                discoverDevicesInLan(autoResumeIfConfigured = false)
            } else {
                onTriggerHeartbeat()
            }

            val latestCfg = configRepository.getConfig()
            if (latestCfg.autoStartRunning && stateStore.current.connectedCount > 0 && !stateStore.current.isRunning) {
                onStartAttendance()
            }
        }
    }

    /** Đợi Wi-Fi/LAN cấp xong IPv4 DHCP và định tuyến ổn định (tối đa 7s) trước khi nối hoặc quét LAN. */
    private suspend fun waitForLanIpv4Ready() {
        var waited = 0L
        while (waited < MAX_DHCP_WAIT_MS && !internetProbe.hasLanOrWifiIpv4()) {
            stateStore.update { it.copy(statusHint = "Đang chờ Wi-Fi/LAN cấp địa chỉ IP...") }
            delay(DHCP_POLL_STEP_MS)
            waited += DHCP_POLL_STEP_MS
        }
        if (dhcpSettleDelayMs > 0L) delay(dhcpSettleDelayMs)
    }

    /** Dò máy chấm công trong mạng LAN bằng Ping an toàn + bắt tay FK thật rồi tự thêm & kết nối. */
    suspend fun discoverDevicesInLan(autoResumeIfConfigured: Boolean = false) {
        if (stateStore.current.isRunning || stateStore.current.isDiscovering) return
        val cfg = configRepository.getConfig()
        stateStore.update { it.copy(isDiscovering = true, isBusy = true, statusHint = "Đang tự dò máy chấm công trong LAN...") }
        stateStore.appendSystemEvent("Mạng LAN", "-", "Bắt đầu tự động quét máy chấm công trong mạng nội bộ...", EventSeverity.INFO)
        try {
            connManager.disconnectAll()
            delay(600L)
            val report = discoverDevicesUseCase(
                knownDevices = cfg.devices,
                port = cfg.defaultPort,
                machineNo = cfg.defaultMachineNo,
                password = cfg.defaultPassword,
                license = cfg.defaultLicense,
                useUdp = cfg.udpMode,
                onProgress = { prog -> stateStore.update { it.copy(statusHint = prog.stageText) } },
            )
            val existing = configRepository.getConfig().devices
            val relocatedBySerial = report.relocatedDevices.associateBy { it.serialNumber }
            val updatedExisting = existing.map { dev ->
                val moved = dev.serialNumber.takeIf { it.isNotBlank() }?.let { relocatedBySerial[it] }
                if (moved != null && moved.ip != dev.ip) {
                    stateStore.appendSystemEvent(dev.displayName, "-", "Tự tìm lại Hikvision sau khi đổi IP DHCP: ${dev.ip} → ${moved.ip}", EventSeverity.SUCCESS)
                    dev.copy(ip = moved.ip)
                } else dev
            }
            val existingIps = updatedExisting.map { it.ip }.toSet()
            val newlyAdded = report.foundIps.filterNot { it in existingIps }.map { ip ->
                DeviceConfig(ip, cfg.defaultPort, cfg.defaultMachineNo, "", cfg.defaultPassword, cfg.defaultLicense, cfg.udpMode)
            }
            if (newlyAdded.isNotEmpty() || relocatedBySerial.isNotEmpty()) {
                // Đưa máy mới tìm thấy lên đầu danh sách để kết nối tức thì, đồng thời lưu IP mới của máy Hikvision vừa định vị lại
                configRepository.updateDevices(newlyAdded + updatedExisting)
            }
            val scanned = report.scannedPrefixes.joinToString(", ") { "$it.x" }.ifBlank { "chưa có mạng LAN/Wi-Fi" }
            val (msg, sev) = if (report.foundIps.isNotEmpty()) {
                "Dò xong ($scanned): tìm thấy ${report.foundIps.size} máy chấm công (${newlyAdded.size} máy mới)." to EventSeverity.SUCCESS
            } else {
                "Chưa thấy máy ở [$scanned] (${report.pingAliveCount} máy đáp Ping). Hệ thống sẽ tự dò lại." to EventSeverity.WARNING
            }
            stateStore.appendSystemEvent("Mạng LAN", "-", msg, sev)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            stateStore.appendSystemEvent("Mạng LAN", "-", "Lỗi khi dò máy: ${t.message ?: t.javaClass.simpleName}", EventSeverity.ERROR)
        } finally {
            stateStore.update { it.copy(isDiscovering = false, isBusy = false, statusHint = "") }
        }
        val currentDevices = configRepository.getConfig().devices
        if (currentDevices.isNotEmpty()) {
            connManager.connectAll(maxAttempts = 1) { onTriggerHeartbeat() }
            if (autoResumeIfConfigured && configRepository.getConfig().autoStartRunning &&
                stateStore.current.connectedCount > 0 && !stateStore.current.isRunning
            ) {
                onStartAttendance()
            }
        }
    }

    companion object {
        private const val MAX_DHCP_WAIT_MS = 4_000L
        private const val DHCP_POLL_STEP_MS = 400L
        const val DEFAULT_DHCP_SETTLE_MS = 900L
    }
}

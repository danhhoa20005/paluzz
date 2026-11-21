package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConnectionState
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.ClearDeviceLogsUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectDeviceUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectedDeviceHandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Quản lý vòng đời kết nối (`handle`) của toàn bộ máy chấm công đang mở.
 * Tách khỏi [AttendanceEngine] để đảm bảo nguyên tắc đơn trách nhiệm (SRP).
 */
internal class DeviceConnectionManager(
    private val configRepository: ConfigRepository,
    private val deviceGateway: FkDeviceGateway,
    private val connectDeviceUseCase: ConnectDeviceUseCase,
    private val clearDeviceLogsUseCase: ClearDeviceLogsUseCase,
    private val stateStore: EngineStateStore,
) {
    private val connectMutex = Mutex()
    private val handlesByKey = ConcurrentHashMap<String, Int>()

    fun getHandle(deviceKey: String): Int? = handlesByKey[deviceKey]
    fun getAllHandles(): List<Int> = handlesByKey.values.toList()
    fun getEntries(): List<Pair<String, Int>> = handlesByKey.entries.map { it.key to it.value }

    fun getConnectedHandles(): List<ConnectedDeviceHandle> {
        val cfgByKey = configRepository.getConfig().devices.associateBy { it.key }
        return handlesByKey.entries.mapNotNull { (key, handle) ->
            val cfg = cfgByKey[key] ?: return@mapNotNull null
            if (deviceGateway.getConnectionInfo(handle) != null) ConnectedDeviceHandle(cfg, handle) else null
        }
    }

    suspend fun connectAll(maxAttempts: Int = 5, onEachBatchDone: () -> Unit) = connectMutex.withLock {
        stateStore.update { it.copy(isBusy = true, statusHint = "Đang kết nối máy chấm công...") }
        try {
            for (dev in configRepository.getConfig().devices) {
                if (handlesByKey.containsKey(dev.key)) continue
                connectSingleLocked(dev, maxAttempts = maxAttempts)
            }
            onEachBatchDone()
        } finally {
            stateStore.update { it.copy(isBusy = false, statusHint = "") }
        }
    }

    suspend fun connectIfOffline(device: DeviceConfig, maxAttempts: Int, silentFail: Boolean = false): Boolean =
        connectMutex.withLock {
            if (handlesByKey.containsKey(device.key)) return@withLock true
            connectSingleLocked(device, maxAttempts, silentFail)
        }

    /** Dọn sạch bộ đệm chấm công của mọi máy đang mở; nếu socket đã chết (`-3`) thì tự nối lại ngay. */
    suspend fun clearLogsOrReconnectAll(configured: List<DeviceConfig>) {
        val byKey = configured.associateBy { it.key }
        for ((key, handle) in getEntries()) {
            val dev = byKey[key]
            val name = dev?.displayName ?: key
            val res = clearDeviceLogsUseCase(handle)
            if (res.isCommError && dev != null) {
                stateStore.appendSystemEvent(name, "-", "Phiên cũ bị ngắt (-3), đang nối lại trước khi bắt đầu...", EventSeverity.WARNING)
                reconnect(dev, maxAttempts = 2, waitMs = 1_000L)
            } else {
                val wiped = res.data ?: 0
                val extra = if (wiped > 0) " (đã bỏ $wiped lượt cũ)" else ""
                stateStore.appendSystemEvent(name, "-", "Đã dọn sạch bộ nhớ đệm trước khi bắt đầu$extra", EventSeverity.INFO)
            }
        }
    }

    /** Nối nhẹ mọi máy đang offline (dùng khi quay lại app hoặc mạng phục hồi), trả về true nếu có ít nhất 1 máy nối được. */
    suspend fun reconnectAllOffline(configured: List<DeviceConfig>, maxAttempts: Int, silentFail: Boolean): Boolean {
        var recovered = false
        for (dev in configured) {
            if (handlesByKey.containsKey(dev.key)) continue
            if (connectIfOffline(dev, maxAttempts = maxAttempts, silentFail = silentFail)) recovered = true
        }
        return recovered
    }

    suspend fun reconnect(
        device: DeviceConfig,
        maxAttempts: Int,
        waitMs: Long = 1_000L,
        silentFail: Boolean = false,
    ): Boolean = connectMutex.withLock {
        disconnectSingleLocked(device.key)
        delay(waitMs)
        connectSingleLocked(device, maxAttempts, silentFail)
    }

    suspend fun disconnectSingle(deviceKey: String) = connectMutex.withLock {
        disconnectSingleLocked(deviceKey)
    }

    suspend fun disconnectAll() = connectMutex.withLock {
        handlesByKey.clear()
        deviceGateway.disconnectAll()
        stateStore.update { snap ->
            snap.copy(devices = snap.devices.map { it.copy(state = DeviceConnectionState.DISCONNECTED, statusText = "Đã ngắt kết nối") })
        }
    }

    /** Đóng ngay các socket TCP đã chết khi mất Wi-Fi/LAN để tránh kẹt trạng thái Online giả. */
    suspend fun markAllLostOnNetworkDrop(reason: String = "Mất Wi-Fi — chờ tự nối lại...") = connectMutex.withLock {
        if (handlesByKey.isEmpty()) return@withLock
        handlesByKey.clear()
        runCatching { deviceGateway.disconnectAll() }
        stateStore.update { snap ->
            snap.copy(
                devices = snap.devices.map {
                    it.copy(
                        state = DeviceConnectionState.ERROR,
                        statusText = reason,
                        lastError = reason,
                    )
                },
            )
        }
    }

    /** Nối thử 1 lần rồi ngắt ngay (dùng ở màn Thêm máy để xác minh IP trước khi lưu). */
    suspend fun probeConnect(device: DeviceConfig): Pair<Boolean, String> = runCatching {
        val timeout = configRepository.getConfig().connectTimeoutMs
        val res = deviceGateway.connect(
            machineNo = device.machineNo,
            ip = device.ip,
            port = device.port,
            timeoutMs = timeout,
            netPassword = device.password,
            license = device.license,
            useUdp = device.useUdp,
        )
        if (res.isSuccess && res.data != null) {
            val label = "${res.data.transportName} / ${res.data.profile.displayName}"
            deviceGateway.disconnect(res.data.handle)
            delay(700L)
            true to "Kết nối thử thành công ($label)"
        } else {
            false to res.message
        }
    }.getOrElse { false to (it.message ?: "Lỗi kết nối thử") }

    private suspend fun connectSingleLocked(
        device: DeviceConfig,
        maxAttempts: Int,
        silentFail: Boolean = false,
    ): Boolean {
        val prevState = stateStore.current.devices.firstOrNull { it.config.key == device.key }?.state
        if (!silentFail || prevState != DeviceConnectionState.ERROR) {
            stateStore.updateDevice(device.key) { it.copy(state = DeviceConnectionState.CONNECTING, statusText = "Đang kết nối...") }
        }
        val timeout = configRepository.getConfig().connectTimeoutMs
        val res = runCatching {
            connectDeviceUseCase(device, timeoutMs = timeout, maxAttempts = maxAttempts, clearStaleLogsOnConnect = true)
        }.getOrElse { err -> FkResult.fail(FkErrorCode.ERR_COMM, err.message) }
        return if (res.isSuccess && res.data != null) {
            val info = res.data
            handlesByKey[device.key] = info.handle
            stateStore.updateDevice(device.key) {
                it.copy(
                    state = DeviceConnectionState.CONNECTED,
                    statusText = "Đã kết nối (${info.profile.displayName})",
                    firmwareProfile = info.profile.displayName,
                    lastError = null,
                )
            }
            stateStore.appendSystemEvent(device.displayName, "-", "Kết nối OK (${info.transportName} / ${info.profile.displayName})", EventSeverity.SUCCESS)
            true
        } else {
            handlesByKey.remove(device.key)
            stateStore.updateDevice(device.key) {
                it.copy(state = DeviceConnectionState.ERROR, statusText = "Mất kết nối (tự nối lại...)", lastError = res.message)
            }
            if (!silentFail || prevState != DeviceConnectionState.ERROR) {
                stateStore.appendSystemEvent(device.displayName, "-", "Kết nối thất bại: ${res.message}", EventSeverity.ERROR)
            }
            false
        }
    }

    private suspend fun disconnectSingleLocked(deviceKey: String) {
        handlesByKey.remove(deviceKey)?.let { deviceGateway.disconnect(it) }
        stateStore.updateDevice(deviceKey) { it.copy(state = DeviceConnectionState.DISCONNECTED, statusText = "Chưa kết nối") }
    }
}

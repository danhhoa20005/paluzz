package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.data.remote.AttendanceApiClient
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem
import com.edusing.chamcongtvbox.domain.model.IpSuggestion
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.domain.model.LoginResult
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import com.edusing.chamcongtvbox.domain.model.ServerCheckInResult
import com.edusing.chamcongtvbox.domain.model.SubnetInfo
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.AuthRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.InternetCheckResult
import com.edusing.chamcongtvbox.domain.repository.InternetProbe
import com.edusing.chamcongtvbox.domain.repository.InternetStatus
import com.edusing.chamcongtvbox.domain.repository.LanDiscoveryRepository
import com.edusing.chamcongtvbox.domain.usecase.ClearDeviceLogsUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectDeviceUseCase
import com.edusing.chamcongtvbox.domain.usecase.DiscoverDevicesUseCase
import com.edusing.chamcongtvbox.domain.usecase.PollAttendanceUseCase
import com.edusing.chamcongtvbox.domain.usecase.SendHeartbeatUseCase
import com.edusing.chamcongtvbox.domain.usecase.ValidateCredentialsUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kiểm thử hồi quy cho các sự cố vận hành thực tế:
 * - Chống bấm đúp / chạy chồng `startAttendance` và không khởi động lại khi đã chạy.
 * - Tự phục hồi socket cũ hỏng (`-3`) khi bấm Bắt đầu và tự kết nối lại khi máy offline.
 * - Không gọi `enableDevice(true)` khi socket đã báo lỗi giao tiếp (`-3`).
 * - Phản hồi 404/400 "not found" từ `/check-in-out/.../no-auth/` không được đá đăng xuất.
 */
class AttendanceEngineRecoveryTest {

    private val branding = BrandingConfig.forMode(AppMode.PALUZZ)
    private val testDevice = DeviceConfig(ip = "192.168.1.224", port = 5005, name = "test")

    @Test
    fun switchAppMode_stopsPollingDisconnectsOldDevicesAndSwitchesModeAndDomain() = runBlocking {
        val gateway = FakeGateway()
        val cfgRepo = FakeConfigRepo(
            AppConfig(
                mode = AppMode.PALUZZ,
                apiDomain = "https://api.paluzz.com",
                accountId = "11366",
                username = "admin",
                password = "pw",
                devices = listOf(testDevice),
                pollIntervalMs = 60_000L,
            )
        )
        val engine = buildEngine(cfgRepo, gateway)
        try {
            engine.connectAllDevices(autoResumeIfConfigured = false)
            assertTrue(engine.startAttendance())
            assertTrue(engine.state.value.isRunning)
            assertEquals(1, engine.state.value.connectedCount)

            val next = engine.switchAppMode(AppMode.DIEM_DANH)
            assertEquals(AppMode.DIEM_DANH, next.mode)
            assertEquals("https://api.diemdanhhs.com", next.apiDomain)
            assertEquals("DIEMDANH", cfgRepo.getBranding().brandHeader)
            assertFalse("Phải dừng chấm công khi chuyển chế độ", engine.state.value.isRunning)
            assertEquals("Phải ngắt phiên cũ khi chuyển chế độ", 0, engine.state.value.connectedCount)
            assertEquals("Phải giữ lại danh sách máy chấm công LAN", 1, next.devices.size)
        } finally {
            engine.disconnectAllAndStop()
        }
    }

    @Test
    fun startAttendance_concurrentCallsAndReopen_startsOnlyOnce() = runBlocking {
        val gateway = FakeGateway()
        val cfgRepo = FakeConfigRepo(
            AppConfig(
                apiDomain = "https://api.paluzz.com",
                accountId = "11366",
                username = "admin",
                password = "pw",
                devices = listOf(testDevice),
                pollIntervalMs = 60_000L,
            )
        )
        val engine = buildEngine(cfgRepo, gateway)
        try {
            engine.connectAllDevices(autoResumeIfConfigured = false)
            assertEquals(1, engine.state.value.connectedCount)

            val results = listOf(
                async { engine.startAttendance() },
                async { engine.startAttendance() },
            ).awaitAll()
            assertEquals(listOf(true, true), results)
            assertTrue(engine.state.value.isRunning)

            // Gọi lại connectAllDevices(autoResume=true) giống khi mở lại Activity -> KHÔNG được chạy lại startAttendance
            engine.connectAllDevices(autoResumeIfConfigured = true)
            val startLogs = engine.state.value.events.count { it.message.startsWith("BẮT ĐẦU CHẤM CÔNG") }
            assertEquals(1, startLogs)
        } finally {
            engine.disconnectAllAndStop()
        }
    }

    @Test
    fun startAttendance_staleHandleGetsCommError_reconnectsAndStartsCleanly() = runBlocking {
        val gateway = FakeGateway()
        val cfgRepo = FakeConfigRepo(
            AppConfig(
                apiDomain = "https://api.paluzz.com",
                accountId = "11366",
                username = "admin",
                password = "pw",
                devices = listOf(testDevice),
                pollIntervalMs = 60_000L,
            )
        )
        val engine = buildEngine(cfgRepo, gateway)
        try {
            engine.connectAllDevices(autoResumeIfConfigured = false)
            assertEquals(1, engine.state.value.connectedCount)

            // Giả lập socket cũ bị chết trước khi bấm Bắt đầu (handle 1 trả ERR_COMM)
            gateway.deadHandles.add(1)

            val started = engine.startAttendance()
            assertTrue(started)
            assertTrue(engine.state.value.isRunning)
            assertEquals(1, engine.state.value.connectedCount)
            assertTrue("Phải mở handle mới sau khi nối lại", gateway.connectCalls.get() >= 2)
        } finally {
            engine.disconnectAllAndStop()
        }
    }

    @Test
    fun triggerGentleReconnectIfOffline_recoversDeviceAndAutoResumes() = runBlocking {
        val gateway = FakeGateway().apply { failConnectUntilAttempt = 2 }
        val cfgRepo = FakeConfigRepo(
            AppConfig(
                apiDomain = "https://api.paluzz.com",
                accountId = "11366",
                username = "admin",
                password = "pw",
                devices = listOf(testDevice),
                autoStartRunning = true,
                pollIntervalMs = 60_000L,
            )
        )
        val engine = buildEngine(cfgRepo, gateway)
        try {
            assertEquals(0, engine.state.value.connectedCount)
            engine.triggerGentleReconnectIfOffline(delayMs = 0L)
            repeat(40) {
                if (engine.state.value.connectedCount == 1 && engine.state.value.isRunning) return@repeat
                delay(100L)
            }
            assertEquals(1, engine.state.value.connectedCount)
            assertTrue(engine.state.value.isRunning)
        } finally {
            engine.disconnectAllAndStop()
        }
    }

    @Test
    fun clearDeviceLogs_doesNotCallEnableTrueAfterCommError() = runBlocking {
        val gateway = FakeGateway().apply { failLoadWithCommError = true }
        val useCase = ClearDeviceLogsUseCase(gateway)
        val res = useCase(handle = 1)
        assertTrue(res.isCommError)
        assertEquals(listOf(false), gateway.enableCalls)
    }

    @Test
    fun checkInOut_404NotFoundDoesNotTriggerAuthError_while401Does() = runBlocking {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            runCatching {
                listOf(
                    "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{\"message\":\"Employee not found\"}",
                    "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{\"message\":\"Unauthorized\"}",
                ).forEach { httpResp ->
                    server.accept().use { sock ->
                        val br = BufferedReader(InputStreamReader(sock.getInputStream()))
                        while (true) {
                            val line = br.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        sock.getOutputStream().write(httpResp.toByteArray(Charsets.UTF_8))
                        sock.getOutputStream().flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val client = AttendanceApiClient(brandingConfig = branding, appVersion = "1.0.0")
            val domain = "http://127.0.0.1:${server.localPort}"
            val r404 = client.checkInOut(domain, "11366", "999999", testDevice)
            assertFalse(r404.success)
            assertFalse("Lỗi không thấy thẻ (404 not found) tuyệt đối không được đá đăng xuất", r404.isAuthError)

            val r401 = client.checkInOut(domain, "11366", "999999", testDevice)
            assertFalse(r401.success)
            assertTrue(r401.isAuthError)
        } finally {
            runCatching { server.close() }
            t.join(1_000)
        }
    }

    @Test
    fun internetOutage_thenRestored_autoScansLanConnectsAndEntersReadyState() = runBlocking {
        val gateway = FakeGateway()
        val cfgRepo = FakeConfigRepo(
            AppConfig(
                apiDomain = "https://api.paluzz.com",
                accountId = "11366",
                username = "admin",
                password = "pw",
                devices = emptyList(),
                autoStartRunning = true,
                pollIntervalMs = 60_000L,
            )
        )
        val probe = FakeInternetProbe(online = false)
        val lanRepo = FakeLanRepo(discoveredHosts = listOf(LanHostInfo(ip = "192.168.1.224", isFkTerminal = true)))
        val engine = buildEngine(cfgRepo, gateway, internetProbe = probe, lanRepoOverride = lanRepo)
        try {
            engine.startBackgroundMonitors()
            // Chờ watchdog xác nhận mất Internet (2 lần kiểm tra lỗi) và kích nối lại mạng
            repeat(40) {
                if (engine.state.value.internetStatus == InternetStatus.OFFLINE && probe.reconnectRequests.get() >= 1) return@repeat
                delay(50L)
            }
            assertEquals(InternetStatus.OFFLINE, engine.state.value.internetStatus)
            assertTrue("Phải chủ động kích nối lại mạng khi mất Internet", probe.reconnectRequests.get() >= 1)
            assertFalse(engine.state.value.isRunning)

            // Có lại mạng Wi-Fi + Internet -> tự dò LAN -> tự kết nối máy quét mặt -> tự vào trạng thái sẵn sàng quét
            probe.hasLanOrWifi = true
            probe.online = true
            engine.onNetworkRestored()
            repeat(80) {
                val s = engine.state.value
                if (s.internetStatus == InternetStatus.ONLINE && s.connectedCount == 1 && s.isRunning) return@repeat
                delay(80L)
            }
            val snap = engine.state.value
            assertEquals(InternetStatus.ONLINE, snap.internetStatus)
            assertEquals("Phải tự tìm và lưu máy chấm công từ LAN", 1, cfgRepo.getConfig().devices.size)
            assertEquals("Phải tự kết nối máy chấm công", 1, snap.connectedCount)
            assertTrue("Phải tự đưa vào trạng thái sẵn sàng chờ quét", snap.isRunning)
        } finally {
            engine.disconnectAllAndStop()
        }
    }

    @Test
    fun manualStop_doesNotAutoRestartWhenInternetRecovers() = runBlocking {
        val gateway = FakeGateway()
        val cfgRepo = FakeConfigRepo(
            AppConfig(
                apiDomain = "https://api.paluzz.com",
                accountId = "11366",
                username = "admin",
                password = "pw",
                devices = listOf(testDevice),
                autoStartRunning = true,
                pollIntervalMs = 60_000L,
            )
        )
        val probe = FakeInternetProbe(online = true)
        val engine = buildEngine(cfgRepo, gateway, internetProbe = probe)
        try {
            engine.connectAllDevices(autoResumeIfConfigured = true)
            assertTrue(engine.state.value.isRunning)

            // Người dùng chủ động bấm Dừng chấm công
            engine.stopAttendance()
            assertFalse(engine.state.value.isRunning)
            assertFalse(cfgRepo.getConfig().autoStartRunning)

            // Mô phỏng mất mạng rồi có lại mạng -> không tự ép bật lại khi user đã chủ động dừng
            engine.startBackgroundMonitors()
            probe.online = false
            engine.onSystemNetworkLost()
            repeat(30) {
                if (engine.state.value.internetStatus == InternetStatus.OFFLINE) return@repeat
                delay(50L)
            }
            assertEquals(InternetStatus.OFFLINE, engine.state.value.internetStatus)

            probe.online = true
            engine.onNetworkRestored()
            repeat(30) {
                if (engine.state.value.internetStatus == InternetStatus.ONLINE) return@repeat
                delay(50L)
            }
            assertEquals(InternetStatus.ONLINE, engine.state.value.internetStatus)
            assertFalse("Phải tôn trọng lệnh dừng thủ công của người dùng", engine.state.value.isRunning)
        } finally {
            engine.disconnectAllAndStop()
        }
    }

    @Test
    fun bootStartup_withEmptyDevices_autoDiscoversConnectsAndStartsReady() = runBlocking {
        val gateway = FakeGateway()
        val cfgRepo = FakeConfigRepo(
            AppConfig(
                apiDomain = "https://api.paluzz.com",
                accountId = "11366",
                username = "admin",
                password = "pw",
                devices = emptyList(),
                autoStartRunning = true,
                pollIntervalMs = 60_000L,
            )
        )
        val lanRepo = FakeLanRepo(discoveredHosts = listOf(LanHostInfo(ip = "192.168.1.224", isFkTerminal = true)))
        val engine = buildEngine(cfgRepo, gateway, lanRepoOverride = lanRepo)
        try {
            engine.autoDiscoverConnectAndReady(fromInternetRecovery = false)
            assertEquals(1, cfgRepo.getConfig().devices.size)
            assertEquals(1, engine.state.value.connectedCount)
            assertTrue(engine.state.value.isRunning)
        } finally {
            engine.disconnectAllAndStop()
        }
    }

    @Test
    fun wifiLostWhile4gOnline_closesZombieSocket_thenWifiRestored_autoReconnectsAndResumes() = runBlocking {
        val gateway = FakeGateway()
        val cfgRepo = FakeConfigRepo(
            AppConfig(
                apiDomain = "https://api.paluzz.com",
                accountId = "11366",
                username = "admin",
                password = "pw",
                devices = listOf(testDevice),
                autoStartRunning = true,
                pollIntervalMs = 60_000L,
            )
        )
        val probe = FakeInternetProbe(online = true, hasLanOrWifi = true)
        val engine = buildEngine(cfgRepo, gateway, internetProbe = probe)
        try {
            engine.connectAllDevices(autoResumeIfConfigured = true)
            assertEquals(1, engine.state.value.connectedCount)
            assertTrue(engine.state.value.isRunning)

            engine.startBackgroundMonitors()
            // Mất Wi-Fi trong khi 4G vẫn giữ Internet online -> phải đóng ngay socket LAN chết nhưng giữ autoStartRunning
            probe.hasLanOrWifi = false
            engine.onLanTransportLost()
            repeat(40) {
                if (engine.state.value.connectedCount == 0 && !engine.state.value.isRunning) return@repeat
                delay(40L)
            }
            assertEquals("Phải đóng ngay socket chết khi mất Wi-Fi", 0, engine.state.value.connectedCount)
            assertFalse(engine.state.value.isRunning)
            assertTrue("Phải giữ cờ tự chạy 24/7 khi mất Wi-Fi", cfgRepo.getConfig().autoStartRunning)

            // Wi-Fi kết nối lại -> tự nối máy và tự quay lại trạng thái chờ quét 24/7
            probe.hasLanOrWifi = true
            engine.onLanTransportRestored()
            repeat(80) {
                if (engine.state.value.connectedCount == 1 && engine.state.value.isRunning) return@repeat
                delay(60L)
            }
            assertEquals("Phải tự kết nối lại máy sau khi Wi-Fi có lại", 1, engine.state.value.connectedCount)
            assertTrue("Phải tự quay lại trạng thái sẵn sàng quét", engine.state.value.isRunning)
        } finally {
            engine.disconnectAllAndStop()
        }
    }

    @Test
    fun wifiDropAndRouterChangesDeviceIp_autoRescansLanAndConnectsNewIp() = runBlocking {
        val gateway = FakeGateway().apply { blockedIps.add("192.168.1.224") }
        val cfgRepo = FakeConfigRepo(
            AppConfig(
                apiDomain = "https://api.paluzz.com",
                accountId = "11366",
                username = "admin",
                password = "pw",
                devices = listOf(testDevice),
                autoStartRunning = true,
                pollIntervalMs = 60_000L,
            )
        )
        val probe = FakeInternetProbe(online = false, hasLanOrWifi = false)
        val lanRepo = FakeLanRepo(discoveredHosts = listOf(LanHostInfo(ip = "192.168.1.225", isFkTerminal = true)))
        val engine = buildEngine(cfgRepo, gateway, internetProbe = probe, lanRepoOverride = lanRepo)
        try {
            engine.startBackgroundMonitors()
            repeat(30) {
                if (engine.state.value.internetStatus == InternetStatus.OFFLINE) return@repeat
                delay(40L)
            }
            assertEquals(InternetStatus.OFFLINE, engine.state.value.internetStatus)

            // Wi-Fi có lại nhưng máy quét mặt bị router đổi sang IP mới .225
            probe.hasLanOrWifi = true
            probe.online = true
            engine.onLanTransportRestored()
            repeat(100) {
                val s = engine.state.value
                if (s.connectedCount == 1 && s.isRunning && cfgRepo.getConfig().devices.any { it.ip == "192.168.1.225" }) {
                    return@repeat
                }
                delay(80L)
            }
            assertTrue("Phải tự tìm thấy IP mới .225 qua quét LAN", cfgRepo.getConfig().devices.any { it.ip == "192.168.1.225" })
            assertEquals(1, engine.state.value.connectedCount)
            assertTrue(engine.state.value.isRunning)
        } finally {
            engine.disconnectAllAndStop()
        }
    }

    @Test
    fun offlineCheckIn_queuedDuringWanOutage_autoFlushedWhenInternetReturns() = runBlocking {
        val gateway = FakeGateway()
        var wanUp = false
        val submittedCards = mutableListOf<String>()
        val attRepo = object : AttendanceRepository {
            override suspend fun submitCheckInOut(apiDomain: String, accountId: String, studentCardId: String, device: DeviceConfig): ServerCheckInResult {
                return if (!wanUp) {
                    ServerCheckInResult(false, "10:00:00 20/11/2026", 1L, "No route to host", isRetryableNetworkError = true)
                } else {
                    submittedCards.add(studentCardId)
                    ServerCheckInResult(true, "10:00:05 20/11/2026", 2L, "Điểm danh thành công")
                }
            }
            override suspend fun sendHeartbeat(apiDomain: String, accountId: String, primaryMachineNo: Int, running: Boolean, devices: List<DeviceHeartbeatItem>) = Unit
            override fun lastKnownServerTimeMillis(): Long? = null
        }
        val pollUc = PollAttendanceUseCase(gateway, attRepo)
        gateway.pendingLogs.add(AttendanceLog(123456, 1, 0, 2026, 11, 20, 10, 0, 0))

        // Lúc mất Internet WAN: quẹt mặt vẫn được tạm lưu vào hàng đợi, không bị mất
        val firstOutcome = pollUc(1, testDevice, "https://api.paluzz.com", "11366", 60L)
        assertEquals(1, firstOutcome.recordsRead)
        assertEquals(1, pollUc.pendingCount)
        assertTrue(submittedCards.isEmpty())

        // Khi có lại Internet WAN: tự xả hàng đợi đẩy bù lên web thành công
        wanUp = true
        val flushOutcome = pollUc.flushPendingCheckIns("https://api.paluzz.com", "11366", 60L)
        assertEquals(0, pollUc.pendingCount)
        assertEquals(listOf("123456"), submittedCards)
        assertEquals(1, flushOutcome.events.size)
        assertTrue(flushOutcome.events.first().message.contains("Đẩy bù thành công"))
    }

    private fun buildEngine(
        cfgRepo: FakeConfigRepo,
        gateway: FakeGateway,
        internetProbe: InternetProbe = FakeInternetProbe(online = true),
        lanRepoOverride: LanDiscoveryRepository? = null,
    ): AttendanceEngine {
        val attRepo = object : AttendanceRepository {
            override suspend fun submitCheckInOut(apiDomain: String, accountId: String, studentCardId: String, device: DeviceConfig) =
                ServerCheckInResult(true, "10:00:00 19/11/2026", 1L, "OK")
            override suspend fun sendHeartbeat(apiDomain: String, accountId: String, primaryMachineNo: Int, running: Boolean, devices: List<DeviceHeartbeatItem>) = Unit
            override fun lastKnownServerTimeMillis(): Long? = null
        }
        val authRepo = object : AuthRepository {
            override suspend fun login(apiDomain: String, username: String, password: String) = LoginResult(success = true, accountId = "11366")
            override suspend fun testServerConnection(apiDomain: String) = "OK"
        }
        val lanRepo = lanRepoOverride ?: FakeLanRepo()
        val clearUc = ClearDeviceLogsUseCase(gateway)
        val connectUc = ConnectDeviceUseCase(gateway, clearUc)
        val pollUc = PollAttendanceUseCase(gateway, attRepo)
        return AttendanceEngine(
            configRepository = cfgRepo,
            attendanceRepository = attRepo,
            deviceGateway = gateway,
            connectDeviceUseCase = connectUc,
            clearDeviceLogsUseCase = clearUc,
            pollAttendanceUseCase = pollUc,
            discoverDevicesUseCase = DiscoverDevicesUseCase(lanRepo, gateway),
            sendHeartbeatUseCase = SendHeartbeatUseCase(attRepo),
            validateCredentialsUseCase = ValidateCredentialsUseCase(authRepo, cfgRepo),
            internetProbe = internetProbe,
            onlineCheckIntervalMs = 80L,
            offlineCheckIntervalMs = 60L,
            dhcpSettleDelayMs = 20L,
        )
    }

    private class FakeInternetProbe(
        @Volatile var online: Boolean,
        @Volatile var hasLanOrWifi: Boolean = online,
    ) : InternetProbe {
        val reconnectRequests = AtomicInteger(0)
        override suspend fun checkInternet(apiDomain: String): InternetCheckResult =
            if (online) InternetCheckResult(true, 10L, "OK", hasLanOrWifi = hasLanOrWifi)
            else InternetCheckResult(false, 0L, "Mất mạng", hasLanOrWifi = hasLanOrWifi)
        override fun hasLanOrWifiIpv4(): Boolean = hasLanOrWifi
        override fun requestReconnectNetwork() { reconnectRequests.incrementAndGet() }
    }

    private class FakeLanRepo(private val discoveredHosts: List<LanHostInfo> = emptyList()) : LanDiscoveryRepository {
        override suspend fun detectActiveSubnets(preferredIps: Set<String>) =
            if (discoveredHosts.isEmpty()) emptyList()
            else listOf(SubnetInfo("192.168.1.10", "192.168.1", "255.255.255.0", "192.168.1.1", "eth0"))
        override suspend fun detectActiveSubnet(): SubnetInfo? = detectActiveSubnets(emptySet()).firstOrNull()
        override suspend fun detectRoutableExtraPrefixes(localSubnets: List<SubnetInfo>, knownIps: Set<String>) = emptyList<String>()
        override suspend fun scanSubnetByPing(prefix: String, localIp: String?, configuredIps: Set<String>, port: Int, onProgress: (Int, Int) -> Unit) = discoveredHosts
        override suspend fun scanLanHosts(configuredIps: Set<String>, port: Int, customPrefix: String?, onProgress: (Int, Int) -> Unit) = discoveredHosts
        override suspend fun suggestFreeIp(port: Int, excludeIps: Set<String>): IpSuggestion? = null
    }

    private class FakeConfigRepo(initial: AppConfig) : ConfigRepository {
        private val configsByMode = mutableMapOf(initial.mode to initial)
        private val flow = MutableStateFlow(initial)
        override val configFlow: StateFlow<AppConfig> = flow
        override fun getConfig(): AppConfig = flow.value
        override fun getBranding(): BrandingConfig = BrandingConfig.forMode(flow.value.mode)
        override suspend fun saveConfig(config: AppConfig) {
            configsByMode[config.mode] = config
            flow.value = config
        }
        override suspend fun switchMode(targetMode: AppMode): AppConfig {
            val prevDevices = flow.value.devices
            val fallback = AppConfig(mode = targetMode, apiDomain = BrandingConfig.forMode(targetMode).defaultApiDomain)
            val next = (configsByMode[targetMode] ?: fallback).let {
                if (it.devices.isEmpty() && prevDevices.isNotEmpty()) it.copy(devices = prevDevices) else it
            }
            configsByMode[targetMode] = next
            flow.value = next
            return next
        }
        override suspend fun upsertSavedAccount(account: SavedAccount) = Unit
        override suspend fun removeSavedAccount(username: String) = Unit
        override suspend fun updateDevices(devices: List<DeviceConfig>) { saveConfig(flow.value.copy(devices = devices)) }
        override suspend fun setAutoStartRunning(running: Boolean) { saveConfig(flow.value.copy(autoStartRunning = running)) }
        override suspend fun clearActiveCredentials() = Unit
    }

    private class FakeGateway : FkDeviceGateway {
        val connectCalls = AtomicInteger(0)
        val nextHandle = AtomicInteger(1)
        val deadHandles = mutableSetOf<Int>()
        val blockedIps = mutableSetOf<String>()
        val pendingLogs = mutableListOf<AttendanceLog>()
        val enableCalls = mutableListOf<Boolean>()
        var failConnectUntilAttempt = 0
        var failLoadWithCommError = false

        override suspend fun connect(machineNo: Int, ip: String, port: Int, timeoutMs: Int, netPassword: Int, license: Int, useUdp: Boolean): FkResult<FkConnectionInfo> {
            val attempt = connectCalls.incrementAndGet()
            if (ip in blockedIps) return FkResult.fail(FkErrorCode.ERR_COMM, "Host unreachable")
            if (attempt < failConnectUntilAttempt) return FkResult.fail(FkErrorCode.ERR_COMM, "Connection refused")
            val h = nextHandle.getAndIncrement()
            return FkResult.ok(FkConnectionInfo(h, ip, port, machineNo, FkFirmwareProfile.CIF11_1, "FKViaDev-TCP", 1024))
        }
        override suspend fun disconnect(handle: Int) = Unit
        override suspend fun disconnectAll() = Unit
        override suspend fun enableDevice(handle: Int, enable: Boolean): FkResult<Unit> {
            enableCalls.add(enable)
            return if (handle in deadHandles) FkResult.fail(FkErrorCode.ERR_COMM) else FkResult.ok(Unit)
        }
        override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean): FkResult<List<AttendanceLog>> = when {
            failLoadWithCommError || handle in deadHandles -> FkResult.fail(FkErrorCode.ERR_COMM)
            pendingLogs.isNotEmpty() -> FkResult.ok(pendingLogs.toList().also { pendingLogs.clear() })
            else -> FkResult.fail(FkErrorCode.ERR_LOG_END)
        }
        override suspend fun emptyGeneralLogData(handle: Int): FkResult<Unit> =
            if (handle in deadHandles) FkResult.fail(FkErrorCode.ERR_COMM) else FkResult.ok(Unit)
        override suspend fun readAllUserIds(handle: Int) = FkResult.ok<List<FkUserCredential>>(emptyList())
        override suspend fun putCardEnrollData(handle: Int, enrollNumber: Int, cardNumber: Int, privilege: Int) = FkResult.ok(Unit)
        override suspend fun saveEnrollData(handle: Int) = FkResult.ok(Unit)
        override suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int) = FkResult.ok(Unit)
        override suspend fun getUserName(handle: Int, enrollNumber: Int) = FkResult.ok("")
        override suspend fun setUserName(handle: Int, enrollNumber: Int, name: String) = FkResult.ok(Unit)
        override fun getConnectionInfo(handle: Int): FkConnectionInfo? =
            FkConnectionInfo(handle, "192.168.1.224", 5005, 1, FkFirmwareProfile.CIF11_1, "FKViaDev-TCP", 1024)
    }
}

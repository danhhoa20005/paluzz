package com.edusing.chamcongtvbox

import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice
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
import com.edusing.chamcongtvbox.domain.repository.LanDiscoveryRepository
import com.edusing.chamcongtvbox.domain.usecase.ClearDeviceLogsUseCase
import com.edusing.chamcongtvbox.domain.usecase.DiscoverDevicesUseCase
import com.edusing.chamcongtvbox.domain.usecase.PollAttendanceUseCase
import com.edusing.chamcongtvbox.domain.usecase.ScanLanDevicesUseCase
import com.edusing.chamcongtvbox.domain.usecase.SendHeartbeatUseCase
import com.edusing.chamcongtvbox.domain.usecase.ValidateCredentialsUseCase
import com.edusing.chamcongtvbox.service.AttendanceLoopRunner
import com.edusing.chamcongtvbox.service.EngineStateStore
import com.edusing.chamcongtvbox.service.web.WebConsoleAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLoginAndReconnectTest {

    @Test
    fun `WebConsoleAuth persistent token survives reboot and revokes immediately when password changes`() {
        val deviceSalt = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        var clock = 1_000L
        val beforeReboot = WebConsoleAuth(clockMillis = { clock }, persistentSalt = deviceSalt)
        val token = beforeReboot.issuePersistentToken("Pass@2026", isLoggedIn = true)
        assertTrue(token.startsWith("p1."))

        // Mô phỏng khởi động lại TV Box sau 30 ngày với instance mới cùng deviceSalt
        clock += 30L * 24 * 60 * 60_000L
        val afterReboot = WebConsoleAuth(clockMillis = { clock }, persistentSalt = deviceSalt)
        assertTrue(afterReboot.isTokenValid(token, "Pass@2026", isLoggedIn = true))
        // Đổi mật khẩu -> token cũ tự động vô hiệu hoá ngay
        assertFalse(afterReboot.isTokenValid(token, "NewPass@999", isLoggedIn = true))
        assertFalse(afterReboot.isTokenValid(token, "", isLoggedIn = false))
    }

    @Test
    fun `DiscoverDevicesUseCase auto-connects new Hikvision SADP terminals once without retrying 401`() = runBlocking {
        val attemptsByIp = mutableMapOf<String, Int>()
        val sadpList = listOf(
            HikvisionSadpDevice("192.168.1.8", 80, "DS-K1T320MX", "SN-MATCH-1", isAccessTerminal = true, activated = true),
            HikvisionSadpDevice("192.168.1.22", 80, "DS-K1T341", "SN-WRONG-2", isAccessTerminal = true, activated = true),
            HikvisionSadpDevice("192.168.1.99", 80, "DS-2CD1023", "SN-CAM-3", isAccessTerminal = false, activated = true),
        )
        val fakeGw = object : StubGateway() {
            override suspend fun discoverHikvisionSadp(timeoutMs: Int) = sadpList
            override suspend fun connect(device: DeviceConfig, timeoutMs: Int): FkResult<FkConnectionInfo> {
                attemptsByIp[device.ip] = (attemptsByIp[device.ip] ?: 0) + 1
                return if (device.ip == "192.168.1.8" && device.accessPassword == "SavedPass123") {
                    FkResult.ok(FkConnectionInfo(101, device.ip, 80, 1, FkFirmwareProfile.HIKVISION_ISAPI, "ISAPI", 30, "SN-MATCH-1"))
                } else {
                    FkResult.fail(FkErrorCode.ERR_BAD_PARAM, "401")
                }
            }
        }
        val useCase = DiscoverDevicesUseCase(EmptyLanRepo(), fakeGw)
        val report = useCase(
            knownDevices = emptyList(), port = 5005, machineNo = 1, password = 0, license = 5912, useUdp = false,
            hikUsername = "admin", hikPassword = "SavedPass123",
        )

        assertEquals(1, report.autoDiscoveredHikDevices.size)
        assertEquals("192.168.1.8", report.autoDiscoveredHikDevices[0].ip)
        assertEquals("DS-K1T320MX", report.autoDiscoveredHikDevices[0].name)
        assertEquals("SavedPass123", report.autoDiscoveredHikDevices[0].accessPassword)
        // Máy sai mật khẩu chỉ thử đúng 1 lần duy nhất (tuyệt đối không brute-force khoá 30p) và camera bị bỏ qua (0 lần)
        assertEquals(1, attemptsByIp["192.168.1.22"])
        assertEquals(null, attemptsByIp["192.168.1.99"])
        // Máy sai mật khẩu được báo vào unpairedHikDevices để người dùng nhập MK riêng nếu muốn
        assertEquals(1, report.unpairedHikDevices.size)
        assertEquals("192.168.1.22", report.unpairedHikDevices[0].ip)
    }

    @Test
    fun `3-layer discovery still finds Hikvision via TCP 80 ISAPI when both SADP multicast and ICMP ping fail`() = runBlocking {
        val lanRepo = object : LanDiscoveryRepository {
            override suspend fun detectActiveSubnets(preferredIps: Set<String>) = listOf(SubnetInfo("192.168.1.50", "192.168.1", "255.255.255.0", "192.168.1.1", "wlan0"))
            override suspend fun detectActiveSubnet() = detectActiveSubnets().first()
            override suspend fun detectRoutableExtraPrefixes(localSubnets: List<SubnetInfo>, knownIps: Set<String>) = emptyList<String>()
            override suspend fun scanSubnetByPing(prefix: String, localIp: String?, configuredIps: Set<String>, port: Int, onProgress: (Int, Int) -> Unit) = emptyList<LanHostInfo>()
            override suspend fun scanLanHosts(configuredIps: Set<String>, port: Int, customPrefix: String?, onProgress: (Int, Int) -> Unit) = emptyList<LanHostInfo>()
            override suspend fun suggestFreeIp(port: Int, excludeIps: Set<String>): IpSuggestion? = null
        }
        val fakeGw = object : StubGateway() {
            override suspend fun discoverHikvisionSadp(timeoutMs: Int) = emptyList<HikvisionSadpDevice>() // Router chặn multicast
            override suspend fun sweepHikvisionSubnet(prefix: String, excludeIps: Set<String>, localIp: String?) = listOf(
                HikvisionSadpDevice("192.168.1.8", 80, "Hikvision ISAPI", "", isAccessTerminal = true, activated = true),
                HikvisionSadpDevice("192.168.1.22", 80, "Hikvision ISAPI", "", isAccessTerminal = true, activated = true),
            )
        }
        // 1. Dò LAN trên Trạm trực khi chưa lưu MK -> báo cả 2 máy vào unpairedHikDevices
        val discoverUc = DiscoverDevicesUseCase(lanRepo, fakeGw)
        val report = discoverUc(emptyList(), 5005, 1, 0, 5912, false, hikUsername = "admin", hikPassword = "")
        assertEquals(2, report.unpairedHikDevices.size)
        assertEquals("192.168.1.8", report.unpairedHikDevices[0].ip)

        // 2. ScanLanDevicesUseCase (màn Thêm & Nhận máy) -> hiện ngay qua onQuickResults dù Ping & SADP đều 0
        var quickCount = 0
        val scanUc = ScanLanDevicesUseCase(lanRepo, fakeGw)
        val hosts = scanUc(configuredIps = emptySet(), onQuickResults = { quickCount = it.size })
        assertEquals(2, quickCount)
        assertEquals(2, hosts.count { it.isHikvisionTerminal })
    }

    @Test
    fun `AttendanceLoopRunner silently renews expired session on 401 instead of forcing re-login`() = runBlocking {
        val cfg = AppConfig(
            mode = AppMode.PALUZZ, apiDomain = "api.paluzz.com",
            accountId = "acc-1", orgName = "Cty A", username = "admin", password = "ValidPassword",
            rememberAccount = true, token = "expired-tok",
        )
        val cfgRepo = InMemoryConfigRepo(cfg)
        var loginCalls = 0
        var passwordStillValid = true
        val authRepo = object : AuthRepository {
            override suspend fun login(apiDomain: String, username: String, password: String): LoginResult {
                loginCalls++
                return if (passwordStillValid) {
                    LoginResult(true, "acc-1", "Cty A", "fresh-token-$loginCalls")
                } else {
                    LoginResult(false, error = "Sai mật khẩu", isInvalidCredentials = true)
                }
            }
            override suspend fun testServerConnection(apiDomain: String) = "OK"
        }
        var returnAuthError = false
        val attRepo = object : AttendanceRepository {
            override suspend fun submitCheckInOut(apiDomain: String, accountId: String, studentCardId: String, device: DeviceConfig) =
                if (returnAuthError) ServerCheckInResult(false, "08:00:00", 1_000L, "401", isAuthError = true)
                else ServerCheckInResult(false, "08:00:00", 1_000L, "net", isRetryableNetworkError = true)
            override suspend fun sendHeartbeat(apiDomain: String, accountId: String, primaryMachineNo: Int, running: Boolean, devices: List<DeviceHeartbeatItem>) = Unit
            override fun lastKnownServerTimeMillis(): Long? = 1_000L
        }
        val dev = DeviceConfig("192.168.1.8", 80, 1, "Hik", protocol = DeviceProtocol.HIKVISION_ISAPI)
        val gwWithLog = object : StubGateway() {
            override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean) =
                FkResult.ok(listOf(AttendanceLog(1, 75, 0, 2026, 11, 21, 8, 0, 0, rawCardId = "001")))
        }
        val pollUc = PollAttendanceUseCase(gwWithLog, attRepo)
        // Bước 1: đưa 1 lượt vào hàng đợi chờ bù (lỗi mạng tạm), rồi bật 401 khi xả hàng đợi
        pollUc(1, dev, "api.paluzz.com", "acc-1", 60)
        assertEquals(1, pollUc.pendingCount)
        returnAuthError = true

        var kickedReason: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = EngineStateStore(attRepo).apply {
            update { it.copy(internetStatus = com.edusing.chamcongtvbox.domain.repository.InternetStatus.ONLINE) }
        }
        val runner = AttendanceLoopRunner(
            scope, cfgRepo, attRepo, store, ClearDeviceLogsUseCase(StubGateway()),
            pollUc, SendHeartbeatUseCase(attRepo),
            ValidateCredentialsUseCase(authRepo, cfgRepo), Mutex(),
            getHandle = { 1 }, reconnectOfflineDevice = { _, _, _ -> true }, reconnectAfterCommError = { true },
            onAutoResumeNeeded = {}, onAutoDiscoverNeeded = {}, onPollFreezeDetected = {},
            onAuthInvalid = { kickedReason = it },
        )
        // 1. Gặp 401 khi xả hàng đợi nhưng mật khẩu lưu vẫn đúng -> tự làm mới token, KHÔNG bị đá ra màn hình Login!
        runner.flushPendingQueueNow()
        assertEquals(null, kickedReason)
        assertTrue(cfgRepo.getConfig().token.startsWith("fresh-token-"))

        // 2. Khi mật khẩu thật sự bị đổi trên server -> mới đá ra Login với thông báo rõ ràng
        passwordStillValid = false
        runner.refreshCredentialsSilentlyAsync()
        assertTrue(kickedReason != null && kickedReason!!.contains("thay đổi"))
        scope.cancel()
    }

    private class EmptyLanRepo : LanDiscoveryRepository {
        override suspend fun detectActiveSubnets(preferredIps: Set<String>) = emptyList<SubnetInfo>()
        override suspend fun detectActiveSubnet(): SubnetInfo? = null
        override suspend fun detectRoutableExtraPrefixes(localSubnets: List<SubnetInfo>, knownIps: Set<String>) = emptyList<String>()
        override suspend fun scanSubnetByPing(prefix: String, localIp: String?, configuredIps: Set<String>, port: Int, onProgress: (Int, Int) -> Unit) = emptyList<LanHostInfo>()
        override suspend fun scanLanHosts(configuredIps: Set<String>, port: Int, customPrefix: String?, onProgress: (Int, Int) -> Unit) = emptyList<LanHostInfo>()
        override suspend fun suggestFreeIp(port: Int, excludeIps: Set<String>): IpSuggestion? = null
    }

    private open class StubGateway : FkDeviceGateway {
        override suspend fun connect(machineNo: Int, ip: String, port: Int, timeoutMs: Int, netPassword: Int, license: Int, useUdp: Boolean) = FkResult.fail<FkConnectionInfo>(FkErrorCode.ERR_COMM)
        override suspend fun disconnect(handle: Int) = Unit
        override suspend fun disconnectAll() = Unit
        override suspend fun enableDevice(handle: Int, enable: Boolean) = FkResult.ok(Unit)
        override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean) = FkResult.fail<List<AttendanceLog>>(FkErrorCode.ERR_LOG_END)
        override suspend fun emptyGeneralLogData(handle: Int) = FkResult.ok(Unit)
        override suspend fun readAllUserIds(handle: Int) = FkResult.ok(emptyList<FkUserCredential>())
        override suspend fun putCardEnrollData(handle: Int, enrollNumber: Int, cardNumber: Int, privilege: Int) = FkResult.ok(Unit)
        override suspend fun setUserName(handle: Int, enrollNumber: Int, name: String) = FkResult.ok(Unit)
        override suspend fun saveEnrollData(handle: Int) = FkResult.ok(Unit)
        override suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int) = FkResult.ok(Unit)
        override suspend fun getUserName(handle: Int, enrollNumber: Int) = FkResult.ok("")
        override fun getConnectionInfo(handle: Int): FkConnectionInfo? = null
    }

    private class InMemoryConfigRepo(initial: AppConfig) : ConfigRepository {
        private val flow = MutableStateFlow(initial)
        override val configFlow: StateFlow<AppConfig> = flow
        override fun getConfig(): AppConfig = flow.value
        override fun getBranding(): BrandingConfig = BrandingConfig.forMode(flow.value.mode)
        override suspend fun switchMode(targetMode: AppMode): AppConfig = flow.value
        override suspend fun saveConfig(config: AppConfig) { flow.value = config }
        override suspend fun upsertSavedAccount(account: SavedAccount) = Unit
        override suspend fun removeSavedAccount(username: String) = Unit
        override suspend fun updateDevices(devices: List<DeviceConfig>) { flow.value = flow.value.copy(devices = devices) }
        override suspend fun setAutoStartRunning(running: Boolean) { flow.value = flow.value.copy(autoStartRunning = running) }
        override suspend fun clearActiveCredentials() = Unit
    }
}

package com.edusing.chamcongtvbox

import com.edusing.chamcongtvbox.data.hikvision.HikvisionGatewayImpl
import com.edusing.chamcongtvbox.data.hikvision.HikvisionIsapiClient
import com.edusing.chamcongtvbox.data.local.ConfigBackupCodec
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import com.edusing.chamcongtvbox.domain.model.ServerCheckInResult
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.usecase.ClearDeviceLogsUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectDeviceUseCase
import com.edusing.chamcongtvbox.service.AlertNotifier
import com.edusing.chamcongtvbox.service.DeviceConnectionManager
import com.edusing.chamcongtvbox.service.EngineStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class OptimizationsV26Test {

    @Test
    fun `HikvisionIsapiClient caches Digest nonce and halves HTTP requests on subsequent calls`() {
        val totalHttpHits = AtomicInteger(0)
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    sock.use { s ->
                        totalHttpHits.incrementAndGet()
                        val input = BufferedInputStream(s.getInputStream())
                        val hdr = ByteArrayOutputStream(); var m = 0
                        while (true) {
                            val b = input.read(); if (b == -1) return@use; hdr.write(b)
                            m = when { m == 0 && b == 13 -> 1; m == 1 && b == 10 -> 2; m == 2 && b == 13 -> 3; m == 3 && b == 10 -> 4; b == 13 -> 1; else -> 0 }
                            if (m == 4) break
                        }
                        val headerText = hdr.toString(Charsets.UTF_8.name())
                        val authed = headerText.contains("Authorization: Digest", ignoreCase = true)
                        val (code, extra, body) = if (!authed) {
                            Triple(401, "WWW-Authenticate: Digest realm=\"DS\", nonce=\"cachednonce99\", qop=\"auth\"\r\n", "")
                        } else {
                            Triple(200, "", """<DeviceInfo><model>DS-K1T320MX</model><serialNumber>SN1</serialNumber></DeviceInfo>""")
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        s.getOutputStream().write("HTTP/1.1 $code OK\r\n${extra}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        s.getOutputStream().write(bytes); s.getOutputStream().flush()
                    }
                }
            }
        }
        try {
            val client = HikvisionIsapiClient()
            val dev = DeviceConfig("127.0.0.1", server.localPort, 1, "Hik", protocol = DeviceProtocol.HIKVISION_ISAPI, username = "admin", accessPassword = "pw")
            // Lần 1: 2 lượt (401 lấy nonce + 200 OK)
            assertTrue(client.fetchDeviceInfo(dev).first.isSuccess)
            assertEquals(2, totalHttpHits.get())
            // Lần 2 & 3: dùng ngay nonce cache -> chỉ tốn ĐÚNG 1 lượt HTTP mỗi lần (tổng 4 thay vì 6!)
            assertTrue(client.fetchDeviceInfo(dev).first.isSuccess)
            assertTrue(client.fetchDeviceInfo(dev).first.isSuccess)
            assertEquals(4, totalHttpHits.get())
        } finally {
            server.close(); worker.join(1_000)
        }
    }

    @Test
    fun `DeviceConnectionManager locks silent reconnects on Hikvision 401 to prevent 30-min lockout`() = runBlocking {
        var connectAttempts = 0
        val dev = DeviceConfig("192.168.1.8", 80, 1, "Hik", protocol = DeviceProtocol.HIKVISION_ISAPI, username = "admin", accessPassword = "wrong")
        val gw = object : FkDeviceGateway {
            override suspend fun connect(machineNo: Int, ip: String, port: Int, timeoutMs: Int, netPassword: Int, license: Int, useUdp: Boolean) = FkResult.fail<FkConnectionInfo>(FkErrorCode.ERR_COMM)
            override suspend fun connect(device: DeviceConfig, timeoutMs: Int): FkResult<FkConnectionInfo> {
                connectAttempts++
                return FkResult.fail(FkErrorCode.ERR_BAD_PARAM, "Sai mật khẩu (401)")
            }
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
        val cfgRepo = FakeRepo(AppConfig(apiDomain = "api.paluzz.com", devices = listOf(dev)))
        val store = EngineStateStore(FakeAttRepo()).apply { syncConfiguredDevices(listOf(dev)) }
        val mgr = DeviceConnectionManager(cfgRepo, gw, ConnectDeviceUseCase(gw, ClearDeviceLogsUseCase(gw)), ClearDeviceLogsUseCase(gw), store)

        // Lần đầu thất bại 401 -> đánh dấu authLockedKeys
        assertFalse(mgr.connectIfOffline(dev, maxAttempts = 2, silentFail = false))
        assertEquals(1, connectAttempts)
        assertTrue(mgr.isAuthLocked(dev.key))

        // Các vòng nền silentFail = true tiếp theo PHẢI bỏ qua hoàn toàn (0 lần gọi mạng thêm -> không bao giờ khoá 30p!)
        repeat(10) { assertFalse(mgr.connectIfOffline(dev, maxAttempts = 1, silentFail = true)) }
        assertEquals(1, connectAttempts)

        // Khi người dùng sửa mật khẩu / clearAuthLock -> cho phép kết nối lại bình thường
        mgr.clearAuthLock(dev.key)
        assertFalse(mgr.isAuthLocked(dev.key))
    }

    @Test
    fun `HikvisionGatewayImpl adaptively skips redundant HTTP polls while Webhook heartbeats are active`() = runBlocking {
        var now = 1_760_000_000_000L
        val acsHits = AtomicInteger(0)
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    sock.use { s ->
                        val input = BufferedInputStream(s.getInputStream())
                        val hdr = ByteArrayOutputStream(); var m = 0
                        while (true) {
                            val b = input.read(); if (b == -1) return@use; hdr.write(b)
                            m = when { m == 0 && b == 13 -> 1; m == 1 && b == 10 -> 2; m == 2 && b == 13 -> 3; m == 3 && b == 10 -> 4; b == 13 -> 1; else -> 0 }
                            if (m == 4) break
                        }
                        val firstLine = hdr.toString(Charsets.UTF_8.name()).lineSequence().first()
                        val body = if (firstLine.contains("AcsEvent")) {
                            acsHits.incrementAndGet()
                            """{"AcsEvent":{"totalMatches":0,"InfoList":[]}}"""
                        } else {
                            """<DeviceInfo><model>DS-K1T320MX</model><serialNumber>SN1</serialNumber></DeviceInfo>"""
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        s.getOutputStream().write(bytes); s.getOutputStream().flush()
                    }
                }
            }
        }
        try {
            val gw = HikvisionGatewayImpl(clockMillis = { now })
            val dev = DeviceConfig("127.0.0.1", server.localPort, 1, "Hik", protocol = DeviceProtocol.HIKVISION_ISAPI, username = "admin", accessPassword = "pw")
            val handle = gw.connect(dev, 3_000).data!!.handle

            // Nhận 1 heartbeat Webhook -> đánh dấu Webhook đang sống
            gw.onWebhookEvent("127.0.0.1", "<EventNotificationAlert><eventType>heartBeat</eventType></EventNotificationAlert>")
            // Sau 10s (dưới 28s) và không có sự kiện trong hàng đợi -> giãn poll, KHÔNG tốn HTTP call!
            now += 10_000L
            assertEquals(FkErrorCode.ERR_LOG_END, gw.loadGeneralLogData(handle, true).code)
            assertEquals(0, acsHits.get())

            // Sau 30s (>= 28s) -> thực hiện 1 lượt poll dự phòng
            now += 20_000L
            gw.loadGeneralLogData(handle, true)
            assertEquals(1, acsHits.get())
        } finally {
            server.close(); worker.join(1_000)
        }
    }

    @Test
    fun `ConfigBackupCodec encrypts and decrypts AES-256-GCM safely and rejects wrong password`() {
        val plain = """{"activeMode":"PALUZZ","orgName":"Công ty TNHH Ánh Dương","devices":[{"ip":"192.168.1.8"}]}"""
        val encrypted = ConfigBackupCodec.encrypt(plain, "SecretKey@2026")
        assertFalse(encrypted.contains("192.168.1.8"))
        assertTrue(encrypted.contains("chamcong-tvbox-backup-v1"))

        val ok = ConfigBackupCodec.decrypt(encrypted, "SecretKey@2026")
        assertTrue(ok.isSuccess)
        assertEquals(plain, ok.getOrThrow())

        val bad = ConfigBackupCodec.decrypt(encrypted, "WrongPass")
        assertTrue(bad.isFailure)

        val alertText = AlertNotifier.formatAlertText("Cty A", "Mất kết nối", "Offline 5 phút", "Cổng chính", "08:05:00")
        assertTrue(alertText.contains("[Cty A]") && alertText.contains("Cổng chính"))
    }

    private class FakeRepo(initial: AppConfig) : ConfigRepository {
        private val flow = MutableStateFlow(initial)
        override val configFlow: StateFlow<AppConfig> = flow
        override fun getConfig() = flow.value
        override fun getBranding() = BrandingConfig.forMode(flow.value.mode)
        override suspend fun switchMode(targetMode: AppMode) = flow.value
        override suspend fun saveConfig(config: AppConfig) { flow.value = config }
        override suspend fun upsertSavedAccount(account: SavedAccount) = Unit
        override suspend fun removeSavedAccount(username: String) = Unit
        override suspend fun updateDevices(devices: List<DeviceConfig>) { flow.value = flow.value.copy(devices = devices) }
        override suspend fun setAutoStartRunning(running: Boolean) = Unit
        override suspend fun clearActiveCredentials() = Unit
    }

    private class FakeAttRepo : AttendanceRepository {
        override suspend fun submitCheckInOut(apiDomain: String, accountId: String, studentCardId: String, device: DeviceConfig) =
            ServerCheckInResult(true, "08:00:00", 1_000L, "OK")
        override suspend fun sendHeartbeat(apiDomain: String, accountId: String, primaryMachineNo: Int, running: Boolean, devices: List<DeviceHeartbeatItem>) = Unit
        override fun lastKnownServerTimeMillis(): Long? = null
    }
}

package com.edusing.chamcongtvbox

import com.edusing.chamcongtvbox.data.hikvision.HikvisionFaceNormalizer
import com.edusing.chamcongtvbox.data.hikvision.HikvisionIsapiClient
import com.edusing.chamcongtvbox.data.hikvision.HikvisionIsapiParser
import com.edusing.chamcongtvbox.data.hikvision.HikvisionWebhookTracker
import com.edusing.chamcongtvbox.data.local.HikvisionSyncWatermarkStore
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.ClassGroup
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.DeviceUser
import com.edusing.chamcongtvbox.domain.model.HikvisionDoorCommand
import com.edusing.chamcongtvbox.domain.model.HikvisionHealthStatus
import com.edusing.chamcongtvbox.domain.model.HikvisionHistoryItem
import com.edusing.chamcongtvbox.domain.model.HikvisionWebhookConfig
import com.edusing.chamcongtvbox.domain.model.LoginResult
import com.edusing.chamcongtvbox.domain.model.Member
import com.edusing.chamcongtvbox.domain.model.ServerCheckInResult
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.AuthRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.MemberRepository
import com.edusing.chamcongtvbox.domain.usecase.AbbreviateNamesUseCase
import com.edusing.chamcongtvbox.domain.usecase.AutoPushMembersUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectedDeviceHandle
import com.edusing.chamcongtvbox.domain.usecase.PollAttendanceUseCase
import com.edusing.chamcongtvbox.domain.usecase.PushMembersToDevicesUseCase
import com.edusing.chamcongtvbox.domain.usecase.SyncHikvisionFacesUseCase
import com.edusing.chamcongtvbox.domain.usecase.ValidateCredentialsUseCase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** Kiểm thử chuẩn hoá toàn diện Hikvision ISAPI v2.7.0: phân trang, lỗi ẩn HTTP 200, chuẩn hoá ảnh, đồng bộ mặt chéo & 401 guard. */
class HikvisionStandardizationTest {

    @Test
    fun acsEventPagination_advancesByActualReturnedCount_neverSkipsEvents() {
        val seenPositions = mutableListOf<Int>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ISAPI/AccessControl/AcsEvent") { ex ->
            val reqBody = ex.requestBody.bufferedReader().readText()
            val pos = JSONObject(reqBody).getJSONObject("AcsEventCond").getInt("searchResultPosition")
            seenPositions.add(pos)
            // Giả lập firmware V3.5.20 chỉ trả tối đa 2 bản ghi/trang dù client yêu cầu 30
            val items = when (pos) {
                0 -> listOf("101" to 1, "102" to 2)
                2 -> listOf("103" to 3, "104" to 4)
                else -> listOf("105" to 5)
            }
            val arr = JSONArray().apply {
                items.forEach { (emp, sn) ->
                    put(JSONObject().put("major", 5).put("minor", 75).put("employeeNoString", emp).put("serialNo", sn).put("time", "2026-11-22T08:00:0${sn}+07:00"))
                }
            }
            val resp = JSONObject().put(
                "AcsEvent",
                JSONObject()
                    .put("totalMatches", 5)
                    .put("numOfMatches", items.size)
                    .put("responseStatusStrg", if (pos + items.size < 5) "MORE" else "OK")
                    .put("InfoList", arr),
            ).toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            val dev = DeviceConfig(ip = "127.0.0.1", port = server.address.port, protocol = DeviceProtocol.HIKVISION_ISAPI)
            val (res, events) = HikvisionIsapiClient().fetchAccessEvents(dev, 0L, 1_000_000L, 1_000L, maxPages = 10)
            assertTrue(res.isSuccess)
            assertEquals(listOf(0, 2, 4), seenPositions)
            assertEquals(listOf("101", "102", "103", "104", "105"), events.map { it.employeeNo })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun parser_detectsHttp200IsapiBodyErrors_andTranslatesVietnamese() {
        assertTrue(HikvisionIsapiParser.isIsapiBodyError("""{"statusCode":6,"subStatusCode":"deviceBusy"}"""))
        assertTrue(HikvisionIsapiParser.isIsapiBodyError("""<ResponseStatus><statusCode>4</statusCode><subStatusCode>badParameters</subStatusCode></ResponseStatus>"""))
        assertFalse(HikvisionIsapiParser.isIsapiBodyError("""{"statusCode":1,"subStatusCode":"ok"}"""))
        assertTrue(HikvisionIsapiParser.extractErrorDetail("""{"subStatusCode":"deviceBusy"}""").contains("menu Admin"))
        assertTrue(HikvisionIsapiParser.extractErrorDetail("""{"subStatusCode":"picAnalysisFailed"}""").contains("chính diện"))
        assertEquals("/LOCALS/pic/001.jpg", HikvisionIsapiParser.parseFaceUrl("""{"MatchList":[{"faceURL":"/LOCALS/pic/001.jpg"}]}"""))
        assertEquals(listOf("12345678", "87654321"), HikvisionIsapiParser.parseCardNumbers("""{"CardInfoSearch":{"CardInfo":[{"cardNo":"12345678"},{"cardNo":"87654321"}]}}"""))
    }

    @Test
    fun faceNormalizer_acceptsValidJpeg_rejectsInvalidOrOversized() {
        val validJpeg = ByteArray(2_048).apply { this[0] = 0xFF.toByte(); this[1] = 0xD8.toByte(); this[2] = 0xFF.toByte() }
        val normalized = HikvisionFaceNormalizer.normalizeToHikvisionJpeg(validJpeg)
        assertNotNull(normalized)
        assertEquals(2_048, normalized!!.size)

        val notJpeg = ByteArray(2_048) { 0x42 }
        assertNull(HikvisionFaceNormalizer.normalizeToHikvisionJpeg(notJpeg))
        assertNull(HikvisionFaceNormalizer.normalizeToHikvisionJpeg(ByteArray(100)))
    }

    @Test
    fun syncHikvisionFaces_clonesMissingFacesOnceAcrossMultipleTargets() = runBlocking {
        val src = DeviceConfig(ip = "192.168.1.8", port = 80, name = "Cổng chính", protocol = DeviceProtocol.HIKVISION_ISAPI)
        val dst1 = DeviceConfig(ip = "192.168.1.22", port = 80, name = "Cổng phụ", protocol = DeviceProtocol.HIKVISION_ISAPI)
        val dst2 = DeviceConfig(ip = "192.168.1.23", port = 80, name = "Tầng 2", protocol = DeviceProtocol.HIKVISION_ISAPI)
        val downloads = mutableMapOf<String, Int>()
        val pushed = mutableListOf<Triple<String, String, String>>()
        val fakeJpeg = ByteArray(1_024).apply { this[0] = 0xFF.toByte(); this[1] = 0xD8.toByte(); this[2] = 0xFF.toByte() }

        val ctrl = object : StubHikControl() {
            override suspend fun fetchDetailedUsers(device: DeviceConfig): Result<List<DeviceUser>> = Result.success(
                when (device.ip) {
                    src.ip -> listOf(
                        DeviceUser(1, "Nguyễn An", rawEmployeeNo = "001", faceCount = 1, cardCount = 1),
                        DeviceUser(2, "Trần Bình", rawEmployeeNo = "NV02", faceCount = 1, cardCount = 0),
                        DeviceUser(3, "Lê Chưa Mặt", rawEmployeeNo = "003", faceCount = 0, cardCount = 0),
                    )
                    dst1.ip -> listOf(DeviceUser(1, "Nguyễn An", rawEmployeeNo = "001", faceCount = 1, cardCount = 1))
                    else -> emptyList()
                },
            )
            override suspend fun downloadFacePhoto(device: DeviceConfig, employeeNo: String): Result<ByteArray> {
                downloads[employeeNo] = (downloads[employeeNo] ?: 0) + 1
                return Result.success(fakeJpeg)
            }
            override suspend fun fetchUserCards(device: DeviceConfig, employeeNo: String): List<String> =
                if (employeeNo == "001") listOf("88001122") else emptyList()
            override suspend fun upsertUserWithOptionalFace(
                device: DeviceConfig, employeeNo: String, fullName: String, cardNo: String, jpegBytes: ByteArray?,
            ): Result<String> {
                pushed.add(Triple(device.ip, employeeNo, cardNo))
                return Result.success("OK")
            }
        }

        val summary = SyncHikvisionFacesUseCase(ctrl)(src, listOf(dst1, dst2), skipExistingFace = true).getOrThrow()
        assertEquals(2, summary.sourceFaceCount)
        assertEquals(3, summary.synced) // dst1 chỉ cần NV02; dst2 cần 001 + NV02
        assertEquals(1, summary.skippedExisting) // 001 đã có mặt sẵn ở dst1
        assertEquals(0, summary.failed)
        assertEquals(1, downloads["NV02"]) // Tải từ nguồn đúng 1 lần dù đẩy xuống 2 máy!
        assertEquals(1, downloads["001"])
        assertTrue(Triple(dst2.ip, "001", "88001122") in pushed)
        assertTrue(Triple(dst1.ip, "NV02", "") in pushed)
    }

    @Test
    fun autoPush_preservesAlphanumericAnd10DigitMembersForHikvision() = runBlocking {
        val hikDev = DeviceConfig(ip = "192.168.1.8", port = 80, protocol = DeviceProtocol.HIKVISION_ISAPI)
        val pushedEmpNos = mutableListOf<String>()
        val cfgRepo = InMemoryConfigRepo(AppConfig(mode = AppMode.PALUZZ, apiDomain = "https://paluzz.com", token = "tok", accountId = "1", username = "u", password = "p"))
        val memberRepo = object : MemberRepository {
            override suspend fun getClasses(apiDomain: String, bearerToken: String) = Result.success(emptyList<ClassGroup>())
            override suspend fun getStudents(apiDomain: String, bearerToken: String) = Result.success(
                listOf(
                    Member(cardId = "", rawCard = "NV01", name = "Nguyễn Văn A", classId = 0),
                    Member(cardId = "", rawCard = "9876543210", name = "Trần Thị B", classId = 0),
                    Member(cardId = "1", rawCard = "001", name = "Lê C", classId = 0),
                ),
            )
        }
        val ctrl = object : StubHikControl() {
            override suspend fun fetchDetailedUsers(device: DeviceConfig) = Result.success(emptyList<DeviceUser>())
            override suspend fun upsertUserWithOptionalFace(
                device: DeviceConfig, employeeNo: String, fullName: String, cardNo: String, jpegBytes: ByteArray?,
            ): Result<String> {
                pushedEmpNos.add(employeeNo); return Result.success("OK")
            }
        }
        val pushUc = PushMembersToDevicesUseCase(StubFkGateway(), AbbreviateNamesUseCase(), ctrl)
        val authRepo = object : AuthRepository {
            override suspend fun login(apiDomain: String, username: String, password: String) = LoginResult(true, "tok", "1", "Org")
            override suspend fun testServerConnection(apiDomain: String) = "OK"
        }
        val autoPush = AutoPushMembersUseCase(cfgRepo, memberRepo, ValidateCredentialsUseCase(authRepo, cfgRepo), pushUc)
        val outcome = autoPush(listOf(ConnectedDeviceHandle(hikDev, 100_001)))
        assertTrue(outcome.success)
        assertEquals(listOf("NV01", "9876543210", "001"), pushedEmpNos)
    }

    @Test
    fun webhookTracker_isolatesPerDeviceAndMatchesPayloadIp() {
        val tracker = HikvisionWebhookTracker()
        val hb = """{"ipAddress":"192.168.1.8","eventType":"heartBeat"}"""
        tracker.recordAndRoute("10.0.0.1", hb, nowMs = 10_000L) { _, _, _ -> false }
        assertTrue(tracker.isActiveForDevice("192.168.1.8", singleSession = false, nowMs = 20_000L, windowMs = 90_000L))
        assertFalse(tracker.isActiveForDevice("192.168.1.22", singleSession = false, nowMs = 20_000L, windowMs = 90_000L))
    }

    @Test
    fun pollAttendance_locksHikvisionImmediatelyOnMidSession401_andWatermarkCovers45sAdminOutage() = runBlocking {
        val hikDev = DeviceConfig(ip = "192.168.1.8", port = 80, protocol = DeviceProtocol.HIKVISION_ISAPI)
        val gw = object : StubFkGateway() {
            override suspend fun enableDevice(handle: Int, enable: Boolean) = FkResult.ok(Unit)
            override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean) =
                FkResult.fail<List<com.edusing.chamcongtvbox.domain.model.AttendanceLog>>(FkErrorCode.ERR_BAD_PARAM, "401")
        }
        val attRepo = object : AttendanceRepository {
            override suspend fun submitCheckInOut(apiDomain: String, accountId: String, studentCardId: String, device: DeviceConfig) =
                ServerCheckInResult(true, "08:00:00", 1_000L, "OK")
            override suspend fun sendHeartbeat(apiDomain: String, accountId: String, primaryMachineNo: Int, running: Boolean, devices: List<com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem>) = Unit
            override fun lastKnownServerTimeMillis(): Long? = null
            override fun resetNetworkConnections() = Unit
        }
        val outcome = PollAttendanceUseCase(gw, attRepo)(100_001, hikDev, "https://paluzz.com", "1", 60L)
        assertTrue(outcome.deviceAuthLocked)
        assertFalse(outcome.needsReconnect)

        val now = 1_760_000_000_000L
        assertNotNull(HikvisionSyncWatermarkStore.computeWindow(now - 45_000L, now))
        assertNull(HikvisionSyncWatermarkStore.computeWindow(now - 15_000L, now))
    }

    private open class StubHikControl : HikvisionControlGateway {
        override suspend fun fetchHealth(device: DeviceConfig) = Result.failure<HikvisionHealthStatus>(UnsupportedOperationException())
        override suspend fun controlDoor(device: DeviceConfig, command: HikvisionDoorCommand, doorNo: Int) = Result.success("OK")
        override suspend fun syncVietnamTime(device: DeviceConfig) = Result.success("OK")
        override suspend fun lockStaticIp(device: DeviceConfig) = Result.success("OK")
        override suspend fun rebootDevice(device: DeviceConfig) = Result.success("OK")
        override suspend fun captureSnapshot(device: DeviceConfig) = Result.failure<ByteArray>(UnsupportedOperationException())
        override suspend fun uploadFacePhoto(device: DeviceConfig, employeeNo: String, jpegBytes: ByteArray) = Result.success("OK")
        override suspend fun upsertUserWithOptionalFace(device: DeviceConfig, employeeNo: String, fullName: String, cardNo: String, jpegBytes: ByteArray?) = Result.success("OK")
        override suspend fun fetchDetailedUsers(device: DeviceConfig) = Result.success(emptyList<DeviceUser>())
        override suspend fun fetchHistoryEvents(device: DeviceConfig, sinceMs: Long, untilMs: Long) = Result.success(emptyList<HikvisionHistoryItem>())
        override suspend fun deleteUserByEmployeeNo(device: DeviceConfig, employeeNo: String) = Result.success(Unit)
        override suspend fun fetchSerialNumber(device: DeviceConfig, timeoutMs: Int): String? = null
        override suspend fun downloadAvatarBytes(url: String): ByteArray? = null
        override suspend fun fetchWebhookConfig(device: DeviceConfig) = Result.success(HikvisionWebhookConfig(false))
        override suspend fun configureWebhook(device: DeviceConfig, config: HikvisionWebhookConfig) = Result.success("OK")
        override suspend fun clearWebhook(device: DeviceConfig) = Result.success("OK")
    }

    private open class StubFkGateway : FkDeviceGateway {
        override suspend fun connect(machineNo: Int, ip: String, port: Int, timeoutMs: Int, netPassword: Int, license: Int, useUdp: Boolean) =
            FkResult.ok(FkConnectionInfo(1, ip, port, machineNo, FkFirmwareProfile.HIKVISION_ISAPI, "ISAPI", 30))
        override suspend fun disconnect(handle: Int) = Unit
        override suspend fun disconnectAll() = Unit
        override suspend fun enableDevice(handle: Int, enable: Boolean) = FkResult.ok(Unit)
        override suspend fun emptyGeneralLogData(handle: Int) = FkResult.ok(Unit)
        override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean) =
            FkResult.fail<List<com.edusing.chamcongtvbox.domain.model.AttendanceLog>>(FkErrorCode.ERR_LOG_END)
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
        override suspend fun updateDevices(devices: List<DeviceConfig>) { flow.value = flow.value.copy(devices = devices) }
        override suspend fun upsertSavedAccount(account: com.edusing.chamcongtvbox.domain.model.SavedAccount) = Unit
        override suspend fun removeSavedAccount(username: String) = Unit
        override suspend fun setAutoStartRunning(running: Boolean) { flow.value = flow.value.copy(autoStartRunning = running) }
        override suspend fun clearActiveCredentials() { flow.value = flow.value.copy(token = "", accountId = "") }
    }
}

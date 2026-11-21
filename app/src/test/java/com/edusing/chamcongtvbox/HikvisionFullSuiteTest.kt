package com.edusing.chamcongtvbox

import com.edusing.chamcongtvbox.data.hikvision.HikvisionControlApi
import com.edusing.chamcongtvbox.data.remote.ApiResponseParser
import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.DeviceUser
import com.edusing.chamcongtvbox.domain.model.HikvisionDoorCommand
import com.edusing.chamcongtvbox.domain.model.HikvisionHistoryItem
import com.edusing.chamcongtvbox.domain.model.Member
import com.edusing.chamcongtvbox.domain.model.ServerCheckInResult
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.usecase.AbbreviateNamesUseCase
import com.edusing.chamcongtvbox.domain.usecase.BackfillHikvisionEventsUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectedDeviceHandle
import com.edusing.chamcongtvbox.domain.usecase.PollAttendanceUseCase
import com.edusing.chamcongtvbox.domain.usecase.PushMembersToDevicesUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket

/** Kiểm thử trọn bộ tính năng phần cứng chuyên sâu Hikvision DS-K1T320MX (v1.8.0). */
class HikvisionFullSuiteTest {

    @Test
    fun controlApi_endToEndHealthDoorStaticIpFaceSnapshotUsersHistoryReboot() = runTest {
        var staticIpWritten = false
        var faceUploaded = false
        var doorOpened = false
        var rebootCalled = false
        val server = ServerSocket(0)
        val port = server.localPort
        val worker = Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { sock ->
                        val input = BufferedInputStream(sock.getInputStream())
                        val hdrBuf = ByteArrayOutputStream()
                        var matched = 0
                        while (true) {
                            val b = input.read(); if (b == -1) return@use
                            hdrBuf.write(b)
                            matched = when {
                                matched == 0 && b == '\r'.code -> 1; matched == 1 && b == '\n'.code -> 2
                                matched == 2 && b == '\r'.code -> 3; matched == 3 && b == '\n'.code -> 4
                                b == '\r'.code -> 1; else -> 0
                            }
                            if (matched == 4) break
                        }
                        val lines = hdrBuf.toString("UTF-8").split("\r\n")
                        val reqParts = lines.firstOrNull()?.split(' ') ?: return@use
                        val method = reqParts.getOrNull(0).orEmpty()
                        val path = reqParts.getOrNull(1).orEmpty()
                        var auth = ""; var contentLen = 0
                        for (l in lines.drop(1)) {
                            if (l.startsWith("Authorization:", true)) auth = l.substringAfter(':').trim()
                            if (l.startsWith("Content-Length:", true)) contentLen = l.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                        val bodyBytes = ByteArray(contentLen)
                        var read = 0
                        while (read < contentLen) { val n = input.read(bodyBytes, read, contentLen - read); if (n <= 0) break; read += n }
                        val bodyStr = String(bodyBytes, 0, read, Charsets.UTF_8)
                        val out = sock.getOutputStream()
                        if (auth.isEmpty()) {
                            out.write("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"DS-FC3B673D\", qop=\"auth\", nonce=\"n123\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            out.flush(); return@use
                        }
                        if (path.startsWith("/ISAPI/Streaming/channels/101/picture")) {
                            val fakeJpeg = ByteArray(1024) { 0x55 }
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${fakeJpeg.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(fakeJpeg); out.flush(); return@use
                        }
                        val respBody = when {
                            path.startsWith("/ISAPI/System/deviceInfo") ->
                                "<DeviceInfo><model>DS-K1T320MX</model><serialNumber>SN-TEST-01</serialNumber><firmwareVersion>V3.9.50</firmwareVersion></DeviceInfo>"
                            path.startsWith("/ISAPI/AccessControl/UserInfo/Count") ->
                                """{"UserInfoCount":{"userNumber":3,"bindFaceUserNumber":2,"bindCardUserNumber":1}}"""
                            path.startsWith("/ISAPI/AccessControl/CardInfo/Count") ->
                                """{"CardInfoCount":{"cardNumber":1}}"""
                            path.startsWith("/ISAPI/AccessControl/AcsWorkStatus") ->
                                """{"AcsWorkStatus":{"doorLockStatus":[0],"hostAntiDismantleStatus":"close"}}"""
                            path.startsWith("/ISAPI/System/Network/interfaces/1/ipAddress") && method == "GET" ->
                                "<IPAddress><addressingType>dynamic</addressingType><ipAddress>192.168.1.8</ipAddress><subnetMask>255.255.255.0</subnetMask><DefaultGateway><ipAddress>192.168.1.1</ipAddress></DefaultGateway></IPAddress>"
                            path.startsWith("/ISAPI/System/Network/interfaces/1/ipAddress") && method == "PUT" -> {
                                staticIpWritten = bodyStr.contains("<addressingType>static</addressingType>")
                                "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>"
                            }
                            path.startsWith("/ISAPI/System/time") ->
                                "<Time><localTime>2026-11-21T15:30:00+07:00</localTime></Time>"
                            path.startsWith("/ISAPI/AccessControl/RemoteControl/door/1") -> {
                                doorOpened = bodyStr.contains("<cmd>open</cmd>")
                                "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>"
                            }
                            path.startsWith("/ISAPI/System/reboot") -> {
                                rebootCalled = true; "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>"
                            }
                            path.startsWith("/ISAPI/Intelligent/FDLib/FaceDataRecord") -> {
                                faceUploaded = bodyStr.contains("FaceDataRecord") && bodyStr.contains("FaceImage") && bodyStr.contains("001")
                                """{"statusCode":1,"statusString":"OK"}"""
                            }
                            path.startsWith("/ISAPI/AccessControl/UserInfo/Search") ->
                                """{"UserInfoSearch":{"totalMatches":2,"UserInfo":[{"employeeNo":"1","name":"LOC01","numOfFace":1,"numOfCard":0},{"employeeNo":"001","name":"nguyễn danh hoà","numOfFace":0,"numOfCard":1}]}}"""
                            path.startsWith("/ISAPI/AccessControl/AcsEvent") ->
                                """{"AcsEvent":{"totalMatches":1,"InfoList":[{"major":5,"minor":75,"time":"2026-11-21T11:49:52+07:00","employeeNoString":"2","name":"khoa","serialNo":132}]}}"""
                            else -> """{"statusCode":1,"statusString":"OK"}"""
                        }
                        val bytes = respBody.toByteArray(Charsets.UTF_8)
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(bytes); out.flush()
                    }
                }
            }
        }
        worker.isDaemon = true; worker.start()
        try {
            val api = HikvisionControlApi()
            val dev = DeviceConfig(ip = "127.0.0.1", port = port, protocol = DeviceProtocol.HIKVISION_ISAPI, username = "admin", accessPassword = "pw")

            val health = api.fetchHealth(dev).getOrThrow()
            assertEquals("DS-K1T320MX", health.model)
            assertEquals("SN-TEST-01", health.serialNumber)
            assertEquals(3, health.userCount)
            assertEquals(2, health.faceCount)
            assertEquals(1, health.cardCount)
            assertTrue(health.isDhcp)
            assertFalse(health.antiDismantleAlert)

            assertTrue(api.controlDoor(dev, HikvisionDoorCommand.OPEN).isSuccess)
            assertTrue(doorOpened)

            assertTrue(api.lockStaticIp(dev).isSuccess)
            assertTrue(staticIpWritten)

            val snap = api.captureSnapshot(dev).getOrThrow()
            assertEquals(1024, snap.size)

            val fakeFace = ByteArray(1024) { 0x7F }
            assertTrue(api.uploadFacePhoto(dev, "001", fakeFace).isSuccess)
            assertTrue(faceUploaded)

            // Đảm bảo mã "1" và "001" tách biệt hoàn toàn, giữ đúng numOfFace
            val users = api.fetchDetailedUsers(dev).getOrThrow()
            assertEquals(2, users.size)
            assertEquals("1", users[0].displayCardId); assertEquals(1, users[0].faceCount)
            assertEquals("001", users[1].displayCardId); assertEquals(0, users[1].faceCount)
            assertTrue(users[0].uniqueKey != users[1].uniqueKey)

            val history = api.fetchHistoryEvents(dev, 0L, 1_000L).getOrThrow()
            assertEquals(1, history.size)
            assertEquals("2", history[0].employeeNo)
            assertEquals("Khuôn mặt", history[0].verifyMethod)

            assertTrue(api.rebootDevice(dev).isSuccess)
            assertTrue(rebootCalled)
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun backfillUseCase_deduplicatesWithinWindowAndSubmitsMissedScans() = runTest {
        val sentCards = mutableListOf<String>()
        val fakeRepo = object : AttendanceRepository {
            override suspend fun submitCheckInOut(apiDomain: String, accountId: String, studentCardId: String, device: DeviceConfig): ServerCheckInResult {
                sentCards.add(studentCardId)
                return ServerCheckInResult(true, "08:00:00", 1_000L, "OK")
            }
            override suspend fun sendHeartbeat(apiDomain: String, accountId: String, primaryMachineNo: Int, running: Boolean, devices: List<DeviceHeartbeatItem>) = Unit
            override fun lastKnownServerTimeMillis(): Long? = null
            override fun resetNetworkConnections() = Unit
        }
        val fakeCtrl = object : HikvisionControlGateway by HikvisionControlApi() {
            override suspend fun fetchHistoryEvents(device: DeviceConfig, sinceMs: Long, untilMs: Long) = Result.success(
                listOf(
                    HikvisionHistoryItem("08:00:00", 100_000L, "101", "An", "Khuôn mặt", 1),
                    HikvisionHistoryItem("08:00:20", 120_000L, "101", "An", "Khuôn mặt", 2), // trùng trong 60s
                    HikvisionHistoryItem("08:05:00", 400_000L, "101", "An", "Khuôn mặt", 3), // hợp lệ sau 5 phút
                    HikvisionHistoryItem("08:01:00", "001".length.toLong() + 160_000L, "001", "Hoà", "Khuôn mặt", 4),
                )
            )
        }
        val useCase = BackfillHikvisionEventsUseCase(fakeCtrl, fakeRepo)
        val dev = DeviceConfig(ip = "192.168.1.8", port = 80, protocol = DeviceProtocol.HIKVISION_ISAPI)
        val summary = useCase(dev, "api.paluzz.com", "acc1", 0L, 900_000L, dedupSeconds = 60L).getOrThrow()
        assertEquals(4, summary.totalFound)
        assertEquals(3, summary.submitted)
        assertEquals(1, summary.skippedDuplicate)
        assertEquals(listOf("101", "001", "101"), sentCards)
    }

    @Test
    fun apiResponseParser_extractsAvatarUrlFromMemberJson() {
        val raw = """{"data":[{"name":"Nguyễn Văn A","studentCardId":"10025","classId":3,"avatarUrl":"https://cdn.paluzz.com/a.jpg"}]}"""
        val members = ApiResponseParser.parseStudentsResponse(200, raw).getOrThrow()
        assertEquals(1, members.size)
        assertEquals("https://cdn.paluzz.com/a.jpg", members[0].avatarUrl)
    }

    @Test
    fun endToEndTwoWay_pushLeadingZeroAlphanumericAnd10DigitCardThenPollToServer() = runTest {
        val enrolled = mutableListOf<Triple<String, String, String>>()
        val fakeCtrl = object : HikvisionControlGateway by HikvisionControlApi() {
            override suspend fun fetchDetailedUsers(device: DeviceConfig) = Result.success(emptyList<DeviceUser>())
            override suspend fun upsertUserWithOptionalFace(
                device: DeviceConfig, employeeNo: String, fullName: String, cardNo: String, jpegBytes: ByteArray?,
            ): Result<String> {
                enrolled.add(Triple(employeeNo, fullName, cardNo))
                return Result.success("OK")
            }
        }
        val hikDev = DeviceConfig(ip = "192.168.1.8", port = 80, protocol = DeviceProtocol.HIKVISION_ISAPI)
        val dummyGw = object : FkDeviceGateway {
            override suspend fun connect(machineNo: Int, ip: String, port: Int, timeoutMs: Int, netPassword: Int, license: Int, useUdp: Boolean): FkResult<FkConnectionInfo> = FkResult.fail(FkErrorCode.ERR_COMM)
            override suspend fun disconnect(handle: Int) = Unit
            override suspend fun disconnectAll() = Unit
            override suspend fun enableDevice(handle: Int, enable: Boolean) = FkResult.ok(Unit)
            override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean) = FkResult.ok(
                listOf(
                    AttendanceLog(1, 75, 0, 2026, 11, 21, 8, 0, 0, rawCardId = "001"),
                    AttendanceLog(99, 75, 0, 2026, 11, 21, 8, 1, 0, rawCardId = "NV01"),
                    AttendanceLog(77, 75, 0, 2026, 11, 21, 8, 2, 0, rawCardId = "3123456789"),
                )
            )
            override suspend fun emptyGeneralLogData(handle: Int) = FkResult.ok(Unit)
            override suspend fun readAllUserIds(handle: Int) = FkResult.ok(emptyList<FkUserCredential>())
            override suspend fun putCardEnrollData(handle: Int, enrollNumber: Int, cardNumber: Int, privilege: Int) = FkResult.ok(Unit)
            override suspend fun saveEnrollData(handle: Int) = FkResult.ok(Unit)
            override suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int) = FkResult.ok(Unit)
            override suspend fun getUserName(handle: Int, enrollNumber: Int) = FkResult.ok("Nguyễn Danh Hoà")
            override suspend fun setUserName(handle: Int, enrollNumber: Int, name: String) = FkResult.ok(Unit)
            override fun getConnectionInfo(handle: Int): FkConnectionInfo? = null
        }

        // Chiều 1: Đẩy nhân sự từ server tổng xuống Hikvision (giữ nguyên "001", "NV01", thẻ 10 số & tên tiếng Việt có dấu)
        val members = listOf(
            Member(cardId = "1", rawCard = "001", name = "Nguyễn Danh Hoà", classId = 1),
            Member(cardId = "", rawCard = "NV01", name = "Trần Minh Khoa", classId = 1),
            Member(cardId = "", rawCard = "3123456789", name = "Lê Thị Lan", classId = 1),
        )
        val pushSummary = PushMembersToDevicesUseCase(dummyGw, AbbreviateNamesUseCase(), fakeCtrl)(
            targets = listOf(ConnectedDeviceHandle(hikDev, 100_001)),
            members = members,
        )
        assertEquals(3, pushSummary.succeeded)
        assertEquals(Triple("001", "Nguyễn Danh Hoà", "001"), enrolled[0])
        assertEquals(Triple("NV01", "Trần Minh Khoa", ""), enrolled[1]) // Mã chữ không gắn CardInfo số
        assertEquals(Triple("3123456789", "Lê Thị Lan", "3123456789"), enrolled[2])

        // Chiều 2: Quét mặt trên Hikvision -> gửi chính xác nguyên mã "001", "NV01", "3123456789" lên server tổng Paluzz!
        val sentToServer = mutableListOf<String>()
        val repo = object : AttendanceRepository {
            override suspend fun submitCheckInOut(apiDomain: String, accountId: String, studentCardId: String, device: DeviceConfig): ServerCheckInResult {
                sentToServer.add(studentCardId)
                return ServerCheckInResult(true, "08:00:00", 1_000L, "Chấm công thành công")
            }
            override suspend fun sendHeartbeat(apiDomain: String, accountId: String, primaryMachineNo: Int, running: Boolean, devices: List<DeviceHeartbeatItem>) = Unit
            override fun lastKnownServerTimeMillis(): Long? = null
            override fun resetNetworkConnections() = Unit
        }
        val pollOutcome = PollAttendanceUseCase(dummyGw, repo)(100_001, hikDev, "api.paluzz.com", "acc1", 60L)
        assertEquals(listOf("001", "NV01", "3123456789"), sentToServer)
        assertEquals(3, pollOutcome.events.size)
    }
}

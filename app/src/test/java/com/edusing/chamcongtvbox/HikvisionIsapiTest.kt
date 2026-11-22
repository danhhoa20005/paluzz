package com.edusing.chamcongtvbox

import com.edusing.chamcongtvbox.data.hikvision.HikvisionDigestChallenge
import com.edusing.chamcongtvbox.data.hikvision.HikvisionGatewayImpl
import com.edusing.chamcongtvbox.data.hikvision.HikvisionIsapiParser
import com.edusing.chamcongtvbox.data.hikvision.HikvisionLanTls
import com.edusing.chamcongtvbox.data.protocol.HybridDeviceGatewayImpl
import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.Member
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential
import com.edusing.chamcongtvbox.domain.usecase.AbbreviateNamesUseCase
import com.edusing.chamcongtvbox.domain.usecase.ClearDeviceLogsUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectDeviceUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectedDeviceHandle
import com.edusing.chamcongtvbox.domain.usecase.PushMembersToDevicesUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử đơn vị cho giao thức Hikvision DS-K1T320MX (Digest Auth, ISAPI Parser, Hybrid Gateway & đẩy tên UTF-8). */
class HikvisionIsapiTest {

    @Test
    fun digestAuth_parsesAndSignsMd5AndSha256Correctly() {
        val md5Header = """Digest realm="DS-K1T320MX", qop="auth", nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093", opaque="5ccc069c403ebaf9f0171e9517f40e41""""
        val challenge = HikvisionDigestChallenge.parse(listOf(md5Header))
        assertNotNull(challenge)
        assertTrue(challenge!!.isDigest)
        val auth = challenge.buildAuthorization(
            method = "GET",
            requestUriWithQuery = "/ISAPI/System/deviceInfo",
            username = "admin",
            password = "Password123",
            nonceCount = 1,
            clientNonceOverride = "0a4f113b",
        )
        assertTrue(auth.startsWith("Digest "))
        assertTrue(auth.contains("""username="admin""""))
        assertTrue(auth.contains("""realm="DS-K1T320MX""""))
        assertTrue(auth.contains("""qop=auth"""))
        assertTrue(auth.contains("""nc=00000001"""))
        assertTrue(auth.contains("""cnonce="0a4f113b""""))

        val shaHeader = """Digest realm="Hikvision", algorithm=SHA-256, qop="auth", nonce="abc123""""
        val shaAuth = HikvisionDigestChallenge.parse(listOf(shaHeader))!!.buildAuthorization(
            method = "POST",
            requestUriWithQuery = "/ISAPI/AccessControl/AcsEvent?format=json",
            username = "admin",
            password = "Secret!",
            clientNonceOverride = "11223344",
        )
        assertTrue(shaAuth.contains("algorithm=SHA-256"))
        val respHex = Regex("""response="([0-9a-f]+)"""").find(shaAuth)!!.groupValues[1]
        assertEquals(64, respHex.length)

        val basic = HikvisionDigestChallenge.parse(listOf("""Basic realm="Hik""""))!!
        assertEquals("Basic YWRtaW46MTIzNA==", basic.buildAuthorization("GET", "/", "admin", "1234"))

        // Kiểm thử đúng header WWW-Authenticate thực tế từ DS-K1T320MX (firmware 2024+, nonce base64 có dấu '=')
        val realHeader = """Digest qop="auth", realm="DS-FC3B673D", nonce="NTcxZjM3NjA5NWFlMGRiMDc1YTgwMjA1OTRhY2E1MWU=", stale="false", opaque="", domain="::""""
        val realChallenge = HikvisionDigestChallenge.parse(listOf(realHeader))!!
        assertEquals("DS-FC3B673D", realChallenge.params["realm"])
        assertEquals("NTcxZjM3NjA5NWFlMGRiMDc1YTgwMjA1OTRhY2E1MWU=", realChallenge.params["nonce"])
        val realAuth = realChallenge.buildAuthorization("GET", "/ISAPI/System/deviceInfo", "admin", "Pass1234", 1, "abcd1234")
        assertTrue(realAuth.contains("realm=\"DS-FC3B673D\""))
        assertTrue(realAuth.contains("nonce=\"NTcxZjM3NjA5NWFlMGRiMDc1YTgwMjA1OTRhY2E1MWU=\""))

        // Kiểm thử chỉ cho phép self-signed HTTPS ở IP LAN nội bộ & bóc IP từ đường dẫn web Hikvision
        assertTrue(HikvisionLanTls.isPrivateLanIp("192.168.1.8"))
        assertTrue(HikvisionLanTls.isPrivateLanIp("10.0.0.5"))
        assertTrue(!HikvisionLanTls.isPrivateLanIp("8.8.8.8"))
        assertEquals("192.168.1.8", HikvisionLanTls.extractHostFromInput("https://192.168.1.8/doc/index.html#/portal/login"))
    }

    @Test
    fun parser_parsesDeviceInfoXmlAndJson() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <DeviceInfo version="2.0"><deviceName>Cong Chinh</deviceName><model>DS-K1T320MX</model>
            <serialNumber>DS-K1T320MX20240101AA</serialNumber><firmwareVersion>V3.2.30</firmwareVersion></DeviceInfo>"""
        val infoXml = HikvisionIsapiParser.parseDeviceInfo(xml)
        assertEquals("DS-K1T320MX", infoXml.model)
        assertEquals("Cong Chinh", infoXml.deviceName)
        assertEquals("V3.2.30", infoXml.firmwareVersion)

        val json = """{"DeviceInfo":{"deviceName":"Sanh A","model":"DS-K1T320MX","serialNumber":"SN99","firmwareVersion":"V4.0"}}"""
        val infoJson = HikvisionIsapiParser.parseDeviceInfo(json)
        assertEquals("Sanh A", infoJson.deviceName)
        assertEquals("SN99", infoJson.serialNumber)
    }

    @Test
    fun parser_filtersAcsEventsAndParsesMultipartWebhookWithTrailingJpeg() {
        val acsJson = """{
          "AcsEvent": {
            "totalMatches": 3,
            "InfoList": [
              {"major":5,"minor":75,"time":"2026-11-21T08:15:30+07:00","employeeNoString":"10025","name":"Nguyễn Văn An","serialNo":501},
              {"major":5,"minor":21,"time":"2026-11-21T08:16:00+07:00","employeeNoString":"10025","serialNo":502},
              {"major":5,"minor":1,"time":"2026-11-21T08:17:05+07:00","cardNo":"88991","serialNo":503}
            ]
          }
        }"""
        val page = HikvisionIsapiParser.parseAcsEvents(acsJson, "192.168.1.64", 0L)
        assertEquals(3, page.totalMatches)
        assertEquals(2, page.events.size)
        assertEquals("10025", page.events[0].employeeNo)
        assertEquals("Nguyễn Văn An", page.events[0].employeeName)
        assertEquals(8, page.events[0].hour)
        assertEquals(15, page.events[0].minute)
        assertEquals("88991", page.events[1].employeeNo)

        // Multipart webhook kèm dữ liệu ảnh JPEG chứa ký tự '}' ở cuối vẫn tách đúng khối JSON đầu tiên
        val multipart = "--boundary\r\nContent-Type: application/json\r\n\r\n" +
            """{"ipAddress":"192.168.1.64","dateTime":"2026-11-21T09:30:12+07:00","AccessControllerEvent":{"majorEventType":5,"subEventType":75,"employeeNoString":"2001","name":"Trần Thị Hoa","serialNo":901}}""" +
            "\r\n--boundary\r\nContent-Type: image/jpeg\r\n\r\n\u00FF\u00D8fake_jpeg_with_brace_}_inside\r\n--boundary--"
        val wh = HikvisionIsapiParser.parseWebhookPayload(multipart, "192.168.1.64", 0L)
        assertNotNull(wh)
        assertEquals("2001", wh!!.employeeNo)
        assertEquals("Trần Thị Hoa", wh.employeeName)
        assertEquals(901, wh.serialNo)

        val invalidWh = HikvisionIsapiParser.parseWebhookPayload("""{"AccessControllerEvent":{"majorEventType":5,"subEventType":21}}""", "192.168.1.64", 0L)
        assertNull(invalidWh)
    }

    @Test
    fun hybridGateway_routesFkAndHikvisionAndPushKeepsFullUtf8ForHikvision() = runTest {
        val fkFake = FakeProtocolGateway(baseHandle = 1, profile = FkFirmwareProfile.CIF13)
        val hikFake = FakeProtocolGateway(baseHandle = HikvisionGatewayImpl.HANDLE_OFFSET, profile = FkFirmwareProfile.HIKVISION_ISAPI)
        val hybrid = HybridDeviceGatewayImpl(fkFake, hikFake)

        val fkDev = DeviceConfig(ip = "192.168.1.201", port = 5005, protocol = DeviceProtocol.FK)
        val hikDev = DeviceConfig(ip = "192.168.1.64", port = 80, protocol = DeviceProtocol.HIKVISION_ISAPI, accessPassword = "Admin123")

        val fkConn = hybrid.connect(fkDev)
        val hikConn = hybrid.connect(hikDev)
        assertTrue(fkConn.isSuccess && fkConn.data!!.handle < HikvisionGatewayImpl.HANDLE_OFFSET)
        assertTrue(hikConn.isSuccess && hikConn.data!!.handle >= HikvisionGatewayImpl.HANDLE_OFFSET)
        assertEquals("Hikvision DS-K1T320MX", hybrid.probeHikvisionHost("192.168.1.64", 80))

        val pushUseCase = PushMembersToDevicesUseCase(hybrid, AbbreviateNamesUseCase())
        val summary = pushUseCase(
            targets = listOf(
                ConnectedDeviceHandle(fkDev, fkConn.data!!.handle),
                ConnectedDeviceHandle(hikDev, hikConn.data!!.handle),
            ),
            members = listOf(Member(cardId = "1001", rawCard = "1001", name = "Nguyễn Minh Khôi", classId = 1)),
        )
        assertEquals(2, summary.succeeded)
        // Máy FK rút gọn không dấu <= 6 byte; máy Hikvision giữ nguyên tiếng Việt có dấu UTF-8
        val fkName = fkFake.writtenNames[1001].orEmpty()
        assertTrue("Tên FK phải rút gọn <= 6 byte nhưng là '$fkName'", fkName.length in 1..6 && fkName.all { it.code < 128 })
        assertEquals("Nguyễn Minh Khôi", hikFake.writtenNames[1001])
    }

    @Test
    fun endToEnd_realHikvisionGatewayOverLocalhostDigestServer() = runTest {
        val handledPaths = mutableListOf<String>()
        var authChallengeHits = 0
        var authorizedHits = 0
        val server = java.net.ServerSocket(0)
        val port = server.localPort
        val worker = Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { sock ->
                        val input = java.io.BufferedInputStream(sock.getInputStream())
                        val hdrBuf = java.io.ByteArrayOutputStream()
                        var matched = 0
                        while (true) {
                            val b = input.read(); if (b == -1) return@use
                            hdrBuf.write(b)
                            matched = when {
                                matched == 0 && b == '\r'.code -> 1
                                matched == 1 && b == '\n'.code -> 2
                                matched == 2 && b == '\r'.code -> 3
                                matched == 3 && b == '\n'.code -> 4
                                b == '\r'.code -> 1
                                else -> 0
                            }
                            if (matched == 4) break
                        }
                        val lines = hdrBuf.toString("UTF-8").split("\r\n")
                        val path = lines.firstOrNull()?.split(' ')?.getOrNull(1).orEmpty()
                        var auth = ""
                        var contentLen = 0
                        for (line in lines.drop(1)) {
                            if (line.startsWith("Authorization:", ignoreCase = true)) auth = line.substringAfter(':').trim()
                            if (line.startsWith("Content-Length:", ignoreCase = true)) contentLen = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                        if (contentLen > 0) {
                            val buf = ByteArray(contentLen)
                            var r = 0
                            while (r < contentLen) { val n = input.read(buf, r, contentLen - r); if (n <= 0) break; r += n }
                        }
                        val out = sock.getOutputStream()
                        if (auth.isEmpty()) {
                            authChallengeHits++
                            val resp = "HTTP/1.1 401 Unauthorized\r\nServer: webs\r\n" +
                                """WWW-Authenticate: Digest realm="DS-K1T320MX", qop="auth", nonce="testnonce123"""" + "\r\n" +
                                "Content-Length: 0\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray()); out.flush(); return@use
                        }
                        authorizedHits++
                        handledPaths.add(path)
                        val body = when {
                            path.startsWith("/ISAPI/System/deviceInfo") ->
                                """<DeviceInfo><model>DS-K1T320MX</model><deviceName>Cong 1</deviceName></DeviceInfo>"""
                            path.startsWith("/ISAPI/AccessControl/AcsEvent") ->
                                """{"AcsEvent":{"totalMatches":1,"InfoList":[{"major":5,"minor":75,"time":"2026-11-21T08:30:00+07:00","employeeNoString":"7788","name":"Lê Hoàng Nam","serialNo":42}]}}"""
                            path.startsWith("/ISAPI/AccessControl/UserInfo/Search") ->
                                """{"UserInfoSearch":{"totalMatches":1,"UserInfo":[{"employeeNo":"7788","name":"Lê Hoàng Nam"}]}}"""
                            else -> """{"statusCode":1,"statusString":"OK"}"""
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        out.write(hdr.toByteArray()); out.write(bytes); out.flush()
                    }
                }
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            val gw = HikvisionGatewayImpl()
            // 1. Dò không cần mật khẩu nhận đúng Hikvision ISAPI
            assertEquals("Hikvision ISAPI", gw.probeHikvisionHost("127.0.0.1", port))

            // 2. Kết nối có Digest Auth + tự đồng bộ giờ VN
            val dev = DeviceConfig(ip = "127.0.0.1", port = port, protocol = DeviceProtocol.HIKVISION_ISAPI, username = "admin", accessPassword = "Admin123")
            val conn = gw.connect(dev, timeoutMs = 4_000)
            assertTrue("Phải nối OK nhưng nhận: ${conn.message}", conn.isSuccess)
            val handle = conn.data!!.handle
            assertEquals("ISAPI/DS-K1T320MX", conn.data!!.transportName)

            // 3. Nhận webhook tức thì + đọc AcsEvent kèm chống trùng serialNo
            assertTrue(gw.onWebhookEvent("127.0.0.1", """{"ipAddress":"127.0.0.1","dateTime":"2026-11-21T08:30:00+07:00","AccessControllerEvent":{"majorEventType":5,"subEventType":75,"employeeNoString":"7788","name":"Lê Hoàng Nam","serialNo":42}}"""))
            val logsRes = gw.loadGeneralLogData(handle, readMark = true)
            assertTrue(logsRes.isSuccess)
            assertEquals(1, logsRes.data!!.size) // Trùng serialNo=42 giữa webhook và poll được gộp thành đúng 1 lượt!
            assertEquals("7788", logsRes.data!![0].cardIdString)
            assertEquals("Lê Hoàng Nam", gw.getUserName(handle, 7788).data)

            // 4. Đọc nhân sự, nạp nhân sự tiếng Việt & xoá 1 lệnh duy nhất
            assertEquals(1, gw.readAllUserIds(handle).data!!.size)
            assertTrue(gw.putCardEnrollData(handle, 9001, 9001).isSuccess)
            assertTrue(gw.setUserName(handle, 9001, "Phạm Thu Hà").isSuccess)
            assertEquals("Phạm Thu Hà", gw.getUserName(handle, 9001).data)
            assertTrue(gw.deleteEnrollData(handle, 9001, 0).isSuccess)
            assertEquals(FkErrorCode.ERR_NO_MORE_DATA, gw.deleteEnrollData(handle, 9001, 1).code)

            gw.disconnect(handle)
            // Nhờ cache Digest nonce, chỉ cần <= 2 lượt challenge 401 ban đầu cho toàn bộ >= 6 lệnh ISAPI kế tiếp!
            assertTrue("Mong đợi cache nonce giảm 401 xuống <=3 nhưng là $authChallengeHits (auth=$authorizedHits)", authChallengeHits in 1..3 && authorizedHits >= 6)
            assertTrue(handledPaths.any { it.startsWith("/ISAPI/System/time") })
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun connectDeviceUseCase_stopsImmediatelyOnHikvision401ToPreventLockout() = runTest {
        var attempts = 0
        val rejectingGateway = object : FakeProtocolGateway(HikvisionGatewayImpl.HANDLE_OFFSET, FkFirmwareProfile.HIKVISION_ISAPI) {
            override suspend fun connect(device: DeviceConfig, timeoutMs: Int): FkResult<FkConnectionInfo> {
                attempts++
                return FkResult.fail(FkErrorCode.ERR_BAD_PARAM, "Sai mật khẩu Hikvision (401)")
            }
        }
        val useCase = ConnectDeviceUseCase(rejectingGateway, ClearDeviceLogsUseCase(rejectingGateway))
        val hikDev = DeviceConfig(ip = "192.168.1.64", port = 80, protocol = DeviceProtocol.HIKVISION_ISAPI, accessPassword = "wrong")
        val res = useCase(hikDev, maxAttempts = 5, retryDelayMs = 1L)
        assertEquals(FkErrorCode.ERR_BAD_PARAM, res.code)
        assertEquals(1, attempts)
    }

    private open class FakeProtocolGateway(
        private val baseHandle: Int,
        private val profile: FkFirmwareProfile,
    ) : FkDeviceGateway {
        private var nextHandle = baseHandle
        val writtenNames = mutableMapOf<Int, String>()

        override suspend fun connect(
            machineNo: Int, ip: String, port: Int, timeoutMs: Int, netPassword: Int, license: Int, useUdp: Boolean,
        ): FkResult<FkConnectionInfo> = FkResult.ok(FkConnectionInfo(nextHandle++, ip, port, machineNo, profile, "TCP", 30))

        override suspend fun connect(device: DeviceConfig, timeoutMs: Int): FkResult<FkConnectionInfo> =
            FkResult.ok(FkConnectionInfo(nextHandle++, device.ip, device.port, device.machineNo, profile, "ISAPI", 30))

        override suspend fun probeHikvisionHost(ip: String, port: Int): String? =
            if (profile == FkFirmwareProfile.HIKVISION_ISAPI) "Hikvision DS-K1T320MX" else null

        override suspend fun disconnect(handle: Int) = Unit
        override suspend fun disconnectAll() = Unit
        override suspend fun enableDevice(handle: Int, enable: Boolean) = FkResult.ok(Unit)
        override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean): FkResult<List<AttendanceLog>> =
            FkResult.fail(FkErrorCode.ERR_LOG_END)
        override suspend fun emptyGeneralLogData(handle: Int) = FkResult.ok(Unit)
        override suspend fun readAllUserIds(handle: Int) = FkResult.ok(emptyList<FkUserCredential>())
        override suspend fun putCardEnrollData(handle: Int, enrollNumber: Int, cardNumber: Int, privilege: Int) = FkResult.ok(Unit)
        override suspend fun saveEnrollData(handle: Int) = FkResult.ok(Unit)
        override suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int) = FkResult.ok(Unit)
        override suspend fun getUserName(handle: Int, enrollNumber: Int) = FkResult.ok(writtenNames[enrollNumber].orEmpty())
        override suspend fun setUserName(handle: Int, enrollNumber: Int, name: String): FkResult<Unit> {
            writtenNames[enrollNumber] = name
            return FkResult.ok(Unit)
        }
        override fun getConnectionInfo(handle: Int): FkConnectionInfo? = null
    }
}

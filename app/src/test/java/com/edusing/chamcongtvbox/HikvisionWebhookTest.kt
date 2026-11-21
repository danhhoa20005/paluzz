package com.edusing.chamcongtvbox

import com.edusing.chamcongtvbox.data.hikvision.HikvisionControlApi
import com.edusing.chamcongtvbox.data.hikvision.HikvisionGatewayImpl
import com.edusing.chamcongtvbox.data.hikvision.HikvisionIsapiClient
import com.edusing.chamcongtvbox.data.hikvision.HikvisionIsapiParser
import com.edusing.chamcongtvbox.data.hikvision.HikvisionWebhookApi
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.HikvisionWebhookConfig
import com.edusing.chamcongtvbox.service.web.HttpRequestBodyReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class HikvisionWebhookTest {

    @Test
    fun `HttpRequestBodyReader handles Expect 100-continue and chunked multipart Hikvision webhook`() {
        val jsonPart = """{"ipAddress":"192.168.1.8","dateTime":"2026-11-21T08:15:30+07:00","eventType":"AccessControllerEvent","AccessControllerEvent":{"majorEventType":5,"subEventType":75,"employeeNoString":"001","name":"Nguyễn Văn Hùng","serialNo":409}}"""
        val part1 = ("--MIME_boundary\r\nContent-Disposition: form-data; name=\"event_log\"\r\nContent-Type: application/json\r\n\r\n$jsonPart\r\n").toByteArray(Charsets.UTF_8)
        val part2 = ("--MIME_boundary\r\nContent-Disposition: form-data; name=\"Picture\"\r\nContent-Type: image/jpeg\r\n\r\nFAKE_JPEG_DATA\r\n--MIME_boundary--\r\n").toByteArray(Charsets.UTF_8)

        val rawReq = ByteArrayOutputStream().apply {
            write("POST /hikvision/event HTTP/1.1\r\nHost: 192.168.1.50:8080\r\nExpect: 100-continue\r\nTransfer-Encoding: chunked\r\nContent-Type: multipart/form-data; boundary=MIME_boundary\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write("${part1.size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII)); write(part1); write("\r\n".toByteArray(Charsets.US_ASCII))
            write("${part2.size.toString(16)};ext=pic\r\n".toByteArray(Charsets.US_ASCII)); write(part2); write("\r\n".toByteArray(Charsets.US_ASCII))
            write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        }.toByteArray()

        val responseOut = ByteArrayOutputStream()
        val parsed = HttpRequestBodyReader.parseRequest(BufferedInputStream(ByteArrayInputStream(rawReq)), responseOut)
        assertNotNull(parsed)
        assertEquals("HTTP/1.1 100 Continue\r\n\r\n", responseOut.toString(Charsets.US_ASCII.name()))
        assertEquals("POST", parsed!!.method)
        assertEquals("/hikvision/event", parsed.path)

        val ev = HikvisionIsapiParser.parseWebhookPayload(parsed.body, "192.168.1.8", 1_700_000_000_000L)
        assertNotNull(ev)
        assertEquals("001", ev!!.employeeNo)
        assertEquals("Nguyễn Văn Hùng", ev.employeeName)
        assertEquals(75, ev.minor)
        assertEquals(409, ev.serialNo)
    }

    @Test
    fun `HttpRequestBodyReader drains oversize Content-Length without dropping leading JSON`() {
        val json = """{"dateTime":"2026-11-21T09:00:00+07:00","AccessControllerEvent":{"majorEventType":5,"subEventType":1,"employeeNoString":"NV09","name":"Trần An","serialNo":55}}"""
        val bodyBytes = json.toByteArray(Charsets.UTF_8) + ByteArray(160_000) { 'X'.code.toByte() }
        val raw = "POST /hikvision/event HTTP/1.1\r\nContent-Length: ${bodyBytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII) + bodyBytes

        val input = BufferedInputStream(ByteArrayInputStream(raw))
        val req = HttpRequestBodyReader.parseRequest(input, ByteArrayOutputStream())
        assertNotNull(req)
        assertEquals(0, input.available()) // Đã xả gọn toàn bộ phần dư ảnh

        val ev = HikvisionIsapiParser.parseWebhookPayload(req!!.body, "192.168.1.9", 0L)
        assertNotNull(ev)
        assertEquals("NV09", ev!!.employeeNo)
    }

    @Test
    fun `parseWebhookXml supports IP HTTP, hostname HTTPS, and disabled slot`() {
        val ipXml = """<HttpHostNotification><id>1</id><url>/api/attendance/webhook/TEST-A</url><protocolType>HTTP</protocolType><addressingFormatType>ipaddress</addressingFormatType><ipAddress>192.168.1.200</ipAddress><portNo>5000</portNo></HttpHostNotification>"""
        val ipCfg = HikvisionWebhookApi.parseWebhookXml(ipXml)
        assertTrue(ipCfg.enabled)
        assertEquals("http://192.168.1.200:5000/api/attendance/webhook/TEST-A", ipCfg.summaryUrl)

        val hostXml = """<HttpHostNotification><id>1</id><url>/events/hik</url><protocolType>HTTPS</protocolType><addressingFormatType>hostname</addressingFormatType><hostName>hook.example.com</hostName><portNo>443</portNo></HttpHostNotification>"""
        val hostCfg = HikvisionWebhookApi.parseWebhookXml(hostXml)
        assertTrue(hostCfg.enabled)
        assertEquals("HTTPS", hostCfg.protocolType)
        assertEquals("hostname", hostCfg.addressingType)
        assertEquals("https://hook.example.com:443/events/hik", hostCfg.summaryUrl)

        val offXml = """<HttpHostNotification><id>1</id><url></url><protocolType>HTTP</protocolType><addressingFormatType>ipaddress</addressingFormatType><ipAddress>0.0.0.0</ipAddress><portNo>0</portNo></HttpHostNotification>"""
        assertFalse(HikvisionWebhookApi.parseWebhookXml(offXml).enabled)
    }

    @Test
    fun `configureWebhook uses SubscribeEvent, falls back on old firmware, and clearWebhook resets slot`() = runBlocking {
        val capturedBodies = mutableListOf<String>()
        var rejectNextSubscribe = false
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    sock.use { s ->
                        val input = BufferedInputStream(s.getInputStream())
                        val hdr = ByteArrayOutputStream()
                        var m = 0
                        while (true) {
                            val b = input.read(); if (b == -1) return@use; hdr.write(b)
                            m = when { m == 0 && b == 13 -> 1; m == 1 && b == 10 -> 2; m == 2 && b == 13 -> 3; m == 3 && b == 10 -> 4; b == 13 -> 1; else -> 0 }
                            if (m == 4) break
                        }
                        val headerText = hdr.toString(Charsets.UTF_8.name())
                        val len = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(headerText)?.groupValues?.get(1)?.toInt() ?: 0
                        val bodyBytes = ByteArray(len)
                        var r = 0; while (r < len) { val n = input.read(bodyBytes, r, len - r); if (n <= 0) break; r += n }
                        val body = String(bodyBytes, 0, r, Charsets.UTF_8)
                        val first = headerText.lineSequence().first()
                        val hasAuth = headerText.contains("Authorization: Digest", ignoreCase = true)

                        val (code, resp) = when {
                            !hasAuth -> 401 to ""
                            first.startsWith("GET /ISAPI/Event/notification/httpHosts/1") ->
                                200 to """<HttpHostNotification><id>1</id><url>/hikvision/event</url><protocolType>HTTP</protocolType><addressingFormatType>ipaddress</addressingFormatType><ipAddress>192.168.1.50</ipAddress><portNo>8080</portNo></HttpHostNotification>"""
                            first.startsWith("PUT /ISAPI/Event/notification/httpHosts/1") -> {
                                capturedBodies.add(body)
                                if (rejectNextSubscribe && body.contains("<SubscribeEvent>")) {
                                    rejectNextSubscribe = false
                                    400 to """<ResponseStatus><subStatusCode>badXmlContent</subStatusCode></ResponseStatus>"""
                                } else {
                                    200 to """<ResponseStatus><statusCode>1</statusCode></ResponseStatus>"""
                                }
                            }
                            else -> 404 to ""
                        }
                        val extra = if (code == 401) "WWW-Authenticate: Digest realm=\"DS\", nonce=\"abc123\", qop=\"auth\"\r\n" else ""
                        val outBytes = resp.toByteArray(Charsets.UTF_8)
                        s.getOutputStream().write("HTTP/1.1 $code OK\r\n${extra}Content-Length: ${outBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        s.getOutputStream().write(outBytes); s.getOutputStream().flush()
                    }
                }
            }
        }
        try {
            val ctrl = HikvisionControlApi(HikvisionIsapiClient())
            val dev = DeviceConfig(
                ip = "127.0.0.1", port = server.localPort, machineNo = 1, name = "MockHik",
                protocol = DeviceProtocol.HIKVISION_ISAPI, username = "admin", accessPassword = "testpass",
            )

            val got = ctrl.fetchWebhookConfig(dev).getOrThrow()
            assertTrue(got.enabled)
            assertEquals("192.168.1.50", got.hostOrIp)

            // 1. Firmware mới nhận đủ <SubscribeEvent> với IP LAN
            val okIp = ctrl.configureWebhook(dev, HikvisionWebhookConfig(true, "HTTP", "ipaddress", "192.168.1.60", 8080, "/hikvision/event"))
            assertTrue(okIp.isSuccess)
            assertTrue(capturedBodies.last().contains("<ipAddress>192.168.1.60</ipAddress>"))
            assertTrue(capturedBodies.last().contains("<SubscribeEvent>"))

            // 2. Firmware cũ từ chối <SubscribeEvent> -> tự fallback sang XML gọn + hostname HTTPS
            rejectNextSubscribe = true
            val okHost = ctrl.configureWebhook(dev, HikvisionWebhookConfig(true, "HTTPS", "hostname", "api.example.com", 443, "hook/in"))
            assertTrue(okHost.isSuccess)
            assertTrue(capturedBodies.last().contains("<hostName>api.example.com</hostName>"))
            assertTrue(capturedBodies.last().contains("<url>/hook/in</url>"))
            assertFalse(capturedBodies.last().contains("<SubscribeEvent>"))

            // 3. Tắt Webhook
            assertTrue(ctrl.clearWebhook(dev).isSuccess)
            assertTrue(capturedBodies.last().contains("<ipAddress>0.0.0.0</ipAddress>"))
            assertTrue(capturedBodies.last().contains("<portNo>0</portNo>"))
        } finally {
            server.close(); worker.join(1_000)
        }
    }

    @Test
    fun `HikvisionGatewayImpl tracks webhook attendance events and heartbeats accurately`() {
        var fakeNow = 1_760_000_000_000L
        val gw = HikvisionGatewayImpl(clockMillis = { fakeNow })

        // Heartbeat XML -> ghi nhận telemetry nhưng trả false để không gọi poll dư
        val hbOk = gw.onWebhookEvent(
            "::ffff:192.168.1.8",
            "<EventNotificationAlert><eventType>heartBeat</eventType></EventNotificationAlert>",
        )
        assertFalse(hbOk)
        assertEquals(0, gw.webhookStats().eventCount)
        assertEquals(1, gw.webhookStats().heartbeatCount)
        assertEquals("192.168.1.8", gw.webhookStats().lastSourceIp)
        assertEquals("Heartbeat OK", gw.webhookStats().lastSummary)

        // Sự kiện quét mặt thực -> tăng eventCount & lưu summary
        fakeNow += 5_000L
        val evJson = """{"dateTime":"2026-11-21T08:30:00+07:00","AccessControllerEvent":{"majorEventType":5,"subEventType":75,"employeeNoString":"007","name":"Lê Minh","serialNo":91}}"""
        gw.onWebhookEvent("::ffff:192.168.1.8", evJson)
        val stats = gw.webhookStats()
        assertEquals(1, stats.eventCount)
        assertEquals(1, stats.heartbeatCount)
        assertEquals(fakeNow, stats.lastReceivedAtMs)
        assertEquals("#007 Lê Minh", stats.lastSummary)
    }
}

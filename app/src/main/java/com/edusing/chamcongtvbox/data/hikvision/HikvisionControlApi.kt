package com.edusing.chamcongtvbox.data.hikvision

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceUser
import com.edusing.chamcongtvbox.domain.model.HikvisionDoorCommand
import com.edusing.chamcongtvbox.domain.model.HikvisionHealthStatus
import com.edusing.chamcongtvbox.domain.model.HikvisionHistoryItem
import com.edusing.chamcongtvbox.domain.model.HikvisionWebhookConfig
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Cài đặt trọn bộ tính năng phần cứng chuyên sâu cho Hikvision `DS-K1T320MX` (`V3.9.50+`):
 * sức khoẻ phần cứng, mở cửa, khoá IP tĩnh, nạp ảnh khuôn mặt AI (`FDLib`), chụp Camera, lịch sử & tự định vị IP.
 */
internal class HikvisionControlApi(
    private val client: HikvisionIsapiClient = HikvisionIsapiClient(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : HikvisionControlGateway {

    private val enrollment = HikvisionEnrollmentApi(client)
    private val webhookApi = HikvisionWebhookApi(client)
    private val avatarClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    override suspend fun fetchHealth(device: DeviceConfig): Result<HikvisionHealthStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val (infoRes, info) = client.fetchDeviceInfo(device, timeoutMs = 5_000)
            require(infoRes.isSuccess && info != null) { "Không đọc được thiết bị (HTTP ${infoRes.statusCode})" }

            val userCountJson = runCatching { JSONObject(client.request(device, "GET", "/ISAPI/AccessControl/UserInfo/Count?format=json").body).optJSONObject("UserInfoCount") }.getOrNull()
            val cardCountJson = runCatching { JSONObject(client.request(device, "GET", "/ISAPI/AccessControl/CardInfo/Count?format=json").body).optJSONObject("CardInfoCount") }.getOrNull()
            val fdCapJson = runCatching { JSONObject(client.request(device, "GET", "/ISAPI/Intelligent/FDLib/capabilities?format=json").body) }.getOrNull()
            val workJson = runCatching { JSONObject(client.request(device, "GET", "/ISAPI/AccessControl/AcsWorkStatus?format=json").body).optJSONObject("AcsWorkStatus") }.getOrNull()
            val ipXml = runCatching { client.request(device, "GET", "/ISAPI/System/Network/interfaces/1/ipAddress").body }.getOrDefault("")
            val timeXml = runCatching { client.request(device, "GET", "/ISAPI/System/time").body }.getOrDefault("")

            val doorLockArr = workJson?.optJSONArray("doorLockStatus")
            val doorLocked = (doorLockArr?.optInt(0, 0) ?: 0) == 0
            val tamper = workJson?.optString("hostAntiDismantleStatus", "").equals("open", ignoreCase = true)
            val addrType = xmlTag(ipXml, "addressingType")
            val maxFaces = fdCapJson?.optInt("FDRecordDataMaxNum", 0)?.takeIf { it > 0 } ?: 500

            HikvisionHealthStatus(
                model = info.model,
                serialNumber = info.serialNumber,
                firmwareVersion = info.firmwareVersion,
                userCount = userCountJson?.optInt("userNumber", 0) ?: 0,
                faceCount = userCountJson?.optInt("bindFaceUserNumber", 0) ?: 0,
                cardCount = cardCountJson?.optInt("cardNumber", userCountJson?.optInt("bindCardUserNumber", 0) ?: 0) ?: 0,
                maxFaceCapacity = maxFaces,
                doorLocked = doorLocked,
                antiDismantleAlert = tamper,
                isDhcp = addrType.equals("dynamic", ignoreCase = true),
                ipAddress = xmlTag(ipXml, "ipAddress").ifBlank { device.ip },
                subnetMask = xmlTag(ipXml, "subnetMask"),
                gateway = xmlTag(ipXml, "DefaultGateway").let { if (it.isBlank()) xmlTag(ipXml, "ipAddress") else xmlTag(it, "ipAddress") },
                deviceTimeText = xmlTag(timeXml, "localTime"),
            )
        }
    }

    override suspend fun controlDoor(device: DeviceConfig, command: HikvisionDoorCommand, doorNo: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val xml = """<?xml version="1.0" encoding="UTF-8"?><RemoteControlDoor version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema"><cmd>${command.isapiCmd}</cmd></RemoteControlDoor>"""
            val res = client.request(device, "PUT", "/ISAPI/AccessControl/RemoteControl/door/$doorNo", xml, HikvisionIsapiClient.XML_MEDIA, 5_000)
            require(res.isSuccess) { "Điều khiển cửa lỗi HTTP ${res.statusCode} ${HikvisionIsapiParser.extractErrorDetail(res.body)}" }
            "Đã gửi lệnh ${command.label} tới ${device.displayName}"
        }
    }

    override suspend fun syncVietnamTime(device: DeviceConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val now = clockMillis()
            val res = client.syncVietnamTime(device, now)
            require(res.isSuccess) { "Đồng bộ giờ lỗi HTTP ${res.statusCode}" }
            "Đã đồng bộ giờ Việt Nam (${HikvisionIsapiParser.formatVietnamIsoTime(now, false)})"
        }
    }

    override suspend fun lockStaticIp(device: DeviceConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val getRes = client.request(device, "GET", "/ISAPI/System/Network/interfaces/1/ipAddress", timeoutMs = 5_000)
            require(getRes.isSuccess) { "Không đọc được cấu hình IP (HTTP ${getRes.statusCode})" }
            val currentXml = getRes.body
            val ip = xmlTag(currentXml, "ipAddress").ifBlank { device.ip }
            if (!xmlTag(currentXml, "addressingType").equals("dynamic", ignoreCase = true)) {
                return@runCatching "Máy $ip vốn đã ở chế độ IP tĩnh (Static)."
            }
            val updatedXml = currentXml.replace(Regex("<addressingType>[^<]*</addressingType>", RegexOption.IGNORE_CASE), "<addressingType>static</addressingType>")
            val putRes = client.request(device, "PUT", "/ISAPI/System/Network/interfaces/1/ipAddress", updatedXml, HikvisionIsapiClient.XML_MEDIA, 6_000)
            require(putRes.isSuccess) { "Khoá IP tĩnh lỗi HTTP ${putRes.statusCode} ${HikvisionIsapiParser.extractErrorDetail(putRes.body)}" }
            "Đã khoá cố định IP tĩnh $ip (đã tắt DHCP)."
        }
    }

    override suspend fun rebootDevice(device: DeviceConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = client.request(device, "PUT", "/ISAPI/System/reboot", body = "", contentType = HikvisionIsapiClient.XML_MEDIA, timeoutMs = 5_000)
            require(res.isSuccess) { "Khởi động lại thất bại (HTTP ${res.statusCode})" }
            "Đã gửi lệnh khởi động lại ${device.displayName} (máy sẽ online lại sau ~30 giây)."
        }
    }

    override suspend fun captureSnapshot(device: DeviceConfig): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, bytes) = client.requestBytes(device, "GET", "/ISAPI/Streaming/channels/101/picture", timeoutMs = 6_000)
            require(code in 200..299 && bytes.size > 512) { "Không chụp được ảnh camera (HTTP $code)" }
            bytes
        }
    }

    override suspend fun uploadFacePhoto(device: DeviceConfig, employeeNo: String, jpegBytes: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(employeeNo.isNotBlank()) { "Thiếu mã nhân sự" }
            require(jpegBytes.size in 512..MAX_FACE_BYTES) { "Ảnh mặt phải từ 0.5 KB đến 250 KB (hiện ${jpegBytes.size / 1024} KB)" }
            val delBody = JSONObject().put("FPID", JSONArray().put(JSONObject().put("value", employeeNo))).toString()
            runCatching {
                client.request(device, "PUT", "/ISAPI/Intelligent/FDLib/FDSearch/Delete?format=json&FDID=1&faceLibType=blackFD", delBody)
            }
            val (contentType, payload) = buildFaceMultipart(employeeNo, jpegBytes)
            var (code, respBytes) = client.requestBytes(device, "POST", "/ISAPI/Intelligent/FDLib/FaceDataRecord?format=json", payload, contentType, 15_000)
            if (code !in 200..299 && code != 401) {
                val fb = client.requestBytes(device, "PUT", "/ISAPI/Intelligent/FDLib/FDSetUp?format=json", payload, contentType, 15_000)
                code = fb.first; respBytes = fb.second
            }
            val respText = String(respBytes, Charsets.UTF_8)
            require(code in 200..299) {
                val detail = HikvisionIsapiParser.extractErrorDetail(respText)
                "Nạp ảnh mặt lỗi HTTP $code${if (detail.isNotBlank()) " ($detail — chụp rõ chính diện, đủ sáng)" else ""}"
            }
            "Đã nạp ảnh khuôn mặt AI thành công cho mã #$employeeNo"
        }
    }

    override suspend fun upsertUserWithOptionalFace(
        device: DeviceConfig, employeeNo: String, fullName: String, cardNo: String, jpegBytes: ByteArray?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanNo = employeeNo.trim()
            require(cleanNo.isNotEmpty()) { "Vui lòng nhập mã nhân sự / mã thẻ" }
            val userRes = enrollment.upsertUserAndCard(device, cleanNo, fullName.trim(), cardNo.trim())
            require(userRes.isSuccess) { "Nạp hồ sơ lỗi HTTP ${userRes.statusCode} ${HikvisionIsapiParser.extractErrorDetail(userRes.body)}" }
            if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                val faceRes = uploadFacePhoto(device, cleanNo, jpegBytes)
                if (faceRes.isFailure) throw faceRes.exceptionOrNull()!!
                "Đã tạo hồ sơ + ảnh khuôn mặt AI cho $fullName (#$cleanNo)"
            } else {
                "Đã nạp hồ sơ $fullName (#$cleanNo) xuống máy ${device.displayName}"
            }
        }
    }

    override suspend fun fetchDetailedUsers(device: DeviceConfig): Result<List<DeviceUser>> = withContext(Dispatchers.IO) {
        runCatching {
            val (res, users) = enrollment.fetchAllUsers(device, clockMillis())
            require(res.isSuccess) { "Đọc danh sách Hikvision lỗi HTTP ${res.statusCode}" }
            users.map { u ->
                DeviceUser(
                    enrollNumber = u.employeeNo.toIntOrNull() ?: (u.employeeNo.hashCode() and 0x7FFFFFFF),
                    deviceName = u.name,
                    rawEmployeeNo = u.employeeNo,
                    faceCount = u.numOfFace,
                    cardCount = u.numOfCard,
                )
            }
        }
    }

    override suspend fun fetchHistoryEvents(device: DeviceConfig, sinceMs: Long, untilMs: Long): Result<List<HikvisionHistoryItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val (res, events) = client.fetchAccessEvents(device, sinceMs, untilMs, clockMillis(), maxPages = 15)
            require(res.isSuccess) { "Đọc lịch sử chấm công lỗi HTTP ${res.statusCode}" }
            events.distinctBy { it.eventKey }.sortedByDescending { it.epochMs }.map { e ->
                HikvisionHistoryItem(
                    timeText = "%02d:%02d:%02d %02d/%02d/%04d".format(e.hour, e.minute, e.second, e.day, e.month, e.year),
                    epochMs = e.epochMs,
                    employeeNo = e.employeeNo,
                    name = e.employeeName,
                    verifyMethod = describeMinor(e.minor),
                    serialNo = e.serialNo,
                )
            }
        }
    }

    override suspend fun deleteUserByEmployeeNo(device: DeviceConfig, employeeNo: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val res = enrollment.deleteUser(device, employeeNo.trim())
            require(res.isSuccess) { "Xoá #$employeeNo lỗi HTTP ${res.statusCode}" }
        }
    }

    override suspend fun fetchSerialNumber(device: DeviceConfig, timeoutMs: Int): String? = withContext(Dispatchers.IO) {
        runCatching { client.fetchDeviceInfo(device, timeoutMs).second?.serialNumber?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    override suspend fun downloadAvatarBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return@withContext null
        runCatching {
            avatarClient.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val bytes = res.body?.bytes() ?: return@use null
                if (bytes.size in 512..MAX_FACE_BYTES) bytes else null
            }
        }.getOrNull()
    }

    override suspend fun fetchWebhookConfig(device: DeviceConfig): Result<HikvisionWebhookConfig> = webhookApi.fetchWebhookConfig(device)
    override suspend fun configureWebhook(device: DeviceConfig, config: HikvisionWebhookConfig): Result<String> = webhookApi.configureWebhook(device, config)
    override suspend fun clearWebhook(device: DeviceConfig): Result<String> = webhookApi.clearWebhook(device)

    private fun buildFaceMultipart(employeeNo: String, jpegBytes: ByteArray): Pair<String, ByteArray> {
        val boundary = "----HikvisionMinMoeBoundary7e9a3c1d"
        val jsonPart = JSONObject().put("faceLibType", "blackFD").put("FDID", "1").put("FPID", employeeNo).toString()
        val out = ByteArrayOutputStream(jpegBytes.size + 512)
        fun write(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        write("--$boundary\r\nContent-Disposition: form-data; name=\"FaceDataRecord\"\r\nContent-Type: application/json\r\n\r\n$jsonPart\r\n")
        write("--$boundary\r\nContent-Disposition: form-data; name=\"FaceImage\"; filename=\"face.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
        out.write(jpegBytes)
        write("\r\n--$boundary--\r\n")
        return "multipart/form-data; boundary=$boundary" to out.toByteArray()
    }

    private fun describeMinor(minor: Int): String = when (minor) {
        75, 77, 78, 79, 80 -> "Khuôn mặt"
        38, 40, 43 -> "Vân tay"
        1, 2 -> "Thẻ từ"
        25, 105 -> "Mật khẩu"
        104 -> "Mã QR"
        112 -> "Mống mắt"
        else -> "Xác thực #$minor"
    }

    private fun xmlTag(xml: String, tag: String): String =
        Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    companion object {
        private const val MAX_FACE_BYTES = 260_000
    }
}

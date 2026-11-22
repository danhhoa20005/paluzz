package com.edusing.chamcongtvbox.data.hikvision

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Xử lý sinh trắc học khuôn mặt (`FDLib`) & thẻ Mifare (`CardInfo`) chuẩn Hikvision MinMoe (`DS-K1T320MX` / `MFWX`):
 * - Tự chuẩn hoá ảnh JPEG (`≤ 640px`, `< 195 KB`), ưu tiên `FDSetUp` và fallback `FaceDataRecord`, kiểm tra cả `statusCode` JSON.
 * - Tải ảnh khuôn mặt gốc từ máy nguồn (`FDSearch` → `faceURL`) để nhân bản sang nhiều máy Hikvision khác.
 */
internal class HikvisionFaceSyncApi(
    private val client: HikvisionIsapiClient,
) {
    fun uploadFacePhoto(device: DeviceConfig, employeeNo: String, rawImageBytes: ByteArray): Result<String> = runCatching {
        val cleanNo = employeeNo.trim()
        require(cleanNo.isNotEmpty()) { "Thiếu mã nhân sự" }
        val jpegBytes = HikvisionFaceNormalizer.normalizeToHikvisionJpeg(rawImageBytes)
            ?: throw IllegalArgumentException("Ảnh không hợp lệ hoặc vượt quá 200 KB (cần ảnh JPEG/PNG rõ khuôn mặt)")

        val delBody = JSONObject().put("FPID", JSONArray().put(JSONObject().put("value", cleanNo))).toString()
        runCatching {
            client.request(device, "PUT", "/ISAPI/Intelligent/FDLib/FDSearch/Delete?format=json&FDID=1&faceLibType=blackFD", delBody)
        }

        val (contentType, payload) = buildFaceMultipart(cleanNo, jpegBytes)
        // Ưu tiên FDSetUp (chuẩn V3.5–V3.9+ hỗ trợ upsert mượt); fallback sang FaceDataRecord cho firmware đời cũ
        var (code, respBytes) = client.requestBytes(device, "PUT", "/ISAPI/Intelligent/FDLib/FDSetUp?format=json", payload, contentType, 15_000)
        var respText = String(respBytes, Charsets.UTF_8)
        if ((!isOk(code, respText)) && code != 401) {
            val fb = client.requestBytes(device, "POST", "/ISAPI/Intelligent/FDLib/FaceDataRecord?format=json", payload, contentType, 15_000)
            code = fb.first; respBytes = fb.second; respText = String(respBytes, Charsets.UTF_8)
        }
        require(isOk(code, respText)) {
            val detail = HikvisionIsapiParser.extractErrorDetail(respText)
            "Nạp ảnh mặt lỗi HTTP $code${if (detail.isNotBlank()) " ($detail)" else " (chụp rõ chính diện, đủ sáng)"}"
        }
        "Đã nạp ảnh khuôn mặt AI (${jpegBytes.size / 1024} KB) cho mã #$cleanNo"
    }

    /** Tải ảnh khuôn mặt gốc đang lưu trên máy Hikvision nguồn (`FDSearch` → `faceURL`) để sao chép sang máy khác. */
    fun downloadFacePhoto(device: DeviceConfig, employeeNo: String): Result<ByteArray> = runCatching {
        val cleanNo = employeeNo.trim()
        require(cleanNo.isNotEmpty()) { "Thiếu mã nhân sự" }
        val payload = JSONObject()
            .put("searchResultPosition", 0)
            .put("maxResults", 1)
            .put("faceLibType", "blackFD")
            .put("FDID", "1")
            .put("FPID", cleanNo)
            .toString()
        val searchRes = client.request(device, "POST", "/ISAPI/Intelligent/FDLib/FDSearch?format=json", payload, timeoutMs = 8_000)
        require(searchRes.isSuccess && !HikvisionIsapiParser.isIsapiBodyError(searchRes.body)) {
            "Tìm khuôn mặt #$cleanNo lỗi HTTP ${searchRes.statusCode} ${HikvisionIsapiParser.extractErrorDetail(searchRes.body)}"
        }
        val faceUrl = HikvisionIsapiParser.parseFaceUrl(searchRes.body)
            ?: throw IllegalStateException("Chưa có ảnh khuôn mặt cho mã #$cleanNo trên ${device.displayName}")
        val (code, bytes) = client.downloadFaceUrlBytes(device, faceUrl, timeoutMs = 10_000)
        require(code in 200..299 && bytes.size >= 512) { "Không tải được ảnh khuôn mặt #$cleanNo (HTTP $code)" }
        bytes
    }

    /** Đọc danh sách mã thẻ Mifare gắn với nhân sự trên máy Hikvision để đồng bộ đầy đủ sang máy khác. */
    fun fetchUserCards(device: DeviceConfig, employeeNo: String): List<String> = runCatching {
        val payload = JSONObject().put(
            "CardInfoSearchCond",
            JSONObject()
                .put("searchID", "card_${employeeNo}_${System.currentTimeMillis() % 10000}")
                .put("searchResultPosition", 0)
                .put("maxResults", 10)
                .put("EmployeeNoList", JSONArray().put(JSONObject().put("employeeNo", employeeNo.trim()))),
        ).toString()
        val res = client.request(device, "POST", "/ISAPI/AccessControl/CardInfo/Search?format=json", payload, timeoutMs = 5_000)
        if (!res.isSuccess) emptyList() else HikvisionIsapiParser.parseCardNumbers(res.body)
    }.getOrDefault(emptyList())

    private fun isOk(httpCode: Int, respText: String): Boolean =
        httpCode in 200..299 && !HikvisionIsapiParser.isIsapiBodyError(respText)

    companion object {
        fun buildFaceMultipart(employeeNo: String, jpegBytes: ByteArray): Pair<String, ByteArray> {
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
    }
}

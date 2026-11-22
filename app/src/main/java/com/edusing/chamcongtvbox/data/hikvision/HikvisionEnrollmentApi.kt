package com.edusing.chamcongtvbox.data.hikvision

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thao tác nạp hồ sơ nhân sự (`UserInfo`), gắn mã thẻ (`CardInfo`), đọc danh sách và xoá người dùng
 * trên máy chấm công khuôn mặt Hikvision `DS-K1T320MX` (hỗ trợ đầy đủ tiếng Việt UTF-8).
 */
internal class HikvisionEnrollmentApi(
    private val client: HikvisionIsapiClient,
) {
    fun fetchAllUsers(device: DeviceConfig, nowMs: Long): Pair<HikHttpResponse, List<HikDeviceUser>> {
        val all = mutableListOf<HikDeviceUser>()
        var pos = 0
        var lastRes = HikHttpResponse(200, "")
        while (pos < MAX_USERS_FETCH) {
            val payload = JSONObject().put(
                "UserInfoSearchCond",
                JSONObject()
                    .put("searchID", "usr_${nowMs}_$pos")
                    .put("searchResultPosition", pos)
                    .put("maxResults", PAGE_SIZE),
            ).toString()
            val res = client.request(device, "POST", "/ISAPI/AccessControl/UserInfo/Search?format=json", payload)
            lastRes = res
            if (!res.isSuccess) return res to emptyList()
            val parsed = HikvisionIsapiParser.parseUserSearch(res.body)
            if (parsed.users.isEmpty()) break
            all.addAll(parsed.users)
            pos += parsed.users.size
            if (pos >= parsed.totalMatches) break
        }
        return lastRes to all
    }

    fun upsertUserAndCard(device: DeviceConfig, employeeNo: String, fullName: String, cardNo: String): HikHttpResponse {
        val cleanNo = employeeNo.trim().take(32)
        val safeName = fullName.trim().take(32).ifBlank { "NV $cleanNo" }
        val userPayload = JSONObject().put(
            "UserInfo",
            JSONObject()
                .put("employeeNo", cleanNo)
                .put("name", safeName)
                .put("userType", "normal")
                .put("doorRight", "1")
                .put("RightPlan", JSONArray().put(JSONObject().put("doorNo", 1).put("planTemplateNo", "1")))
                .put(
                    "Valid",
                    JSONObject()
                        .put("enable", true)
                        .put("beginTime", "2024-01-01T00:00:00")
                        .put("endTime", "2036-12-31T23:59:59")
                        .put("timeType", "local"),
                ),
        ).toString()

        var userRes = client.request(device, "PUT", "/ISAPI/AccessControl/UserInfo/SetUp?format=json", userPayload)
        if (!isUserOk(userRes) && !userRes.isUnauthorized) {
            userRes = client.request(device, "POST", "/ISAPI/AccessControl/UserInfo/Record?format=json", userPayload)
            if (!isUserOk(userRes) && !userRes.isUnauthorized) {
                userRes = client.request(device, "PUT", "/ISAPI/AccessControl/UserInfo/Modify?format=json", userPayload)
            }
        }
        if (!isUserOk(userRes)) return userRes

        val cleanCard = cardNo.trim()
        // Chỉ gắn CardInfo khi mã thẻ thuần chữ số (thẻ từ Mifare); không làm thất bại hồ sơ nếu mã là chữ (NV01) hoặc thẻ đã tồn tại
        if (cleanCard.isNotEmpty() && cleanCard.all(Char::isDigit)) {
            val cardPayload = JSONObject().put(
                "CardInfo",
                JSONObject()
                    .put("employeeNo", cleanNo)
                    .put("cardNo", cleanCard)
                    .put("cardType", "normalCard"),
            ).toString()
            runCatching {
                val cardRes = client.request(device, "PUT", "/ISAPI/AccessControl/CardInfo/SetUp?format=json", cardPayload)
                if (!isUserOk(cardRes) && !cardRes.isUnauthorized) {
                    client.request(device, "POST", "/ISAPI/AccessControl/CardInfo/Record?format=json", cardPayload)
                }
            }
        }
        return userRes
    }

    private fun isUserOk(res: HikHttpResponse): Boolean =
        res.isSuccess && !HikvisionIsapiParser.isIsapiBodyError(res.body)

    fun deleteUser(device: DeviceConfig, employeeNo: String): HikHttpResponse {
        val payload = JSONObject().put(
            "UserInfoDelCond",
            JSONObject().put("EmployeeNoList", JSONArray().put(JSONObject().put("employeeNo", employeeNo))),
        ).toString()
        return client.request(device, "PUT", "/ISAPI/AccessControl/UserInfo/Delete?format=json", payload)
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val MAX_USERS_FETCH = 3_000
    }
}

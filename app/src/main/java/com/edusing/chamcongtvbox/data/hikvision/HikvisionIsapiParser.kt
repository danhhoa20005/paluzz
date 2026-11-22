package com.edusing.chamcongtvbox.data.hikvision

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Bộ phân tích XML/JSON giao thức Hikvision ISAPI (an toàn API 24+ và không phụ thuộc Android). */
internal object HikvisionIsapiParser {

    private val VN_TZ: TimeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val ISO_DATE_REGEX = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})""")

    /** Mã `minor` thuộc `major = 5` biểu thị xác thực ĐẠT HỢP LỆ trên mọi dòng máy chấm công Hikvision (Mặt/Thẻ/Vân tay/QR/Mống mắt). */
    val VALID_PASS_MINORS: Set<Int> = setOf(1, 2, 25, 38, 40, 43, 75, 77, 78, 79, 80, 104, 105, 112)

    fun parseDeviceInfo(rawText: String): HikDeviceInfo {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{")) {
            runCatching {
                val root = JSONObject(trimmed)
                val info = root.optJSONObject("DeviceInfo") ?: root
                return HikDeviceInfo(
                    deviceName = info.optString("deviceName", "").trim(),
                    model = info.optString("model", "").trim().ifEmpty { "Hikvision" },
                    serialNumber = info.optString("serialNumber", "").trim(),
                    firmwareVersion = info.optString("firmwareVersion", "").trim(),
                )
            }
        }
        return HikDeviceInfo(
            deviceName = extractXmlTag(trimmed, "deviceName"),
            model = extractXmlTag(trimmed, "model").ifEmpty { "Hikvision" },
            serialNumber = extractXmlTag(trimmed, "serialNumber"),
            firmwareVersion = extractXmlTag(trimmed, "firmwareVersion"),
        )
    }

    fun parseUserSearch(jsonText: String): HikUserSearchPage {
        val root = JSONObject(jsonText)
        val info = root.optJSONObject("UserInfoSearch") ?: root
        val total = info.optInt("totalMatches", 0)
        val raw = info.opt("UserInfo")
        val users = mutableListOf<HikDeviceUser>()
        when (raw) {
            is JSONArray -> for (i in 0 until raw.length()) raw.optJSONObject(i)?.let { mapUser(it)?.let(users::add) }
            is JSONObject -> mapUser(raw)?.let(users::add)
        }
        return HikUserSearchPage(totalMatches = total, users = users)
    }

    fun parseAcsEvents(jsonText: String, deviceKeySeed: String, fallbackNowMs: Long): HikAcsEventPage {
        val root = JSONObject(jsonText)
        val wrapper = root.optJSONObject("AcsEvent") ?: root
        val total = wrapper.optInt("totalMatches", 0)
        val numMatches = wrapper.optInt("numOfMatches", 0)
        val statusStrg = wrapper.optString("responseStatusStrg", "").trim()
        val list = wrapper.optJSONArray("InfoList") ?: return HikAcsEventPage(total, emptyList(), numMatches, 0, statusStrg)
        val events = mutableListOf<HikParsedEvent>()
        for (i in 0 until list.length()) {
            list.optJSONObject(i)?.let { parseSingleEvent(it, deviceKeySeed, fallbackNowMs)?.let(events::add) }
        }
        return HikAcsEventPage(total, events, numMatches, list.length(), statusStrg)
    }

    /** Trích xuất `faceURL` từ phản hồi `/ISAPI/Intelligent/FDLib/FDSearch?format=json` để sao chép khuôn mặt giữa nhiều máy. */
    fun parseFaceUrl(jsonText: String): String? = runCatching {
        val root = JSONObject(jsonText.trim())
        val list = root.optJSONArray("MatchList") ?: return@runCatching null
        for (i in 0 until list.length()) {
            val url = list.optJSONObject(i)?.optString("faceURL", "")?.trim().orEmpty()
            if (url.isNotEmpty()) return@runCatching url
        }
        null
    }.getOrNull()

    /** Trích xuất danh sách `cardNo` từ phản hồi `/ISAPI/AccessControl/CardInfo/Search?format=json`. */
    fun parseCardNumbers(jsonText: String): List<String> = runCatching {
        val root = JSONObject(jsonText.trim())
        val wrap = root.optJSONObject("CardInfoSearch") ?: root
        val raw = wrap.opt("CardInfo")
        val out = mutableListOf<String>()
        when (raw) {
            is JSONArray -> for (i in 0 until raw.length()) {
                raw.optJSONObject(i)?.optString("cardNo", "")?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            }
            is JSONObject -> raw.optString("cardNo", "").trim().takeIf { it.isNotEmpty() }?.let(out::add)
        }
        out.distinct()
    }.getOrDefault(emptyList())

    /** Phát hiện trường hợp máy Hikvision trả HTTP 200 nhưng body JSON/XML báo lỗi nghiệp vụ (`statusCode > 1`). */
    fun isIsapiBodyError(bodyText: String): Boolean {
        val trimmed = bodyText.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("{")) {
            return runCatching {
                val obj = JSONObject(trimmed)
                val code = obj.optInt("statusCode", 1)
                val sub = obj.optString("subStatusCode", "").trim()
                code > 1 && !sub.equals("ok", ignoreCase = true)
            }.getOrDefault(false)
        }
        if (trimmed.contains("<ResponseStatus", ignoreCase = true)) {
            val code = extractXmlTag(trimmed, "statusCode").toIntOrNull() ?: 1
            val sub = extractXmlTag(trimmed, "subStatusCode")
            return code > 1 && !sub.equals("ok", ignoreCase = true)
        }
        return false
    }

    /** Giải mã gói đẩy tức thì từ `HTTP Listening` (`POST /hikvision/event`), miễn nhiễm ảnh JPEG nối sau. */
    fun parseWebhookPayload(rawPayload: String, remoteIp: String, fallbackNowMs: Long): HikParsedEvent? {
        val jsonSlice = extractFirstBalancedJsonObject(rawPayload) ?: return null
        return runCatching {
            val root = JSONObject(jsonSlice)
            val acs = root.optJSONObject("AccessControllerEvent") ?: root
            val merged = JSONObject()
            copyKeys(root, merged)
            copyKeys(acs, merged)
            val dateTime = root.optString("dateTime", "").ifBlank { acs.optString("time", "") }
            if (dateTime.isNotBlank()) merged.put("time", dateTime)
            parseSingleEvent(merged, root.optString("ipAddress", "").ifBlank { remoteIp }, fallbackNowMs)
        }.getOrNull()
    }

    /** Định dạng thời gian ISO giờ Việt Nam (`yyyy-MM-ddTHH:mm:ss+07:00`) cho truy vấn ISAPI. */
    fun formatVietnamIsoTime(epochMs: Long, includeOffset: Boolean = true): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = VN_TZ }
        val base = fmt.format(Date(epochMs))
        return if (includeOffset) "$base+07:00" else base
    }

    fun extractErrorDetail(bodyText: String): String {
        val trimmed = bodyText.trim()
        val rawCode = if (trimmed.startsWith("{")) {
            runCatching {
                val obj = JSONObject(trimmed)
                obj.optString("subStatusCode", "")
                    .ifBlank { obj.optString("errorMsg", "") }
                    .ifBlank { obj.optString("statusString", "") }
            }.getOrDefault("")
        } else {
            extractXmlTag(trimmed, "subStatusCode").ifBlank { extractXmlTag(trimmed, "statusString") }
        }
        return translateIsapiError(rawCode)
    }

    /** Chuyển mã lỗi kỹ thuật ISAPI sang thông báo tiếng Việt dễ hiểu cho người vận hành. */
    fun translateIsapiError(rawCode: String): String {
        val clean = rawCode.trim()
        if (clean.isEmpty()) return ""
        return when (clean.lowercase()) {
            "devicebusy", "busy" -> "Máy đang bận (đang mở menu Admin)"
            "operationdenied", "nopermission" -> "Không đủ quyền thao tác (kiểm tra tài khoản admin)"
            "picanalysisfailed", "facemodelingfailed", "facefeatureextractfailed" -> "Không nhận diện được khuôn mặt trong ảnh (cần chụp rõ chính diện, đủ sáng)"
            "picsizetoolarge", "pictoobig" -> "Ảnh vượt quá dung lượng cho phép (< 200 KB)"
            "usernotexist", "employeeidnotexist" -> "Nhân sự chưa tồn tại trên máy"
            "cardnoalreadyexist" -> "Mã thẻ đã tồn tại"
            "maxusernum", "full" -> "Bộ nhớ máy đã đầy"
            "badparameters", "invalidformat" -> "Tham số dữ liệu không hợp lệ ($clean)"
            else -> clean
        }
    }

    /** Trích địa chỉ IPv4 nằm trong gói Webhook JSON của Hikvision (hữu ích khi chạy qua cầu nối/NAT). */
    fun extractWebhookSourceIp(rawPayload: String): String = runCatching {
        val slice = extractFirstBalancedJsonObject(rawPayload) ?: return@runCatching ""
        JSONObject(slice).optString("ipAddress", "").trim()
    }.getOrDefault("")

    private fun parseSingleEvent(raw: JSONObject, seed: String, fallbackNowMs: Long): HikParsedEvent? {
        val major = if (raw.has("major")) raw.optInt("major", 5) else raw.optInt("majorEventType", 5)
        val minor = if (raw.has("minor")) raw.optInt("minor", 0) else raw.optInt("subEventType", 0)
        if (major != 5 || minor !in VALID_PASS_MINORS) return null

        val empNo = raw.optString("employeeNoString", "").ifBlank { raw.optString("employeeNo", "") }.trim()
        val cardNo = raw.optString("cardNo", "").trim()
        val effectiveId = empNo.ifEmpty { cardNo }
        if (effectiveId.isEmpty()) return null

        val dt = parseDateParts(raw.optString("time", "").ifBlank { raw.optString("dateTime", "") }.trim(), fallbackNowMs)
        val serialNo = raw.optInt("serialNo", 0)
        val cleanSeed = seed.replace('.', '_').ifBlank { "hik" }
        val key = if (serialNo > 0) "${cleanSeed}_sn_$serialNo" else "${cleanSeed}_${effectiveId}_${dt.epochMs / 1000L}"

        return HikParsedEvent(
            eventKey = key, employeeNo = effectiveId, employeeName = raw.optString("name", "").trim(),
            cardNo = cardNo, serialNo = serialNo, minor = minor,
            year = dt.year, month = dt.month, day = dt.day, hour = dt.hour, minute = dt.minute, second = dt.second,
            epochMs = dt.epochMs,
        )
    }

    private fun mapUser(obj: JSONObject): HikDeviceUser? {
        val empNo = obj.optString("employeeNo", "").trim()
        if (empNo.isEmpty()) return null
        return HikDeviceUser(empNo, obj.optString("name", "").trim(), obj.optInt("numOfFace", 0), obj.optInt("numOfCard", 0))
    }

    private data class DateParts(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int, val epochMs: Long)

    private fun parseDateParts(raw: String, fallbackMs: Long): DateParts {
        val m = ISO_DATE_REGEX.find(raw)
        val cal = Calendar.getInstance(VN_TZ)
        if (m != null) {
            val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
            val hh = m.groupValues[4].toInt(); val mm = m.groupValues[5].toInt(); val ss = m.groupValues[6].toInt()
            cal.set(y, (mo - 1).coerceIn(0, 11), d.coerceIn(1, 31), hh, mm, ss)
            cal.set(Calendar.MILLISECOND, 0)
            return DateParts(y, mo, d, hh, mm, ss, cal.timeInMillis)
        }
        cal.timeInMillis = fallbackMs
        return DateParts(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND), fallbackMs,
        )
    }

    private fun extractFirstBalancedJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inString = false; var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return raw.substring(start, i + 1)
            }
        }
        return null
    }

    private fun extractXmlTag(xml: String, tag: String): String =
        Regex("<$tag[^>]*>([^<]*)</$tag>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    private fun copyKeys(src: JSONObject, dst: JSONObject) {
        val it = src.keys()
        while (it.hasNext()) { val k = it.next(); dst.put(k, src.opt(k)) }
    }
}

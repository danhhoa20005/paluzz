package com.edusing.chamcongtvbox.data.remote

import com.edusing.chamcongtvbox.domain.model.ClassGroup
import com.edusing.chamcongtvbox.domain.model.LoginResult
import com.edusing.chamcongtvbox.domain.model.Member
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * Bộ phân tích JSON phản hồi từ máy chủ (`login`, `classes`, `students`, lỗi xác thực).
 * Viết thuần Kotlin dễ kiểm thử đơn vị, hỗ trợ nhiều cấu trúc lồng nhau giống `ApiClient.cs`.
 */
object ApiResponseParser {

    /** Kiểm tra chuỗi lỗi có phải do sai tài khoản/mật khẩu (khác với lỗi mất mạng). */
    fun isInvalidAccountError(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        val lower = error.lowercase(Locale.ROOT)
        return lower.contains("incorrect") ||
            lower.contains("unauthor") ||
            lower.contains("http 401") ||
            lower.contains("http 403") ||
            lower.contains("sai") ||
            lower.contains("not found")
    }

    fun parseLoginResponse(httpCode: Int, rawBody: String): LoginResult {
        val root = parseJsonOrNull(rawBody)
        if (httpCode >= 400) {
            val serverMsg = (root as? JSONObject)?.optString("message")?.takeIf { it.isNotBlank() }
            val msg = when {
                httpCode >= 500 && !serverMsg.isNullOrBlank() -> "Máy chủ đang gặp sự cố (HTTP $httpCode): $serverMsg"
                httpCode >= 500 -> "Máy chủ đang gặp sự cố (HTTP $httpCode). Vui lòng thử lại sau."
                !serverMsg.isNullOrBlank() -> serverMsg
                else -> "HTTP $httpCode"
            }
            return LoginResult(
                success = false,
                error = msg,
                isInvalidCredentials = httpCode == 401 || httpCode == 403 || (httpCode < 500 && isInvalidAccountError(msg)),
            )
        }

        val dataObj = (root as? JSONObject)?.optJSONObject("data")
        val accountObj = dataObj?.optJSONObject("account")

        // Khớp thứ tự ưu tiên của ApiClient.cs: data.account.id -> accountId -> data.id -> id ngắn bất kỳ
        val accountId = firstNonEmpty(
            optStringClean(accountObj, "id"),
            findFirstStringRecursive(root, listOf("accountId")),
            optStringClean(dataObj, "id"),
            findShortIdRecursive(root),
        )
        val orgName = firstNonEmpty(
            optStringClean(accountObj, "name"),
            optStringClean(accountObj, "fullName"),
            findFirstStringRecursive(root, listOf("name")),
        )
        val token = firstNonEmpty(
            optStringClean(dataObj, "accessToken"),
            findFirstStringRecursive(root, listOf("accessToken", "access_token", "token", "jwt")),
        )

        if (accountId.isEmpty()) {
            val preview = rawBody.take(300)
            return LoginResult(
                success = false,
                error = "Đăng nhập OK nhưng không tìm thấy account.id trong phản hồi: $preview",
            )
        }
        return LoginResult(success = true, accountId = accountId, orgName = orgName, token = token)
    }

    fun extractMessage(rawBody: String): String? {
        val obj = parseJsonOrNull(rawBody) as? JSONObject ?: return null
        return optStringClean(obj, "message").ifBlank { null }
    }

    fun parseClassesResponse(httpCode: Int, rawBody: String): Result<List<ClassGroup>> {
        if (httpCode >= 400) return Result.failure(IllegalStateException(extractMessage(rawBody) ?: "HTTP $httpCode"))
        val root = parseJsonOrNull(rawBody) ?: return Result.failure(IllegalStateException("Không đọc được JSON danh sách lớp."))
        val arr = findClassArray(root) ?: return Result.failure(IllegalStateException("Không tìm thấy danh sách lớp trong phản hồi."))

        val list = ArrayList<ClassGroup>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optInt("id", optStringClean(obj, "id").toIntOrNull() ?: 0)
            val name = firstNonEmpty(optStringClean(obj, "className"), optStringClean(obj, "name")).ifEmpty { "Lớp $id" }
            if (id > 0) list.add(ClassGroup(id = id, name = name))
        }
        return Result.success(list)
    }

    fun parseStudentsResponse(
        httpCode: Int,
        rawBody: String,
        personLabelLower: String = "học sinh",
    ): Result<List<Member>> {
        if (httpCode >= 400) return Result.failure(IllegalStateException(extractMessage(rawBody) ?: "HTTP $httpCode"))
        val root = parseJsonOrNull(rawBody) ?: return Result.failure(IllegalStateException("Không đọc được JSON danh sách $personLabelLower."))
        val arr = findStudentArray(root) ?: return Result.failure(IllegalStateException("Không tìm thấy danh sách $personLabelLower trong phản hồi."))

        val list = ArrayList<Member>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = firstNonEmpty(optStringClean(obj, "studentName"), optStringClean(obj, "name"), optStringClean(obj, "fullName"))
            val card1 = optStringClean(obj, "studentCardId")
            val card2 = optStringClean(obj, "studentCardIdSecond")
            val pushCard = firstNumeric32BitCard(card1, card2, optStringClean(obj, "cardId"), optStringClean(obj, "card"))
            val nestedClass = obj.optJSONObject("classes")
            val classId = firstNonEmpty(optStringClean(obj, "classId"), optStringClean(nestedClass, "id")).toIntOrNull() ?: 0
            val rawCard = firstNonEmpty(card1, card2)
            if (name.isNotEmpty() || rawCard.isNotEmpty()) {
                list.add(Member(cardId = pushCard, rawCard = rawCard, name = name, classId = classId))
            }
        }
        return Result.success(list)
    }

    /** Máy chấm công chỉ nạp được mã số nguyên dương 32-bit (`1 .. 2.147.483.647`). */
    fun firstNumeric32BitCard(vararg candidates: String): String {
        for (c in candidates) {
            val trimmed = c.trim()
            val parsed = trimmed.toLongOrNull() ?: continue
            if (parsed in 1L..Int.MAX_VALUE.toLong()) return trimmed
        }
        return ""
    }

    private fun parseJsonOrNull(raw: String): Any? = runCatching { JSONTokener(raw.trim()).nextValue() }.getOrNull()

    private fun optStringClean(obj: JSONObject?, key: String): String {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return ""
        return obj.opt(key)?.toString()?.trim().orEmpty()
    }

    private fun firstNonEmpty(vararg vals: String): String = vals.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun findFirstStringRecursive(node: Any?, keys: List<String>): String {
        if (node == null) return ""
        for (key in keys) {
            val found = searchKey(node, key)
            if (found.isNotEmpty()) return found
        }
        return ""
    }

    /** Tìm trường `id` ngắn (`<= 12` ký tự) tương đương `FindIdNear` trong `ApiClient.cs`. */
    private fun findShortIdRecursive(node: Any?): String = when (node) {
        is JSONObject -> {
            val direct = optStringClean(node, "id")
            if (direct.isNotEmpty() && direct.length <= 12) direct
            else node.keys().asSequence().map { findShortIdRecursive(node.opt(it)) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        }
        is JSONArray -> (0 until node.length()).asSequence().map { findShortIdRecursive(node.opt(it)) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        else -> ""
    }

    private fun searchKey(node: Any?, key: String): String = when (node) {
        is JSONObject -> {
            val direct = optStringClean(node, key)
            if (direct.isNotEmpty()) direct
            else node.keys().asSequence().map { searchKey(node.opt(it), key) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        }
        is JSONArray -> (0 until node.length()).asSequence().map { searchKey(node.opt(it), key) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        else -> ""
    }

    private fun findStudentArray(node: Any?): JSONArray? =
        searchArray(node) { it.has("studentCardId") || (it.has("name") && !it.has("className")) } ?: (node as? JSONArray)

    private fun findClassArray(node: Any?): JSONArray? =
        searchArray(node) { it.has("className") || (it.has("id") && it.has("name")) } ?: (node as? JSONArray)

    private fun searchArray(node: Any?, predicate: (JSONObject) -> Boolean): JSONArray? = when (node) {
        is JSONArray -> {
            val first = node.optJSONObject(0)
            if (first != null && predicate(first)) node
            else (0 until node.length()).asSequence().mapNotNull { searchArray(node.opt(it), predicate) }.firstOrNull()
        }
        is JSONObject -> node.keys().asSequence().mapNotNull { searchArray(node.opt(it), predicate) }.firstOrNull()
        else -> null
    }
}

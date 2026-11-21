package com.edusing.chamcongtvbox.remote

import com.edusing.chamcongtvbox.data.remote.ApiResponseParser
import com.edusing.chamcongtvbox.data.remote.ServerTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử phân tích JSON đăng nhập, danh sách lớp/thành viên và chuyển đổi giờ VN UTC+7. */
class ApiResponseParserTest {

    @Test
    fun parseLoginResponse_handlesStandardNestedPayloadAndErrors() {
        val body = """
            {"data":{"accessToken":"jwt-xyz","account":{"id":"school-001","name":"Trường THCS Chu Văn An"}}}
        """.trimIndent()

        val ok = ApiResponseParser.parseLoginResponse(200, body)
        assertTrue(ok.success)
        assertEquals("school-001", ok.accountId)
        assertEquals("Trường THCS Chu Văn An", ok.orgName)
        assertEquals("jwt-xyz", ok.token)

        val err401 = ApiResponseParser.parseLoginResponse(401, """{"message":"Sai mật khẩu"}""")
        assertFalse(err401.success)
        assertTrue(err401.isInvalidCredentials)

        val err500 = ApiResponseParser.parseLoginResponse(500, """{"message":"password authentication failed for user dd_app"}""")
        assertFalse(err500.success)
        assertFalse(err500.isInvalidCredentials)
        assertTrue(err500.error.orEmpty().contains("HTTP 500"))

        val fallbackId = ApiResponseParser.parseLoginResponse(200, """{"user":{"id":9876,"name":"Đơn vị A"},"token":"tok"}""")
        assertTrue(fallbackId.success)
        assertEquals("9876", fallbackId.accountId)
        assertEquals("tok", fallbackId.token)
    }

    @Test
    fun isInvalidAccountError_detectsVietnameseAndHttpAuthMessages() {
        assertTrue(ApiResponseParser.isInvalidAccountError("Sai tài khoản hoặc mật khẩu"))
        assertTrue(ApiResponseParser.isInvalidAccountError("HTTP 401 Unauthorized"))
        assertFalse(ApiResponseParser.isInvalidAccountError("Connection timed out"))
    }

    @Test
    fun extractServerTimeAndFormatVietnamUtcPlus7() {
        val extracted = ServerTimeParser.extractFromMessage("Điểm danh thành công vào lúc: 07:30:15 19/11/2026")
        assertNotNull(extracted)
        assertEquals("07:30:15 19/11/2026", extracted!!.first)
        assertEquals("07:30:15 19/11/2026", ServerTimeParser.formatVietnamTime(extracted.second))

        val gmtMs = ServerTimeParser.parseHttpDateMillis("Thu, 19 Nov 2026 01:05:09 GMT")
        assertNotNull(gmtMs)
        assertEquals("08:05:09 19/11/2026", ServerTimeParser.formatVietnamTime(gmtMs!!))
    }

    @Test
    fun parseClassesAndStudents_filtersInvalid32BitCardsCleanly() {
        val classesJson = """{"data":[{"id":10,"className":"6A1"},{"id":11,"name":"6A2"}]}"""
        val classes = ApiResponseParser.parseClassesResponse(200, classesJson).getOrThrow()
        assertEquals(2, classes.size)

        val studentsJson = """
            {"data":[
              {"studentName":"Nguyễn Văn A","studentCardId":"123456","classId":10},
              {"studentName":"Trần Thị B","studentCardId":"abc","studentCardIdSecond":"987654","classes":{"id":11}},
              {"studentName":"Lê Văn C","studentCardId":"9999999999","classId":10},
              {"studentName":"Phạm Văn D","studentCardId":"0","classId":10}
            ]}
        """.trimIndent()
        val students = ApiResponseParser.parseStudentsResponse(200, studentsJson).getOrThrow()
        assertTrue(students[0].isPushable)
        assertEquals(987654, students[1].cardIdInt)
        assertFalse(students[2].isPushable)
        assertFalse(students[3].isPushable)
    }
}

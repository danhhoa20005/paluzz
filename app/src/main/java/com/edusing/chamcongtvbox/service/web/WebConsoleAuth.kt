package com.edusing.chamcongtvbox.service.web

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Xác thực phiên cho trang Web quản trị LAN (`http://<IP-TV-Box>:8080`):
 * - Nếu TV Box đã đăng nhập: dùng đúng mật khẩu tài khoản web tổng để mở khoá quản trị từ xa.
 * - Nếu TV Box chưa đăng nhập lần đầu: chấp nhận mã mặc định `1234` để kỹ thuật viên cài từ xa qua điện thoại.
 * - Sinh token ngẫu nhiên 256-bit, so sánh hằng thời gian chống timing attack, tự hết hạn sau 24 giờ.
 */
internal class WebConsoleAuth(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Long>()

    fun verifyPassword(inputPassword: String, savedAccountPassword: String, isLoggedIn: Boolean): Boolean {
        val expected = when {
            savedAccountPassword.isNotBlank() -> savedAccountPassword
            !isLoggedIn -> DEFAULT_SETUP_PIN
            else -> DEFAULT_SETUP_PIN
        }
        return constantTimeEquals(inputPassword.trim(), expected)
    }

    fun issueToken(): String {
        pruneExpired()
        if (sessions.size >= MAX_SESSIONS) sessions.clear()
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        sessions[token] = clockMillis() + SESSION_TTL_MS
        return token
    }

    fun isTokenValid(token: String?): Boolean {
        val clean = token?.trim().orEmpty()
        if (clean.isBlank()) return false
        val expiresAt = sessions[clean] ?: return false
        if (clockMillis() > expiresAt) {
            sessions.remove(clean)
            return false
        }
        return true
    }

    fun revokeAll() = sessions.clear()

    private fun pruneExpired() {
        val now = clockMillis()
        sessions.entries.removeAll { it.value < now }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(ab, bb)
    }

    companion object {
        const val DEFAULT_SETUP_PIN = "1234"
        private const val MAX_SESSIONS = 16
        private const val SESSION_TTL_MS = 24 * 60 * 60_000L
    }
}

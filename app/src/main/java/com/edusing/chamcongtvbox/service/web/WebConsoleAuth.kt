package com.edusing.chamcongtvbox.service.web

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Xác thực phiên cho trang Web quản trị LAN (`http://<IP-TV-Box>:8080`):
 * - Nếu TV Box đã đăng nhập: dùng đúng mật khẩu tài khoản web tổng để mở khoá quản trị từ xa.
 * - Nếu TV Box chưa đăng nhập lần đầu: chấp nhận mã mặc định `1234` để kỹ thuật viên cài từ xa qua điện thoại.
 * - Token bền vững (`issuePersistentToken`) dùng HMAC-SHA256 theo muối riêng lưu 1 lần trên Box + mật khẩu hiện hành:
 *   đăng nhập 1 lần giữ phiên xuyên qua khởi động lại TV Box / cập nhật OTA, và tự vô hiệu ngay khi đổi mật khẩu.
 */
internal class WebConsoleAuth(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val persistentSalt: String = "",
) {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Long>()

    fun verifyPassword(inputPassword: String, savedAccountPassword: String, isLoggedIn: Boolean): Boolean {
        val expected = effectivePassword(savedAccountPassword, isLoggedIn)
        return constantTimeEquals(inputPassword.trim(), expected)
    }

    /** Cấp token phiên bền vững theo mật khẩu hiện tại (đăng nhập 1 lần, không bị văng khi TV Box khởi động lại). */
    fun issuePersistentToken(savedAccountPassword: String, isLoggedIn: Boolean): String {
        if (persistentSalt.isBlank()) return issueToken()
        return computePersistentToken(persistentSalt, effectivePassword(savedAccountPassword, isLoggedIn))
    }

    /** Kiểm tra token bền vững (ưu tiên) hoặc token phiên tạm trong RAM. */
    fun isTokenValid(token: String?, savedAccountPassword: String, isLoggedIn: Boolean): Boolean {
        val clean = token?.trim().orEmpty()
        if (clean.isBlank()) return false
        if (persistentSalt.isNotBlank() && clean.startsWith(PERSISTENT_PREFIX)) {
            val expected = computePersistentToken(persistentSalt, effectivePassword(savedAccountPassword, isLoggedIn))
            return constantTimeEquals(clean, expected)
        }
        return isTokenValid(clean)
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

    private fun effectivePassword(savedAccountPassword: String, isLoggedIn: Boolean): String = when {
        savedAccountPassword.isNotBlank() -> savedAccountPassword
        !isLoggedIn -> DEFAULT_SETUP_PIN
        else -> DEFAULT_SETUP_PIN
    }

    private fun computePersistentToken(salt: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("chamcong-console-v1|$secret".toByteArray(Charsets.UTF_8))
        return PERSISTENT_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    private fun pruneExpired() {
        val now = clockMillis()
        sessions.entries.removeAll { it.value < now }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        const val DEFAULT_SETUP_PIN = "1234"
        private const val PERSISTENT_PREFIX = "p1."
        private const val SALT_PREFS = "chamcong_console_auth_v1"
        private const val KEY_SALT = "device_hmac_salt"
        private const val MAX_SESSIONS = 16
        private const val SESSION_TTL_MS = 24 * 60 * 60_000L

        /** Đọc hoặc khởi tạo 1 lần chuỗi muối 256-bit cố định cho TV Box (sống sót qua khởi động lại & OTA). */
        fun loadOrCreateDeviceSalt(context: Context): String = runCatching {
            val prefs = context.applicationContext.getSharedPreferences(SALT_PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_SALT, null)?.takeIf { it.length >= 32 } ?: run {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val salt = bytes.joinToString("") { "%02x".format(it) }
                prefs.edit().putString(KEY_SALT, salt).commit()
                salt
            }
        }.getOrDefault("")
    }
}

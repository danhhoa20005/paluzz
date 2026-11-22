package com.edusing.chamcongtvbox.data.local

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bộ mã hoá / giải mã sao lưu cấu hình chuẩn **AES-256-GCM** (thuần JDK `javax.crypto`, 0 thư viện ngoài):
 * xuất toàn bộ tài khoản, danh sách máy FK + Hikvision, lịch đẩy nhân sự sang 1 tệp `.json` có mật khẩu bảo vệ
 * để nhân bản sang TV Box / điện thoại mới trong 5 giây.
 */
internal object ConfigBackupCodec {

    private const val FORMAT_TAG = "chamcong-tvbox-backup-v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private val RANDOM = SecureRandom()

    fun encrypt(plainJson: String, passphrase: String): String {
        require(passphrase.isNotEmpty()) { "Vui lòng nhập mật khẩu bảo vệ tệp sao lưu" }
        val salt = ByteArray(16).also { RANDOM.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { RANDOM.nextBytes(it) }
        val key = deriveAes256Key(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val cipherBytes = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", FORMAT_TAG)
            .put("salt", toHex(salt))
            .put("iv", toHex(iv))
            .put("data", toHex(cipherBytes))
            .toString(2)
    }

    fun decrypt(envelopeJson: String, passphrase: String): Result<String> = runCatching {
        require(passphrase.isNotEmpty()) { "Thiếu mật khẩu giải mã tệp sao lưu" }
        val obj = JSONObject(envelopeJson)
        require(obj.optString("format") == FORMAT_TAG) { "Tệp không đúng định dạng sao lưu Chấm công TV Box" }
        val salt = fromHex(obj.getString("salt"))
        val iv = fromHex(obj.getString("iv"))
        val data = fromHex(obj.getString("data"))
        val key = deriveAes256Key(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.recoverCatching { err ->
        val msg = if (err is javax.crypto.AEADBadTagException) "Sai mật khẩu giải mã hoặc tệp sao lưu bị sửa đổi" else (err.message ?: "Lỗi giải mã tệp sao lưu")
        throw IllegalArgumentException(msg)
    }

    private fun deriveAes256Key(passphrase: String, salt: ByteArray): SecretKeySpec {
        // Kéo giãn khoá 10.000 vòng SHA-256 (an toàn & tương thích 100% mọi Android API 24+)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(passphrase.toByteArray(Charsets.UTF_8))
        var hash = md.digest()
        repeat(10_000) {
            md.reset(); md.update(hash); md.update(salt); hash = md.digest()
        }
        return SecretKeySpec(hash, "AES")
    }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) append("%02x".format(b))
    }

    private fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Dữ liệu hex không hợp lệ" }
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

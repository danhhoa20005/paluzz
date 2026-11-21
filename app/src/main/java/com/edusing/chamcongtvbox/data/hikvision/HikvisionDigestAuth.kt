package com.edusing.chamcongtvbox.data.hikvision

import okio.ByteString.Companion.encodeUtf8
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Bộ giải mã thách thức & ký xác thực HTTP Digest chuẩn RFC 7616 cho máy chấm công Hikvision ISAPI.
 * Hỗ trợ đầy đủ `MD5`, `SHA-256`, `MD5-sess`, `SHA-256-sess`, `qop="auth"` và dự phòng `Basic`.
 */
internal data class HikvisionDigestChallenge(
    val scheme: String,
    val params: Map<String, String>,
) {
    val isDigest: Boolean get() = scheme.equals("Digest", ignoreCase = true)
    val isBasic: Boolean get() = scheme.equals("Basic", ignoreCase = true)

    fun buildAuthorization(
        method: String,
        requestUriWithQuery: String,
        username: String,
        password: String,
        nonceCount: Int = 1,
        clientNonceOverride: String? = null,
    ): String {
        if (isBasic) {
            val raw = "$username:$password".encodeUtf8().base64()
            return "Basic $raw"
        }

        val realm = params["realm"].orEmpty()
        val nonce = params["nonce"].orEmpty()
        val opaque = params["opaque"]
        val algorithmRaw = (params["algorithm"] ?: "MD5").uppercase()
        val qopTokens = params["qop"].orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        val selectedQop = when {
            "auth" in qopTokens -> "auth"
            qopTokens.isNotEmpty() -> qopTokens.first()
            else -> ""
        }

        val cnonce = clientNonceOverride ?: generateCnonce()
        val ncHex = nonceCount.toString(16).padStart(8, '0')
        val digestName = if (algorithmRaw.startsWith("SHA-256")) "SHA-256" else "MD5"

        var ha1 = hashHex(digestName, "$username:$realm:$password")
        if (algorithmRaw.endsWith("-SESS")) {
            ha1 = hashHex(digestName, "$ha1:$nonce:$cnonce")
        }
        val ha2 = hashHex(digestName, "${method.uppercase()}:$requestUriWithQuery")
        val responseHash = if (selectedQop.isNotEmpty()) {
            hashHex(digestName, "$ha1:$nonce:$ncHex:$cnonce:$selectedQop:$ha2")
        } else {
            hashHex(digestName, "$ha1:$nonce:$ha2")
        }

        val parts = mutableListOf(
            """username="${escapeQuoted(username)}"""",
            """realm="${escapeQuoted(realm)}"""",
            """nonce="${escapeQuoted(nonce)}"""",
            """uri="$requestUriWithQuery"""",
            """response="$responseHash"""",
        )
        params["algorithm"]?.takeIf { it.isNotBlank() }?.let { parts.add("algorithm=$it") }
        opaque?.takeIf { it.isNotBlank() }?.let { parts.add("""opaque="${escapeQuoted(it)}"""") }
        if (selectedQop.isNotEmpty()) {
            parts.add("qop=$selectedQop")
            parts.add("nc=$ncHex")
            parts.add("""cnonce="$cnonce"""")
        }
        return "Digest ${parts.joinToString(", ")}"
    }

    companion object {
        private val PARAM_REGEX = Regex("""([a-zA-Z0-9_-]+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
        private val SECURE_RANDOM = SecureRandom()

        fun parse(headerValues: List<String>): HikvisionDigestChallenge? {
            if (headerValues.isEmpty()) return null
            for (raw in headerValues) {
                val trimmed = raw.trim()
                if (trimmed.lowercase().startsWith("digest ")) {
                    return HikvisionDigestChallenge("Digest", parseKeyValues(trimmed.substring(7)))
                }
            }
            for (raw in headerValues) {
                val trimmed = raw.trim()
                if (trimmed.lowercase().startsWith("basic")) {
                    return HikvisionDigestChallenge("Basic", emptyMap())
                }
            }
            return null
        }

        private fun parseKeyValues(input: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            for (m in PARAM_REGEX.findAll(input)) {
                val key = m.groupValues[1].lowercase()
                val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
                if (key.isNotEmpty()) map[key] = value
            }
            return map
        }

        private fun hashHex(algorithm: String, text: String): String {
            val digest = MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
            }
            return sb.toString()
        }

        private fun generateCnonce(): String {
            val bytes = ByteArray(8)
            SECURE_RANDOM.nextBytes(bytes)
            val sb = StringBuilder(16)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
            }
            return sb.toString()
        }

        private fun escapeQuoted(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

        private const val HEX_CHARS = "0123456789abcdef"
    }
}

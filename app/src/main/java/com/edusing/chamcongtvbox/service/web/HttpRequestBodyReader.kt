package com.edusing.chamcongtvbox.service.web

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Yêu cầu HTTP đã giải mã hoàn chỉnh từ kết nối LAN (hỗ trợ `Content-Length`, `chunked` & `Expect: 100-continue`). */
internal data class ParsedHttpRequest(
    val method: String,
    val path: String,
    val query: String,
    val token: String?,
    val body: String,
)

/**
 * Bộ đọc thân gói HTTP/1.1 chuẩn cho máy chủ quản trị LAN & cổng nhận Webhook Hikvision (`HTTP Listening`):
 * - Trả ngay `HTTP/1.1 100 Continue` khi máy Hikvision gửi `Expect: 100-continue`.
 * - Giải mã chuẩn `Transfer-Encoding: chunked` (thường gặp trên firmware Hikvision `V3.5+`/`V3.9+` kèm ảnh multipart).
 * - Giới hạn bộ nhớ đệm an toàn và xả phần ảnh dư (tối đa 512 KB) để tránh lỗi TCP RST trên thiết bị chấm công.
 */
internal object HttpRequestBodyReader {

    private const val MAX_HEADER_BYTES = 8_192
    private const val MAX_BODY_BYTES = 32_768
    private const val MAX_WEBHOOK_BODY_BYTES = 131_072
    private const val MAX_FACE_UPLOAD_BYTES = 400_000
    private const val MAX_DRAIN_BYTES = 524_288
    private val CONTINUE_100 = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII)

    fun parseRequest(input: BufferedInputStream, output: OutputStream): ParsedHttpRequest? {
        val headerBuf = ByteArrayOutputStream()
        var matched = 0
        while (headerBuf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) return null
            headerBuf.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        val lines = headerBuf.toString(Charsets.UTF_8.name()).split("\r\n")
        val firstParts = lines.firstOrNull()?.split(' ') ?: return null
        if (firstParts.size < 2) return null

        val method = firstParts[0].uppercase()
        val rawTarget = firstParts[1]
        val path = rawTarget.substringBefore('?')
        val query = rawTarget.substringAfter('?', "")
        val bodyLimit = bodyLimitFor(path)

        var contentLength = -1
        var isChunked = false
        var expectContinue = false
        var token: String? = null
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim().lowercase()
            val v = line.substring(idx + 1).trim()
            when (k) {
                "content-length" -> contentLength = v.toIntOrNull()?.coerceAtLeast(0) ?: -1
                "transfer-encoding" -> if (v.contains("chunked", ignoreCase = true)) isChunked = true
                "expect" -> if (v.contains("100-continue", ignoreCase = true)) expectContinue = true
                "x-console-token" -> token = v
            }
        }

        if (expectContinue) {
            runCatching { output.write(CONTINUE_100); output.flush() }
        }

        val bodyBytes = when {
            isChunked -> readChunkedBody(input, bodyLimit)
            contentLength > 0 -> readFixedBody(input, contentLength, bodyLimit)
            else -> ByteArray(0)
        }
        return ParsedHttpRequest(method, path, query, token, String(bodyBytes, Charsets.UTF_8))
    }

    internal fun readChunkedBody(input: BufferedInputStream, maxKeepBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val scratch = ByteArray(4_096)
        var drained = 0
        while (true) {
            val sizeLine = readAsciiLine(input) ?: break
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (chunkSize <= 0) {
                // Đọc hết dòng trailer (nếu có) đến dòng rỗng kết thúc gói chunked
                while (true) {
                    val trailer = readAsciiLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            var remaining = chunkSize
            while (remaining > 0) {
                val want = minOf(remaining, scratch.size)
                val n = input.read(scratch, 0, want)
                if (n <= 0) return out.toByteArray()
                val keepRoom = maxKeepBytes - out.size()
                if (keepRoom > 0) {
                    val toKeep = minOf(keepRoom, n)
                    out.write(scratch, 0, toKeep)
                    drained += (n - toKeep)
                } else {
                    drained += n
                }
                remaining -= n
                if (drained > MAX_DRAIN_BYTES) return out.toByteArray()
            }
            // Bỏ qua cặp CRLF kết thúc mỗi chunk
            readAsciiLine(input)
        }
        return out.toByteArray()
    }

    private fun readFixedBody(input: BufferedInputStream, contentLength: Int, maxKeepBytes: Int): ByteArray {
        val keep = minOf(contentLength, maxKeepBytes)
        val bodyBytes = ByteArray(keep)
        var readTotal = 0
        while (readTotal < keep) {
            val n = input.read(bodyBytes, readTotal, keep - readTotal)
            if (n <= 0) break
            readTotal += n
        }
        var extra = minOf(contentLength - keep, MAX_DRAIN_BYTES)
        if (extra > 0) {
            val drainBuf = ByteArray(4_096)
            while (extra > 0) {
                val n = input.read(drainBuf, 0, minOf(extra, drainBuf.size))
                if (n <= 0) break
                extra -= n
            }
        }
        return if (readTotal == keep) bodyBytes else bodyBytes.copyOf(readTotal)
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val sb = StringBuilder(32)
        var sawAny = false
        while (sb.length < 256) {
            val b = input.read()
            if (b == -1) return if (sawAny) sb.toString() else null
            sawAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun bodyLimitFor(path: String): Int = when {
        path == "/api/hikvision/face-upload" || path == "/api/hikvision/quick-enroll" -> MAX_FACE_UPLOAD_BYTES
        path.trimEnd('/') == "/hikvision/event" -> MAX_WEBHOOK_BODY_BYTES
        else -> MAX_BODY_BYTES
    }
}

package com.edusing.chamcongtvbox.data.protocol

import java.nio.charset.StandardCharsets

/**
 * Tiện ích đọc/ghi nhị phân Little-Endian và chuỗi UTF-16LE khớp struct x86 của `FKAttend.dll`.
 */
object BinaryUtils {

    fun u8(buf: ByteArray, offset: Int): Int = buf[offset].toInt() and 0xFF

    fun u16LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8)

    fun u32LE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    fun putU16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    fun putU32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    /** Mã hoá chuỗi sang UTF-16LE độ dài cố định, luôn chừa 2 byte NUL ở cuối. */
    fun encodeUtf16LeFixed(text: String, byteLength: Int): ByteArray {
        val out = ByteArray(byteLength)
        if (byteLength < 2 || text.isEmpty()) return out
        val maxChars = (byteLength / 2) - 1
        val clipped = if (text.length > maxChars) text.substring(0, maxChars) else text
        val encoded = clipped.toByteArray(StandardCharsets.UTF_16LE)
        System.arraycopy(encoded, 0, out, 0, encoded.size.coerceAtMost(byteLength - 2))
        return out
    }

    /** Giải mã lát cắt UTF-16LE kết thúc bằng 2 byte `0x00 0x00`. */
    fun decodeUtf16LeNullTerminated(buf: ByteArray, offset: Int = 0, maxBytes: Int = buf.size - offset): String {
        val safeLen = maxBytes.coerceAtMost(buf.size - offset).coerceAtLeast(0)
        var end = 0
        while (end + 1 < safeLen) {
            if (buf[offset + end].toInt() == 0 && buf[offset + end + 1].toInt() == 0) break
            end += 2
        }
        if (end <= 0) return ""
        return String(buf, offset, end, StandardCharsets.UTF_16LE).trimEnd('\u0000')
    }

    /** Giải mã lát cắt ASCII/UTF-8 kết thúc bằng byte `0x00`. */
    fun decodeAsciiNullTerminated(buf: ByteArray, offset: Int = 0, maxBytes: Int = buf.size - offset): String {
        val safeLen = maxBytes.coerceAtMost(buf.size - offset).coerceAtLeast(0)
        var end = 0
        while (end < safeLen && buf[offset + end].toInt() != 0) end++
        if (end <= 0) return ""
        return String(buf, offset, end, StandardCharsets.UTF_8).trimEnd('\u0000')
    }

    /** Tính tổng unsigned byte 16-bit dùng cho checksum header `LFWViaDev`. */
    fun byteSum16(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int {
        var sum = 0
        for (i in offset until (offset + length)) {
            sum = (sum + (buf[i].toInt() and 0xFF)) and 0xFFFF
        }
        return sum
    }
}

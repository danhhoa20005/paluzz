package com.edusing.chamcongtvbox.service.web

import java.io.ByteArrayOutputStream
import java.util.Locale

internal data class MdnsMatchedQuery(
    val txId: Int,
    val wantUnicast: Boolean,
)

/**
 * Bộ mã hoá / giải mã gói tin mDNS chuẩn RFC 6762 / RFC 1035 (thuần Kotlin):
 * - Nhận truy vấn bản ghi `A` / `ANY` cho tên miền LAN cố định `chamcong.local` (hỗ trợ nén tên `0xC0`, nhiều câu hỏi).
 * - Trả lời bản ghi `A` kèm cờ `AA` (`0x8400`) và `Cache-Flush` (`0x8001`) để Mac, Windows 10/11, iPhone, Android
 *   tự phân giải ngay `http://chamcong.local:8080` kể cả khi Router đổi IP TV Box.
 */
internal object MdnsPacketCodec {

    private const val TYPE_A = 1
    private const val TYPE_ANY = 255
    private const val CLASS_IN = 1
    private const val CLASS_ANY = 255
    private const val CLASS_MASK = 0x7FFF
    private const val FLAG_QU_OR_FLUSH = 0x8000

    fun parseMatchingQuery(packet: ByteArray, length: Int, targetHost: String): MdnsMatchedQuery? {
        if (length < 12 || length > packet.size) return null
        val txId = readU16(packet, 0)
        val flags = readU16(packet, 2)
        // Bit 15 (QR) == 1 là gói phản hồi, chỉ xử lý gói truy vấn (QR == 0)
        if ((flags and 0x8000) != 0) return null

        val qdCount = readU16(packet, 4)
        if (qdCount <= 0 || qdCount > 32) return null

        val normalizedTarget = targetHost.trim().trimEnd('.').lowercase(Locale.US)
        var offset = 12
        var matched = false
        var wantUnicast = false

        for (i in 0 until qdCount) {
            val (name, nextOffset) = readDnsName(packet, length, offset) ?: return null
            if (nextOffset + 4 > length) return null
            val qType = readU16(packet, nextOffset)
            val rawClass = readU16(packet, nextOffset + 2)
            offset = nextOffset + 4

            val qClass = rawClass and CLASS_MASK
            val isTarget = name.lowercase(Locale.US) == normalizedTarget
            val isIpv4Query = qType == TYPE_A || qType == TYPE_ANY
            val isInClass = qClass == CLASS_IN || qClass == CLASS_ANY
            if (isTarget && isIpv4Query && isInClass) {
                matched = true
                if ((rawClass and FLAG_QU_OR_FLUSH) != 0) wantUnicast = true
            }
        }
        return if (matched) MdnsMatchedQuery(txId = txId, wantUnicast = wantUnicast) else null
    }

    fun buildARecordResponse(
        host: String,
        ipv4Bytes: ByteArray,
        txId: Int = 0,
        cacheFlush: Boolean = true,
        ttlSeconds: Int = 120,
    ): ByteArray {
        require(ipv4Bytes.size == 4) { "IPv4 phải gồm đúng 4 byte" }
        val out = ByteArrayOutputStream(64)
        // Header 12 byte: ID, Flags=0x8400 (QR=1, AA=1), QD=0, AN=1, NS=0, AR=0
        writeU16(out, txId and 0xFFFF)
        writeU16(out, 0x8400)
        writeU16(out, 0)
        writeU16(out, 1)
        writeU16(out, 0)
        writeU16(out, 0)

        // Answer RR: Name + Type A + Class IN (+ Cache-Flush) + TTL + IPv4
        writeDnsName(out, host.trim().trimEnd('.'))
        writeU16(out, TYPE_A)
        writeU16(out, if (cacheFlush) (CLASS_IN or FLAG_QU_OR_FLUSH) else CLASS_IN)
        writeU32(out, ttlSeconds.coerceIn(10, 3600))
        writeU16(out, 4)
        out.write(ipv4Bytes)
        return out.toByteArray()
    }

    private fun readDnsName(packet: ByteArray, length: Int, startOffset: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var pos = startOffset
        var endPos = -1
        var jumps = 0

        while (pos < length) {
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) {
                if (endPos < 0) endPos = pos + 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (pos + 1 >= length || ++jumps > 10) return null
                val ptr = ((len and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                if (ptr >= length) return null
                if (endPos < 0) endPos = pos + 2
                pos = ptr
                continue
            }
            if ((len and 0xC0) != 0 || pos + 1 + len > length) return null
            labels.add(String(packet, pos + 1, len, Charsets.UTF_8))
            pos += 1 + len
        }
        if (endPos < 0) return null
        return labels.joinToString(".") to endPos
    }

    private fun writeDnsName(out: ByteArrayOutputStream, host: String) {
        host.split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size and 0x3F)
            out.write(bytes)
        }
        out.write(0)
    }

    private fun readU16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}

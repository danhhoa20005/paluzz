package com.edusing.chamcongtvbox.service.web

import com.edusing.chamcongtvbox.domain.model.AppConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Kiểm thử mã hoá / giải mã gói tin mDNS RFC 6762 cho tên miền cố định `chamcong.local`. */
class MdnsPacketCodecTest {

    private fun buildQueryPacket(
        host: String,
        qType: Int = 1,
        qClass: Int = 1,
        txId: Int = 0,
        flags: Int = 0,
        extraHost: String? = null,
        extraType: Int = 28,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
        fun writeName(h: String) {
            h.split('.').forEach { l -> val b = l.toByteArray(); out.write(b.size); out.write(b) }
            out.write(0)
        }
        u16(txId); u16(flags); u16(if (extraHost != null) 2 else 1); u16(0); u16(0); u16(0)
        if (extraHost != null) { writeName(extraHost); u16(extraType); u16(1) }
        writeName(host); u16(qType); u16(qClass)
        return out.toByteArray()
    }

    @Test
    fun parseMatchingQuery_matchesChamcongLocalCaseInsensitiveAndMultiQuestion() {
        val pkt = buildQueryPacket(host = "ChamCong.LOCAL", qType = 1, qClass = 0x8001, txId = 0x1234, extraHost = "other.local")
        val matched = MdnsPacketCodec.parseMatchingQuery(pkt, pkt.size, "chamcong.local")
        assertNotNull(matched)
        assertEquals(0x1234, matched!!.txId)
        assertTrue("Phải nhận diện cờ QU (unicast response)", matched.wantUnicast)
    }

    @Test
    fun parseMatchingQuery_ignoresResponsesUnrelatedHostsAndMalformedBytes() {
        val responsePkt = buildQueryPacket("chamcong.local", flags = 0x8400)
        assertNull("Không được tự trả lời gói response", MdnsPacketCodec.parseMatchingQuery(responsePkt, responsePkt.size, "chamcong.local"))

        val otherHost = buildQueryPacket("printer.local")
        assertNull(MdnsPacketCodec.parseMatchingQuery(otherHost, otherHost.size, "chamcong.local"))

        val aaaaOnly = buildQueryPacket("chamcong.local", qType = 28)
        assertNull("Không trả bản ghi IPv4 cho truy vấn chỉ hỏi AAAA", MdnsPacketCodec.parseMatchingQuery(aaaaOnly, aaaaOnly.size, "chamcong.local"))

        assertNull(MdnsPacketCodec.parseMatchingQuery(byteArrayOf(1, 2, 3), 3, "chamcong.local"))
    }

    @Test
    fun buildARecordResponse_encodesValidRfc6762HeaderCacheFlushAndIpv4() {
        val ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 33)
        val resp = MdnsPacketCodec.buildARecordResponse("chamcong.local", ip, txId = 0, cacheFlush = true, ttlSeconds = 120)

        // Header: txId=0, flags=0x8400 (QR+AA), QD=0, AN=1
        assertEquals(0x84, resp[2].toInt() and 0xFF)
        assertEquals(0x00, resp[3].toInt() and 0xFF)
        assertEquals(1, resp[7].toInt() and 0xFF)
        // 4 byte cuối chính là địa chỉ IPv4 192.168.1.33
        assertArrayEquals(ip, resp.copyOfRange(resp.size - 4, resp.size))
        // Cờ Cache-Flush (0x8001) nằm trước TTL (4 byte) + RDLENGTH (2 byte) + IPv4 (4 byte) = 12 byte từ cuối
        val classHi = resp[resp.size - 12].toInt() and 0xFF
        val classLo = resp[resp.size - 11].toInt() and 0xFF
        assertEquals(0x80, classHi)
        assertEquals(0x01, classLo)
    }

    @Test
    fun buildARecordResponse_legacyUnicastClearsCacheFlushBit() {
        val ip = byteArrayOf(10, 0, 0, 5)
        val resp = MdnsPacketCodec.buildARecordResponse("chamcong.local", ip, txId = 0x55AA, cacheFlush = false)
        assertEquals(0x55, resp[0].toInt() and 0xFF)
        assertEquals(0xAA, resp[1].toInt() and 0xFF)
        assertFalse((resp[resp.size - 12].toInt() and 0x80) != 0)
    }

    @Test
    fun sanitizeLocalHost_normalizesMultiBoxHostnamesSafely() {
        assertEquals("chamcong", AppConfig.sanitizeLocalHost(""))
        assertEquals("chamcong2", AppConfig.sanitizeLocalHost("ChamCong2.local"))
        assertEquals("cong-chinh-1", AppConfig.sanitizeLocalHost("  Cong Chinh 1!! "))
    }
}

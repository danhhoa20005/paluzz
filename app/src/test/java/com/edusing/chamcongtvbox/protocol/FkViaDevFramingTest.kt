package com.edusing.chamcongtvbox.protocol

import com.edusing.chamcongtvbox.data.protocol.BinaryUtils
import com.edusing.chamcongtvbox.data.protocol.FkViaDevTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FkViaDevFramingTest {

    @Test
    fun buildSendCommandFrame_exact16BytesLittleEndian() {
        val frame = FkViaDevTransport.buildRequestFrame(
            machineNo = 1,
            cmd = 0x80,
            param = 0x12345678,
            inParam16 = 0x00AB,
            blockIndex = 0xFFFF,
            blockSize = 0,
            seqNo = 7,
            writeChunk = null,
        )

        assertEquals(16, frame.size)
        val expected = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(),
            0x01, 0x80.toByte(),
            0x78, 0x56, 0x34, 0x12,
            0xAB.toByte(), 0x00,
            0xFF.toByte(), 0xFF.toByte(),
            0x00, 0x00,
            0x07, 0x00,
        )
        assertArrayEquals(expected, frame)
    }

    @Test
    fun buildWriteDataFrame_appends55AAPayloadAndTwoZeroPadBytes() {
        val chunk = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val frame = FkViaDevTransport.buildRequestFrame(
            machineNo = 2,
            cmd = 0xC8,
            param = 1001,
            inParam16 = 0,
            blockIndex = 0,
            blockSize = chunk.size,
            seqNo = 3,
            writeChunk = chunk,
        )

        assertEquals(16 + 2 + 4 + 2, frame.size)
        assertEquals(0x55.toByte(), frame[16])
        assertEquals(0xAA.toByte(), frame[17])
        assertArrayEquals(chunk, frame.copyOfRange(18, 22))
        assertEquals(0x00.toByte(), frame[22])
        assertEquals(0x00.toByte(), frame[23])
    }

    @Test
    fun findResponseHeader_skipsLeadingGarbageAndMatchesSeqOrZeroWildcard() {
        val buf = ByteArray(25)
        // Leading noise including fake AA 55 with wrong seqNo (99)
        buf[0] = 0xAA.toByte()
        buf[1] = 0x55.toByte()
        BinaryUtils.putU16LE(buf, 8, 99)

        // Valid response starting at offset 12 with seqNo = 5
        buf[12] = 0xAA.toByte()
        buf[13] = 0x55.toByte()
        buf[14] = 0x01
        buf[15] = 0x01
        BinaryUtils.putU32LE(buf, 16, 42)
        BinaryUtils.putU16LE(buf, 20, 5)

        assertEquals(12, FkViaDevTransport.findResponseHeader(buf, buf.size, expectedSeqNo = 5))
        assertEquals(-1, FkViaDevTransport.findResponseHeader(buf, buf.size, expectedSeqNo = 6))

        // SeqNo = 0 acts as wildcard (matching FKViaDev.dll)
        BinaryUtils.putU16LE(buf, 20, 0)
        assertEquals(12, FkViaDevTransport.findResponseHeader(buf, buf.size, expectedSeqNo = 6))
    }
}

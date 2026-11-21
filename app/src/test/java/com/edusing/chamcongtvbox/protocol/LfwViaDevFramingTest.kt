package com.edusing.chamcongtvbox.protocol

import com.edusing.chamcongtvbox.data.protocol.BinaryUtils
import com.edusing.chamcongtvbox.data.protocol.LfwViaDevTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class LfwViaDevFramingTest {

    @Test
    fun buildRequestFrame_computes16BitUnsignedByteSumChecksum() {
        val frame = LfwViaDevTransport.buildRequestFrame(
            machineNo = 1,
            cmd = 0xB4,
            param = 8,
            inParam32 = 0x00000011,
            blockIndex = 0xFFFF,
            blockSize = 0,
            seqNo = 4,
            writeChunk = null,
        )

        assertEquals(20, frame.size)
        assertEquals(0x55.toByte(), frame[0])
        assertEquals(0xAA.toByte(), frame[1])
        assertEquals(1, BinaryUtils.u8(frame, 2))
        assertEquals(0xB4, BinaryUtils.u8(frame, 3))
        assertEquals(8, BinaryUtils.u32LE(frame, 4))
        assertEquals(0x11, BinaryUtils.u32LE(frame, 8))
        assertEquals(0xFFFF, BinaryUtils.u16LE(frame, 12))
        assertEquals(0, BinaryUtils.u16LE(frame, 14))
        assertEquals(4, BinaryUtils.u16LE(frame, 18))

        // Verify checksum manually: sum of all header bytes excluding [16..17]
        var manualSum = 0
        for (i in 0 until 20) {
            if (i == 16 || i == 17) continue
            manualSum = (manualSum + (frame[i].toInt() and 0xFF)) and 0xFFFF
        }
        assertEquals(manualSum, BinaryUtils.u16LE(frame, 16))
    }

    @Test
    fun findResponseHeader_acceptsValidChecksumAndRejectsCorruptedHeader() {
        val resp = ByteArray(16)
        resp[0] = 0xAA.toByte()
        resp[1] = 0x55.toByte()
        resp[2] = 0x01
        resp[3] = 0x01
        BinaryUtils.putU32LE(resp, 4, 123)
        BinaryUtils.putU32LE(resp, 12, 9)
        val cs = LfwViaDevTransport.computeRespHeaderChecksum(resp, 0)
        BinaryUtils.putU16LE(resp, 10, cs)

        assertEquals(0, LfwViaDevTransport.findResponseHeader(resp, resp.size, expectedSeqNo = 9))
        assertEquals(-1, LfwViaDevTransport.findResponseHeader(resp, resp.size, expectedSeqNo = 10))

        // Corrupt 1 bit in outParam without updating checksum -> must be rejected
        resp[4] = (resp[4].toInt() xor 0x01).toByte()
        assertEquals(-1, LfwViaDevTransport.findResponseHeader(resp, resp.size, expectedSeqNo = 9))
    }
}

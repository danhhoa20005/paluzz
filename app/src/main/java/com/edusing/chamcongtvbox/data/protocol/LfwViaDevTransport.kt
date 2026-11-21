package com.edusing.chamcongtvbox.data.protocol

import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Cài đặt thuần Kotlin giao thức `LFWViaDev.dll` cho máy Linux Firmware (`CFKAttend_LFW`).
 * Tự động dùng dự phòng khi bắt tay `FKViaDev` thất bại.
 *
 * Khác với `FKViaDev`: Request dài 20B và Response dài 16B, đều kèm checksum tổng byte 16-bit.
 */
class LfwViaDevTransport(
    private val channel: FkByteChannel,
    override var maxBlockSize: Int = FkTransport.DEFAULT_BLOCK_SIZE,
    private val maxRetries: Int = MAX_RETRIES,
) : FkTransport {

    override val transportName: String = "LFWViaDev-${channel.protocolLabel}"
    private var seqNo: Int = 0

    private fun nextSeqNo(): Int {
        seqNo = if (seqNo >= 0xFFFF || seqNo < 0) 1 else seqNo + 1
        return seqNo
    }

    override fun sendCommand(machineNo: Int, cmd: Int, param: Int, inParam: Int, timeoutMs: Int): FkTransportResponse {
        val timeout = if (timeoutMs > 0) timeoutMs else FkTransport.DEFAULT_TIMEOUT_MS
        var lastError = FkErrorCode.ERR_COMM
        for (attempt in 1..maxRetries.coerceAtLeast(1)) {
            val seq = nextSeqNo()
            val frame = buildRequestFrame(machineNo, cmd, param, inParam, 0xFFFF, 0, seq, null)
            val resp = exchangeFrame(frame, seq, expectedDataLen = 0, timeoutMs = timeout)
            if (resp.resultCode == FkErrorCode.SUCCESS || resp.resultCode == FkErrorCode.ERR_DEVICE_FAIL) return resp
            lastError = resp.resultCode
            if (!channel.isOpen) break
        }
        return FkTransportResponse(lastError)
    }

    override fun readData(
        machineNo: Int,
        cmd: Int,
        totalLength: Int,
        param: Int,
        inParam: Int,
        timeoutMs: Int,
    ): FkTransportResponse {
        if (totalLength <= 0) return FkTransportResponse(FkErrorCode.SUCCESS, 0, ByteArray(0))
        val timeout = if (timeoutMs > 0) timeoutMs else FkTransport.DEFAULT_TIMEOUT_MS
        val result = ByteArray(totalLength)
        val chunkLimit = maxBlockSize.coerceIn(64, 4096)
        var offset = 0
        var blockIndex = 0
        var lastOutParam = 0

        while (offset < totalLength) {
            val chunkLen = (totalLength - offset).coerceAtMost(chunkLimit)
            val resp = retryChunk {
                val seq = nextSeqNo()
                val frame = buildRequestFrame(machineNo, cmd, param, inParam, blockIndex, chunkLen, seq, null)
                exchangeFrame(frame, seq, expectedDataLen = chunkLen, timeoutMs = timeout)
            }
            if (!resp.isSuccess) return FkTransportResponse(resp.resultCode, resp.outParam)
            if (resp.data.size < chunkLen) return FkTransportResponse(FkErrorCode.ERR_COMM)
            System.arraycopy(resp.data, 0, result, offset, chunkLen)
            lastOutParam = resp.outParam
            offset += chunkLen
            blockIndex++
        }
        return FkTransportResponse(FkErrorCode.SUCCESS, lastOutParam, result)
    }

    override fun writeData(
        machineNo: Int,
        cmd: Int,
        payload: ByteArray,
        param: Int,
        inParam: Int,
        timeoutMs: Int,
    ): FkTransportResponse {
        if (payload.isEmpty()) return sendCommand(machineNo, cmd, param, inParam, timeoutMs)
        val timeout = if (timeoutMs > 0) timeoutMs else FkTransport.DEFAULT_TIMEOUT_MS
        val chunkLimit = maxBlockSize.coerceIn(64, 4096)
        var offset = 0
        var blockIndex = 0
        var lastOutParam = 0

        while (offset < payload.size) {
            val chunkLen = (payload.size - offset).coerceAtMost(chunkLimit)
            val chunk = payload.copyOfRange(offset, offset + chunkLen)
            val resp = retryChunk {
                val seq = nextSeqNo()
                val frame = buildRequestFrame(machineNo, cmd, param, inParam, blockIndex, chunkLen, seq, chunk)
                exchangeFrame(frame, seq, expectedDataLen = 0, timeoutMs = timeout)
            }
            if (!resp.isSuccess) return FkTransportResponse(resp.resultCode, resp.outParam)
            lastOutParam = resp.outParam
            offset += chunkLen
            blockIndex++
        }
        return FkTransportResponse(FkErrorCode.SUCCESS, lastOutParam)
    }

    private inline fun retryChunk(block: () -> FkTransportResponse): FkTransportResponse {
        var last = FkTransportResponse(FkErrorCode.ERR_COMM)
        for (attempt in 1..maxRetries.coerceAtLeast(1)) {
            val resp = block()
            last = resp
            if (resp.resultCode == FkErrorCode.SUCCESS || resp.resultCode == FkErrorCode.ERR_DEVICE_FAIL) break
            if (!channel.isOpen) break
        }
        return last
    }

    private fun exchangeFrame(
        request: ByteArray,
        expectedSeqNo: Int,
        expectedDataLen: Int,
        timeoutMs: Int,
    ): FkTransportResponse {
        return try {
            channel.drainInput()
            channel.sendFrame(request, timeoutMs)

            val buffer = ByteArray(HEADER_RESP_SIZE + expectedDataLen + 64)
            var totalRead = 0
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(500)

            while (System.currentTimeMillis() < deadline) {
                val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(100)
                val n = try {
                    channel.readChunk(buffer, totalRead, buffer.size - totalRead, remaining)
                } catch (_: SocketTimeoutException) {
                    return FkTransportResponse(FkErrorCode.ERR_COMM)
                }
                if (n < 0) return FkTransportResponse(FkErrorCode.ERR_COMM)
                if (n == 0) continue
                totalRead += n

                val hdrIdx = findResponseHeader(buffer, totalRead, expectedSeqNo)
                if (hdrIdx < 0) {
                    if (totalRead >= buffer.size) return FkTransportResponse(FkErrorCode.ERR_COMM)
                    continue
                }

                val status = BinaryUtils.u8(buffer, hdrIdx + 3)
                val outParam = BinaryUtils.u32LE(buffer, hdrIdx + 4)
                if (status != 0x01) {
                    val code = if (status == 0xFD) FkErrorCode.ERR_COMM else FkErrorCode.ERR_DEVICE_FAIL
                    return FkTransportResponse(code, outParam)
                }
                if (expectedDataLen == 0) return FkTransportResponse(FkErrorCode.SUCCESS, outParam)

                // Đọc ngay khi đủ [16B header][55 AA][payload] (khớp LFWViaDev.dll, tránh treo nếu thiếu 2B đuôi)
                val neededEnd = hdrIdx + HEADER_RESP_SIZE + 2 + expectedDataLen
                if (neededEnd > buffer.size) return FkTransportResponse(FkErrorCode.ERR_COMM)
                if (totalRead < neededEnd) continue
                if (buffer[hdrIdx + 16] != MAGIC_REQ_0 || buffer[hdrIdx + 17] != MAGIC_REQ_1) {
                    return FkTransportResponse(FkErrorCode.ERR_COMM)
                }
                val payload = buffer.copyOfRange(hdrIdx + 18, hdrIdx + 18 + expectedDataLen)
                return FkTransportResponse(FkErrorCode.SUCCESS, outParam, payload)
            }
            FkTransportResponse(FkErrorCode.ERR_COMM)
        } catch (_: IOException) {
            FkTransportResponse(FkErrorCode.ERR_COMM)
        }
    }

    override fun close() = channel.close()

    companion object {
        const val HEADER_REQ_SIZE = 20
        const val HEADER_RESP_SIZE = 16
        const val MAGIC_REQ_0: Byte = 0x55.toByte()
        const val MAGIC_REQ_1: Byte = 0xAA.toByte()
        const val MAGIC_RESP_0: Byte = 0xAA.toByte()
        const val MAGIC_RESP_1: Byte = 0x55.toByte()
        private const val MAX_RETRIES = 3

        fun connect(
            ip: String, port: Int, timeoutMs: Int, useUdp: Boolean = false,
            connectTimeoutMs: Int = timeoutMs, maxRetries: Int = MAX_RETRIES,
        ): LfwViaDevTransport {
            val ch = if (useUdp) UdpByteChannel.open(ip, port, timeoutMs)
            else TcpByteChannel.open(ip, port, connectTimeoutMs, timeoutMs)
            return LfwViaDevTransport(ch, maxRetries = maxRetries)
        }

        /** Đóng gói khung yêu cầu 20B có tính checksum tổng unsigned byte 16-bit tại `[16..17]`. */
        fun buildRequestFrame(
            machineNo: Int,
            cmd: Int,
            param: Int,
            inParam32: Int,
            blockIndex: Int,
            blockSize: Int,
            seqNo: Int,
            writeChunk: ByteArray?,
        ): ByteArray {
            val hasData = writeChunk != null && blockSize > 0
            val buf = ByteArray(if (hasData) HEADER_REQ_SIZE + 2 + blockSize + 2 else HEADER_REQ_SIZE)
            buf[0] = MAGIC_REQ_0
            buf[1] = MAGIC_REQ_1
            buf[2] = (machineNo and 0xFF).toByte()
            buf[3] = (cmd and 0xFF).toByte()
            BinaryUtils.putU32LE(buf, 4, param)
            BinaryUtils.putU32LE(buf, 8, inParam32)
            BinaryUtils.putU16LE(buf, 12, blockIndex)
            BinaryUtils.putU16LE(buf, 14, blockSize)
            BinaryUtils.putU16LE(buf, 18, seqNo)
            BinaryUtils.putU16LE(buf, 16, BinaryUtils.byteSum16(buf, 0, HEADER_REQ_SIZE))

            if (writeChunk != null && blockSize > 0) {
                buf[20] = MAGIC_REQ_0
                buf[21] = MAGIC_REQ_1
                System.arraycopy(writeChunk, 0, buf, 22, blockSize)
                buf[22 + blockSize] = 0
                buf[23 + blockSize] = 0
            }
            return buf
        }

        /** Tìm đầu khung phản hồi LFW 16B hợp lệ cả `seqNo` lẫn `checksum`. */
        fun findResponseHeader(buffer: ByteArray, length: Int, expectedSeqNo: Int): Int {
            val limit = length - HEADER_RESP_SIZE
            for (i in 0..limit) {
                if (buffer[i] == MAGIC_RESP_0 && buffer[i + 1] == MAGIC_RESP_1) {
                    val respSeq = BinaryUtils.u32LE(buffer, i + 12)
                    if (respSeq != 0 && respSeq != (expectedSeqNo and 0xFFFF)) continue
                    if (BinaryUtils.u16LE(buffer, i + 10) == computeRespHeaderChecksum(buffer, i)) return i
                }
            }
            return -1
        }

        /** Tính tổng unsigned byte 16-bit của header phản hồi 16B (bỏ qua 2 byte checksum `[10..11]`). */
        fun computeRespHeaderChecksum(buffer: ByteArray, headerStart: Int): Int {
            var sum = 0
            for (k in 0 until HEADER_RESP_SIZE) {
                if (k == 10 || k == 11) continue
                sum = (sum + (buffer[headerStart + k].toInt() and 0xFF)) and 0xFFFF
            }
            return sum
        }
    }
}

package com.edusing.chamcongtvbox.protocol

import com.edusing.chamcongtvbox.data.protocol.BinaryUtils
import com.edusing.chamcongtvbox.data.protocol.FkCommands
import com.edusing.chamcongtvbox.data.protocol.FkDeviceGatewayImpl
import com.edusing.chamcongtvbox.data.protocol.FkViaDevTransport
import com.edusing.chamcongtvbox.data.protocol.LfwViaDevTransport
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kiểm thử đầu-cuối qua Socket thật trên localhost (127.0.0.1) giả lập 3 loại máy chấm công:
 * 1. FKViaDev qua TCP (`CIF11_1`, `license = 5912`)
 * 2. LFWViaDev qua TCP (tự động fallback sang Linux Firmware)
 * 3. FKViaDev qua UDP (`UDP_MODE = true`)
 */
class FkDeviceGatewaySessionTest {

    @Test
    fun fkViaDev_tcp_cif11_1_fullEndToEndSession() = runTest {
        val enrolledCards = ConcurrentHashMap<Int, ByteArray>()
        val userNames = ConcurrentHashMap<Int, String>()
        val unreadLogs = mutableListOf(buildPacked12Log(100200, 2026, 11, 19, 8, 15, 22))
        val savedFlash = AtomicBoolean(false)
        val running = AtomicBoolean(true)

        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                while (running.get()) {
                    val sock = server.accept()
                    val dis = DataInputStream(sock.getInputStream())
                    val out = sock.getOutputStream()
                    try {
                        while (running.get()) {
                            val hdr = ByteArray(16).also { dis.readFully(it) }
                            val machineNo = BinaryUtils.u8(hdr, 2)
                            val cmd = BinaryUtils.u8(hdr, 3)
                            val param = BinaryUtils.u32LE(hdr, 4)
                            val inParam = BinaryUtils.u16LE(hdr, 8)
                            val blockIndex = BinaryUtils.u16LE(hdr, 10)
                            val blockSize = BinaryUtils.u16LE(hdr, 12)
                            val seqNo = BinaryUtils.u16LE(hdr, 14)

                            var writePayload: ByteArray? = null
                            if (blockIndex != 0xFFFF && blockSize > 0 && isWriteCmd(cmd)) {
                                dis.readFully(ByteArray(2))
                                writePayload = ByteArray(blockSize).also { dis.readFully(it) }
                                dis.readFully(ByteArray(2))
                            }

                            when (cmd) {
                                FkCommands.CONNECT_AUTH -> sendFkResp(out, machineNo, 1, 0, seqNo)
                                FkCommands.GET_DEVICE_INFO -> {
                                    val extJson = """{"CommunicationInterfaceId":"11_1"}"""
                                    if (param == 0) {
                                        val info = ByteArray(48).apply {
                                            BinaryUtils.putU16LE(this, 0, 0x0104)
                                            BinaryUtils.putU32LE(this, 8, 5912) // Mã bản quyền thực tế từ AppMayTinh
                                            BinaryUtils.putU16LE(this, 46, extJson.length)
                                        }
                                        sendFkResp(out, machineNo, 1, 0, seqNo, info)
                                    } else {
                                        sendFkResp(out, machineNo, 1, 0, seqNo, extJson.toByteArray(Charsets.US_ASCII))
                                    }
                                }
                                FkCommands.ENABLE_DEVICE -> sendFkResp(out, machineNo, 1, 0, seqNo)
                                FkCommands.GET_DEVICE_STATUS -> {
                                    val v = when (param) { 2 -> enrolledCards.size; 6, 8 -> unreadLogs.size; else -> 0 }
                                    sendFkResp(out, machineNo, 1, v, seqNo)
                                }
                                FkCommands.READ_UNREAD_GLOG -> {
                                    val buf = ByteArray(unreadLogs.size * 12)
                                    unreadLogs.forEachIndexed { i, r -> System.arraycopy(r, 0, buf, i * 12, 12) }
                                    sendFkResp(out, machineNo, 1, unreadLogs.size, seqNo, buf)
                                }
                                FkCommands.MARK_GLOG_READ -> sendFkResp(out, machineNo, 1, 0, seqNo)
                                FkCommands.EMPTY_GENERAL_LOG -> { unreadLogs.clear(); sendFkResp(out, machineNo, 1, 0, seqNo) }
                                FkCommands.PUT_ENROLL_DATA -> { enrolledCards[BinaryUtils.u32LE(writePayload!!, 0)] = writePayload; sendFkResp(out, machineNo, 1, 0, seqNo) }
                                FkCommands.SET_USER_NAME -> { userNames[param] = BinaryUtils.decodeUtf16LeNullTerminated(writePayload!!, 0, writePayload.size); sendFkResp(out, machineNo, 1, 0, seqNo) }
                                FkCommands.GET_USER_NAME -> sendFkResp(out, machineNo, 1, 0, seqNo, BinaryUtils.encodeUtf16LeFixed(userNames[param].orEmpty(), 14))
                                FkCommands.READ_ALL_USER_ID -> {
                                    val ids = enrolledCards.keys.sorted()
                                    val buf = ByteArray(ids.size * 8)
                                    ids.forEachIndexed { idx, id ->
                                        BinaryUtils.putU32LE(buf, idx * 8, id)
                                        buf[idx * 8 + 5] = 1
                                        BinaryUtils.putU16LE(buf, idx * 8 + 6, 0x02)
                                    }
                                    sendFkResp(out, machineNo, 1, ids.size, seqNo, buf)
                                }
                                FkCommands.SAVE_ENROLL_DATA -> { savedFlash.set(true); sendFkResp(out, machineNo, 1, 0, seqNo) }
                                FkCommands.DELETE_ENROLL_DATA -> {
                                    val ok = inParam == FkErrorCode.BACKUP_CARD && enrolledCards.remove(param) != null
                                    if (ok) userNames.remove(param)
                                    sendFkResp(out, machineNo, if (ok) 1 else 0, 0, seqNo)
                                }
                                else -> sendFkResp(out, machineNo, 0, 0, seqNo)
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { sock.close() }
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        val gateway = FkDeviceGatewayImpl()
        try {
            val conn = gateway.connect(1, "127.0.0.1", server.localPort, timeoutMs = 3_000, license = 5912)
            assertTrue(conn.isSuccess)
            val info = conn.data!!
            assertEquals(FkFirmwareProfile.CIF11_1, info.profile)
            assertEquals("FKViaDev-TCP", info.transportName)
            assertNotNull(gateway.getConnectionInfo(info.handle))

            assertTrue(gateway.enableDevice(info.handle, false).isSuccess)
            val logs = gateway.loadGeneralLogData(info.handle, readMark = true).data!!
            assertEquals(100200, logs.single().enrollNumber)
            assertTrue(gateway.emptyGeneralLogData(info.handle).isSuccess)
            assertEquals(FkErrorCode.ERR_LOG_END, gateway.loadGeneralLogData(info.handle, readMark = true).code)

            assertTrue(gateway.putCardEnrollData(info.handle, 556677, 556677).isSuccess)
            assertTrue(gateway.setUserName(info.handle, 556677, "NT.P").isSuccess)
            assertTrue(gateway.saveEnrollData(info.handle).isSuccess)
            assertTrue(savedFlash.get())
            assertEquals("NT.P", gateway.getUserName(info.handle, 556677).data)
            assertEquals(listOf(556677), gateway.readAllUserIds(info.handle).data!!.map { it.enrollNumber })

            assertTrue(gateway.deleteEnrollData(info.handle, 556677, FkErrorCode.BACKUP_CARD).isSuccess)
            assertTrue(gateway.readAllUserIds(info.handle).data!!.isEmpty())

            gateway.disconnect(info.handle)
            assertNull(gateway.getConnectionInfo(info.handle))
        } finally {
            running.set(false)
            runCatching { server.close() }
            thread.join(1_000)
        }
    }

    @Test
    fun lfwViaDev_tcp_autoFallbackHandshake() = runTest {
        val running = AtomicBoolean(true)
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                while (running.get()) {
                    val sock = server.accept()
                    val dis = DataInputStream(sock.getInputStream())
                    val out = sock.getOutputStream()
                    try {
                        val first16 = ByteArray(16).also { dis.readFully(it) }
                        sock.soTimeout = 150
                        val extra4 = ByteArray(4)
                        val isLfw = runCatching { dis.readFully(extra4); true }.getOrDefault(false)
                        if (!isLfw) { sock.close(); continue }
                        sock.soTimeout = 4_000
                        var hdr = first16 + extra4
                        while (running.get()) {
                            val cmd = BinaryUtils.u8(hdr, 3)
                            val seqNo = BinaryUtils.u16LE(hdr, 18)
                            when (cmd) {
                                FkCommands.CONNECT_AUTH -> sendLfwResp(out, 1, 1, 0, seqNo)
                                FkCommands.GET_DEVICE_INFO -> sendLfwResp(out, 1, 1, 0, seqNo, ByteArray(54).apply { BinaryUtils.putU32LE(this, 8, 5912) })
                                else -> sendLfwResp(out, 1, 1, 0, seqNo)
                            }
                            hdr = ByteArray(20).also { dis.readFully(it) }
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { sock.close() }
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        val gateway = FkDeviceGatewayImpl()
        try {
            val conn = gateway.connect(1, "127.0.0.1", server.localPort, timeoutMs = 2_000)
            assertTrue(conn.isSuccess)
            assertEquals(FkFirmwareProfile.LFW, conn.data!!.profile)
            assertEquals("LFWViaDev-TCP", conn.data!!.transportName)
            gateway.disconnect(conn.data!!.handle)
        } finally {
            running.set(false)
            runCatching { server.close() }
            thread.join(1_000)
        }
    }

    @Test
    fun fkViaDev_udpMode_connectsAndReadsDeviceInfo() = runTest {
        val running = AtomicBoolean(true)
        val udpServer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            val recvBuf = ByteArray(2048)
            try {
                while (running.get()) {
                    val pkt = DatagramPacket(recvBuf, recvBuf.size)
                    udpServer.receive(pkt)
                    val cmd = BinaryUtils.u8(recvBuf, 3)
                    val seqNo = BinaryUtils.u16LE(recvBuf, 14)
                    val respBytes = when (cmd) {
                        FkCommands.CONNECT_AUTH -> buildFkRespBytes(1, 1, 0, seqNo, null)
                        FkCommands.GET_DEVICE_INFO -> {
                            val info = ByteArray(48).apply { BinaryUtils.putU32LE(this, 8, 5912) }
                            buildFkRespBytes(1, 1, 0, seqNo, info)
                        }
                        else -> buildFkRespBytes(1, 1, 0, seqNo, null)
                    }
                    udpServer.send(DatagramPacket(respBytes, respBytes.size, pkt.address, pkt.port))
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        val gateway = FkDeviceGatewayImpl()
        try {
            val conn = gateway.connect(1, "127.0.0.1", udpServer.localPort, timeoutMs = 2_000, useUdp = true)
            assertTrue("Kết nối UDP thất bại: ${conn.message}", conn.isSuccess)
            assertEquals("FKViaDev-UDP", conn.data!!.transportName)
            assertEquals(FkFirmwareProfile.GEN, conn.data!!.profile)
            gateway.disconnect(conn.data!!.handle)
        } finally {
            running.set(false)
            runCatching { udpServer.close() }
            thread.join(1_000)
        }
    }

    @Test
    fun closedTcpPort_doesNotRetryLfwTransport() = runTest {
        var lfwCalled = false
        val gateway = FkDeviceGatewayImpl(
            fkTransportFactory = { _, _, _, _ -> throw java.net.ConnectException("Connection refused") },
            lfwTransportFactory = { _, _, _, _ ->
                lfwCalled = true
                throw AssertionError("Không được gọi LFW khi mở socket TCP đã thất bại")
            },
        )
        val res = gateway.connect(1, "127.0.0.1", 59999, timeoutMs = 900, useUdp = false)
        assertEquals(FkErrorCode.ERR_COMM, res.code)
        assertTrue(!lfwCalled)
    }

    @Test
    fun fkSession_rejectsAbsurdLogCountSafelyWithoutOom() = runTest {
        val fakeTransport = object : com.edusing.chamcongtvbox.data.protocol.FkTransport {
            override val transportName = "Fake"
            override var maxBlockSize = 1024
            override fun sendCommand(machineNo: Int, cmd: Int, param: Int, inParam: Int, timeoutMs: Int) =
                com.edusing.chamcongtvbox.data.protocol.FkTransportResponse(FkErrorCode.SUCCESS, outParam = 50_000_000)
            override fun readData(machineNo: Int, cmd: Int, totalLength: Int, param: Int, inParam: Int, timeoutMs: Int): com.edusing.chamcongtvbox.data.protocol.FkTransportResponse {
                throw AssertionError("Không được cấp phát bộ đệm cho số bản ghi bất thường ($totalLength)")
            }
            override fun writeData(machineNo: Int, cmd: Int, payload: ByteArray, param: Int, inParam: Int, timeoutMs: Int) =
                com.edusing.chamcongtvbox.data.protocol.FkTransportResponse(FkErrorCode.SUCCESS)
            override fun close() = Unit
        }
        val session = com.edusing.chamcongtvbox.data.protocol.FkSession(
            info = com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo(1, "127.0.0.1", 5005, 1, FkFirmwareProfile.CIF11_1, "Fake", 1024),
            transport = fakeTransport,
            legacyLogType = 0,
        )
        val res = session.loadGeneralLogData(readMark = true)
        assertEquals(FkErrorCode.ERR_INVALID_DATA, res.code)
    }

    @Test
    fun fkSession_doesNotMarkLogsReadWhenPayloadFailsToParse() = runTest {
        var markedRead = false
        val fakeTransport = object : com.edusing.chamcongtvbox.data.protocol.FkTransport {
            override val transportName = "Fake"
            override var maxBlockSize = 1024
            override fun sendCommand(machineNo: Int, cmd: Int, param: Int, inParam: Int, timeoutMs: Int): com.edusing.chamcongtvbox.data.protocol.FkTransportResponse {
                if (cmd == FkCommands.MARK_GLOG_READ) markedRead = true
                val outParam = if (cmd == FkCommands.GET_DEVICE_STATUS) 1 else 0
                return com.edusing.chamcongtvbox.data.protocol.FkTransportResponse(FkErrorCode.SUCCESS, outParam = outParam)
            }
            override fun readData(machineNo: Int, cmd: Int, totalLength: Int, param: Int, inParam: Int, timeoutMs: Int) =
                com.edusing.chamcongtvbox.data.protocol.FkTransportResponse(FkErrorCode.SUCCESS, data = ByteArray(totalLength))
            override fun writeData(machineNo: Int, cmd: Int, payload: ByteArray, param: Int, inParam: Int, timeoutMs: Int) =
                com.edusing.chamcongtvbox.data.protocol.FkTransportResponse(FkErrorCode.SUCCESS)
            override fun close() = Unit
        }
        val session = com.edusing.chamcongtvbox.data.protocol.FkSession(
            info = com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo(1, "127.0.0.1", 5005, 1, FkFirmwareProfile.CIF11, "Fake", 1024),
            transport = fakeTransport,
            legacyLogType = 6,
        )
        val res = session.loadGeneralLogData(readMark = true)
        assertEquals(FkErrorCode.ERR_INVALID_DATA, res.code)
        assertTrue("Không được đánh dấu đã đọc khi giải mã ra 0 lượt", !markedRead)
    }

    private fun isWriteCmd(cmd: Int): Boolean =
        cmd == FkCommands.PUT_ENROLL_DATA || cmd == FkCommands.SET_USER_NAME || cmd == FkCommands.SET_USER_INFO

    private fun buildFkRespBytes(machineNo: Int, status: Int, outParam: Int, seqNo: Int, data: ByteArray?): ByteArray {
        val hdr = ByteArray(10).apply {
            this[0] = FkViaDevTransport.MAGIC_RESP_0
            this[1] = FkViaDevTransport.MAGIC_RESP_1
            this[2] = machineNo.toByte()
            this[3] = status.toByte()
            BinaryUtils.putU32LE(this, 4, outParam)
            BinaryUtils.putU16LE(this, 8, seqNo)
        }
        return if (data != null && status == 1) hdr + byteArrayOf(0x55, 0xAA.toByte()) + data + byteArrayOf(0, 0) else hdr
    }

    private fun sendFkResp(out: java.io.OutputStream, machineNo: Int, status: Int, outParam: Int, seqNo: Int, data: ByteArray? = null) {
        out.write(buildFkRespBytes(machineNo, status, outParam, seqNo, data))
        out.flush()
    }

    private fun sendLfwResp(out: java.io.OutputStream, machineNo: Int, status: Int, outParam: Int, seqNo: Int, data: ByteArray? = null) {
        val hdr = ByteArray(16).apply {
            this[0] = LfwViaDevTransport.MAGIC_RESP_0
            this[1] = LfwViaDevTransport.MAGIC_RESP_1
            this[2] = machineNo.toByte()
            this[3] = status.toByte()
            BinaryUtils.putU32LE(this, 4, outParam)
            BinaryUtils.putU32LE(this, 12, seqNo)
            BinaryUtils.putU16LE(this, 10, LfwViaDevTransport.computeRespHeaderChecksum(this, 0))
        }
        out.write(hdr)
        if (data != null && status == 1) {
            out.write(byteArrayOf(0x55, 0xAA.toByte()))
            out.write(data)
            out.write(byteArrayOf(0, 0))
        }
        out.flush()
    }

    private fun buildPacked12Log(enrollNo: Int, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): ByteArray {
        val packed = ((y - 1900) shl 2) or (mo shl 12) or (d shl 16) or (h shl 21) or (mi shl 26)
        return ByteArray(12).apply {
            BinaryUtils.putU32LE(this, 0, enrollNo)
            this[6] = 3
            this[7] = s.toByte()
            BinaryUtils.putU32LE(this, 8, packed)
        }
    }
}

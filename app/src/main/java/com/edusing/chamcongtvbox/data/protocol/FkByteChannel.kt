package com.edusing.chamcongtvbox.data.protocol

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Kênh truyền byte tầng thấp dùng chung cho cả TCP (`Socket`) và UDP (`DatagramSocket`).
 * Giúp `FkViaDevTransport` và `LfwViaDevTransport` chạy cả TCP lẫn UDP mà không trùng mã.
 */
interface FkByteChannel : Closeable {
    val protocolLabel: String
    val isOpen: Boolean
    fun drainInput()
    fun sendFrame(frame: ByteArray, timeoutMs: Int)
    fun readChunk(buffer: ByteArray, offset: Int, maxLen: Int, timeoutMs: Int): Int
}

/** Kênh truyền TCP chuẩn (`anProtocolType = 0`). */
class TcpByteChannel(
    private val socket: Socket,
) : FkByteChannel {

    override val protocolLabel: String = "TCP"
    override val isOpen: Boolean get() = !socket.isClosed && socket.isConnected

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun drainInput() {
        while (input.available() > 0) {
            if (input.skip(input.available().toLong()) <= 0) break
        }
    }

    override fun sendFrame(frame: ByteArray, timeoutMs: Int) {
        socket.soTimeout = timeoutMs.coerceAtLeast(500)
        output.write(frame)
        output.flush()
    }

    override fun readChunk(buffer: ByteArray, offset: Int, maxLen: Int, timeoutMs: Int): Int {
        socket.soTimeout = timeoutMs.coerceAtLeast(100)
        return input.read(buffer, offset, maxLen)
    }

    override fun close() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }

    companion object {
        fun open(
            ip: String,
            port: Int,
            connectTimeoutMs: Int,
            readTimeoutMs: Int = connectTimeoutMs,
        ): TcpByteChannel {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            runCatching { socket.setSoLinger(true, 1) }
            socket.soTimeout = readTimeoutMs.coerceAtLeast(500)
            socket.connect(InetSocketAddress(ip, port), connectTimeoutMs.coerceAtLeast(350))
            return TcpByteChannel(socket)
        }
    }
}

/** Kênh truyền UDP (`anProtocolType = 1`, tương ứng `UDP_MODE=true` trong `config.settings`). */
class UdpByteChannel(
    private val socket: DatagramSocket,
    private val remoteAddr: InetAddress,
    private val remotePort: Int,
) : FkByteChannel {

    override val protocolLabel: String = "UDP"
    override val isOpen: Boolean get() = !socket.isClosed

    override fun drainInput() {
        // Xả các gói UDP trễ còn nằm trong bộ đệm nhận nếu có
        val prevTimeout = runCatching { socket.soTimeout }.getOrDefault(1_000)
        try {
            socket.soTimeout = 10
            val tmp = ByteArray(2048)
            val pkt = DatagramPacket(tmp, tmp.size)
            while (true) socket.receive(pkt)
        } catch (_: Exception) {
            // Hết gói tồn đọng
        } finally {
            runCatching { socket.soTimeout = prevTimeout }
        }
    }

    override fun sendFrame(frame: ByteArray, timeoutMs: Int) {
        socket.soTimeout = timeoutMs.coerceAtLeast(500)
        val pkt = DatagramPacket(frame, frame.size, remoteAddr, remotePort)
        socket.send(pkt)
    }

    override fun readChunk(buffer: ByteArray, offset: Int, maxLen: Int, timeoutMs: Int): Int {
        socket.soTimeout = timeoutMs.coerceAtLeast(100)
        val pkt = DatagramPacket(buffer, offset, maxLen)
        socket.receive(pkt)
        return pkt.length
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        fun open(ip: String, port: Int, timeoutMs: Int): UdpByteChannel {
            val addr = InetAddress.getByName(ip)
            val sock = DatagramSocket()
            sock.soTimeout = timeoutMs.coerceAtLeast(1_000)
            sock.connect(addr, port)
            return UdpByteChannel(sock, addr, port)
        }
    }
}

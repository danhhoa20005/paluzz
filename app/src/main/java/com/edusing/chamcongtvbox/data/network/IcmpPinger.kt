package com.edusing.chamcongtvbox.data.network

import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bộ gửi Ping ICMP tối ưu cho Android (không cần quyền root, không gõ cổng TCP 5005 gây treo máy chấm):
 * 1. Ưu tiên socket ICMP không đặc quyền của nhân Linux (`SOCK_DGRAM + IPPROTO_ICMP`) — quét 254 IP chỉ ~1–2 giây.
 * 2. Tự dự phòng sang `/system/bin/ping` có kiểm tra chặt nội dung phản hồi (tránh lỗi exit 0 giả trên ColorOS).
 * 3. Khi chạy trên JVM thuần (Unit Test) tự fallback sang `InetAddress.isReachable`.
 */
object IcmpPinger {

    private val sequenceGen = AtomicInteger(1)

    @Volatile
    private var nativeIcmpAvailable: Boolean? = null

    /** Kiểm tra 1 địa chỉ IP có đang sống trong mạng LAN bằng Ping ICMP an toàn hay không. */
    fun ping(ip: String, timeoutMs: Int = 450): Boolean {
        if (nativeIcmpAvailable != false) {
            val nativeRes = tryNativeIcmpPing(ip, timeoutMs)
            if (nativeRes != null) {
                nativeIcmpAvailable = true
                return nativeRes
            }
            nativeIcmpAvailable = false
        }
        val shellRes = trySystemPing(ip, timeoutMs)
        if (shellRes != null) return shellRes
        return runCatching { InetAddress.getByName(ip).isReachable(timeoutMs) }.getOrDefault(false)
    }

    /** Cho biết thiết bị có hỗ trợ socket ICMP trực tiếp (cho phép tăng đồng thời quét LAN). */
    fun supportsFastNativeIcmp(): Boolean = nativeIcmpAvailable != false

    /**
     * Kiểm tra cổng định tuyến (gateway `.1` của dải LAN thượng nguồn, ví dụ `192.168.1.1`) có thông suốt không.
     * Chỉ kiểm tra ICMP hoặc cổng web/DNS chuẩn của router (80/53/443), tuyệt đối không chạm cổng 5005.
     */
    fun isGatewayReachable(gatewayIp: String, timeoutMs: Int = 350): Boolean {
        if (ping(gatewayIp, timeoutMs)) return true
        for (port in intArrayOf(80, 53, 443)) {
            val open = runCatching {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(gatewayIp, port), timeoutMs.coerceAtLeast(200))
                    true
                }
            }.getOrDefault(false)
            if (open) return true
        }
        return false
    }

    /** Trả về `true/false` nếu socket ICMP gốc chạy được; trả về `null` nếu nhân/JVM không hỗ trợ. */
    private fun tryNativeIcmpPing(ip: String, timeoutMs: Int): Boolean? {
        var fd: FileDescriptor? = null
        return try {
            fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
            val target = InetAddress.getByName(ip)
            Os.connect(fd, target, 0)

            val seq = sequenceGen.getAndIncrement() and 0xFFFF
            val packet = buildIcmpEchoRequest(seq)
            Os.write(fd, packet, 0, packet.size)

            val pfd = StructPollfd().apply {
                this.fd = fd
                this.events = OsConstants.POLLIN.toShort()
            }
            val ready = Os.poll(arrayOf(pfd), timeoutMs.coerceAtLeast(150))
            if (ready <= 0 || (pfd.revents.toInt() and OsConstants.POLLIN) == 0) return false

            val buf = ByteArray(128)
            val n = Os.read(fd, buf, 0, buf.size)
            isValidEchoReply(buf, n)
        } catch (_: LinkageError) {
            null
        } catch (_: RuntimeException) {
            // Trên JVM Unit Test `android.system.Os` ném RuntimeException("Stub!")
            null
        } catch (_: Exception) {
            // Nếu mở được socket (fd != null) nhưng send/connect lỗi mạng (ENETUNREACH/EHOSTUNREACH) -> host không sống
            if (fd != null) false else null
        } finally {
            if (fd != null) runCatching { Os.close(fd) }
        }
    }

    /** Gọi `/system/bin/ping` có giới hạn thời gian và kiểm tra chặt chuỗi phản hồi thật. */
    private fun trySystemPing(ip: String, timeoutMs: Int): Boolean? {
        val waitSec = ((timeoutMs + 999) / 1000).coerceIn(1, 2)
        return try {
            val proc = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", waitSec.toString(), ip)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor((waitSec * 1000L) + 500L, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return false
            }
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val exitCode = proc.exitValue()
            proc.destroy()
            isSuccessfulPingOutput(exitCode, output)
        } catch (_: Exception) {
            null
        }
    }

    /** Kiểm tra kết quả dòng lệnh `ping`: phải exit 0 VÀ có gói phản hồi thật (loại trường hợp Operation not permitted). */
    internal fun isSuccessfulPingOutput(exitCode: Int, output: String): Boolean {
        if (exitCode != 0) return false
        val lower = output.lowercase()
        if (lower.contains("100% packet loss") || lower.contains("operation not permitted")) return false
        return lower.contains("bytes from") || lower.contains("1 received") || lower.contains("1 packets received")
    }

    /** Đóng gói gói tin ICMP Echo Request 16 byte có checksum RFC 1071 chuẩn. */
    internal fun buildIcmpEchoRequest(seq: Int): ByteArray {
        val pkt = ByteArray(16)
        pkt[0] = 8 // Type = Echo Request
        pkt[1] = 0 // Code = 0
        pkt[4] = 0x46 // ID = "FK" (kernel có thể ghi đè khi dùng SOCK_DGRAM)
        pkt[5] = 0x4B
        pkt[6] = ((seq ushr 8) and 0xFF).toByte()
        pkt[7] = (seq and 0xFF).toByte()
        "FKSCAN01".toByteArray(Charsets.US_ASCII).copyInto(pkt, destinationOffset = 8)
        val csum = internetChecksum(pkt)
        pkt[2] = ((csum ushr 8) and 0xFF).toByte()
        pkt[3] = (csum and 0xFF).toByte()
        return pkt
    }

    /** Kiểm tra phản hồi ICMP Echo Reply (hỗ trợ cả dạng nhân đã bỏ header IP lẫn dạng còn header IPv4). */
    internal fun isValidEchoReply(buf: ByteArray, length: Int): Boolean {
        if (length < 8) return false
        if ((buf[0].toInt() and 0xFF) == 0 && (buf[1].toInt() and 0xFF) == 0) return true
        if (length >= 28 && (buf[0].toInt() and 0xF0) == 0x40) {
            val ihl = (buf[0].toInt() and 0x0F) * 4
            if (length >= ihl + 8 && (buf[ihl].toInt() and 0xFF) == 0) return true
        }
        return false
    }

    internal fun internetChecksum(data: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < data.size) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < data.size) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }
}

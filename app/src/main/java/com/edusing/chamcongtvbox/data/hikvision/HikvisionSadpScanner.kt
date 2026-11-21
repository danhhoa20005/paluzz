package com.edusing.chamcongtvbox.data.hikvision

import android.content.Context
import android.net.wifi.WifiManager
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Bộ quét chuẩn **Hikvision SADP** (`UDP Multicast 239.255.255.250:37020`):
 * phát hiện tức thì (< 2 giây, không cần mật khẩu) TẤT CẢ mọi dòng máy Hikvision trong LAN
 * (`DS-K1T320MX`, `DS-K1T320MFWX`, `DS-K1T341/342/343`, `DS-K1T671/673`, `DS-K1T804`, `DS-K1A802`...),
 * kể cả khi máy chặn Ping ICMP hoặc đã đổi cổng HTTP khỏi `80`.
 */
internal class HikvisionSadpScanner(
    private val context: Context? = null,
) {
    suspend fun discover(timeoutMs: Int = 1_800): List<HikvisionSadpDevice> = withContext(Dispatchers.IO) {
        val lock = acquireMulticastLock()
        val foundByKey = LinkedHashMap<String, HikvisionSadpDevice>()
        try {
            val probeBytes = buildProbeXml().toByteArray(Charsets.UTF_8)
            val groupAddr = InetAddress.getByName(SADP_MULTICAST_IP)
            val sockets = openSocketsPerInterface()
            try {
                // Gửi gói dò SADP 2 nhịp cách nhau 120ms trên mọi card mạng vật lý (Ethernet + Wi-Fi)
                repeat(2) { pass ->
                    for (sock in sockets) {
                        runCatching { sock.send(DatagramPacket(probeBytes, probeBytes.size, groupAddr, SADP_PORT)) }
                    }
                    if (pass == 0) Thread.sleep(120L)
                }
                val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(600, 4_000)
                val buf = ByteArray(8_192)
                for (sock in sockets) {
                    runCatching { sock.soTimeout = 180 }
                }
                while (System.currentTimeMillis() < deadline) {
                    var anyPacket = false
                    for (sock in sockets) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(packet)
                            anyPacket = true
                            val xml = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            val fallbackIp = packet.address?.hostAddress.orEmpty()
                            val parsed = parseProbeMatchXml(xml, fallbackIp) ?: continue
                            val dedupKey = parsed.serialNumber.ifBlank { parsed.ip }
                            foundByKey[dedupKey] = parsed
                        } catch (_: SocketTimeoutException) {
                            // Tiếp tục kiểm tra card mạng kế tiếp cho đến hết thời gian chờ
                        } catch (_: Exception) {
                            // Bỏ qua lỗi đọc đơn lẻ
                        }
                    }
                    if (!anyPacket && foundByKey.isNotEmpty() && System.currentTimeMillis() > deadline - 500L) break
                }
            } finally {
                sockets.forEach { runCatching { it.close() } }
            }
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
        foundByKey.values.sortedWith(
            compareByDescending<HikvisionSadpDevice> { it.isAccessTerminal }
                .thenBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 999 }
        )
    }

    private fun openSocketsPerInterface(): List<DatagramSocket> {
        val result = mutableListOf<DatagramSocket>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (ni in ifaces) {
            if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
            val name = ni.name.lowercase()
            if (name.startsWith("tun") || name.startsWith("wg") || name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("p2p")) continue
            val ipv4 = ni.interfaceAddresses.mapNotNull { it.address as? Inet4Address }
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress } ?: continue
            runCatching {
                val ms = MulticastSocket(InetSocketAddress(ipv4, 0)).apply {
                    reuseAddress = true
                    timeToLive = 2
                    networkInterface = ni
                }
                result.add(ms)
            }
        }
        if (result.isEmpty()) {
            runCatching {
                result.add(MulticastSocket().apply { reuseAddress = true; timeToLive = 2 })
            }
        }
        return result
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wm = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.createMulticastLock("AppTVBox::HikSadpLock")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    companion object {
        const val SADP_MULTICAST_IP = "239.255.255.250"
        const val SADP_PORT = 37020

        fun buildProbeXml(uuid: String = UUID.randomUUID().toString()): String =
            """<?xml version="1.0" encoding="utf-8"?><Probe><Uuid>$uuid</Uuid><Types>inquiry</Types></Probe>"""

        /** Giải mã gói XML `<ProbeMatch>` trả về từ mọi dòng máy Hikvision qua giao thức SADP. */
        fun parseProbeMatchXml(xml: String, fallbackIp: String = ""): HikvisionSadpDevice? {
            if (!xml.contains("<ProbeMatch", ignoreCase = true)) return null
            val ip = tag(xml, "IPv4Address").ifBlank { fallbackIp }.trim()
            if (ip.isEmpty() || ip == "0.0.0.0") return null
            val model = tag(xml, "DeviceDescription").ifBlank { tag(xml, "DeviceType") }.ifBlank { "Hikvision" }
            val httpPort = tag(xml, "HttpPort").toIntOrNull()?.takeIf { it in 1..65535 } ?: DeviceConfig.DEFAULT_HIK_PORT
            val serial = tag(xml, "DeviceSN")
            val mac = tag(xml, "MAC")
            val fw = tag(xml, "SoftwareVersion")
            val dhcp = tag(xml, "DHCP").equals("true", ignoreCase = true)
            val activated = !tag(xml, "Activated").equals("false", ignoreCase = true)
            return HikvisionSadpDevice(
                ip = ip,
                httpPort = httpPort,
                model = model,
                serialNumber = serial,
                mac = mac,
                firmwareVersion = fw,
                dhcpEnabled = dhcp,
                activated = activated,
                isAccessTerminal = classifyAccessTerminal(model),
            )
        }

        /** Phân biệt máy chấm công / kiểm soát cửa (`DS-K*`, `MinMoe`, `SH-K*`) với camera/đầu ghi (`DS-2CD`, `DS-76`...). */
        fun classifyAccessTerminal(model: String): Boolean {
            val u = model.trim().uppercase()
            if (u.startsWith("DS-K") || u.startsWith("DS-56") || u.contains("MINMOE") || u.contains("ACCESS") || u.contains("ACS")) {
                return true
            }
            val isCameraOrNvr = u.startsWith("DS-2C") || u.startsWith("DS-7") || u.startsWith("DS-8") ||
                u.startsWith("DS-9") || u.startsWith("IDS-7") || u.startsWith("IPC") || u.startsWith("NVR") || u.startsWith("DVR")
            return !isCameraOrNvr
        }

        private fun tag(xml: String, name: String): String =
            Regex("<$name[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}

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
 * Bộ quét chuẩn **Hikvision SADP** (`UDP Multicast 239.255.255.250:37020` + **Subnet Broadcast**):
 * - Mở đồng thời socket lắng nghe đa hướng chuẩn Linux/Android (`0.0.0.0:37020` tham gia nhóm `239.255.255.250`)
 *   lẫn socket từng card mạng gửi cả Multicast và Broadcast nội bộ để nhận đủ phản hồi trên mọi dòng Android/OPPO.
 */
internal class HikvisionSadpScanner(
    private val context: Context? = null,
) {
    private data class InterfaceTarget(val ni: NetworkInterface, val ipv4: Inet4Address, val broadcast: InetAddress?)

    suspend fun discover(timeoutMs: Int = 1_800): List<HikvisionSadpDevice> = withContext(Dispatchers.IO) {
        val lock = acquireMulticastLock()
        val foundByKey = LinkedHashMap<String, HikvisionSadpDevice>()
        try {
            val probeBytes = buildProbeXml().toByteArray(Charsets.UTF_8)
            val groupAddr = InetAddress.getByName(SADP_MULTICAST_IP)
            val targets = collectActiveInterfaces()
            val sockets = openSockets(targets, groupAddr)
            try {
                // Gửi gói dò SADP 2 nhịp cách nhau 120ms tới cả Multicast 239.255.255.250 và Subnet Broadcast (.255)
                repeat(2) { pass ->
                    for ((sock, bcast) in sockets) {
                        runCatching { sock.send(DatagramPacket(probeBytes, probeBytes.size, groupAddr, SADP_PORT)) }
                        if (bcast != null) {
                            runCatching { sock.send(DatagramPacket(probeBytes, probeBytes.size, bcast, SADP_PORT)) }
                        }
                    }
                    if (pass == 0) Thread.sleep(120L)
                }
                val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(600, 4_000)
                val buf = ByteArray(8_192)
                sockets.forEach { (sock, _) -> runCatching { sock.soTimeout = 160 } }
                while (System.currentTimeMillis() < deadline) {
                    var anyPacket = false
                    for ((sock, _) in sockets) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(packet)
                            anyPacket = true
                            val xml = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            val parsed = parseProbeMatchXml(xml, packet.address?.hostAddress.orEmpty()) ?: continue
                            foundByKey[parsed.serialNumber.ifBlank { parsed.ip }] = parsed
                        } catch (_: SocketTimeoutException) {
                            // Tiếp tục kiểm tra socket kế tiếp
                        } catch (_: Exception) {
                            // Bỏ qua gói hỏng
                        }
                    }
                    if (!anyPacket && foundByKey.isNotEmpty() && System.currentTimeMillis() > deadline - 500L) break
                }
            } finally {
                sockets.forEach { (sock, _) -> runCatching { sock.close() } }
            }
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
        foundByKey.values.sortedWith(
            compareByDescending<HikvisionSadpDevice> { it.isAccessTerminal }
                .thenBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 999 }
        )
    }

    private fun collectActiveInterfaces(): List<InterfaceTarget> {
        val out = mutableListOf<InterfaceTarget>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (ni in ifaces) {
            if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
            val name = ni.name.lowercase()
            if (name.startsWith("tun") || name.startsWith("wg") || name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("p2p")) continue
            val ia = ni.interfaceAddresses.firstOrNull {
                val a = it.address as? Inet4Address
                a != null && !a.isLoopbackAddress && !a.isLinkLocalAddress
            } ?: continue
            out.add(InterfaceTarget(ni, ia.address as Inet4Address, ia.broadcast))
        }
        return out
    }

    private fun openSockets(targets: List<InterfaceTarget>, groupAddr: InetAddress): List<Pair<DatagramSocket, InetAddress?>> {
        val result = mutableListOf<Pair<DatagramSocket, InetAddress?>>()
        // 1. Socket wildcard cổng 37020 tham gia nhóm multicast 239.255.255.250 (chuẩn Linux/Android nhận gói phản hồi multicast)
        runCatching {
            val listener = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SADP_PORT))
                timeToLive = 2
            }
            targets.forEach { t ->
                runCatching { listener.joinGroup(InetSocketAddress(groupAddr, SADP_PORT), t.ni) }
            }
            result.add(listener to null)
        }
        // 2. Socket gửi/nhận unicast trên từng card mạng (kèm broadcast nội bộ)
        for (t in targets) {
            runCatching {
                val ms = MulticastSocket(InetSocketAddress(t.ipv4, 0)).apply {
                    reuseAddress = true
                    broadcast = true
                    timeToLive = 2
                    networkInterface = t.ni
                }
                result.add(ms to t.broadcast)
            }
        }
        if (result.isEmpty()) {
            runCatching { result.add(MulticastSocket().apply { reuseAddress = true; broadcast = true; timeToLive = 2 } to null) }
        }
        return result
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wm = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.createMulticastLock("AppTVBox::HikSadpLock")?.apply { setReferenceCounted(false); acquire() }
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
            return HikvisionSadpDevice(
                ip = ip, httpPort = httpPort, model = model,
                serialNumber = tag(xml, "DeviceSN"), mac = tag(xml, "MAC"), firmwareVersion = tag(xml, "SoftwareVersion"),
                dhcpEnabled = tag(xml, "DHCP").equals("true", ignoreCase = true),
                activated = !tag(xml, "Activated").equals("false", ignoreCase = true),
                isAccessTerminal = classifyAccessTerminal(model),
            )
        }

        /** Phân biệt máy chấm công / kiểm soát cửa (`DS-K*`, `MinMoe`) với camera/đầu ghi (`DS-2CD`, `DS-76`...). */
        fun classifyAccessTerminal(model: String): Boolean {
            val u = model.trim().uppercase()
            if (u.startsWith("DS-K") || u.startsWith("DS-56") || u.contains("MINMOE") || u.contains("ACCESS") || u.contains("ACS")) return true
            val isCameraOrNvr = u.startsWith("DS-2C") || u.startsWith("DS-7") || u.startsWith("DS-8") ||
                u.startsWith("DS-9") || u.startsWith("IDS-7") || u.startsWith("IPC") || u.startsWith("NVR") || u.startsWith("DVR")
            return !isCameraOrNvr
        }

        private fun tag(xml: String, name: String): String =
            Regex("<$name[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}

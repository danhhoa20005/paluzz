package com.edusing.chamcongtvbox.service.web

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Bộ phản hồi mDNS (`224.0.0.251:5353`) giúp máy tính Mac, Windows 10/11, iPhone và Android trong cùng LAN
 * truy cập thẳng TV Box qua tên miền ngắn cố định **`http://chamcong.local:8080`** mà không cần biết IP.
 * - Tự giữ `MulticastLock` để chip Wi-Fi Android không lọc mất gói tin truy vấn mDNS.
 * - Tự phát gói quảng bá `Cache-Flush` mỗi khi TV Box khởi động hoặc đổi IP LAN để trình duyệt cập nhật ngay.
 */
internal class MdnsHostnameResponder(
    private val context: Context,
    private val hostProvider: () -> String = { DEFAULT_HOSTNAME },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: MulticastSocket? = null
    private var listenJob: Job? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val mdnsGroup: InetAddress by lazy { InetAddress.getByName(MDNS_GROUP_IP) }
    private val currentHostname: String get() = hostProvider().trim().ifBlank { DEFAULT_HOSTNAME }

    fun start(httpPort: Int) {
        if (listenJob?.isActive == true) return
        acquireMulticastLock()
        registerNsdService(httpPort)
        listenJob = scope.launch {
            val ms = openAndJoinSocket() ?: return@launch
            socket = ms
            announceNow(ms)

            val buf = ByteArray(2_048)
            val pkt = DatagramPacket(buf, buf.size)
            while (isActive && !ms.isClosed) {
                try {
                    pkt.length = buf.size
                    ms.receive(pkt)
                    handleIncomingPacket(ms, pkt)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    if (!isActive || ms.isClosed) break
                }
            }
        }
    }

    /** Gọi lại khi Wi-Fi/LAN vừa đổi IP để tham gia lại nhóm multicast và quảng bá IP mới cho `chamcong.local`. */
    fun refreshNetwork() {
        scope.launch {
            val ms = socket ?: return@launch
            if (ms.isClosed) return@launch
            joinActiveLanInterfaces(ms)
            announceNow(ms)
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        runCatching { socket?.close() }
        socket = null
        unregisterNsdService()
        releaseMulticastLock()
        scope.cancel()
    }

    private fun handleIncomingPacket(ms: MulticastSocket, pkt: DatagramPacket) {
        val host = currentHostname
        val query = MdnsPacketCodec.parseMatchingQuery(pkt.data, pkt.length, host) ?: return
        val ipBytes = resolveCurrentLanIpv4Bytes() ?: return
        val isLegacyUnicast = pkt.port != MDNS_PORT
        val reply = MdnsPacketCodec.buildARecordResponse(
            host = host,
            ipv4Bytes = ipBytes,
            txId = if (isLegacyUnicast) query.txId else 0,
            cacheFlush = !isLegacyUnicast,
        )
        // Gửi phản hồi unicast nếu máy hỏi yêu cầu QU / cổng khác 5353; ngược lại gửi multicast chuẩn RFC 6762
        if (isLegacyUnicast || query.wantUnicast) {
            runCatching { ms.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port)) }
        }
        if (!isLegacyUnicast) {
            runCatching { ms.send(DatagramPacket(reply, reply.size, mdnsGroup, MDNS_PORT)) }
        }
    }

    private suspend fun announceNow(ms: MulticastSocket) {
        val ipBytes = resolveCurrentLanIpv4Bytes() ?: return
        val packet = MdnsPacketCodec.buildARecordResponse(host = currentHostname, ipv4Bytes = ipBytes, txId = 0, cacheFlush = true)
        repeat(2) { idx ->
            runCatching { ms.send(DatagramPacket(packet, packet.size, mdnsGroup, MDNS_PORT)) }
            if (idx == 0) delay(900L)
        }
    }

    private fun openAndJoinSocket(): MulticastSocket? = runCatching {
        MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(MDNS_PORT))
            timeToLive = 255
            joinActiveLanInterfaces(this)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun joinActiveLanInterfaces(ms: MulticastSocket) {
        val groupSock = InetSocketAddress(mdnsGroup, MDNS_PORT)
        var joinedAny = false
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (iface in ifaces) {
            if (!isEligibleLanInterface(iface)) continue
            val ok = runCatching { ms.joinGroup(groupSock, iface); true }.getOrDefault(false)
            if (ok) joinedAny = true
        }
        if (!joinedAny) runCatching { ms.joinGroup(mdnsGroup) }
    }

    private fun resolveCurrentLanIpv4Bytes(): ByteArray? = runCatching {
        val ip = LocalWebConsoleServer.detectLanIpv4()
        if (ip.isBlank()) return null
        (InetAddress.getByName(ip) as? Inet4Address)?.address
    }.getOrNull()

    private fun isEligibleLanInterface(iface: NetworkInterface): Boolean {
        if (!iface.isUp || iface.isLoopback || iface.isVirtual || !iface.supportsMulticast()) return false
        val n = iface.name.lowercase()
        if (n.startsWith("tun") || n.startsWith("wg") || n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("p2p")) return false
        return iface.interfaceAddresses.any { (it.address as? Inet4Address)?.let { a -> !a.isLoopbackAddress && !a.isLinkLocalAddress } == true }
    }

    private fun registerNsdService(port: Int) = runCatching {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@runCatching
        val info = NsdServiceInfo().apply {
            serviceName = "ChamCong-TVBox"
            serviceType = "_http._tcp."
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        nsdListener = listener
    }

    private fun unregisterNsdService() = runCatching {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        nsdListener?.let { nsd?.unregisterService(it) }
        nsdListener = null
    }

    private fun acquireMulticastLock() = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wm?.createMulticastLock("AppTVBox::MdnsLock")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() = runCatching {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    companion object {
        const val DEFAULT_HOSTNAME = "chamcong.local"
        private const val MDNS_GROUP_IP = "224.0.0.251"
        private const val MDNS_PORT = 5353
    }
}

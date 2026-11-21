package com.edusing.chamcongtvbox.data.network

import com.edusing.chamcongtvbox.domain.model.IpSuggestion
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.domain.model.SubnetInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bộ quét mạng LAN nội bộ cho Android TV Box / điện thoại (chuyển thể & nâng cấp từ `NetScan.cs`):
 * 1. Phát hiện TẤT CẢ dải LAN vật lý (`eth*`, `wlan*`, lọc bỏ VPN/Tailscale/4G/card ảo).
 * 2. Quét Ping bằng [IcmpPinger] (socket ICMP gốc Android, KHÔNG gõ cổng TCP 5005 thô gây treo máy 2 phút).
 * 3. Tự nhận diện thêm dải mạng thượng nguồn thông tuyến (ví dụ điện thoại ở `192.168.2.x` đi sang `192.168.1.x`).
 */
class LanScannerImpl(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pinger: (ip: String, timeoutMs: Int) -> Boolean = { ip, ms -> IcmpPinger.ping(ip, ms) },
    private val gatewayChecker: (ip: String, timeoutMs: Int) -> Boolean = { ip, ms -> IcmpPinger.isGatewayReachable(ip, ms) },
) {
    suspend fun detectActiveSubnets(preferredIps: Set<String> = emptySet()): List<SubnetInfo> = withContext(ioDispatcher) {
        val preferredPrefixes = preferredIps.mapNotNull(::extractSlash24).toSet()
        val scored = mutableListOf<Pair<Int, SubnetInfo>>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())

        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            val name = iface.name.lowercase()
            if (isVirtualOrVpnInterface(name)) continue

            for (ia in iface.interfaceAddresses) {
                val addr = ia.address as? Inet4Address ?: continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val ip = addr.hostAddress ?: continue
                if (isCgnatOrTailscale(ip) || !isPrivateLanIp(ip)) continue

                val prefix = extractSlash24(ip) ?: continue
                val info = SubnetInfo(
                    localIp = ip,
                    prefix = prefix,
                    subnetMask = prefixLengthToMask(ia.networkPrefixLength.toInt()),
                    gatewayGuess = "$prefix.1",
                    interfaceName = iface.name,
                )
                val score = computeSubnetScore(name, prefix, preferredPrefixes)
                scored.add(score to info)
            }
        }
        val seen = LinkedHashSet<String>()
        scored.sortedBy { it.first }
            .map { it.second }
            .filter { seen.add(it.prefix) }
    }

    suspend fun detectActiveSubnet(): SubnetInfo? = detectActiveSubnets().firstOrNull()

    /**
     * Trả về danh sách dải `/24` phụ thông tuyến (không trùng với `localSubnets`):
     * - Dải mạng của các IP máy chấm đã lưu trước đó.
     * - Dải mạng LAN văn phòng phổ biến (`192.168.1`, `192.168.0`) nếu router `.1` thông suốt (<350ms).
     */
    suspend fun detectRoutableExtraPrefixes(
        localSubnets: List<SubnetInfo>,
        knownIps: Set<String> = emptySet(),
    ): List<String> = withContext(ioDispatcher) {
        val localPrefixes = localSubnets.map { it.prefix }.toSet()
        val extras = LinkedHashSet<String>()

        knownIps.mapNotNull(::extractSlash24)
            .filter { it !in localPrefixes }
            .forEach { extras.add(it) }

        // Chỉ dò dải thượng nguồn nếu thiết bị đang có mạng LAN nội bộ (tránh dò khi chỉ có 4G)
        if (localSubnets.isNotEmpty()) {
            val commonOfficePrefixes = listOf("192.168.1", "192.168.0")
            for (prefix in commonOfficePrefixes) {
                if (prefix in localPrefixes || prefix in extras) continue
                if (gatewayChecker("$prefix.1", 350)) extras.add(prefix)
            }
        }
        extras.toList()
    }

    suspend fun scanSubnetByPing(
        prefix: String,
        localIp: String? = null,
        configuredIps: Set<String> = emptySet(),
        port: Int = 5005,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): List<LanHostInfo> = withContext(ioDispatcher) {
        val cleanPrefix = normalizePrefix(prefix) ?: return@withContext emptyList()
        val total = 254
        val scanned = AtomicInteger(0)
        // Native ICMP socket không tốn fork/exec nên chạy 48 luồng; fallback /system/bin/ping giới hạn 16 luồng
        val concurrency = if (IcmpPinger.supportsFastNativeIcmp()) 48 else 16
        val semaphore = Semaphore(concurrency)

        coroutineScope {
            (1..total).map { host ->
                async(ioDispatcher) {
                    val ip = "$cleanPrefix.$host"
                    val alive = semaphore.withPermit {
                        if (ip == localIp) false else pinger(ip, 450)
                    }
                    val done = scanned.incrementAndGet()
                    if (done % 16 == 0 || done == total) onProgress(done, total)
                    if (alive) {
                        LanHostInfo(
                            ip = ip,
                            port = port,
                            reachableByPing = true,
                            alreadyConfigured = ip in configuredIps,
                            note = if (ip in configuredIps) "Đã có trong danh sách" else "Đang bật trong dải $cleanPrefix.x",
                        )
                    } else null
                }
            }.awaitAll().filterNotNull().sortedBy { lastOctet(it.ip) }
        }
    }

    suspend fun scanLanHosts(
        configuredIps: Set<String>,
        port: Int,
        customPrefix: String? = null,
        onProgress: (scanned: Int, total: Int) -> Unit,
    ): List<LanHostInfo> = withContext(ioDispatcher) {
        val custom = customPrefix?.let(::normalizePrefix)
        val localSubnets = detectActiveSubnets(configuredIps)
        if (custom != null) {
            val localIp = localSubnets.firstOrNull { it.prefix == custom }?.localIp
            return@withContext scanSubnetByPing(custom, localIp, configuredIps, port, onProgress)
        }
        if (localSubnets.isEmpty()) return@withContext emptyList()

        val found = LinkedHashMap<String, LanHostInfo>()
        val totalHosts = localSubnets.size * 254
        var baseDone = 0
        for (subnet in localSubnets) {
            val part = scanSubnetByPing(subnet.prefix, subnet.localIp, configuredIps, port) { done, _ ->
                onProgress(baseDone + done, totalHosts)
            }
            part.forEach { found[it.ip] = it }
            baseDone += 254
        }
        found.values.sortedWith(compareBy<LanHostInfo> { it.ip.substringBeforeLast('.') }.thenBy { lastOctet(it.ip) })
    }

    suspend fun suggestFreeIp(port: Int, excludeIps: Set<String> = emptySet()): IpSuggestion? = withContext(ioDispatcher) {
        val subnet = detectActiveSubnet() ?: return@withContext null
        val excluded = excludeIps + subnet.localIp + subnet.gatewayGuess
        val order = (240 downTo 200) + (199 downTo 100) + (99 downTo 2)
        for (last in order) {
            val candidate = "${subnet.prefix}.$last"
            if (candidate in excluded) continue
            if (!pinger(candidate, 300)) {
                return@withContext IpSuggestion(candidate, subnet.subnetMask, subnet.gatewayGuess, subnet.localIp, port)
            }
        }
        IpSuggestion("${subnet.prefix}.201", subnet.subnetMask, subnet.gatewayGuess, subnet.localIp, port)
    }

    private fun computeSubnetScore(ifaceName: String, prefix: String, preferredPrefixes: Set<String>): Int {
        var score = when {
            ifaceName.startsWith("eth") || ifaceName.startsWith("en") -> 0
            ifaceName.startsWith("wlan") || ifaceName.startsWith("wl") -> 10
            else -> 20
        }
        score += when {
            prefix.startsWith("192.168.") -> 0
            prefix.startsWith("10.") -> 2
            else -> 4
        }
        if (prefix in preferredPrefixes) score -= 100
        return score
    }

    private fun isVirtualOrVpnInterface(name: String): Boolean {
        val bad = listOf("tun", "tap", "wg", "tailscale", "docker", "veth", "br-", "rmnet", "ccmni", "p2p", "dummy")
        return bad.any { name.startsWith(it) || name.contains(it) }
    }

    private fun isPrivateLanIp(ip: String): Boolean {
        val p = ip.split('.')
        if (p.size != 4) return false
        val o0 = p[0].toIntOrNull() ?: return false
        val o1 = p[1].toIntOrNull() ?: return false
        return (o0 == 192 && o1 == 168) || o0 == 10 || (o0 == 172 && o1 in 16..31)
    }

    private fun isCgnatOrTailscale(ip: String): Boolean {
        val p = ip.split('.')
        if (p.size != 4) return true
        val o0 = p[0].toIntOrNull() ?: return true
        val o1 = p[1].toIntOrNull() ?: return true
        return (o0 == 100 && o1 in 64..127) || (o0 == 169 && o1 == 254)
    }

    private fun prefixLengthToMask(prefixLen: Int): String {
        val clamped = prefixLen.coerceIn(0, 32)
        val mask = if (clamped == 0) 0 else (-1 shl (32 - clamped))
        return "${(mask ushr 24) and 0xFF}.${(mask ushr 16) and 0xFF}.${(mask ushr 8) and 0xFF}.${mask and 0xFF}"
    }

    private fun lastOctet(ip: String): Int = ip.substringAfterLast('.').toIntOrNull() ?: 999

    companion object {
        /** Tách tiền tố `/24` (`"a.b.c"`) từ địa chỉ IPv4 đầy đủ. */
        fun extractSlash24(ip: String): String? {
            val parts = ip.trim().split('.')
            if (parts.size != 4) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            if (nums.any { it !in 0..255 }) return null
            return "${nums[0]}.${nums[1]}.${nums[2]}"
        }

        /** Chuẩn hoá dải `/24` nhập tay (`"192.168.1"` hoặc `"192.168.1."` hoặc `"192.168.1.201"` -> `"192.168.1"`). */
        fun normalizePrefix(raw: String): String? {
            val clean = raw.trim().trimEnd('.')
            val parts = clean.split('.')
            if (parts.size != 3 && parts.size != 4) return null
            val firstThree = parts.take(3).map { it.toIntOrNull() ?: return null }
            if (firstThree.any { it !in 0..255 }) return null
            return "${firstThree[0]}.${firstThree[1]}.${firstThree[2]}"
        }
    }
}

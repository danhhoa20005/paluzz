package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.IpSuggestion
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.domain.model.SubnetInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.repository.LanDiscoveryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Quét thiết bị trong mạng LAN kết hợp 3 nguồn:
 * 1. **Hikvision SADP Multicast (`239.255.255.250:37020`)**: phát hiện tức thì TẤT CẢ mọi dòng máy Hikvision
 *    (`DS-K1T320MX`, `DS-K1T320MFWX`, `DS-K1T341/343`, `DS-K1T671/673`, `DS-K1T804`...) kèm đúng cổng HTTP và tên model.
 * 2. **Ping ICMP an toàn + HTTP/HTTPS ISAPI probe**: phát hiện máy Hikvision ở dải LAN khác định tuyến (khác subnet).
 * 3. **Bắt tay FK thật (cổng 5005)**: phát hiện toàn bộ máy chấm công dòng FK.
 */
class ScanLanDevicesUseCase(
    private val lanDiscoveryRepository: LanDiscoveryRepository,
    private val deviceGateway: FkDeviceGateway? = null,
) {
    suspend fun detectSubnet(): SubnetInfo? = lanDiscoveryRepository.detectActiveSubnet()

    suspend fun detectSubnets(preferredIps: Set<String> = emptySet()): List<SubnetInfo> =
        lanDiscoveryRepository.detectActiveSubnets(preferredIps)

    suspend fun detectRoutableExtraPrefixes(
        localSubnets: List<SubnetInfo>,
        knownIps: Set<String> = emptySet(),
    ): List<String> = lanDiscoveryRepository.detectRoutableExtraPrefixes(localSubnets, knownIps)

    suspend operator fun invoke(
        configuredIps: Set<String>,
        port: Int = DeviceConfig.DEFAULT_PORT,
        customPrefix: String? = null,
        machineNo: Int = DeviceConfig.DEFAULT_MACHINE_NO,
        password: Int = DeviceConfig.DEFAULT_PASSWORD,
        license: Int = DeviceConfig.DEFAULT_LICENSE,
        useUdp: Boolean = false,
        hikvisionPort: Int = DeviceConfig.DEFAULT_HIK_PORT,
        onQuickResults: (List<LanHostInfo>) -> Unit = {},
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): List<LanHostInfo> = coroutineScope {
        val gateway = deviceGateway
        val activeSubnets = lanDiscoveryRepository.detectActiveSubnets(configuredIps)
        val sweepPrefixes = if (!customPrefix.isNullOrBlank()) {
            listOf(customPrefix.trim().trimEnd('.') to activeSubnets.firstOrNull { it.prefix == customPrefix.trim().trimEnd('.') }?.localIp)
        } else {
            activeSubnets.map { it.prefix to it.localIp }
        }
        // Chạy song song 3 lớp: (1) SADP Multicast+Broadcast (~1.5s), (2) TCP cổng 80/443 ISAPI (vượt chặn Multicast & Ping), (3) Ping + FK 5005
        val sadpDeferred = async {
            val found = gateway?.discoverHikvisionSadp(timeoutMs = 1_500).orEmpty()
            val quickHosts = found.filter { it.isAccessTerminal }.map { dev -> toSadpHost(dev, configuredIps) }
            if (quickHosts.isNotEmpty()) onQuickResults(sortHosts(quickHosts))
            found
        }
        val tcpHikDeferred = async {
            if (gateway == null) return@async emptyList()
            val list = mutableListOf<com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice>()
            for ((prefix, localIp) in sweepPrefixes) {
                list.addAll(gateway.sweepHikvisionSubnet(prefix, localIp = localIp))
            }
            if (list.isNotEmpty()) {
                val quickHosts = list.filter { it.isAccessTerminal }.map { dev -> toSadpHost(dev, configuredIps) }
                if (quickHosts.isNotEmpty()) onQuickResults(sortHosts(quickHosts))
            }
            list
        }
        val pingHosts = lanDiscoveryRepository.scanLanHosts(configuredIps, port, customPrefix, onProgress)
        val sadpDevices = sadpDeferred.await()
        val tcpHikDevices = tcpHikDeferred.await()

        val sadpByIp = sadpDevices.associateBy { it.ip }
        val mergedByIp = LinkedHashMap<String, LanHostInfo>()

        // Ưu tiên thông tin giàu từ SADP (có tên model/Serial/DHCP), sau đó gộp thêm máy tìm qua TCP cổng 80/443 ISAPI
        for (dev in sadpDevices) {
            if (!dev.isAccessTerminal) continue
            mergedByIp[dev.ip] = toSadpHost(dev, configuredIps)
        }
        for (dev in tcpHikDevices) {
            if (!dev.isAccessTerminal || mergedByIp.containsKey(dev.ip)) continue
            mergedByIp[dev.ip] = toSadpHost(dev, configuredIps)
        }

        if (gateway == null) {
            pingHosts.forEach { h -> mergedByIp.putIfAbsent(h.ip, h) }
            return@coroutineScope sortHosts(mergedByIp.values)
        }

        val fkPort = if (port == hikvisionPort) DeviceConfig.DEFAULT_PORT else port
        val remainingPing = pingHosts.filter { it.ip !in sadpByIp && it.ip !in mergedByIp }
        val semaphore = Semaphore(12)
        val verified = remainingPing.map { host ->
            async {
                semaphore.withPermit {
                    val hikLabel = gateway.probeHikvisionHost(host.ip, hikvisionPort)
                    if (!hikLabel.isNullOrBlank()) {
                        return@withPermit host.copy(
                            port = hikvisionPort,
                            isHikvisionTerminal = true,
                            detectedProtocol = DeviceProtocol.HIKVISION_ISAPI,
                            modelHint = hikLabel.removePrefix("Hikvision ").trim(),
                            note = if (host.alreadyConfigured) "$hikLabel • Đã lưu" else "$hikLabel (cổng $hikvisionPort) ✓",
                        )
                    }
                    val res = gateway.connect(machineNo, host.ip, fkPort, timeoutMs = 2_000, netPassword = password, license = license, useUdp = useUdp)
                    if (res.isSuccess && res.data != null) {
                        val profile = res.data.profile.displayName
                        gateway.disconnect(res.data.handle)
                        host.copy(
                            port = fkPort,
                            isFkTerminal = true,
                            detectedProtocol = DeviceProtocol.FK,
                            note = if (host.alreadyConfigured) "Máy chấm công FK ($profile) • Đã lưu" else "Máy chấm công FK ($profile) ✓",
                        )
                    } else host
                }
            }
        }.awaitAll()

        verified.forEach { mergedByIp.putIfAbsent(it.ip, it) }
        sortHosts(mergedByIp.values)
    }

    private fun toSadpHost(dev: com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice, configuredIps: Set<String>): LanHostInfo {
        val already = dev.ip in configuredIps
        val ipMode = if (dev.dhcpEnabled) "DHCP" else "IP tĩnh"
        val actTag = if (dev.activated) "" else " • Chưa kích hoạt"
        return LanHostInfo(
            ip = dev.ip,
            port = dev.httpPort,
            reachableByPing = true,
            alreadyConfigured = already,
            isHikvisionTerminal = true,
            detectedProtocol = DeviceProtocol.HIKVISION_ISAPI,
            modelHint = dev.model,
            serialHint = dev.serialNumber,
            note = "${dev.model} (cổng ${dev.httpPort} • $ipMode$actTag)${if (already) " • Đã lưu" else " ✓"}",
        )
    }

    private fun sortHosts(hosts: Collection<LanHostInfo>): List<LanHostInfo> = hosts.sortedWith(
        compareByDescending<LanHostInfo> { it.isDetectedTerminal }
            .thenByDescending { it.alreadyConfigured }
            .thenBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 999 }
    )
}

/** Gợi ý một địa chỉ IP tĩnh trống (dải .240..200) chưa dùng trong mạng LAN. */
class SuggestFreeIpUseCase(
    private val lanDiscoveryRepository: LanDiscoveryRepository,
) {
    suspend operator fun invoke(
        port: Int = 5005,
        excludeIps: Set<String> = emptySet(),
    ): IpSuggestion? = lanDiscoveryRepository.suggestFreeIp(port, excludeIps)
}

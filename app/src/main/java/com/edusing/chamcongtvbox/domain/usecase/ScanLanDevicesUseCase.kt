package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
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
 * Quét thiết bị đang hoạt động trong mạng LAN (Ping ICMP an toàn + đánh dấu máy chấm công FK thật).
 * Hỗ trợ chọn dải `/24` tuỳ ý (ví dụ quét `192.168.1` khi điện thoại ở Wi-Fi phụ `192.168.2.x`).
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
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): List<LanHostInfo> {
        val hosts = lanDiscoveryRepository.scanLanHosts(configuredIps, port, customPrefix, onProgress)
        val gateway = deviceGateway ?: return hosts
        if (hosts.isEmpty()) return hosts

        // Xác nhận song song xem thiết bị nào đang chạy đúng giao thức máy chấm công FK để đưa lên đầu
        val semaphore = Semaphore(12)
        val verified = coroutineScope {
            hosts.map { host ->
                async {
                    val res = semaphore.withPermit {
                        gateway.connect(machineNo, host.ip, port, timeoutMs = 2_000, netPassword = password, license = license, useUdp = useUdp)
                    }
                    if (res.isSuccess && res.data != null) {
                        val profile = res.data.profile.displayName
                        gateway.disconnect(res.data.handle)
                        host.copy(
                            isFkTerminal = true,
                            note = if (host.alreadyConfigured) "Máy chấm công FK ($profile) • Đã lưu" else "Máy chấm công FK ($profile) ✓",
                        )
                    } else host
                }
            }.awaitAll()
        }
        return verified.sortedWith(
            compareByDescending<LanHostInfo> { it.isFkTerminal }
                .thenByDescending { it.alreadyConfigured }
                .thenBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 999 }
        )
    }
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

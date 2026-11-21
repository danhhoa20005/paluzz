package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.SubnetInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.repository.LanDiscoveryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Tiến độ phát hiện máy chấm công trong mạng LAN. */
data class DiscoveryProgress(
    val stageText: String,
    val current: Int = 0,
    val total: Int = 0,
)

/** Báo cáo chi tiết sau một lượt dò tìm máy chấm công để hiển thị chẩn đoán rõ ràng. */
data class DiscoveryReport(
    val foundIps: List<String>,
    val localSubnets: List<SubnetInfo>,
    val scannedPrefixes: List<String>,
    val pingAliveCount: Int,
    val directHandshakeUsed: Boolean,
)

/**
 * Tự động dò tìm máy chấm công FK thật trong mạng LAN (chuyển thể & tối ưu từ `MainForm.ScanNetwork`):
 * 1. Nối thẳng các IP đã lưu (3 lượt/máy).
 * 2. Quét Ping ICMP an toàn + xác nhận FK song song (`2000ms`) trên toàn bộ dải LAN vật lý.
 * 3. Nếu chưa thấy máy: quét tiếp dải LAN chính thông tuyến (vd `192.168.1.x` từ Wi-Fi phụ `192.168.2.x`).
 * 4. Chỉ khi Ping trên mọi dải đều bỏ sót (máy chặn ICMP): chạy bắt tay FK thật trực tiếp song song
 *    (`1400ms`, KHÔNG mở-đóng cổng thô gây treo máy 2 phút).
 */
class DiscoverDevicesUseCase(
    private val lanDiscoveryRepository: LanDiscoveryRepository,
    private val deviceGateway: FkDeviceGateway,
) {
    private data class SubnetTarget(val prefix: String, val localIp: String?, val label: String)

    suspend operator fun invoke(
        knownDevices: List<DeviceConfig>,
        port: Int,
        machineNo: Int,
        password: Int,
        license: Int,
        useUdp: Boolean,
        onProgress: (DiscoveryProgress) -> Unit = {},
    ): DiscoveryReport {
        val foundIps = LinkedHashSet<String>()
        val checkedIps = LinkedHashSet<String>()
        val scannedPrefixes = LinkedHashSet<String>()
        val targets = mutableListOf<SubnetTarget>()
        val knownIps = knownDevices.map { it.ip.trim() }.filter { it.isNotEmpty() }.toSet()
        var totalPingAlive = 0
        var usedDirectFallback = false

        // 1. Thử nối thẳng các máy đã lưu trước (hỗ trợ cả máy FK cổng 5005 và Hikvision ISAPI cổng 80/443)
        for (known in knownDevices) {
            val ip = known.ip.trim()
            if (ip.isEmpty() || !checkedIps.add(ip)) continue
            onProgress(DiscoveryProgress("Đang kiểm tra máy đã lưu $ip..."))
            val ok = if (known.isHikvision) {
                verifySavedDevice(known, tries = 1, timeoutMs = 2_500)
            } else {
                verifyFkTerminal(ip, known.port, known.machineNo, known.password, known.license, known.useUdp, tries = 3, timeoutMs = 2_000)
            }
            if (ok) foundIps.add(ip)
        }

        // 2. Quét Ping + xác nhận FK song song trên tất cả dải LAN vật lý (eth0/wlan0)
        val localSubnets = lanDiscoveryRepository.detectActiveSubnets(knownIps)
        var foundInLocalSubnets = 0
        for (subnet in localSubnets) {
            val t = SubnetTarget(subnet.prefix, subnet.localIp, "${subnet.prefix}.x (${subnet.interfaceName})")
            targets.add(t)
            scannedPrefixes.add(t.prefix)
            val (found, alive) = pingAndVerifySubnet(t, knownIps, checkedIps, port, machineNo, password, license, useUdp, onProgress)
            foundIps.addAll(found)
            foundInLocalSubnets += found.size
            totalPingAlive += alive
        }

        // 3. Nếu các dải nội bộ chưa thấy máy nào -> quét Ping ngay sang dải LAN chính thông tuyến (vd 192.168.1.x)
        var foundInExtraSubnets = 0
        if (foundInLocalSubnets == 0) {
            val extraPrefixes = lanDiscoveryRepository.detectRoutableExtraPrefixes(localSubnets, knownIps)
            for (prefix in extraPrefixes) {
                if (!scannedPrefixes.add(prefix)) continue
                val t = SubnetTarget(prefix, null, "$prefix.x (LAN thông tuyến)")
                targets.add(t)
                val (found, alive) = pingAndVerifySubnet(t, knownIps, checkedIps, port, machineNo, password, license, useUdp, onProgress)
                foundIps.addAll(found)
                foundInExtraSubnets += found.size
                totalPingAlive += alive
                if (found.isNotEmpty()) break
            }
        }

        // 4. Dự phòng an toàn nếu mọi bước Ping đều chưa thấy máy trên LAN (máy chặn ICMP hoặc Android chặn Ping)
        if (foundInLocalSubnets == 0 && foundInExtraSubnets == 0 && (foundIps.isEmpty() || totalPingAlive == 0)) {
            val orderedTargets = targets.sortedBy { if (it.prefix.startsWith("10.0.2")) 1 else 0 }
            for (t in orderedTargets) {
                val remaining = (1..254).map { "${t.prefix}.$it" }.filter { it != t.localIp && it !in checkedIps }
                if (remaining.isEmpty()) continue
                usedDirectFallback = true
                val direct = verifyBatchParallel(
                    ips = remaining, concurrency = 28, port = port, machineNo = machineNo,
                    password = password, license = license, useUdp = useUdp, timeoutMs = 1_400,
                    stagePrefix = "Dò trực tiếp cổng $port trên ${t.label}", onProgress = onProgress,
                )
                checkedIps.addAll(remaining)
                foundIps.addAll(direct)
                if (direct.isNotEmpty()) break
            }
        }

        return DiscoveryReport(foundIps.toList(), localSubnets, scannedPrefixes.toList(), totalPingAlive, usedDirectFallback)
    }

    private suspend fun pingAndVerifySubnet(
        target: SubnetTarget, knownIps: Set<String>, checkedIps: MutableSet<String>,
        port: Int, machineNo: Int, password: Int, license: Int, useUdp: Boolean,
        onProgress: (DiscoveryProgress) -> Unit,
    ): Pair<List<String>, Int> {
        onProgress(DiscoveryProgress("Đang quét Ping dải ${target.label}..."))
        val aliveHosts = lanDiscoveryRepository.scanSubnetByPing(
            prefix = target.prefix, localIp = target.localIp, configuredIps = knownIps, port = port,
            onProgress = { done, total -> onProgress(DiscoveryProgress("Quét Ping ${target.label} ($done/$total)...", done, total)) },
        ).map { it.ip }.filter { it !in checkedIps }

        val found = verifyBatchParallel(
            ips = aliveHosts, concurrency = 12, port = port, machineNo = machineNo,
            password = password, license = license, useUdp = useUdp, timeoutMs = 2_000,
            stagePrefix = "Xác nhận máy chấm ${target.label}", onProgress = onProgress,
        )
        checkedIps.addAll(aliveHosts)
        return found to aliveHosts.size
    }

    private suspend fun verifyBatchParallel(
        ips: List<String>, concurrency: Int, port: Int, machineNo: Int, password: Int,
        license: Int, useUdp: Boolean, timeoutMs: Int, stagePrefix: String,
        onProgress: (DiscoveryProgress) -> Unit,
    ): List<String> = coroutineScope {
        if (ips.isEmpty()) return@coroutineScope emptyList()
        val semaphore = Semaphore(concurrency)
        val doneCount = AtomicInteger(0)
        val total = ips.size

        ips.map { ip ->
            async {
                val ok = semaphore.withPermit {
                    verifyFkTerminal(ip, port, machineNo, password, license, useUdp, tries = 1, timeoutMs = timeoutMs)
                }
                val done = doneCount.incrementAndGet()
                if (ok || done % 8 == 0 || done == total) {
                    onProgress(DiscoveryProgress("$stagePrefix ($done/$total)...", done, total))
                }
                if (ok) ip else null
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun verifySavedDevice(device: DeviceConfig, tries: Int, timeoutMs: Int): Boolean {
        for (attempt in 1..tries) {
            val res = deviceGateway.connect(device, timeoutMs)
            if (res.isSuccess && res.data != null) {
                deviceGateway.disconnect(res.data.handle)
                return true
            }
            if (attempt < tries) delay(800L)
        }
        return false
    }

    private suspend fun verifyFkTerminal(
        ip: String, port: Int, machineNo: Int, password: Int, license: Int,
        useUdp: Boolean, tries: Int, timeoutMs: Int,
    ): Boolean {
        for (attempt in 1..tries) {
            val res = deviceGateway.connect(machineNo, ip, port, timeoutMs, password, license, useUdp)
            if (res.isSuccess && res.data != null) {
                deviceGateway.disconnect(res.data.handle)
                return true
            }
            if (attempt < tries) delay(800L)
        }
        return false
    }
}

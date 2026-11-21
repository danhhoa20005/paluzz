package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice
import com.edusing.chamcongtvbox.domain.model.SubnetInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Tự động phát hiện 3 lớp mọi máy chấm công Hikvision trong mạng LAN:
 * 1. SADP Multicast (`239.255.255.250:37020`) + Subnet Broadcast (nhanh ~1.5s, có sẵn model & Serial).
 * 2. Quét dự phòng trực tiếp TCP cổng 80/443 + ISAPI (hoạt động 100% kể cả khi router Wi-Fi chặn Multicast & Ping).
 * 3. Định vị máy đổi IP DHCP theo SerialNumber; tự nối máy mới (1 lần thử duy nhất) nếu đã nhớ mật khẩu 1 lần,
 *    hoặc đưa vào `unpairedOut` để hiển thị ngay trên Trạm trực cho người dùng nhập mật khẩu 1 lần.
 */
internal object HikvisionSadpDiscoveryHelper {

    suspend fun discoverAndMatch(
        deviceGateway: FkDeviceGateway,
        knownDevices: List<DeviceConfig>,
        localSubnets: List<SubnetInfo>,
        missingHikDevices: MutableList<DeviceConfig>,
        checkedIps: MutableSet<String>,
        foundIps: MutableSet<String>,
        relocatedOut: MutableList<DeviceConfig>,
        autoDiscoveredOut: MutableList<DeviceConfig>,
        unpairedOut: MutableList<HikvisionSadpDevice>,
        hikUsername: String,
        hikPassword: String,
        onProgress: (DiscoveryProgress) -> Unit,
    ) = coroutineScope {
        onProgress(DiscoveryProgress("Đang tự dò mọi máy Hikvision trong mạng LAN (SADP & ISAPI)..."))
        val sadpJob = async { deviceGateway.discoverHikvisionSadp(timeoutMs = 1_600) }
        val tcpJob = async {
            val swept = mutableListOf<HikvisionSadpDevice>()
            for (sub in localSubnets) {
                swept.addAll(deviceGateway.sweepHikvisionSubnet(sub.prefix, excludeIps = checkedIps.toSet(), localIp = sub.localIp))
            }
            swept
        }
        val mergedByIp = LinkedHashMap<String, HikvisionSadpDevice>()
        sadpJob.await().forEach { d -> mergedByIp[d.ip] = d }
        tcpJob.await().forEach { d -> mergedByIp.putIfAbsent(d.ip, d) }
        val discovered = mergedByIp.values.toList()
        if (discovered.isEmpty()) return@coroutineScope

        // 1. Định vị lại máy Hikvision đã lưu bị router đổi IP DHCP theo SerialNumber (0 lần thử mật khẩu)
        val iter = missingHikDevices.iterator()
        while (iter.hasNext()) {
            val missing = iter.next()
            val match = discovered.firstOrNull {
                it.serialNumber.isNotBlank() && it.serialNumber.equals(missing.serialNumber, ignoreCase = true)
            }
            if (match != null) {
                relocatedOut.add(missing.copy(ip = match.ip, port = match.httpPort))
                foundIps.add(match.ip)
                checkedIps.add(match.ip)
                iter.remove()
            }
        }

        // 2. Xử lý các máy chấm công Hikvision mới phát hiện trong LAN
        val knownSerials = knownDevices.mapNotNull { it.serialNumber.takeIf { sn -> sn.isNotBlank() }?.uppercase() }.toMutableSet()
        val effectiveUser = hikUsername.trim().ifBlank { DeviceConfig.DEFAULT_HIK_USERNAME }
        for (dev in discovered) {
            if (!dev.isAccessTerminal || !dev.activated) continue
            if (dev.ip in checkedIps) continue
            if (dev.serialNumber.isNotBlank() && dev.serialNumber.uppercase() in knownSerials) continue

            if (hikPassword.isBlank()) {
                checkedIps.add(dev.ip)
                unpairedOut.add(dev)
                continue
            }
            onProgress(DiscoveryProgress("Đang tự nối máy Hikvision ${dev.model} (${dev.ip})..."))
            val candidate = DeviceConfig(
                ip = dev.ip, port = dev.httpPort, machineNo = DeviceConfig.DEFAULT_MACHINE_NO,
                name = dev.model.ifBlank { "Hikvision" }, protocol = DeviceProtocol.HIKVISION_ISAPI,
                username = effectiveUser, accessPassword = hikPassword,
                useHttps = dev.httpPort == DeviceConfig.DEFAULT_HIK_HTTPS_PORT, serialNumber = dev.serialNumber,
            )
            val res = deviceGateway.connect(candidate, timeoutMs = 2_500)
            checkedIps.add(dev.ip)
            if (res.isSuccess && res.data != null) {
                val realSerial = res.data.deviceSerialNumber.ifBlank { dev.serialNumber }
                deviceGateway.disconnect(res.data.handle)
                autoDiscoveredOut.add(candidate.copy(serialNumber = realSerial))
                foundIps.add(dev.ip)
                if (realSerial.isNotBlank()) knownSerials.add(realSerial.uppercase())
            } else {
                unpairedOut.add(dev)
            }
        }
    }
}

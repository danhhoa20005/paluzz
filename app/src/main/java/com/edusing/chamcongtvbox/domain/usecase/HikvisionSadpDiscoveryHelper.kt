package com.edusing.chamcongtvbox.domain.usecase

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway

/**
 * Hỗ trợ định vị & tự động kết nối mọi máy chấm công Hikvision trong mạng LAN qua đa hướng SADP (`239.255.255.250:37020`):
 * 1. Định vị lại máy đã lưu bị router đổi IP DHCP theo `serialNumber` (0 lần thử mật khẩu).
 * 2. Nếu người dùng đã lưu tài khoản Hikvision 1 lần, tự thử tối đa **1 lần duy nhất** với máy chấm công Hikvision mới
 *    phát hiện trên LAN (không thử lại nếu 401 để tuyệt đối tránh khoá máy 30 phút).
 */
internal object HikvisionSadpDiscoveryHelper {

    suspend fun discoverAndMatch(
        deviceGateway: FkDeviceGateway,
        knownDevices: List<DeviceConfig>,
        missingHikDevices: MutableList<DeviceConfig>,
        checkedIps: MutableSet<String>,
        foundIps: MutableSet<String>,
        relocatedOut: MutableList<DeviceConfig>,
        autoDiscoveredOut: MutableList<DeviceConfig>,
        hikUsername: String,
        hikPassword: String,
        onProgress: (DiscoveryProgress) -> Unit,
    ) {
        if (missingHikDevices.isEmpty() && hikPassword.isBlank()) return
        onProgress(DiscoveryProgress("Đang dò đa hướng SADP tìm mọi máy Hikvision trong mạng LAN..."))
        val sadpFound = deviceGateway.discoverHikvisionSadp(timeoutMs = 1_800)
        if (sadpFound.isEmpty()) return

        // 1. Định vị máy Hikvision đã lưu bị đổi IP DHCP bằng SerialNumber (không thử mật khẩu)
        val iter = missingHikDevices.iterator()
        while (iter.hasNext()) {
            val missing = iter.next()
            val match = sadpFound.firstOrNull { it.serialNumber.equals(missing.serialNumber, ignoreCase = true) }
            if (match != null) {
                relocatedOut.add(missing.copy(ip = match.ip, port = match.httpPort))
                foundIps.add(match.ip)
                checkedIps.add(match.ip)
                iter.remove()
            }
        }

        // 2. Nếu đã lưu tài khoản Hikvision 1 lần -> tự nối máy chấm công Hikvision mới (tối đa 1 lần thử mỗi máy)
        if (hikPassword.isBlank()) return
        val knownSerials = knownDevices.mapNotNull { it.serialNumber.takeIf { sn -> sn.isNotBlank() }?.uppercase() }.toMutableSet()
        val effectiveUser = hikUsername.trim().ifBlank { DeviceConfig.DEFAULT_HIK_USERNAME }
        for (sadp in sadpFound) {
            if (!sadp.isAccessTerminal || !sadp.activated) continue
            if (sadp.ip in checkedIps) continue
            if (sadp.serialNumber.isNotBlank() && sadp.serialNumber.uppercase() in knownSerials) continue

            onProgress(DiscoveryProgress("Đang tự nối máy Hikvision ${sadp.model} (${sadp.ip})..."))
            val candidate = DeviceConfig(
                ip = sadp.ip,
                port = sadp.httpPort,
                machineNo = DeviceConfig.DEFAULT_MACHINE_NO,
                name = sadp.model.ifBlank { "Hikvision" },
                protocol = DeviceProtocol.HIKVISION_ISAPI,
                username = effectiveUser,
                accessPassword = hikPassword,
                useHttps = sadp.httpPort == DeviceConfig.DEFAULT_HIK_HTTPS_PORT,
                serialNumber = sadp.serialNumber,
            )
            val res = deviceGateway.connect(candidate, timeoutMs = 2_500)
            checkedIps.add(sadp.ip)
            if (res.isSuccess && res.data != null) {
                val realSerial = res.data.deviceSerialNumber.ifBlank { sadp.serialNumber }
                deviceGateway.disconnect(res.data.handle)
                autoDiscoveredOut.add(candidate.copy(serialNumber = realSerial))
                foundIps.add(sadp.ip)
                if (realSerial.isNotBlank()) knownSerials.add(realSerial.uppercase())
            }
        }
    }
}

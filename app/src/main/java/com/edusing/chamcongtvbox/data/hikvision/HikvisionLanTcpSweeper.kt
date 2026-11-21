package com.edusing.chamcongtvbox.data.hikvision

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Bộ quét dự phòng TCP cổng `80` / `443` + xác minh ISAPI không cần mật khẩu (`GET /ISAPI/System/deviceInfo`):
 * - Miễn nhiễm 100% với router Wi-Fi chặn UDP Multicast SADP (IGMP Snooping) và điện thoại Android/OPPO chặn ICMP Ping.
 * - Quét trọn 254 IP của một dải `/24` trong ~1.5 giây nhờ lọc TCP mở cổng (timeout 260ms, 48 luồng song song),
 *   rồi chỉ gửi đúng 1 yêu cầu HTTP GET không mật khẩu tới vài thiết bị mở cổng 80/443 để xác nhận Hikvision ISAPI.
 */
internal class HikvisionLanTcpSweeper(
    private val client: HikvisionIsapiClient,
) {
    suspend fun sweepSubnet(
        prefix: String,
        excludeIps: Set<String> = emptySet(),
        localIp: String? = null,
    ): List<HikvisionSadpDevice> = withContext(Dispatchers.IO) {
        val clean = prefix.trim().trimEnd('.')
        val parts = clean.split('.')
        if (parts.size != 3 || !parts.all { it.toIntOrNull() in 0..255 }) return@withContext emptyList()

        val semaphore = Semaphore(TCP_CONCURRENCY)
        coroutineScope {
            (1..254).map { host ->
                async(Dispatchers.IO) {
                    val ip = "$clean.$host"
                    if (ip == localIp || ip in excludeIps) return@async null
                    semaphore.withPermit { probeSingleIp(ip) }
                }
            }.awaitAll().filterNotNull().sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 999 }
        }
    }

    private fun probeSingleIp(ip: String): HikvisionSadpDevice? {
        val openPort = when {
            isTcpPortOpen(ip, DeviceConfig.DEFAULT_HIK_PORT) -> DeviceConfig.DEFAULT_HIK_PORT
            isTcpPortOpen(ip, DeviceConfig.DEFAULT_HIK_HTTPS_PORT) -> DeviceConfig.DEFAULT_HIK_HTTPS_PORT
            else -> return null
        }
        val label = client.probeIsapiHost(ip, openPort, timeoutMs = ISAPI_PROBE_TIMEOUT_MS) ?: return null
        val model = label.removePrefix("Hikvision ").trim().ifBlank { "Hikvision ISAPI" }
        return HikvisionSadpDevice(
            ip = ip,
            httpPort = openPort,
            model = model,
            serialNumber = "",
            activated = true,
            isAccessTerminal = HikvisionSadpScanner.classifyAccessTerminal(model),
        )
    }

    private fun isTcpPortOpen(ip: String, port: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), TCP_CONNECT_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    companion object {
        private const val TCP_CONCURRENCY = 48
        private const val TCP_CONNECT_TIMEOUT_MS = 260
        private const val ISAPI_PROBE_TIMEOUT_MS = 900
    }
}

package com.edusing.chamcongtvbox.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.edusing.chamcongtvbox.domain.repository.InternetCheckResult
import com.edusing.chamcongtvbox.domain.repository.InternetProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI

/**
 * Kiểm tra Internet & Wi-Fi/LAN thực tế cho mọi dòng Android TV Box (Android 7–15) và Điện thoại:
 * - Phân biệt rõ 3 trạng thái: Wi-Fi đang bị tắt công tắc, rớt Wi-Fi/LAN (chờ nối lại), và đứt Internet WAN.
 * - Chủ động mở socket TCP nhẹ tới `apiDomain` (đối chiếu DNS `1.1.1.1`/`8.8.8.8`).
 * - Tự kích nối lại Wi-Fi (`setWifiEnabled` / `reconnect` / `reassociate` / `enableNetwork` trên Android 7–9
 *   và `reportNetworkConnectivity` trên Android 10+).
 */
class InternetProbeImpl(
    context: Context,
) : InternetProbe {

    private val appContext = context.applicationContext

    override suspend fun checkInternet(apiDomain: String): InternetCheckResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val hasEth = hasEthernetTransport()
        val hasWifi = hasWifiTransport()
        val hasLan = hasEth || hasWifi || hasPrivateLanIpv4()
        val wifiOff = !hasEth && !isWifiSwitchEnabled()

        if (wifiOff) {
            return@withContext InternetCheckResult(
                online = false,
                detail = "Wi-Fi đang tắt",
                hasLanOrWifi = false,
                wifiHardwareDisabled = true,
            )
        }

        if (!hasActiveInternetTransport()) {
            return@withContext InternetCheckResult(
                online = false,
                detail = if (hasLan) "Wi-Fi/LAN chưa có Internet" else "Mất kết nối Wi-Fi / LAN",
                hasLanOrWifi = hasLan,
                wifiHardwareDisabled = false,
            )
        }

        val (host, port) = extractHostPort(apiDomain)
        if (host.isNotBlank() && tcpProbe(host, port, PRIMARY_TIMEOUT_MS)) {
            val ms = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            return@withContext InternetCheckResult(
                online = true,
                latencyMs = ms,
                detail = "Internet OK (${ms}ms)",
                hasLanOrWifi = hasLan,
                wifiHardwareDisabled = false,
            )
        }

        val publicReachable = tcpProbe("1.1.1.1", 53, FALLBACK_TIMEOUT_MS) ||
            tcpProbe("8.8.8.8", 53, FALLBACK_TIMEOUT_MS)
        if (publicReachable && host.isNotBlank() && tcpProbe(host, port, PRIMARY_TIMEOUT_MS)) {
            val ms = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            return@withContext InternetCheckResult(
                online = true,
                latencyMs = ms,
                detail = "Internet OK (${ms}ms)",
                hasLanOrWifi = hasLan,
                wifiHardwareDisabled = false,
            )
        }

        InternetCheckResult(
            online = false,
            detail = if (publicReachable) "Máy chủ web tạm không phản hồi" else "Mất kết nối Internet",
            hasLanOrWifi = hasLan,
            wifiHardwareDisabled = false,
        )
    }

    override fun hasLanOrWifiIpv4(): Boolean = hasPrivateLanIpv4()

    @Suppress("DEPRECATION")
    override fun requestReconnectNetwork() {
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val hasEth = hasEthernetTransport()

            // Trên TV Box Android 7–9 (API < 29): chủ động bật lại Wi-Fi, reconnect, reassociate và kích lại mạng đã lưu
            if (!hasEth && wm != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (!wm.isWifiEnabled) {
                    wm.isWifiEnabled = true
                }
                val connectedId = runCatching { wm.connectionInfo?.networkId ?: -1 }.getOrDefault(-1)
                if (connectedId < 0) {
                    runCatching {
                        wm.configuredNetworks?.forEach { net ->
                            if (net.networkId >= 0) wm.enableNetwork(net.networkId, false)
                        }
                    }
                }
                wm.reconnect()
                wm.reassociate()
            }

            // Trên Android 10+ (API >= 29): kích quét Wi-Fi nhẹ (nếu đang bật nhưng rớt kết nối) và báo ConnectivityService kiểm tra lại
            if (!hasEth && wm != null && wm.isWifiEnabled && !hasWifiTransport()) {
                runCatching { wm.startScan() }
            }
            if (cm != null) {
                cm.activeNetwork?.let { active ->
                    runCatching { cm.reportNetworkConnectivity(active, false) }
                }
            }
        }
    }

    private fun isWifiSwitchEnabled(): Boolean = runCatching {
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return true
        wm.isWifiEnabled
    }.getOrDefault(true)

    private fun hasEthernetTransport(): Boolean = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    private fun hasWifiTransport(): Boolean = hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

    @Suppress("DEPRECATION")
    private fun hasTransport(transport: Int): Boolean = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        cm.allNetworks.any { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@any false
            caps.hasTransport(transport)
        }
    }.getOrDefault(false)

    private fun hasActiveInternetTransport(): Boolean = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            )
    }.getOrDefault(true)

    private fun hasPrivateLanIpv4(): Boolean = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        ifaces.any { iface ->
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) return@any false
            val n = iface.name.lowercase()
            if (n.startsWith("tun") || n.startsWith("wg") || n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("p2p")) {
                return@any false
            }
            iface.interfaceAddresses.any { ia ->
                val addr = ia.address as? Inet4Address ?: return@any false
                val ip = addr.hostAddress ?: return@any false
                !addr.isLoopbackAddress && !addr.isLinkLocalAddress &&
                    (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))
            }
        }
    }.getOrDefault(false)

    private fun extractHostPort(apiDomain: String): Pair<String, Int> = runCatching {
        val normalized = apiDomain.trim().let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it"
        }
        val uri = URI(normalized)
        val host = uri.host.orEmpty()
        val port = when {
            uri.port in 1..65535 -> uri.port
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> 443
        }
        host to port
    }.getOrDefault("" to 443)

    private fun tcpProbe(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    companion object {
        private const val PRIMARY_TIMEOUT_MS = 3_000
        private const val FALLBACK_TIMEOUT_MS = 2_000
    }
}

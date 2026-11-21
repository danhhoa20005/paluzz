@file:Suppress("DEPRECATION")

package com.edusing.chamcongtvbox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address

/**
 * Trình lắng nghe kép trạng thái mạng cho mọi đời Android TV Box (Android 7–15) và Điện thoại:
 * 1. `ConnectivityManager.NetworkCallback` riêng cho Internet tổng và riêng cho Wi-Fi / dây LAN (bắt chính xác
 *    lúc rớt Wi-Fi dù còn 4G và thời điểm router DHCP cấp xong IPv4).
 * 2. `BroadcastReceiver` dự phòng (`WIFI_STATE_CHANGED` / `NETWORK_STATE_CHANGED` / `CONNECTIVITY_ACTION`)
 *    cho các dòng TV Box AOSP giá rẻ không phát đủ `NetworkCallback`.
 */
internal class NetworkStateObserver(
    private val context: Context,
    private val engine: AttendanceEngine,
) {
    private var internetCallback: ConnectivityManager.NetworkCallback? = null
    private var lanWifiCallback: ConnectivityManager.NetworkCallback? = null
    private var legacyReceiver: BroadcastReceiver? = null

    fun register() {
        registerCallbacks()
        registerLegacyBroadcast()
    }

    fun unregister() {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            internetCallback?.let { cm.unregisterNetworkCallback(it) }
            lanWifiCallback?.let { cm.unregisterNetworkCallback(it) }
            internetCallback = null
            lanWifiCallback = null
        }
        runCatching {
            legacyReceiver?.let { context.unregisterReceiver(it) }
            legacyReceiver = null
        }
    }

    private fun registerCallbacks() = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val inetReq = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val inetCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = engine.onNetworkRestored()
            override fun onLost(network: Network) = engine.onSystemNetworkLost()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) engine.onNetworkRestored()
            }
        }
        cm.registerNetworkCallback(inetReq, inetCb)
        internetCallback = inetCb

        val lanReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val lanCb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) = engine.onLanTransportLost()
            override fun onAvailable(network: Network) = engine.onLanTransportRestored()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val hasPrivateV4 = linkProperties.linkAddresses.any { la ->
                    val addr = la.address as? Inet4Address ?: return@any false
                    val ip = addr.hostAddress ?: return@any false
                    !addr.isLoopbackAddress && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))
                }
                if (hasPrivateV4) engine.onLanTransportRestored()
            }
        }
        cm.registerNetworkCallback(lanReq, lanCb)
        lanWifiCallback = lanCb
    }

    private fun registerLegacyBroadcast() = runCatching {
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (isInitialStickyBroadcast) return
                when (intent?.action) {
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                        if (state == WifiManager.WIFI_STATE_DISABLED || state == WifiManager.WIFI_STATE_DISABLING) {
                            engine.onLanTransportLost()
                        } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                            engine.onLanTransportRestored()
                        }
                    }
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                        if (info?.isConnected == true) engine.onLanTransportRestored()
                        else if (info?.state == NetworkInfo.State.DISCONNECTED) engine.onLanTransportLost()
                    }
                    ConnectivityManager.CONNECTIVITY_ACTION -> {
                        val noConn = intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)
                        if (noConn) engine.onSystemNetworkLost() else engine.onNetworkRestored()
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        legacyReceiver = receiver
    }
}

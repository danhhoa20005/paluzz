package com.edusing.chamcongtvbox.data.hikvision

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Hỗ trợ HTTPS (`443`) với chứng chỉ tự ký cục bộ của máy chấm công Hikvision `DS-K1T320MX` (`CN=tmp_comm.cert`).
 * Chỉ kích hoạt giới hạn cho địa chỉ IPv4 mạng LAN nội bộ (`192.168.x.x`, `10.x.x.x`, `172.16–31.x.x`, `127.0.0.1`),
 * tuyệt đối không dùng chung với kết nối API đám mây.
 */
internal object HikvisionLanTls {

    /** Kiểm tra địa chỉ có thuộc dải IPv4 LAN nội bộ (RFC 1918 / Loopback) hay không. */
    fun isPrivateLanIp(host: String): Boolean {
        val parts = host.trim().split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        val c = parts[2].toIntOrNull() ?: return false
        val d = parts[3].toIntOrNull() ?: return false
        if (a !in 0..255 || b !in 0..255 || c !in 0..255 || d !in 0..255) return false
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    /** Trích xuất sạch IPv4 từ chuỗi nhập hoặc đường dẫn web Hikvision (VD `https://192.168.1.8/doc/index.html`). */
    fun extractHostFromInput(raw: String): String {
        val trimmed = raw.trim()
        val noScheme = trimmed.substringAfter("://", trimmed)
        val hostPort = noScheme.substringBefore('/').substringBefore('#').substringBefore('?')
        return hostPort.substringBefore(':').trim()
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    fun applyLanSelfSignedTls(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                require(!chain.isNullOrEmpty()) { "Thiếu chứng chỉ SSL từ máy Hikvision" }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return builder
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(HostnameVerifier { hostname, _ -> isPrivateLanIp(hostname) })
    }
}

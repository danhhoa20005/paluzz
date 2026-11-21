package com.edusing.chamcongtvbox.data.remote

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import java.io.EOFException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Khởi tạo [OkHttpClient] tối ưu cho máy chủ API (khớp hành vi `ApiClient.cs` trên Windows):
 * - Cố định **HTTP/1.1** và ưu tiên **TLS 1.2** (kèm dự phòng TLS 1.3 / `COMPATIBLE_TLS`) để
 *   không bị lỗi bắt tay SSL trên TV Box đời cũ hoặc bộ định tuyến chặn TLS 1.3 / HTTP/2.
 * - Ưu tiên địa chỉ **IPv4** trước IPv6 để tránh treo mạng khi Wi-Fi cấp DNS IPv6 không đi ra ngoài được.
 */
internal object HttpClientFactory {

    private const val TAG = "AttendanceApiClient"

    /** DNS ưu tiên IPv4 trước IPv6 (máy chủ `api.*` chỉ dùng IPv4). */
    val ipv4FirstDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
        }
    }

    /** Client chính: HTTP/1.1 + TLS 1.2 ưu tiên (đồng nhất `ServicePointManager.SecurityProtocol = Tls12`). */
    fun createPrimaryClient(): OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build(),
                ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build(),
                ConnectionSpec.CLEARTEXT,
            )
        )
        .build()

    /** Client dự phòng: tạo kết nối mới hoàn toàn (không tái dùng socket cũ) + bật cả TLS 1.2 và TLS 1.3. */
    fun createFallbackClient(): OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                    .build(),
                ConnectionSpec.COMPATIBLE_TLS,
                ConnectionSpec.CLEARTEXT,
            )
        )
        .build()

    /** Ghi log lỗi an toàn (không vỡ trong unit test JVM thuần). */
    fun logError(context: String, error: Throwable) {
        runCatching { Log.e(TAG, "$context: ${error.javaClass.simpleName}: ${error.message}", error) }
    }

    /** Chuyển ngoại lệ mạng thành thông báo tiếng Việt rõ nguyên nhân kèm tên máy chủ để dễ xử lý. */
    fun formatConnectionError(apiDomain: String, e: Throwable): String {
        val root = rootCause(e)
        val detail = root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
        return when (root) {
            is UnknownHostException ->
                "Không phân giải được tên miền ($apiDomain). Kiểm tra Wi-Fi/DNS trên máy. [$detail]"
            is SSLHandshakeException, is SSLException ->
                "Lỗi bắt tay bảo mật HTTPS/SSL tới $apiDomain. Kiểm tra ngày giờ hệ thống hoặc tường lửa mạng. [$detail]"
            is SocketTimeoutException ->
                "Hết thời gian chờ phản hồi từ $apiDomain. Kiểm tra kết nối Internet. [$detail]"
            is ConnectException ->
                "Không thể kết nối tới $apiDomain (máy chủ từ chối hoặc bị chặn tường lửa). [$detail]"
            is EOFException ->
                "Kết nối tới $apiDomain bị ngắt giữa chừng. Vui lòng thử đăng nhập lại. [$detail]"
            is IllegalArgumentException ->
                "Địa chỉ máy chủ không hợp lệ ($apiDomain): $detail"
            else ->
                "Lỗi kết nối máy chủ ($apiDomain): $detail"
        }
    }

    private fun rootCause(t: Throwable): Throwable {
        var cur = t
        var guard = 0
        while (cur.cause != null && cur.cause !== cur && guard++ < 8) {
            cur = cur.cause!!
        }
        return cur
    }
}

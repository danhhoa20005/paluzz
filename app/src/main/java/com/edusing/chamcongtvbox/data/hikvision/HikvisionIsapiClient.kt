package com.edusing.chamcongtvbox.data.hikvision

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Khách HTTP/HTTPS ISAPI thuần Kotlin (OkHttp + Digest Auth RFC 7616 có cache `nonce`) cho máy Hikvision:
 * - Lưu đệm `nonce` theo từng thiết bị để gửi kèm xác thực ngay từ lượt đầu (giảm 50% gói tin & tăng gấp đôi tốc độ).
 * - Dùng kết nối ngắn hạn chuyên biệt cho mạng LAN, không dùng chung pool với API đám mây.
 */
internal class HikvisionIsapiClient(
    private val baseClient: OkHttpClient = createDefaultLanClient(),
    private val lanHttpsClient: OkHttpClient = createLanHttpsClient(baseClient),
) {
    private data class CachedDigest(val challenge: HikvisionDigestChallenge, val nc: AtomicInteger)
    private val digestCache = ConcurrentHashMap<String, CachedDigest>()

    /** Dò nhanh không cần mật khẩu xem địa chỉ IP có chạy Hikvision ISAPI hay không (`GET /ISAPI/System/deviceInfo`). */
    fun probeIsapiHost(ip: String, port: Int = DeviceConfig.DEFAULT_HIK_PORT, timeoutMs: Int = 700): String? {
        val useHttps = port == DeviceConfig.DEFAULT_HIK_HTTPS_PORT
        val url = buildUrl(ip, port, useHttps = useHttps, "/ISAPI/System/deviceInfo")
        val req = Request.Builder().url(url).get().header("Connection", "close").build()
        return runCatching {
            clientWithTimeout(timeoutMs, useHttps = useHttps, hostIp = ip).newCall(req).execute().use { res ->
                val server = res.header("Server").orEmpty().lowercase()
                val wwwAuth = res.headers("WWW-Authenticate").joinToString(" ")
                val body = res.body?.string().orEmpty()
                val isHikServer = server.contains("webs") || server.contains("hikvision") || server.contains("dnvrs")
                val isHikRealm = wwwAuth.contains("DS-", true) || wwwAuth.contains("HIK", true) ||
                    wwwAuth.contains("IP Camera", true) || wwwAuth.contains("Embedded", true)
                val isIsapiBody = body.contains("isapi.org", true) || body.contains("<DeviceInfo", true) ||
                    body.contains("<ResponseStatus", true) || body.contains("<userCheck", true)
                when {
                    res.code in 200..299 && isIsapiBody -> "Hikvision ${HikvisionIsapiParser.parseDeviceInfo(body).model}"
                    res.code == 401 && wwwAuth.contains("Digest", true) && (isHikServer || isHikRealm || isIsapiBody) -> "Hikvision ISAPI"
                    else -> null
                }
            }
        }.getOrNull()
    }

    fun fetchDeviceInfo(device: DeviceConfig, timeoutMs: Int = 5_000): Pair<HikHttpResponse, HikDeviceInfo?> {
        val res = request(device, "GET", "/ISAPI/System/deviceInfo", timeoutMs = timeoutMs)
        return res to if (res.isSuccess) HikvisionIsapiParser.parseDeviceInfo(res.body) else null
    }

    fun syncVietnamTime(device: DeviceConfig, nowMs: Long): HikHttpResponse {
        val isoLocal = HikvisionIsapiParser.formatVietnamIsoTime(nowMs, includeOffset = false)
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<Time version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema">""" +
            """<timeMode>manual</timeMode><localTime>$isoLocal</localTime><timeZone>CST-7:00:00</timeZone></Time>"""
        return request(device, "PUT", "/ISAPI/System/time", body = xml, contentType = XML_MEDIA, timeoutMs = 4_000)
    }

    /**
     * Đăng ký Webhook `/hikvision/event` lên slot 1 CHỈ KHI slot 1 đang trống (`0.0.0.0`) hoặc vốn thuộc App TV Box,
     * tránh ghi đè hệ thống bên thứ 3 đang gắn sẵn trên máy Hikvision (app vẫn đọc đầy đủ qua polling `AcsEvent`).
     */
    fun registerWebhookHost(device: DeviceConfig, listenerIp: String, listenerPort: Int): HikHttpResponse {
        val current = runCatching { request(device, "GET", "/ISAPI/Event/notification/httpHosts/1", timeoutMs = 3_000) }.getOrNull()
        if (current != null && current.isSuccess) {
            val cur = HikvisionWebhookApi.parseWebhookXml(current.body)
            val alreadyMatches = cur.enabled && cur.hostOrIp.equals(listenerIp, true) &&
                cur.port == listenerPort && cur.url.trimEnd('/').equals("/hikvision/event", true)
            if (alreadyMatches) return current
            val isOwnedOrEmpty = !cur.enabled || cur.url.trimEnd('/').equals("/hikvision/event", true)
            if (!isOwnedOrEmpty) return current
        }
        val fullXml = HikvisionWebhookApi.buildWebhookXml("HTTP", "ipaddress", listenerIp, listenerPort, "/hikvision/event", includeSubscribe = true)
        val first = request(device, "PUT", "/ISAPI/Event/notification/httpHosts/1", body = fullXml, contentType = XML_MEDIA, timeoutMs = 4_000)
        if ((first.isSuccess && !HikvisionIsapiParser.isIsapiBodyError(first.body)) || first.isUnauthorized) return first
        val simpleXml = HikvisionWebhookApi.buildWebhookXml("HTTP", "ipaddress", listenerIp, listenerPort, "/hikvision/event", includeSubscribe = false)
        return request(device, "PUT", "/ISAPI/Event/notification/httpHosts/1", body = simpleXml, contentType = XML_MEDIA, timeoutMs = 4_000)
    }

    fun fetchAccessEvents(
        device: DeviceConfig,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        maxPages: Int = 4,
    ): Pair<HikHttpResponse, List<HikParsedEvent>> {
        val collected = mutableListOf<HikParsedEvent>()
        var pos = 0; var lastRes = HikHttpResponse(200, "")
        val searchId = "acs_$nowMs"
        for (page in 0 until maxPages) {
            val payload = JSONObject().put(
                "AcsEventCond",
                JSONObject()
                    .put("searchID", searchId)
                    .put("searchResultPosition", pos)
                    .put("maxResults", PAGE_SIZE)
                    .put("major", 5)
                    .put("minor", 0)
                    .put("startTime", HikvisionIsapiParser.formatVietnamIsoTime(startMs))
                    .put("endTime", HikvisionIsapiParser.formatVietnamIsoTime(endMs))
                    .put("timeReverseOrder", true),
            ).toString()
            val res = request(device, "POST", "/ISAPI/AccessControl/AcsEvent?format=json", body = payload)
            lastRes = res
            if (!res.isSuccess || HikvisionIsapiParser.isIsapiBodyError(res.body)) return res to emptyList()
            val parsed = HikvisionIsapiParser.parseAcsEvents(res.body, device.ip, nowMs)
            collected.addAll(parsed.events)
            val advance = parsed.pageAdvance
            if (advance <= 0 || parsed.isExplicitEnd) break
            pos += advance
            if (!parsed.hasMoreHint && (parsed.totalMatches == 0 || pos >= parsed.totalMatches)) break
        }
        return lastRes to collected
    }

    /** Tải ảnh khuôn mặt từ `faceURL` trả về bởi `FDLib/FDSearch` (hỗ trợ cả đường dẫn tương đối lẫn URL đầy đủ). */
    @Throws(IOException::class)
    fun downloadFaceUrlBytes(device: DeviceConfig, faceUrl: String, timeoutMs: Int = 10_000): Pair<Int, ByteArray> {
        val trimmed = faceUrl.trim()
        val path = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            val afterScheme = trimmed.substringAfter("://")
            val slash = afterScheme.indexOf('/')
            if (slash >= 0) afterScheme.substring(slash) else "/"
        } else if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return requestBytes(device, "GET", path, timeoutMs = timeoutMs)
    }

    @Throws(IOException::class)
    fun request(
        device: DeviceConfig,
        method: String,
        pathWithQuery: String,
        body: String? = null,
        contentType: String = JSON_MEDIA,
        timeoutMs: Int = 6_000,
    ): HikHttpResponse {
        val (code, bytes, headers) = executeWithDigestCache(device, method, pathWithQuery, body?.toByteArray(Charsets.UTF_8), contentType, timeoutMs)
        return HikHttpResponse(code, String(bytes, Charsets.UTF_8), headers)
    }

    /** Gửi/nhận payload nhị phân (ảnh JPEG camera hoặc multipart ảnh khuôn mặt `FDLib`) có cache Digest Auth. */
    @Throws(IOException::class)
    fun requestBytes(
        device: DeviceConfig,
        method: String,
        pathWithQuery: String,
        bodyBytes: ByteArray? = null,
        contentType: String = JSON_MEDIA,
        timeoutMs: Int = 12_000,
    ): Pair<Int, ByteArray> {
        val (code, bytes, _) = executeWithDigestCache(device, method, pathWithQuery, bodyBytes, contentType, timeoutMs)
        return code to bytes
    }

    private fun executeWithDigestCache(
        device: DeviceConfig,
        method: String,
        pathWithQuery: String,
        bodyBytes: ByteArray?,
        contentType: String,
        timeoutMs: Int,
    ): Triple<Int, ByteArray, Map<String, List<String>>> {
        val url = buildUrl(device.ip, device.port, device.useHttps, pathWithQuery)
        val client = clientWithTimeout(timeoutMs, useHttps = device.useHttps, hostIp = device.ip)
        val user = device.username.ifBlank { DeviceConfig.DEFAULT_HIK_USERNAME }
        val cacheKey = "${device.key}|$user|${device.accessPassword.hashCode()}"

        val cached = digestCache[cacheKey]
        val preAuth = cached?.let {
            it.challenge.buildAuthorization(method, pathWithQuery, user, device.accessPassword, it.nc.getAndIncrement())
        }
        val first = executeRaw(client, method, url, bodyBytes, contentType, authHeader = preAuth)
        if (first.first != 401) return first

        val wwwHeaders = first.third.entries.filter { it.key.equals("WWW-Authenticate", true) }.flatMap { it.value }
        val freshChallenge = HikvisionDigestChallenge.parse(wwwHeaders) ?: run {
            digestCache.remove(cacheKey); return first
        }
        if (digestCache.size >= 32) digestCache.clear()
        val entry = CachedDigest(freshChallenge, AtomicInteger(2))
        digestCache[cacheKey] = entry
        val retryAuth = freshChallenge.buildAuthorization(method, pathWithQuery, user, device.accessPassword, nonceCount = 1)
        val second = executeRaw(client, method, url, bodyBytes, contentType, authHeader = retryAuth)
        if (second.first == 401) digestCache.remove(cacheKey)
        return second
    }

    private fun executeRaw(
        client: OkHttpClient, method: String, url: String, bodyBytes: ByteArray?, contentType: String, authHeader: String?,
    ): Triple<Int, ByteArray, Map<String, List<String>>> {
        val reqBody = if (method.equals("GET", true) || method.equals("DELETE", true)) null
        else (bodyBytes ?: ByteArray(0)).toRequestBody(contentType.toMediaType())
        val builder = Request.Builder().url(url).method(method.uppercase(), reqBody).header("Connection", "close")
        if (!authHeader.isNullOrBlank()) builder.header("Authorization", authHeader)
        client.newCall(builder.build()).execute().use { res ->
            return Triple(res.code, res.body?.bytes() ?: ByteArray(0), res.headers.toMultimap())
        }
    }

    private fun clientWithTimeout(timeoutMs: Int, useHttps: Boolean = false, hostIp: String = ""): OkHttpClient {
        val ms = timeoutMs.coerceIn(500, 30_000).toLong()
        val root = if (useHttps && HikvisionLanTls.isPrivateLanIp(hostIp)) lanHttpsClient else baseClient
        return root.newBuilder().connectTimeout(ms, TimeUnit.MILLISECONDS)
            .readTimeout(ms, TimeUnit.MILLISECONDS).writeTimeout(ms, TimeUnit.MILLISECONDS).build()
    }

    private fun buildUrl(ip: String, port: Int, useHttps: Boolean, pathWithQuery: String): String {
        val scheme = if (useHttps) "https" else "http"
        val cleanPath = if (pathWithQuery.startsWith("/")) pathWithQuery else "/$pathWithQuery"
        return "$scheme://${ip.trim()}:$port$cleanPath"
    }

    companion object {
        private const val PAGE_SIZE = 30
        const val JSON_MEDIA = "application/json; charset=utf-8"
        const val XML_MEDIA = "application/xml; charset=utf-8"

        private fun createDefaultLanClient(): OkHttpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(4, 15, TimeUnit.SECONDS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private fun createLanHttpsClient(base: OkHttpClient): OkHttpClient =
            runCatching { HikvisionLanTls.applyLanSelfSignedTls(base.newBuilder()).build() }.getOrElse { base }
    }
}

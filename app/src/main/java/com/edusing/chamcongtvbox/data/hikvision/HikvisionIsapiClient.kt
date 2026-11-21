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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Khách HTTP/HTTPS ISAPI thuần Kotlin (OkHttp + Digest Auth RFC 7616) cho máy Hikvision `DS-K1T320MX`.
 * Dùng kết nối ngắn hạn chuyên biệt cho mạng LAN, không dùng chung pool với API đám mây.
 */
internal class HikvisionIsapiClient(
    private val baseClient: OkHttpClient = createDefaultLanClient(),
) {
    private val nonceCounter = AtomicInteger(1)

    /** Dò nhanh không cần mật khẩu xem địa chỉ IP có chạy Hikvision ISAPI hay không (`GET /ISAPI/System/deviceInfo`). */
    fun probeIsapiHost(ip: String, port: Int = DeviceConfig.DEFAULT_HIK_PORT, timeoutMs: Int = 700): String? {
        val url = buildUrl(ip, port, useHttps = port == DeviceConfig.DEFAULT_HIK_HTTPS_PORT, "/ISAPI/System/deviceInfo")
        val req = Request.Builder().url(url).get().header("Connection", "close").build()
        return runCatching {
            clientWithTimeout(timeoutMs).newCall(req).execute().use { res ->
                val server = res.header("Server").orEmpty().lowercase()
                val wwwAuth = res.headers("WWW-Authenticate").joinToString(" ")
                val body = res.body?.string().orEmpty()
                val isHikServer = server.contains("webs") || server.contains("hikvision") || server.contains("dnvrs")
                val isHikRealm = wwwAuth.contains("DS-", true) || wwwAuth.contains("HIK", true) ||
                    wwwAuth.contains("IP Camera", true) || wwwAuth.contains("Embedded", true)
                val isIsapiBody = body.contains("isapi.org", true) || body.contains("<DeviceInfo", true) || body.contains("<ResponseStatus", true)
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

    fun registerWebhookHost(device: DeviceConfig, listenerIp: String, listenerPort: Int): HikHttpResponse {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<HttpHostNotification version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema">""" +
            """<id>1</id><url>/hikvision/event</url><protocolType>HTTP</protocolType>""" +
            """<parameterFormatType>JSON</parameterFormatType><addressingFormatType>ipaddress</addressingFormatType>""" +
            """<ipAddress>$listenerIp</ipAddress><portNo>$listenerPort</portNo>""" +
            """<httpAuthenticationMethod>none</httpAuthenticationMethod></HttpHostNotification>"""
        return request(device, "PUT", "/ISAPI/Event/notification/httpHosts/1", body = xml, contentType = XML_MEDIA, timeoutMs = 4_000)
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
            if (!res.isSuccess) return res to emptyList()
            val parsed = HikvisionIsapiParser.parseAcsEvents(res.body, device.ip, nowMs)
            collected.addAll(parsed.events)
            pos += PAGE_SIZE
            if (pos >= parsed.totalMatches || parsed.totalMatches == 0) break
        }
        return lastRes to collected
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
        val url = buildUrl(device.ip, device.port, device.useHttps, pathWithQuery)
        val client = clientWithTimeout(timeoutMs)
        val first = executeSingle(client, method, url, body, contentType, authHeader = null)
        if (!first.isUnauthorized) return first

        val wwwHeaders = first.headers.entries
            .filter { it.key.equals("WWW-Authenticate", ignoreCase = true) }
            .flatMap { it.value }
        val challenge = HikvisionDigestChallenge.parse(wwwHeaders) ?: return first
        val auth = challenge.buildAuthorization(
            method = method,
            requestUriWithQuery = pathWithQuery,
            username = device.username.ifBlank { DeviceConfig.DEFAULT_HIK_USERNAME },
            password = device.accessPassword,
            nonceCount = nonceCounter.getAndIncrement(),
        )
        return executeSingle(client, method, url, body, contentType, authHeader = auth)
    }

    private fun executeSingle(
        client: OkHttpClient,
        method: String,
        url: String,
        body: String?,
        contentType: String,
        authHeader: String?,
    ): HikHttpResponse {
        val reqBody = if (method.equals("GET", true) || method.equals("DELETE", true)) null else (body ?: "").toRequestBody(contentType.toMediaType())
        val builder = Request.Builder().url(url).method(method.uppercase(), reqBody).header("Connection", "close")
        if (!authHeader.isNullOrBlank()) builder.header("Authorization", authHeader)
        client.newCall(builder.build()).execute().use { res ->
            return HikHttpResponse(res.code, res.body?.string().orEmpty(), res.headers.toMultimap())
        }
    }

    private fun clientWithTimeout(timeoutMs: Int): OkHttpClient {
        val ms = timeoutMs.coerceIn(500, 30_000).toLong()
        return baseClient.newBuilder().connectTimeout(ms, TimeUnit.MILLISECONDS)
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
    }
}

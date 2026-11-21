package com.edusing.chamcongtvbox.data.hikvision

import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.HikvisionWebhookConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Đọc, cài đặt và xoá cấu hình Webhook (`HTTP Listening` — `/ISAPI/Event/notification/httpHosts/1`)
 * cho mọi dòng máy Hikvision (tương thích cả firmware cũ lẫn đời mới `V3.9.50+` có `<SubscribeEvent>`).
 */
internal class HikvisionWebhookApi(
    private val client: HikvisionIsapiClient,
) {
    suspend fun fetchWebhookConfig(device: DeviceConfig): Result<HikvisionWebhookConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val res = client.request(device, "GET", "/ISAPI/Event/notification/httpHosts/1", timeoutMs = 5_000)
            require(res.isSuccess) { "Không đọc được cấu hình Webhook (HTTP ${res.statusCode})" }
            parseWebhookXml(res.body)
        }
    }

    suspend fun configureWebhook(device: DeviceConfig, config: HikvisionWebhookConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanHost = config.hostOrIp.trim()
            require(cleanHost.isNotEmpty() && cleanHost != "0.0.0.0") { "Vui lòng nhập IP hoặc tên miền máy chủ nhận Webhook" }
            require(config.port in 1..65535) { "Cổng Webhook không hợp lệ (1–65535)" }
            val cleanUrl = config.url.trim().ifBlank { "/hikvision/event" }.let { if (it.startsWith("/")) it else "/$it" }
            val proto = if (config.protocolType.equals("HTTPS", true)) "HTTPS" else "HTTP"
            val isIp = isIpv4(cleanHost)
            val addrType = if (isIp) "ipaddress" else "hostname"

            // Thử gửi XML đầy đủ có <SubscribeEvent> chuẩn firmware V3.5–V3.9+; nếu máy đời cũ từ chối thì fallback XML gọn
            val fullXml = buildWebhookXml(proto, addrType, cleanHost, config.port, cleanUrl, includeSubscribe = true)
            var res = client.request(device, "PUT", "/ISAPI/Event/notification/httpHosts/1", fullXml, HikvisionIsapiClient.XML_MEDIA, 6_000)
            if (!res.isSuccess && !res.isUnauthorized) {
                val simpleXml = buildWebhookXml(proto, addrType, cleanHost, config.port, cleanUrl, includeSubscribe = false)
                res = client.request(device, "PUT", "/ISAPI/Event/notification/httpHosts/1", simpleXml, HikvisionIsapiClient.XML_MEDIA, 6_000)
            }
            require(res.isSuccess) {
                val detail = HikvisionIsapiParser.extractErrorDetail(res.body)
                "Cài Webhook thất bại (HTTP ${res.statusCode}${if (detail.isNotBlank()) " • $detail" else ""})"
            }
            "Đã cài Webhook: ${proto.lowercase()}://$cleanHost:${config.port}$cleanUrl"
        }
    }

    suspend fun clearWebhook(device: DeviceConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val xml = buildWebhookXml("HTTP", "ipaddress", "0.0.0.0", 0, "", includeSubscribe = false)
            val res = client.request(device, "PUT", "/ISAPI/Event/notification/httpHosts/1", xml, HikvisionIsapiClient.XML_MEDIA, 5_000)
            require(res.isSuccess) { "Tắt Webhook lỗi HTTP ${res.statusCode}" }
            "Đã tắt Webhook trên máy ${device.displayName}"
        }
    }

    companion object {
        fun parseWebhookXml(xml: String): HikvisionWebhookConfig {
            val proto = tag(xml, "protocolType").ifBlank { "HTTP" }.uppercase()
            val addrType = tag(xml, "addressingFormatType").ifBlank { "ipaddress" }.lowercase()
            val ip = tag(xml, "ipAddress")
            val host = tag(xml, "hostName")
            val hostOrIp = if (addrType == "hostname" && host.isNotBlank()) host else ip.ifBlank { host }
            val port = tag(xml, "portNo").toIntOrNull() ?: 0
            val url = tag(xml, "url")
            val enabled = hostOrIp.isNotBlank() && hostOrIp != "0.0.0.0" && port > 0
            return HikvisionWebhookConfig(enabled, proto, addrType, hostOrIp, port, url)
        }

        fun buildWebhookXml(
            protocolType: String,
            addressingType: String,
            hostOrIp: String,
            port: Int,
            url: String,
            includeSubscribe: Boolean,
        ): String {
            val hostTag = if (addressingType == "hostname") {
                "<hostName>${escapeXml(hostOrIp)}</hostName>"
            } else {
                "<ipAddress>${escapeXml(hostOrIp)}</ipAddress>"
            }
            val subscribeBlock = if (includeSubscribe) {
                "<SubscribeEvent><heartbeat>30</heartbeat><eventMode>all</eventMode>" +
                    "<EventList><Event><type>AccessControllerEvent</type>" +
                    "<minorAlarm></minorAlarm><minorException></minorException>" +
                    "<minorOperation></minorOperation><minorEvent></minorEvent></Event></EventList></SubscribeEvent>"
            } else ""
            return """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<HttpHostNotification version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema">""" +
                """<id>1</id><url>${escapeXml(url)}</url><protocolType>$protocolType</protocolType>""" +
                """<parameterFormatType>JSON</parameterFormatType>""" +
                """<addressingFormatType>$addressingType</addressingFormatType>""" +
                """$hostTag<portNo>$port</portNo><httpAuthenticationMethod>none</httpAuthenticationMethod>""" +
                """$subscribeBlock</HttpHostNotification>"""
        }

        private fun isIpv4(s: String): Boolean {
            val p = s.split('.')
            return p.size == 4 && p.all { it.toIntOrNull() in 0..255 }
        }

        private fun escapeXml(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun tag(xml: String, name: String): String =
            Regex("<$name[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}

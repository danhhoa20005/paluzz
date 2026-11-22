package com.edusing.chamcongtvbox.data.hikvision

import com.edusing.chamcongtvbox.domain.model.HikvisionWebhookStats
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bộ theo dõi & định tuyến sự kiện Webhook (`HTTP Listening` — `POST /hikvision/event`) độc lập theo từng máy Hikvision:
 * hỗ trợ chạy đồng thời nhiều máy Hikvision, khớp cả IP kết nối lẫn `ipAddress` trong JSON và cấp tín hiệu thích ứng nhịp poll.
 */
internal class HikvisionWebhookTracker {
    private val lastWebhookAtByIp = ConcurrentHashMap<String, Long>()
    private val eventCount = AtomicInteger(0)
    private val heartbeatCount = AtomicInteger(0)
    @Volatile private var lastAtMs: Long = 0L
    @Volatile private var lastIp: String = ""
    @Volatile private var lastSummary: String = ""

    fun recordAndRoute(
        remoteIp: String,
        rawBody: String,
        nowMs: Long,
        deliver: (cleanIp: String, bodyIp: String, parsed: HikParsedEvent) -> Boolean,
    ): Boolean {
        val cleanIp = remoteIp.trim().removePrefix("::ffff:")
        val bodyIp = HikvisionIsapiParser.extractWebhookSourceIp(rawBody)
        val effectiveIp = bodyIp.ifBlank { cleanIp }
        val parsed = HikvisionIsapiParser.parseWebhookPayload(rawBody, effectiveIp, nowMs)
        val touch = {
            if (cleanIp.isNotBlank()) lastWebhookAtByIp[cleanIp] = nowMs
            if (bodyIp.isNotBlank()) lastWebhookAtByIp[bodyIp] = nowMs
            lastAtMs = nowMs
            lastIp = effectiveIp
        }
        if (parsed == null) {
            if (rawBody.contains("heartBeat", true) || rawBody.contains("EventNotificationAlert", true) || rawBody.contains("AccessControllerEvent", true)) {
                heartbeatCount.incrementAndGet()
                touch()
                lastSummary = if (rawBody.contains("heartBeat", true)) "Heartbeat OK" else "Gói sự kiện hệ thống"
            }
            return false
        }
        eventCount.incrementAndGet()
        touch()
        lastSummary = "#${parsed.employeeNo}${if (parsed.employeeName.isNotBlank()) " ${parsed.employeeName}" else ""}"
        return deliver(cleanIp, bodyIp, parsed)
    }

    fun isActiveForDevice(deviceIp: String, singleSession: Boolean, nowMs: Long, windowMs: Long): Boolean {
        val ts = lastWebhookAtByIp[deviceIp] ?: if (singleSession) lastAtMs else 0L
        return ts > 0L && (nowMs - ts) < windowMs
    }

    fun snapshot(): HikvisionWebhookStats = HikvisionWebhookStats(
        eventCount = eventCount.get(),
        heartbeatCount = heartbeatCount.get(),
        lastReceivedAtMs = lastAtMs,
        lastSourceIp = lastIp,
        lastSummary = lastSummary,
    )
}

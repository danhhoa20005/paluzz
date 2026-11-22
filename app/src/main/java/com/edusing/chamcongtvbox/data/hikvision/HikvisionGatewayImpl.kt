package com.edusing.chamcongtvbox.data.hikvision

import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice
import com.edusing.chamcongtvbox.domain.model.HikvisionWebhookStats
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cổng giao tiếp máy chấm công khuôn mặt Hikvision `DS-K1T320MX` (HTTP/HTTPS ISAPI + Webhook tức thì)
 * cài đặt trực tiếp giao diện [FkDeviceGateway] để tái sử dụng toàn bộ hệ thống 24/7, hàng đợi ngoại tuyến và tự đẩy nhân sự.
 */
internal class HikvisionGatewayImpl(
    private val client: HikvisionIsapiClient = HikvisionIsapiClient(),
    private val sadpScanner: HikvisionSadpScanner = HikvisionSadpScanner(),
    private val webhookTargetProvider: () -> Pair<String, Int>? = { null },
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val onDeviceSynced: (deviceKey: String, epochMs: Long) -> Unit = { _, _ -> },
) : FkDeviceGateway {

    private val enrollment = HikvisionEnrollmentApi(client)
    private val tcpSweeper = HikvisionLanTcpSweeper(client)
    private val handleSeq = AtomicInteger(HANDLE_OFFSET)
    private val sessions = ConcurrentHashMap<Int, HikSession>()
    private val webhookEventCount = AtomicInteger(0)
    private val webhookHeartbeatCount = AtomicInteger(0)
    @Volatile private var lastWebhookAtMs: Long = 0L
    @Volatile private var lastWebhookIp: String = ""
    @Volatile private var lastWebhookSummary: String = ""

    private class HikSession(
        val device: DeviceConfig,
        val info: FkConnectionInfo,
        @Volatile var baselineMs: Long,
        @Volatile var lastPollMs: Long,
        @Volatile var lastClearedAtMs: Long = 0L,
    ) {
        val userNames = ConcurrentHashMap<Int, String>()
        val stagedCards = ConcurrentHashMap<Int, String>()
        val webhookQueue = ConcurrentLinkedQueue<HikParsedEvent>()
        val seenKeys = LinkedHashSet<String>()
    }

    override suspend fun connect(
        machineNo: Int, ip: String, port: Int, timeoutMs: Int, netPassword: Int, license: Int, useUdp: Boolean,
    ): FkResult<FkConnectionInfo> = connect(DeviceConfig(ip = ip, port = port, machineNo = machineNo), timeoutMs)

    override suspend fun connect(device: DeviceConfig, timeoutMs: Int): FkResult<FkConnectionInfo> = withContext(Dispatchers.IO) {
        try {
            val (res, devInfo) = client.fetchDeviceInfo(device, timeoutMs)
            if (res.isUnauthorized) {
                return@withContext FkResult.fail(FkErrorCode.ERR_BAD_PARAM, "Sai tài khoản/mật khẩu Hikvision (401) — dừng thử để tránh khoá máy 30p")
            }
            if (!res.isSuccess || devInfo == null) {
                val detail = HikvisionIsapiParser.extractErrorDetail(res.body)
                return@withContext FkResult.fail(FkErrorCode.ERR_COMM, "Hikvision lỗi HTTP ${res.statusCode}${if (detail.isNotBlank()) " ($detail)" else ""}")
            }
            val handle = handleSeq.getAndIncrement()
            val now = clockMillis()
            val info = FkConnectionInfo(handle, device.ip, device.port, device.machineNo, FkFirmwareProfile.HIKVISION_ISAPI, "ISAPI/${devInfo.model}", 30, devInfo.serialNumber)
            sessions[handle] = HikSession(device, info, baselineMs = now, lastPollMs = now)
            runCatching { client.syncVietnamTime(device, now) }
            webhookTargetProvider()?.let { (lanIp, webPort) ->
                if (lanIp.isNotBlank() && webPort > 0) runCatching { client.registerWebhookHost(device, lanIp, webPort) }
            }
            FkResult.ok(info)
        } catch (e: IOException) {
            FkResult.fail(FkErrorCode.ERR_COMM, "Không nối được Hikvision ${device.ip}:${device.port}: ${e.message ?: e.javaClass.simpleName}")
        } catch (t: Throwable) {
            FkResult.fail(FkErrorCode.ERR_COMM, "Lỗi Hikvision: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    override suspend fun probeHikvisionHost(ip: String, port: Int): String? = withContext(Dispatchers.IO) { client.probeIsapiHost(ip, port) }

    override suspend fun discoverHikvisionSadp(timeoutMs: Int): List<HikvisionSadpDevice> = sadpScanner.discover(timeoutMs)

    override suspend fun sweepHikvisionSubnet(prefix: String, excludeIps: Set<String>, localIp: String?): List<HikvisionSadpDevice> =
        tcpSweeper.sweepSubnet(prefix, excludeIps, localIp)

    /** Nhận sự kiện quét mặt/thẻ & heartbeat đẩy trực tiếp từ Hikvision HTTP Listening (`POST /hikvision/event`). */
    fun onWebhookEvent(remoteIp: String, rawBody: String): Boolean {
        val now = clockMillis()
        val cleanIp = remoteIp.trim().removePrefix("::ffff:")
        val parsed = HikvisionIsapiParser.parseWebhookPayload(rawBody, cleanIp, now)
        if (parsed == null) {
            if (rawBody.contains("heartBeat", true) || rawBody.contains("EventNotificationAlert", true) || rawBody.contains("AccessControllerEvent", true)) {
                webhookHeartbeatCount.incrementAndGet()
                lastWebhookAtMs = now; lastWebhookIp = cleanIp
                lastWebhookSummary = if (rawBody.contains("heartBeat", true)) "Heartbeat OK" else "Gói sự kiện hệ thống"
            }
            return false
        }
        webhookEventCount.incrementAndGet()
        lastWebhookAtMs = now; lastWebhookIp = cleanIp
        lastWebhookSummary = "#${parsed.employeeNo}${if (parsed.employeeName.isNotBlank()) " ${parsed.employeeName}" else ""}"
        var delivered = false
        for (session in sessions.values) {
            if (session.device.ip.equals(cleanIp, ignoreCase = true) || sessions.size == 1) {
                session.webhookQueue.offer(parsed); delivered = true
            }
        }
        return delivered
    }

    /** Trả thống kê gói tin Webhook đã nhận trực tiếp trên TV Box kể từ khi khởi động dịch vụ. */
    fun webhookStats(): HikvisionWebhookStats = HikvisionWebhookStats(
        webhookEventCount.get(), webhookHeartbeatCount.get(), lastWebhookAtMs, lastWebhookIp, lastWebhookSummary,
    )

    override suspend fun disconnect(handle: Int) { sessions.remove(handle) }
    override suspend fun disconnectAll() { sessions.clear() }
    override suspend fun enableDevice(handle: Int, enable: Boolean): FkResult<Unit> =
        if (sessions.containsKey(handle)) FkResult.ok(Unit) else FkResult.fail(FkErrorCode.ERR_NOT_OPEN)
    override suspend fun emptyGeneralLogData(handle: Int): FkResult<Unit> = withContext(Dispatchers.IO) {
        val session = sessions[handle] ?: return@withContext FkResult.fail(FkErrorCode.ERR_NOT_OPEN)
        val now = clockMillis(); session.webhookQueue.clear()
        runCatching {
            val (res, recent) = client.fetchAccessEvents(session.device, now - SEED_WINDOW_MS, now + CLOCK_DRIFT_GRACE_MS, now, maxPages = 2)
            if (res.isSuccess) synchronized(session.seenKeys) { recent.forEach { recordSeenLocked(session, it.eventKey) } }
        }
        session.baselineMs = now; session.lastPollMs = now; session.lastClearedAtMs = now
        FkResult.ok(Unit)
    }

    override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean): FkResult<List<AttendanceLog>> = withContext(Dispatchers.IO) {
        val session = sessions[handle] ?: return@withContext FkResult.fail(FkErrorCode.ERR_NOT_OPEN)
        val now = clockMillis()
        if (now - session.lastClearedAtMs < 1_500L && session.webhookQueue.isEmpty()) return@withContext FkResult.fail(FkErrorCode.ERR_LOG_END)
        // Thích ứng nhịp quét khi Webhook đang đẩy trực tiếp ổn định (<90s): giãn poll nền lên ~28s giảm 3x tải pin/mạng
        val webhookActive = lastWebhookAtMs > 0L && (now - lastWebhookAtMs) < WEBHOOK_LIVE_WINDOW_MS &&
            (lastWebhookIp.equals(session.device.ip, true) || sessions.size == 1)
        if (webhookActive && session.webhookQueue.isEmpty() && (now - session.lastPollMs) < WEBHOOK_FALLBACK_POLL_MS) {
            return@withContext FkResult.fail(FkErrorCode.ERR_LOG_END)
        }
        try {
            val collected = mutableListOf<HikParsedEvent>()
            while (true) { collected.add(session.webhookQueue.poll() ?: break) }
            val startMs = maxOf(session.lastPollMs - POLL_OVERLAP_MS, session.baselineMs - CLOCK_DRIFT_GRACE_MS)
            val (res, polled) = client.fetchAccessEvents(session.device, startMs, now + CLOCK_DRIFT_GRACE_MS, now)
            if (res.isUnauthorized) return@withContext FkResult.fail(FkErrorCode.ERR_COMM, "Lỗi xác thực Hikvision (401)")
            if (!res.isSuccess) return@withContext FkResult.fail(FkErrorCode.ERR_COMM, "Lỗi đọc AcsEvent HTTP ${res.statusCode}")

            collected.addAll(polled); session.lastPollMs = now; onDeviceSynced(session.device.key, now)
            val freshLogs = collected.sortedBy { it.epochMs }.mapNotNull { ev ->
                if (!synchronized(session.seenKeys) { recordSeenLocked(session, ev.eventKey) }) return@mapNotNull null
                val enrollInt = ev.employeeNo.toIntOrNull() ?: (ev.employeeNo.hashCode() and 0x7FFFFFFF)
                if (ev.employeeName.isNotBlank()) session.userNames[enrollInt] = ev.employeeName
                AttendanceLog(enrollInt, ev.minor, 0, ev.year, ev.month, ev.day, ev.hour, ev.minute, ev.second, rawCardId = ev.employeeNo)
            }
            if (freshLogs.isEmpty()) FkResult.fail(FkErrorCode.ERR_LOG_END) else FkResult.ok(freshLogs)
        } catch (e: IOException) { FkResult.fail(FkErrorCode.ERR_COMM, "Mất kết nối Hikvision: ${e.message ?: e.javaClass.simpleName}") }
    }

    override suspend fun readAllUserIds(handle: Int): FkResult<List<FkUserCredential>> = withContext(Dispatchers.IO) {
        val session = sessions[handle] ?: return@withContext FkResult.fail(FkErrorCode.ERR_NOT_OPEN)
        try {
            val (res, users) = enrollment.fetchAllUsers(session.device, clockMillis())
            if (!res.isSuccess) return@withContext FkResult.fail(FkErrorCode.ERR_COMM, "Đọc nhân sự Hikvision lỗi HTTP ${res.statusCode}")
            val creds = users.mapNotNull { u ->
                val id = u.employeeNo.toIntOrNull() ?: return@mapNotNull null
                if (u.name.isNotBlank()) session.userNames[id] = u.name
                FkUserCredential(id, FkErrorCode.BACKUP_FACE, privilege = 0, enabled = true)
            }
            FkResult.ok(creds)
        } catch (e: IOException) {
            FkResult.fail(FkErrorCode.ERR_COMM, "Mất kết nối Hikvision: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override suspend fun putCardEnrollData(handle: Int, enrollNumber: Int, cardNumber: Int, privilege: Int): FkResult<Unit> {
        val session = sessions[handle] ?: return FkResult.fail(FkErrorCode.ERR_NOT_OPEN)
        session.stagedCards[enrollNumber] = cardNumber.toString()
        return FkResult.ok(Unit)
    }

    override suspend fun setUserName(handle: Int, enrollNumber: Int, name: String): FkResult<Unit> = withContext(Dispatchers.IO) {
        val session = sessions[handle] ?: return@withContext FkResult.fail(FkErrorCode.ERR_NOT_OPEN)
        val cardNo = session.stagedCards.remove(enrollNumber).orEmpty()
        try {
            val res = enrollment.upsertUserAndCard(session.device, enrollNumber.toString(), name.trim(), cardNo)
            if (res.isSuccess) {
                session.userNames[enrollNumber] = name.trim(); FkResult.ok(Unit)
            } else {
                val code = if (res.isUnauthorized) FkErrorCode.ERR_COMM else FkErrorCode.ERR_DEVICE_FAIL
                val detail = HikvisionIsapiParser.extractErrorDetail(res.body)
                FkResult.fail(code, "Nạp nhân sự Hikvision lỗi HTTP ${res.statusCode}${if (detail.isNotBlank()) " ($detail)" else ""}")
            }
        } catch (e: IOException) { FkResult.fail(FkErrorCode.ERR_COMM, "Mất kết nối Hikvision: ${e.message ?: e.javaClass.simpleName}") }
    }

    override suspend fun saveEnrollData(handle: Int): FkResult<Unit> = withContext(Dispatchers.IO) {
        val session = sessions[handle] ?: return@withContext FkResult.fail(FkErrorCode.ERR_NOT_OPEN)
        val remaining = session.stagedCards.entries.toList(); session.stagedCards.clear()
        for ((enrollNo, cardNo) in remaining) {
            try {
                val res = enrollment.upsertUserAndCard(session.device, enrollNo.toString(), "NV $enrollNo", cardNo)
                if (!res.isSuccess) return@withContext FkResult.fail(FkErrorCode.ERR_DEVICE_FAIL, "Lưu #$enrollNo lỗi HTTP ${res.statusCode}")
            } catch (e: IOException) { return@withContext FkResult.fail(FkErrorCode.ERR_COMM, "Mất kết nối Hikvision: ${e.message ?: e.javaClass.simpleName}") }
        }
        FkResult.ok(Unit)
    }

    override suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int): FkResult<Unit> = withContext(Dispatchers.IO) {
        val session = sessions[handle] ?: return@withContext FkResult.fail(FkErrorCode.ERR_NOT_OPEN)
        if (backupNumber != 0) return@withContext FkResult.fail(FkErrorCode.ERR_NO_MORE_DATA)
        try {
            val res = enrollment.deleteUser(session.device, enrollNumber.toString())
            if (res.isSuccess) { session.userNames.remove(enrollNumber); FkResult.ok(Unit) }
            else FkResult.fail(FkErrorCode.ERR_DEVICE_FAIL, "Xoá nhân sự Hikvision lỗi HTTP ${res.statusCode}")
        } catch (e: IOException) { FkResult.fail(FkErrorCode.ERR_COMM, "Mất kết nối Hikvision: ${e.message ?: e.javaClass.simpleName}") }
    }

    override suspend fun getUserName(handle: Int, enrollNumber: Int): FkResult<String> =
        sessions[handle]?.let { FkResult.ok(it.userNames[enrollNumber].orEmpty()) } ?: FkResult.fail(FkErrorCode.ERR_NOT_OPEN)

    override fun getConnectionInfo(handle: Int): FkConnectionInfo? = sessions[handle]?.info

    private fun recordSeenLocked(session: HikSession, key: String): Boolean {
        if (!session.seenKeys.add(key)) return false
        if (session.seenKeys.size > MAX_SEEN_KEYS) {
            val iter = session.seenKeys.iterator()
            repeat(MAX_SEEN_KEYS / 4) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        return true
    }

    companion object {
        const val HANDLE_OFFSET = 100_000
        private const val POLL_OVERLAP_MS = 20_000L; private const val CLOCK_DRIFT_GRACE_MS = 60_000L
        private const val SEED_WINDOW_MS = 120_000L; private const val MAX_SEEN_KEYS = 2_000
        private const val WEBHOOK_LIVE_WINDOW_MS = 90_000L; private const val WEBHOOK_FALLBACK_POLL_MS = 28_000L
    }
}

package com.edusing.chamcongtvbox.data.protocol

import android.util.Log
import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cài đặt thuần Kotlin của [FkDeviceGateway] thay thế hoàn toàn 4 DLL Windows
 * (`FKAttend.dll`, `FKViaDev.dll`, `LFWViaDev.dll`, `FKPwdEncDec.dll`).
 * Hỗ trợ cả TCP và UDP, tự nhận diện 18 dòng firmware và tự fallback sang LFW.
 */
class FkDeviceGatewayImpl(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fkTransportFactory: (ip: String, port: Int, timeoutMs: Int, useUdp: Boolean) -> FkTransport =
        { ip, port, timeout, udp -> openFkTransport(ip, port, timeout, udp) },
    private val lfwTransportFactory: (ip: String, port: Int, timeoutMs: Int, useUdp: Boolean) -> FkTransport =
        { ip, port, timeout, udp -> openLfwTransport(ip, port, timeout, udp) },
) : FkDeviceGateway {

    private val nextHandle = AtomicInteger(1)
    private val sessions = ConcurrentHashMap<Int, FkSession>()

    override suspend fun connect(
        machineNo: Int,
        ip: String,
        port: Int,
        timeoutMs: Int,
        netPassword: Int,
        license: Int,
        useUdp: Boolean,
    ): FkResult<FkConnectionInfo> = withContext(ioDispatcher) {
        // 1. Ưu tiên giao thức chuẩn FKViaDev
        val (fkRes, socketOpened) = tryConnectFkViaDev(machineNo, ip, port, timeoutMs, netPassword, license, useUdp)
        if (fkRes.isSuccess || fkRes.code == FkErrorCode.ERR_LICENSE) return@withContext fkRes

        // Nếu cổng TCP không mở được (từ chối/timeout) thì dừng ngay, không lặp LFW gây nhân đôi thời gian dò
        if (!useUdp && !socketOpened) return@withContext fkRes

        // 2. TCP đã mở (hoặc UDP) nhưng FKViaDev không khớp -> tự động thử Linux Firmware (LFWViaDev)
        val lfwRes = tryConnectLfwViaDev(machineNo, ip, port, timeoutMs, netPassword, license, useUdp)
        if (lfwRes.isSuccess || lfwRes.code == FkErrorCode.ERR_LICENSE) return@withContext lfwRes

        fkRes
    }

    private fun tryConnectFkViaDev(
        machineNo: Int,
        ip: String,
        port: Int,
        timeoutMs: Int,
        netPassword: Int,
        license: Int,
        useUdp: Boolean,
    ): Pair<FkResult<FkConnectionInfo>, Boolean> {
        val transport = runCatching { fkTransportFactory(ip, port, timeoutMs, useUdp) }.getOrElse {
            return FkResult.fail<FkConnectionInfo>(FkErrorCode.ERR_COMM, "Không kết nối được $ip:$port (${it.message})") to false
        }
        return try {
            // Chờ ngắn 60ms sau khi mở TCP giống Sleep(100) trong FKViaDev.dll để MCU ổn định phiên
            if (!useUdp) runCatching { Thread.sleep(POST_CONNECT_SETTLE_MS) }
            val cmdTimeout = timeoutMs.coerceAtLeast(MIN_HANDSHAKE_READ_MS)
            val authResp = transport.sendCommand(machineNo, FkCommands.CONNECT_AUTH, param = netPassword, timeoutMs = cmdTimeout)
            if (!authResp.isSuccess) {
                logWarn("FKViaDev CONNECT_AUTH thất bại tại $ip:$port (code=${authResp.resultCode})")
                transport.close()
                return FkResult.fail<FkConnectionInfo>(authResp.resultCode) to true
            }

            val infoResp = transport.readData(machineNo, FkCommands.GET_DEVICE_INFO, totalLength = 48, param = 0, timeoutMs = cmdTimeout)
            if (!infoResp.isSuccess || infoResp.data.size < 48) {
                logWarn("FKViaDev GET_DEVICE_INFO thất bại tại $ip:$port (code=${infoResp.resultCode}, len=${infoResp.data.size})")
                transport.close()
                return FkResult.fail<FkConnectionInfo>(if (infoResp.isSuccess) FkErrorCode.ERR_INVALID_DATA else infoResp.resultCode) to true
            }

            val devInfo = FkDeviceInfoParser.parseFk48(infoResp.data)
            if (!isLicenseCompatible(devInfo.license, license)) {
                logWarn("FKViaDev sai license tại $ip:$port (máy=${devInfo.license}, app=$license)")
                transport.close()
                return FkResult.fail<FkConnectionInfo>(FkErrorCode.ERR_LICENSE) to true
            }
            if (devInfo.prefersSmallBlockSize) transport.maxBlockSize = FkTransport.SMALL_BLOCK_SIZE

            var extJson: String? = null
            if (devInfo.hasExtendedInfo) {
                val extResp = transport.readData(machineNo, FkCommands.GET_DEVICE_INFO, totalLength = devInfo.extInfoLen, param = 1, timeoutMs = cmdTimeout)
                if (extResp.isSuccess && extResp.data.isNotEmpty()) {
                    extJson = BinaryUtils.decodeAsciiNullTerminated(extResp.data)
                }
            }

            registerSession(
                ip = ip,
                port = port,
                machineNo = machineNo,
                profile = FkDeviceInfoParser.resolveProfile(devInfo, extJson),
                transport = transport,
                legacyLogType = devInfo.logType,
            ) to true
        } catch (e: Exception) {
            logWarn("FKViaDev lỗi giao tiếp $ip:$port: ${e.message}")
            transport.close()
            FkResult.fail<FkConnectionInfo>(FkErrorCode.ERR_COMM, e.message) to true
        }
    }

    private fun tryConnectLfwViaDev(
        machineNo: Int,
        ip: String,
        port: Int,
        timeoutMs: Int,
        netPassword: Int,
        license: Int,
        useUdp: Boolean,
    ): FkResult<FkConnectionInfo> {
        val transport = runCatching { lfwTransportFactory(ip, port, timeoutMs, useUdp) }.getOrElse {
            return FkResult.fail(FkErrorCode.ERR_COMM, "LFW không kết nối được $ip:$port (${it.message})")
        }
        return try {
            if (!useUdp) runCatching { Thread.sleep(POST_CONNECT_SETTLE_MS) }
            val cmdTimeout = timeoutMs.coerceAtLeast(MIN_HANDSHAKE_READ_MS)
            val authResp = transport.sendCommand(machineNo, FkCommands.CONNECT_AUTH, param = netPassword, timeoutMs = cmdTimeout)
            if (!authResp.isSuccess) {
                transport.close()
                return FkResult.fail(authResp.resultCode)
            }

            val infoResp = transport.readData(machineNo, FkCommands.GET_DEVICE_INFO, totalLength = 54, param = 0, timeoutMs = cmdTimeout)
            if (!infoResp.isSuccess || infoResp.data.size < 54) {
                transport.close()
                return FkResult.fail(if (infoResp.isSuccess) FkErrorCode.ERR_INVALID_DATA else infoResp.resultCode)
            }

            val devInfo = FkDeviceInfoParser.parseLfw54(infoResp.data)
            if (!isLicenseCompatible(devInfo.license, license)) {
                transport.close()
                return FkResult.fail(FkErrorCode.ERR_LICENSE)
            }

            registerSession(ip, port, machineNo, FkFirmwareProfile.LFW, transport, devInfo.logType)
        } catch (e: Exception) {
            transport.close()
            FkResult.fail(FkErrorCode.ERR_COMM, e.message)
        }
    }

    /** Khớp `deviceLicense == 0` hoặc `configuredLicense == 0`, khớp trực tiếp, hoặc mã OEM chuẩn (`5912`, `1261`). */
    private fun isLicenseCompatible(deviceLicense: Int, configuredLicense: Int): Boolean =
        deviceLicense == 0 || configuredLicense == 0 || deviceLicense == configuredLicense ||
            deviceLicense == 5912 || deviceLicense == 1261

    private fun registerSession(
        ip: String,
        port: Int,
        machineNo: Int,
        profile: FkFirmwareProfile,
        transport: FkTransport,
        legacyLogType: Int,
    ): FkResult<FkConnectionInfo> {
        val handle = nextHandle.getAndIncrement()
        val connInfo = FkConnectionInfo(
            handle = handle,
            ip = ip,
            port = port,
            machineNo = machineNo,
            profile = profile,
            transportName = transport.transportName,
            maxBlockSize = transport.maxBlockSize,
        )
        sessions[handle] = FkSession(connInfo, transport, legacyLogType)
        return FkResult.ok(connInfo)
    }

    override suspend fun disconnect(handle: Int) = withContext(ioDispatcher) {
        sessions.remove(handle)?.close()
        Unit
    }

    override suspend fun disconnectAll() = withContext(ioDispatcher) {
        val all = sessions.values.toList()
        sessions.clear()
        all.forEach { runCatching { it.close() } }
    }

    override suspend fun enableDevice(handle: Int, enable: Boolean) = withSession(handle) { it.enableDevice(enable) }
    override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean) = withSession(handle) { it.loadGeneralLogData(readMark) }
    override suspend fun emptyGeneralLogData(handle: Int) = withSession(handle) { it.emptyGeneralLogData() }
    override suspend fun readAllUserIds(handle: Int) = withSession(handle) { it.readAllUserIds() }
    override suspend fun putCardEnrollData(handle: Int, enrollNumber: Int, cardNumber: Int, privilege: Int) =
        withSession(handle) { it.putCardEnrollData(enrollNumber, cardNumber, privilege) }
    override suspend fun saveEnrollData(handle: Int) = withSession(handle) { it.saveEnrollData() }
    override suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int) =
        withSession(handle) { it.deleteEnrollData(enrollNumber, backupNumber) }
    override suspend fun getUserName(handle: Int, enrollNumber: Int) = withSession(handle) { it.getUserName(enrollNumber) }
    override suspend fun setUserName(handle: Int, enrollNumber: Int, name: String) = withSession(handle) { it.setUserName(enrollNumber, name) }
    override fun getConnectionInfo(handle: Int): FkConnectionInfo? = sessions[handle]?.info

    private suspend inline fun <T> withSession(
        handle: Int,
        crossinline block: suspend (FkSession) -> FkResult<T>,
    ): FkResult<T> = withContext(ioDispatcher) {
        val session = sessions[handle] ?: return@withContext FkResult.fail(FkErrorCode.ERR_NOT_OPEN)
        runCatching { block(session) }.getOrElse { e ->
            logWarn("Lỗi thực thi lệnh FK (handle=$handle): ${e.message}")
            FkResult.fail(FkErrorCode.ERR_COMM, e.message)
        }
    }

    private fun logWarn(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    companion object {
        private const val TAG = "FkDeviceGateway"
        private const val POST_CONNECT_SETTLE_MS = 60L
        private const val MIN_HANDSHAKE_READ_MS = 2_000

        /**
         * Khớp ngưỡng tối thiểu `2000ms` của `FKViaDev.dll` (`0x7d0`) và đảm bảo vượt được nhịp gửi lại TCP SYN (1000ms)
         * của các máy chấm công nhúng (như dòng `192.168.1.224`).
         */
        private fun openFkTransport(ip: String, port: Int, timeoutMs: Int, useUdp: Boolean): FkViaDevTransport {
            val fastProbe = timeoutMs <= 2_000
            val connectMs = if (fastProbe) timeoutMs.coerceIn(1_400, 2_000) else timeoutMs.coerceIn(2_000, 5_000)
            val readMs = timeoutMs.coerceAtLeast(MIN_HANDSHAKE_READ_MS)
            return FkViaDevTransport.connect(ip, port, readMs, useUdp, connectMs, maxRetries = if (fastProbe) 1 else 3)
        }

        private fun openLfwTransport(ip: String, port: Int, timeoutMs: Int, useUdp: Boolean): LfwViaDevTransport {
            val fastProbe = timeoutMs <= 2_000
            val connectMs = if (fastProbe) timeoutMs.coerceIn(1_400, 2_000) else timeoutMs.coerceIn(2_000, 5_000)
            val readMs = timeoutMs.coerceAtLeast(MIN_HANDSHAKE_READ_MS)
            return LfwViaDevTransport.connect(ip, port, readMs, useUdp, connectMs, maxRetries = if (fastProbe) 1 else 3)
        }
    }
}

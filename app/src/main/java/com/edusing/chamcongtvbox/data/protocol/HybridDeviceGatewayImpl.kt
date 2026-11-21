package com.edusing.chamcongtvbox.data.protocol

import com.edusing.chamcongtvbox.data.hikvision.HikvisionGatewayImpl
import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential

/**
 * Cổng đa giao thức (Hybrid Gateway) giúp một TV Box kết nối đồng thời cả máy chấm công FK (cổng 5005)
 * lẫn máy nhận diện khuôn mặt Hikvision `DS-K1T320MX` (ISAPI cổng 80/443).
 */
internal class HybridDeviceGatewayImpl(
    private val fkGateway: FkDeviceGateway,
    private val hikGateway: FkDeviceGateway,
) : FkDeviceGateway {

    private fun forHandle(handle: Int): FkDeviceGateway =
        if (handle >= HikvisionGatewayImpl.HANDLE_OFFSET) hikGateway else fkGateway

    override suspend fun connect(
        machineNo: Int,
        ip: String,
        port: Int,
        timeoutMs: Int,
        netPassword: Int,
        license: Int,
        useUdp: Boolean,
    ): FkResult<FkConnectionInfo> = fkGateway.connect(machineNo, ip, port, timeoutMs, netPassword, license, useUdp)

    override suspend fun connect(device: DeviceConfig, timeoutMs: Int): FkResult<FkConnectionInfo> =
        if (device.isHikvision) hikGateway.connect(device, timeoutMs) else fkGateway.connect(device, timeoutMs)

    override suspend fun probeHikvisionHost(ip: String, port: Int): String? =
        hikGateway.probeHikvisionHost(ip, port)

    override suspend fun discoverHikvisionSadp(timeoutMs: Int): List<HikvisionSadpDevice> =
        hikGateway.discoverHikvisionSadp(timeoutMs)

    override suspend fun disconnect(handle: Int) = forHandle(handle).disconnect(handle)

    override suspend fun disconnectAll() {
        fkGateway.disconnectAll()
        hikGateway.disconnectAll()
    }

    override suspend fun enableDevice(handle: Int, enable: Boolean): FkResult<Unit> =
        forHandle(handle).enableDevice(handle, enable)

    override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean): FkResult<List<AttendanceLog>> =
        forHandle(handle).loadGeneralLogData(handle, readMark)

    override suspend fun emptyGeneralLogData(handle: Int): FkResult<Unit> =
        forHandle(handle).emptyGeneralLogData(handle)

    override suspend fun readAllUserIds(handle: Int): FkResult<List<FkUserCredential>> =
        forHandle(handle).readAllUserIds(handle)

    override suspend fun putCardEnrollData(
        handle: Int,
        enrollNumber: Int,
        cardNumber: Int,
        privilege: Int,
    ): FkResult<Unit> = forHandle(handle).putCardEnrollData(handle, enrollNumber, cardNumber, privilege)

    override suspend fun saveEnrollData(handle: Int): FkResult<Unit> =
        forHandle(handle).saveEnrollData(handle)

    override suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int): FkResult<Unit> =
        forHandle(handle).deleteEnrollData(handle, enrollNumber, backupNumber)

    override suspend fun getUserName(handle: Int, enrollNumber: Int): FkResult<String> =
        forHandle(handle).getUserName(handle, enrollNumber)

    override suspend fun setUserName(handle: Int, enrollNumber: Int, name: String): FkResult<Unit> =
        forHandle(handle).setUserName(handle, enrollNumber, name)

    override fun getConnectionInfo(handle: Int): FkConnectionInfo? =
        forHandle(handle).getConnectionInfo(handle)
}

package com.edusing.chamcongtvbox

import com.edusing.chamcongtvbox.data.hikvision.HikvisionSadpScanner
import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice
import com.edusing.chamcongtvbox.domain.model.IpSuggestion
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.domain.model.SubnetInfo
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential
import com.edusing.chamcongtvbox.domain.repository.LanDiscoveryRepository
import com.edusing.chamcongtvbox.domain.usecase.DiscoverDevicesUseCase
import com.edusing.chamcongtvbox.domain.usecase.ScanLanDevicesUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử quét đa hướng Hikvision SADP tìm mọi dòng máy chấm công & tự định vị lại khi đổi IP DHCP. */
class HikvisionSadpTest {

    @Test
    fun sadpParser_parsesMultipleRealHikvisionModelsAndClassifiesCameras() {
        val xml320Mfwx = """<?xml version="1.0" encoding="UTF-8"?>
            <ProbeMatch>
            <DeviceDescription>DS-K1T320MFWX</DeviceDescription>
            <DeviceSN>DS-K1T320MFWX20241227V030520ENGM5583841</DeviceSN>
            <HttpPort>80</HttpPort>
            <MAC>88-de-39-59-c5-56</MAC>
            <IPv4Address>192.168.1.22</IPv4Address>
            <DHCP>false</DHCP>
            <SoftwareVersion>V3.5.20build 241227</SoftwareVersion>
            <Activated>true</Activated>
            </ProbeMatch>"""
        val d1 = HikvisionSadpScanner.parseProbeMatchXml(xml320Mfwx)
        assertNotNull(d1)
        assertEquals("DS-K1T320MFWX", d1!!.model)
        assertEquals("192.168.1.22", d1.ip)
        assertEquals(80, d1.httpPort)
        assertFalse(d1.dhcpEnabled)
        assertTrue(d1.isAccessTerminal)

        val xml671CustomPort = """<ProbeMatch><DeviceDescription>DS-K1T671M-3XF</DeviceDescription>
            <DeviceSN>SN671</DeviceSN><HttpPort>8081</HttpPort><IPv4Address>192.168.1.45</IPv4Address>
            <DHCP>true</DHCP><Activated>true</Activated></ProbeMatch>"""
        val d2 = HikvisionSadpScanner.parseProbeMatchXml(xml671CustomPort)!!
        assertEquals("DS-K1T671M-3XF", d2.model)
        assertEquals(8081, d2.httpPort)
        assertTrue(d2.dhcpEnabled)
        assertTrue(d2.isAccessTerminal)

        // Camera IP (DS-2CD...) không bị lẫn là máy chấm công
        val xmlCamera = """<ProbeMatch><DeviceDescription>DS-2CD2143G2-I</DeviceDescription>
            <DeviceSN>CAM01</DeviceSN><HttpPort>80</HttpPort><IPv4Address>192.168.1.99</IPv4Address></ProbeMatch>"""
        val cam = HikvisionSadpScanner.parseProbeMatchXml(xmlCamera)!!
        assertFalse(cam.isAccessTerminal)
    }

    @Test
    fun scanLanDevices_mergesAllSadpHikvisionModelsWithFkTerminals() = runTest {
        val sadpList = listOf(
            HikvisionSadpDevice("192.168.1.8", 80, "DS-K1T320MX", "SN1", "", "V3.9.50", dhcpEnabled = true, isAccessTerminal = true),
            HikvisionSadpDevice("192.168.1.22", 8081, "DS-K1T320MFWX", "SN2", "", "V3.5.20", dhcpEnabled = false, isAccessTerminal = true),
            HikvisionSadpDevice("192.168.1.99", 80, "DS-2CD2143G2", "CAM", "", "V5.7", isAccessTerminal = false),
        )
        val fakeGw = FakeSadpGateway(sadpList, fkIps = setOf("192.168.1.201"))
        val fakeLan = FakeLanRepo(listOf(LanHostInfo("192.168.1.201", 5005), LanHostInfo("192.168.1.50", 5005)))
        val useCase = ScanLanDevicesUseCase(fakeLan, fakeGw)

        val result = useCase(configuredIps = setOf("192.168.1.8"))
        val hikResults = result.filter { it.isHikvisionTerminal }
        val fkResults = result.filter { it.isFkTerminal }
        assertEquals(2, hikResults.size) // Camera DS-2CD tự bị lọc ra, giữ đủ cả 320MX và 320MFWX!
        assertEquals("DS-K1T320MX", hikResults.first { it.ip == "192.168.1.8" }.modelHint)
        assertEquals(8081, hikResults.first { it.ip == "192.168.1.22" }.port)
        assertEquals(1, fkResults.size)
        assertEquals("192.168.1.201", fkResults.first().ip)
    }

    @Test
    fun discoverDevices_relocatesDhcpHikvisionViaSadpWithZeroPasswordAttempts() = runTest {
        val fakeGw = FakeSadpGateway(
            sadpDevices = listOf(
                HikvisionSadpDevice("192.168.1.33", 80, "DS-K1T343MWX", "SN-MOVE-99", "", "V3.8", dhcpEnabled = true, isAccessTerminal = true),
            ),
            fkIps = emptySet(),
        )
        val useCase = DiscoverDevicesUseCase(FakeLanRepo(emptyList()), fakeGw)
        val savedOld = DeviceConfig(
            ip = "192.168.1.8", port = 80, protocol = DeviceProtocol.HIKVISION_ISAPI,
            username = "admin", accessPassword = "pw", serialNumber = "SN-MOVE-99",
        )
        val report = useCase(listOf(savedOld), 5005, 1, 0, 5912, false)
        assertEquals(1, report.relocatedDevices.size)
        assertEquals("192.168.1.33", report.relocatedDevices.first().ip)
        assertEquals(1, fakeGw.connectAttempts) // Chỉ thử IP cũ 1 lần; định vị IP mới 100% qua SADP không thử mật khẩu!
    }

    private class FakeSadpGateway(
        private val sadpDevices: List<HikvisionSadpDevice>,
        private val fkIps: Set<String>,
    ) : FkDeviceGateway {
        var connectAttempts = 0
        override suspend fun discoverHikvisionSadp(timeoutMs: Int) = sadpDevices
        override suspend fun connect(device: DeviceConfig, timeoutMs: Int): FkResult<FkConnectionInfo> {
            connectAttempts++
            return FkResult.fail(FkErrorCode.ERR_COMM)
        }
        override suspend fun connect(machineNo: Int, ip: String, port: Int, timeoutMs: Int, netPassword: Int, license: Int, useUdp: Boolean): FkResult<FkConnectionInfo> =
            if (ip in fkIps) FkResult.ok(FkConnectionInfo(1, ip, port, machineNo, FkFirmwareProfile.CIF13, "TCP", 30))
            else FkResult.fail(FkErrorCode.ERR_COMM)
        override suspend fun disconnect(handle: Int) = Unit
        override suspend fun disconnectAll() = Unit
        override suspend fun enableDevice(handle: Int, enable: Boolean) = FkResult.ok(Unit)
        override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean): FkResult<List<AttendanceLog>> = FkResult.fail(FkErrorCode.ERR_LOG_END)
        override suspend fun emptyGeneralLogData(handle: Int) = FkResult.ok(Unit)
        override suspend fun readAllUserIds(handle: Int) = FkResult.ok(emptyList<FkUserCredential>())
        override suspend fun putCardEnrollData(handle: Int, enrollNumber: Int, cardNumber: Int, privilege: Int) = FkResult.ok(Unit)
        override suspend fun saveEnrollData(handle: Int) = FkResult.ok(Unit)
        override suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int) = FkResult.ok(Unit)
        override suspend fun getUserName(handle: Int, enrollNumber: Int) = FkResult.ok("")
        override suspend fun setUserName(handle: Int, enrollNumber: Int, name: String) = FkResult.ok(Unit)
        override fun getConnectionInfo(handle: Int): FkConnectionInfo? = null
    }

    private class FakeLanRepo(private val hosts: List<LanHostInfo>) : LanDiscoveryRepository {
        override suspend fun detectActiveSubnet(): SubnetInfo? = null
        override suspend fun detectActiveSubnets(preferredIps: Set<String>) = emptyList<SubnetInfo>()
        override suspend fun detectRoutableExtraPrefixes(localSubnets: List<SubnetInfo>, knownIps: Set<String>) = emptyList<String>()
        override suspend fun scanLanHosts(configuredIps: Set<String>, port: Int, customPrefix: String?, onProgress: (Int, Int) -> Unit) = hosts
        override suspend fun scanSubnetByPing(prefix: String, localIp: String?, configuredIps: Set<String>, port: Int, onProgress: (Int, Int) -> Unit) = emptyList<LanHostInfo>()
        override suspend fun suggestFreeIp(port: Int, excludeIps: Set<String>): IpSuggestion? = null
    }
}

package com.edusing.chamcongtvbox.domain

import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kiểm thử nghiệp vụ dò tìm máy chấm công [DiscoverDevicesUseCase]:
 * 1. Máy đáp Ping -> tìm thấy ngay bằng xác nhận song song, không cần quét dự phòng.
 * 2. Máy chặn Ping (router vẫn đáp Ping) -> tự chạy bắt tay FK trực tiếp để tìm ra máy.
 * 3. Điện thoại ở Wi-Fi phụ `192.168.2.x`, máy chấm ở mạng LAN chính `192.168.1.x` -> tự dò thông tuyến ra máy.
 */
class DiscoverDevicesUseCaseTest {

    @Test
    fun findsTerminalThatRespondsToPingWithoutDirectFallback() = runTest {
        val repo = FakeLanRepo(
            localSubnets = listOf(SubnetInfo("192.168.1.28", "192.168.1", "255.255.255.0", "192.168.1.1", "eth0")),
            pingByPrefix = mapOf("192.168.1" to listOf("192.168.1.1", "192.168.1.201")),
        )
        val gateway = FakeGateway(terminalIps = setOf("192.168.1.201"))
        val useCase = DiscoverDevicesUseCase(repo, gateway)

        val report = useCase(emptyList(), 5005, 1, 0, 5912, false)
        assertEquals(listOf("192.168.1.201"), report.foundIps)
        assertFalse(report.directHandshakeUsed)
        assertEquals(listOf("192.168.1"), report.scannedPrefixes)
    }

    @Test
    fun findsTerminalThatBlocksPingViaSafeDirectHandshakeFallback() = runTest {
        val repo = FakeLanRepo(
            localSubnets = listOf(SubnetInfo("192.168.1.28", "192.168.1", "255.255.255.0", "192.168.1.1", "wlan0")),
            pingByPrefix = mapOf("192.168.1" to listOf("192.168.1.1")), // Chỉ router đáp ping, máy .224 chặn ping
        )
        val gateway = FakeGateway(terminalIps = setOf("192.168.1.224"))
        val useCase = DiscoverDevicesUseCase(repo, gateway)

        val report = useCase(emptyList(), 5005, 1, 0, 5912, false)
        assertEquals(listOf("192.168.1.224"), report.foundIps)
        assertTrue(report.directHandshakeUsed)
    }

    @Test
    fun findsTerminalOnRoutedUpstreamLanWhenPhoneIsOnSecondaryWifi() = runTest {
        val repo = FakeLanRepo(
            localSubnets = listOf(SubnetInfo("192.168.2.53", "192.168.2", "255.255.255.0", "192.168.2.1", "wlan0")),
            extraPrefixes = listOf("192.168.1"),
            pingByPrefix = mapOf(
                "192.168.2" to listOf("192.168.2.253"),
                "192.168.1" to listOf("192.168.1.1", "192.168.1.201"),
            ),
        )
        val gateway = FakeGateway(terminalIps = setOf("192.168.1.201"))
        val useCase = DiscoverDevicesUseCase(repo, gateway)

        val report = useCase(emptyList(), 5005, 1, 0, 5912, false)
        assertEquals(listOf("192.168.1.201"), report.foundIps)
        assertEquals(listOf("192.168.2", "192.168.1"), report.scannedPrefixes)
        assertFalse(report.directHandshakeUsed)
    }

    private class FakeLanRepo(
        private val localSubnets: List<SubnetInfo>,
        private val extraPrefixes: List<String> = emptyList(),
        private val pingByPrefix: Map<String, List<String>> = emptyMap(),
    ) : LanDiscoveryRepository {
        override suspend fun detectActiveSubnets(preferredIps: Set<String>) = localSubnets
        override suspend fun detectActiveSubnet() = localSubnets.firstOrNull()
        override suspend fun detectRoutableExtraPrefixes(localSubnets: List<SubnetInfo>, knownIps: Set<String>) = extraPrefixes
        override suspend fun scanSubnetByPing(
            prefix: String,
            localIp: String?,
            configuredIps: Set<String>,
            port: Int,
            onProgress: (Int, Int) -> Unit,
        ): List<LanHostInfo> {
            onProgress(254, 254)
            return pingByPrefix[prefix].orEmpty().map { LanHostInfo(ip = it, port = port) }
        }

        override suspend fun scanLanHosts(
            configuredIps: Set<String>,
            port: Int,
            customPrefix: String?,
            onProgress: (Int, Int) -> Unit,
        ) = emptyList<LanHostInfo>()

        override suspend fun suggestFreeIp(port: Int, excludeIps: Set<String>): IpSuggestion? = null
    }

    private class FakeGateway(private val terminalIps: Set<String>) : FkDeviceGateway {
        private var handleSeq = 1
        override suspend fun connect(
            machineNo: Int,
            ip: String,
            port: Int,
            timeoutMs: Int,
            netPassword: Int,
            license: Int,
            useUdp: Boolean,
        ): FkResult<FkConnectionInfo> {
            return if (ip in terminalIps) {
                FkResult.ok(FkConnectionInfo(handleSeq++, ip, port, machineNo, FkFirmwareProfile.CIF11_1, "FKViaDev-TCP", 1024))
            } else {
                FkResult.fail(FkErrorCode.ERR_COMM)
            }
        }

        override suspend fun disconnect(handle: Int) = Unit
        override suspend fun disconnectAll() = Unit
        override suspend fun enableDevice(handle: Int, enable: Boolean) = FkResult.ok(Unit)
        override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean) = FkResult.ok(emptyList<AttendanceLog>())
        override suspend fun emptyGeneralLogData(handle: Int) = FkResult.ok(Unit)
        override suspend fun readAllUserIds(handle: Int) = FkResult.ok(emptyList<FkUserCredential>())
        override suspend fun putCardEnrollData(handle: Int, enrollNumber: Int, cardNumber: Int, privilege: Int) = FkResult.ok(Unit)
        override suspend fun saveEnrollData(handle: Int) = FkResult.ok(Unit)
        override suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int) = FkResult.ok(Unit)
        override suspend fun getUserName(handle: Int, enrollNumber: Int) = FkResult.ok("")
        override suspend fun setUserName(handle: Int, enrollNumber: Int, name: String) = FkResult.ok(Unit)
        override fun getConnectionInfo(handle: Int): FkConnectionInfo? = null
    }
}

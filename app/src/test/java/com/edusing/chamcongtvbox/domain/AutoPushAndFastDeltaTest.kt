package com.edusing.chamcongtvbox.domain

import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.ClassGroup
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem
import com.edusing.chamcongtvbox.domain.model.LoginResult
import com.edusing.chamcongtvbox.domain.model.Member
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import com.edusing.chamcongtvbox.domain.model.ServerCheckInResult
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkFirmwareProfile
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.AuthRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.MemberRepository
import com.edusing.chamcongtvbox.domain.repository.PendingCheckInStorage
import com.edusing.chamcongtvbox.domain.repository.StoredPendingCheckIn
import com.edusing.chamcongtvbox.domain.usecase.AbbreviateNamesUseCase
import com.edusing.chamcongtvbox.domain.usecase.AutoPushMembersUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectedDeviceHandle
import com.edusing.chamcongtvbox.domain.usecase.PendingCheckInQueue
import com.edusing.chamcongtvbox.domain.usecase.PushMembersToDevicesUseCase
import com.edusing.chamcongtvbox.domain.usecase.ValidateCredentialsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm thử tối ưu Delta Fast-Path, AutoPushMembersUseCase và độ bền hàng đợi ngoại tuyến qua cúp điện. */
class AutoPushAndFastDeltaTest {

    private val dev = DeviceConfig(ip = "192.168.1.224", port = 5005, machineNo = 1)

    @Test
    fun deltaFastPath_whenAllMembersAlreadyOnDevice_zeroLocksZeroGetNameZeroFlashWrites() = runBlocking {
        val gw = RecordingGateway(existingIds = listOf(1001, 1002, 1003))
        val uc = PushMembersToDevicesUseCase(gw, AbbreviateNamesUseCase())
        val members = listOf(
            Member("1001", "1001", "Nguyen Van An", 1),
            Member("1002", "1002", "Tran Thi Binh", 1),
            Member("1003", "1003", "Le Van Cuong", 1),
        )

        val summary = uc(listOf(ConnectedDeviceHandle(dev, 1)), members, skipExistingOnDevice = true)

        assertEquals(0, summary.succeeded)
        assertEquals(3, summary.skippedExisting)
        assertEquals(0, summary.failed)
        assertEquals("Không có người mới -> không được đọc tên hàng loạt", 0, gw.getUserNameCalls)
        assertEquals("Không có người mới -> không được khoá màn hình máy quét", 0, gw.enableCalls.size)
        assertEquals("Không có người mới -> không được ghi mòn Flash", 0, gw.saveEnrollCalls)
    }

    @Test
    fun autoPushUseCase_refreshesExpiredTokenAndPushesOnlyNewMembers() = runBlocking {
        val gw = RecordingGateway(existingIds = listOf(1001, 1002))
        val cfgRepo = SimpleConfigRepo(
            AppConfig(
                apiDomain = "https://api.paluzz.com",
                accountId = "11366",
                username = "admin",
                password = "secret",
                token = "expired",
            )
        )
        val authRepo = object : AuthRepository {
            override suspend fun login(apiDomain: String, username: String, password: String) =
                LoginResult(success = true, accountId = "11366", orgName = "Cty A", token = "fresh")
            override suspend fun testServerConnection(apiDomain: String) = "OK"
        }
        val memberRepo = object : MemberRepository {
            override suspend fun getClasses(apiDomain: String, bearerToken: String) = Result.success(emptyList<ClassGroup>())
            override suspend fun getStudents(apiDomain: String, bearerToken: String): Result<List<Member>> {
                if (bearerToken != "fresh") return Result.failure(IllegalStateException("HTTP 401"))
                return Result.success(
                    listOf(
                        Member("1001", "1001", "Nguyen Van An", 1),
                        Member("1002", "1002", "Tran Thi Binh", 1),
                        Member("1003", "1003", "Pham Minh Duc", 1),
                    )
                )
            }
        }
        val pushUc = PushMembersToDevicesUseCase(gw, AbbreviateNamesUseCase())
        val autoUc = AutoPushMembersUseCase(cfgRepo, memberRepo, ValidateCredentialsUseCase(authRepo, cfgRepo), pushUc)

        val out = autoUc(listOf(ConnectedDeviceHandle(dev, 1)))

        assertTrue(out.success)
        assertEquals(1, out.summary?.succeeded)
        assertEquals(2, out.summary?.skippedExisting)
        assertEquals(listOf(1003), gw.pushedCardIds)
        assertEquals(1, gw.saveEnrollCalls)
    }

    @Test
    fun pendingCheckInQueue_persistsAcrossSimulatedPowerOutageAndFlushesOnRestart() = runBlocking {
        var persisted: List<StoredPendingCheckIn> = emptyList()
        val storage = object : PendingCheckInStorage {
            override fun loadAll(): List<StoredPendingCheckIn> = persisted
            override fun saveAll(items: List<StoredPendingCheckIn>) { persisted = items }
        }
        val now = 1_760_000_000_000L

        // Phiên 1: Mất mạng WAN -> lưu 1 lượt chấm công rồi giả lập mất điện TV Box
        val queueBeforeCrash = PendingCheckInQueue(clockMillis = { now }, storage = storage)
        assertTrue(queueBeforeCrash.enqueue("555888", dev, "Nguyen An", 60_000L))
        assertEquals(1, persisted.size)

        // Phiên 2: TV Box khởi động lại -> tự khôi phục hàng đợi từ bộ nhớ flash và xả bù khi có mạng
        val uploaded = mutableListOf<String>()
        val attRepo = object : AttendanceRepository {
            override suspend fun submitCheckInOut(apiDomain: String, accountId: String, studentCardId: String, device: DeviceConfig): ServerCheckInResult {
                uploaded.add(studentCardId)
                return ServerCheckInResult(true, "08:00:00", now, "OK")
            }
            override suspend fun sendHeartbeat(apiDomain: String, accountId: String, primaryMachineNo: Int, running: Boolean, devices: List<DeviceHeartbeatItem>) = Unit
            override fun lastKnownServerTimeMillis(): Long? = now
        }
        val queueAfterReboot = PendingCheckInQueue(clockMillis = { now + 30_000L }, storage = storage)
        assertEquals(1, queueAfterReboot.size)
        val flushOut = queueAfterReboot.flush("https://api.paluzz.com", "11366", attRepo) {}
        assertEquals(listOf("555888"), uploaded)
        assertEquals(0, queueAfterReboot.size)
        assertEquals(0, persisted.size)
        assertEquals(1, flushOut.events.size)
    }

    private class RecordingGateway(private val existingIds: List<Int>) : FkDeviceGateway {
        var getUserNameCalls = 0
        var saveEnrollCalls = 0
        val enableCalls = mutableListOf<Boolean>()
        val pushedCardIds = mutableListOf<Int>()

        override suspend fun connect(machineNo: Int, ip: String, port: Int, timeoutMs: Int, netPassword: Int, license: Int, useUdp: Boolean) =
            FkResult.ok(FkConnectionInfo(1, ip, port, machineNo, FkFirmwareProfile.CIF11_1, "FK", 1024))
        override suspend fun disconnect(handle: Int) = Unit
        override suspend fun disconnectAll() = Unit
        override suspend fun enableDevice(handle: Int, enable: Boolean): FkResult<Unit> { enableCalls.add(enable); return FkResult.ok(Unit) }
        override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean) = FkResult.ok<List<AttendanceLog>>(emptyList())
        override suspend fun emptyGeneralLogData(handle: Int) = FkResult.ok(Unit)
        override suspend fun readAllUserIds(handle: Int) = FkResult.ok(existingIds.map { FkUserCredential(it, 11, 0, true) })
        override suspend fun putCardEnrollData(handle: Int, enrollNumber: Int, cardNumber: Int, privilege: Int): FkResult<Unit> {
            pushedCardIds.add(enrollNumber); return FkResult.ok(Unit)
        }
        override suspend fun saveEnrollData(handle: Int): FkResult<Unit> { saveEnrollCalls++; return FkResult.ok(Unit) }
        override suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int) = FkResult.ok(Unit)
        override suspend fun getUserName(handle: Int, enrollNumber: Int): FkResult<String> { getUserNameCalls++; return FkResult.ok("U$enrollNumber") }
        override suspend fun setUserName(handle: Int, enrollNumber: Int, name: String) = FkResult.ok(Unit)
        override fun getConnectionInfo(handle: Int): FkConnectionInfo? = null
    }

    private class SimpleConfigRepo(initial: AppConfig) : ConfigRepository {
        private val flow = MutableStateFlow(initial)
        override val configFlow: StateFlow<AppConfig> = flow
        override fun getConfig(): AppConfig = flow.value
        override fun getBranding(): BrandingConfig = BrandingConfig.forMode(flow.value.mode)
        override suspend fun switchMode(targetMode: AppMode) = flow.value
        override suspend fun saveConfig(config: AppConfig) { flow.value = config }
        override suspend fun upsertSavedAccount(account: SavedAccount) = Unit
        override suspend fun removeSavedAccount(username: String) = Unit
        override suspend fun updateDevices(devices: List<DeviceConfig>) { flow.value = flow.value.copy(devices = devices) }
        override suspend fun setAutoStartRunning(running: Boolean) { flow.value = flow.value.copy(autoStartRunning = running) }
        override suspend fun clearActiveCredentials() = Unit
    }
}

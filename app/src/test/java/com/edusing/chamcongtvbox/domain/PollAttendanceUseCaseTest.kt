package com.edusing.chamcongtvbox.domain

import com.edusing.chamcongtvbox.domain.model.AttendanceLog
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.model.ServerCheckInResult
import com.edusing.chamcongtvbox.domain.protocol.FkConnectionInfo
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.FkErrorCode
import com.edusing.chamcongtvbox.domain.protocol.FkResult
import com.edusing.chamcongtvbox.domain.protocol.FkUserCredential
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.usecase.PollAttendanceUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollAttendanceUseCaseTest {

    private val testDevice = DeviceConfig(ip = "192.168.1.201", name = "Cổng chính")

    @Test
    fun dedup_suppressesRepeatedTapOnlyAfterServerSuccess_andAllowsRetryAfterFailure() = runTest {
        var nowMs = 1_000_000L
        var nextServerResult = ServerCheckInResult(
            success = false,
            serverTimeFormatted = "08:00:00 19/11/2026",
            serverTimeMillis = nowMs,
            message = "Timeout mạng",
        )
        val submittedCards = mutableListOf<String>()
        val fakeGateway = FakeGateway(
            logsToReturn = { listOf(AttendanceLog(12345, 3, 0, 2026, 11, 19, 8, 0, 0)) }
        )
        val fakeRepo = FakeAttendanceRepo(
            onSubmit = { card ->
                submittedCards.add(card)
                nextServerResult
            }
        )

        val useCase = PollAttendanceUseCase(fakeGateway, fakeRepo, clockMillis = { nowMs })

        // 1. First tap fails on server -> MUST NOT record dedup timestamp
        val r1 = useCase(1, testDevice, "https://api.diemdanhhs.com", "acc1", dedupSeconds = 60)
        assertEquals(1, submittedCards.size)
        assertEquals(EventSeverity.ERROR, r1.events.single().severity)

        // 2. Retry 5 seconds later succeeds -> allowed because previous attempt failed!
        nowMs += 5_000L
        nextServerResult = ServerCheckInResult(
            success = true,
            serverTimeFormatted = "08:00:05 19/11/2026",
            serverTimeMillis = nowMs,
            message = "Điểm danh thành công",
        )
        val r2 = useCase(1, testDevice, "https://api.diemdanhhs.com", "acc1", dedupSeconds = 60)
        assertEquals(2, submittedCards.size)
        assertEquals(EventSeverity.SUCCESS, r2.events.single().severity)

        // 3. Another tap 10 seconds later -> suppressed by 60s dedup window!
        nowMs += 10_000L
        val r3 = useCase(1, testDevice, "https://api.diemdanhhs.com", "acc1", dedupSeconds = 60)
        assertEquals(2, submittedCards.size) // NOT sent again
        assertEquals(EventSeverity.WARNING, r3.events.single().severity)

        // 4. After 61 seconds -> allowed again
        nowMs += 61_000L
        val r4 = useCase(1, testDevice, "https://api.diemdanhhs.com", "acc1", dedupSeconds = 60)
        assertEquals(3, submittedCards.size)
        assertEquals(EventSeverity.SUCCESS, r4.events.single().severity)
    }

    @Test
    fun commErrorMinus3_setsNeedsReconnect() = runTest {
        val fakeGateway = FakeGateway(
            enableResult = FkResult.fail(FkErrorCode.ERR_COMM),
            logsToReturn = { emptyList() },
        )
        val fakeRepo = FakeAttendanceRepo { ServerCheckInResult(true, "", 0L, "OK") }
        val useCase = PollAttendanceUseCase(fakeGateway, fakeRepo)

        val outcome = useCase(1, testDevice, "https://api.diemdanhhs.com", "acc1", 60)
        assertTrue(outcome.needsReconnect)
        assertFalse(outcome.authError)
    }

    @Test
    fun server401_setsAuthErrorFlag() = runTest {
        val fakeGateway = FakeGateway(
            logsToReturn = { listOf(AttendanceLog(999, 3, 0, 2026, 11, 19, 8, 5, 0)) }
        )
        val fakeRepo = FakeAttendanceRepo {
            ServerCheckInResult(
                success = false,
                serverTimeFormatted = "08:05:00 19/11/2026",
                serverTimeMillis = 0L,
                message = "HTTP 401 Unauthorized",
                isAuthError = true,
            )
        }
        val useCase = PollAttendanceUseCase(fakeGateway, fakeRepo)

        val outcome = useCase(1, testDevice, "https://api.diemdanhhs.com", "acc1", 60)
        assertTrue(outcome.authError)
        assertFalse(outcome.needsReconnect)
    }

    @Test
    fun livePoll_submitsCheckInBeforeGetUserName_andDoesNotEmptyFlash() = runTest {
        val callOrder = mutableListOf<String>()
        val fakeGateway = FakeGateway(
            logsToReturn = { listOf(AttendanceLog(54321, 3, 0, 2026, 11, 20, 8, 15, 0)) },
            onEmptyCalled = { callOrder.add("empty") },
            onGetUserNameCalled = { callOrder.add("getUserName") },
        )
        val fakeRepo = FakeAttendanceRepo {
            callOrder.add("submitCheckInOut")
            ServerCheckInResult(true, "08:15:00 20/11/2026", 1L, "OK")
        }
        val useCase = PollAttendanceUseCase(fakeGateway, fakeRepo)

        val outcome = useCase(1, testDevice, "https://api.paluzz.com", "11366", 60)
        assertEquals(1, outcome.events.size)
        assertEquals(listOf("submitCheckInOut", "getUserName"), callOrder)
    }

    private class FakeAttendanceRepo(
        private val onSubmit: (String) -> ServerCheckInResult,
    ) : AttendanceRepository {
        override suspend fun submitCheckInOut(
            apiDomain: String,
            accountId: String,
            studentCardId: String,
            device: DeviceConfig,
        ): ServerCheckInResult = onSubmit(studentCardId)

        override suspend fun sendHeartbeat(
            apiDomain: String,
            accountId: String,
            primaryMachineNo: Int,
            running: Boolean,
            devices: List<DeviceHeartbeatItem>,
        ) = Unit

        override fun lastKnownServerTimeMillis(): Long? = null
    }

    private class FakeGateway(
        private val enableResult: FkResult<Unit> = FkResult.ok(Unit),
        private val logsToReturn: () -> List<AttendanceLog>,
        private val onEmptyCalled: () -> Unit = {},
        private val onGetUserNameCalled: () -> Unit = {},
    ) : FkDeviceGateway {
        override suspend fun connect(machineNo: Int, ip: String, port: Int, timeoutMs: Int, netPassword: Int, license: Int, useUdp: Boolean) =
            FkResult.fail<FkConnectionInfo>(FkErrorCode.ERR_COMM)
        override suspend fun disconnect(handle: Int) = Unit
        override suspend fun disconnectAll() = Unit
        override suspend fun enableDevice(handle: Int, enable: Boolean) = enableResult
        override suspend fun loadGeneralLogData(handle: Int, readMark: Boolean): FkResult<List<AttendanceLog>> {
            val data = logsToReturn()
            return if (data.isEmpty()) FkResult.fail(FkErrorCode.ERR_LOG_END) else FkResult.ok(data)
        }
        override suspend fun emptyGeneralLogData(handle: Int): FkResult<Unit> {
            onEmptyCalled()
            return FkResult.ok(Unit)
        }
        override suspend fun readAllUserIds(handle: Int) = FkResult.ok<List<FkUserCredential>>(emptyList())
        override suspend fun putCardEnrollData(handle: Int, enrollNumber: Int, cardNumber: Int, privilege: Int) = FkResult.ok(Unit)
        override suspend fun saveEnrollData(handle: Int) = FkResult.ok(Unit)
        override suspend fun deleteEnrollData(handle: Int, enrollNumber: Int, backupNumber: Int) = FkResult.ok(Unit)
        override suspend fun getUserName(handle: Int, enrollNumber: Int): FkResult<String> {
            onGetUserNameCalled()
            return FkResult.ok("NGUYEN")
        }
        override suspend fun setUserName(handle: Int, enrollNumber: Int, name: String) = FkResult.ok(Unit)
        override fun getConnectionInfo(handle: Int): FkConnectionInfo? = null
    }
}

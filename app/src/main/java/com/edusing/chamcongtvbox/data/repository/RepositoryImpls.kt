package com.edusing.chamcongtvbox.data.repository

import com.edusing.chamcongtvbox.data.local.SecureConfigStore
import com.edusing.chamcongtvbox.data.local.WordDocExporter
import com.edusing.chamcongtvbox.data.network.LanScannerImpl
import com.edusing.chamcongtvbox.data.remote.AttendanceApiClient
import com.edusing.chamcongtvbox.domain.model.AppConfig
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.ClassGroup
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceHeartbeatItem
import com.edusing.chamcongtvbox.domain.model.IpSuggestion
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.domain.model.LoginResult
import com.edusing.chamcongtvbox.domain.model.Member
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import com.edusing.chamcongtvbox.domain.model.ServerCheckInResult
import com.edusing.chamcongtvbox.domain.model.SubnetInfo
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.AuthRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.ExportRepository
import com.edusing.chamcongtvbox.domain.repository.LanDiscoveryRepository
import com.edusing.chamcongtvbox.domain.repository.MemberRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cài đặt kho lưu trữ cấu hình an toàn đa luồng kèm luồng [StateFlow] cho giao diện. */
class ConfigRepositoryImpl(
    private val store: SecureConfigStore,
) : ConfigRepository {

    private val mutex = Mutex()
    private val _configFlow = MutableStateFlow(store.load())
    override val configFlow: StateFlow<AppConfig> = _configFlow.asStateFlow()

    override fun getConfig(): AppConfig = _configFlow.value
    override fun getBranding(): BrandingConfig = store.brandingFor(_configFlow.value.mode)

    override suspend fun switchMode(targetMode: AppMode): AppConfig = mutex.withLock {
        val prev = _configFlow.value
        if (prev.mode == targetMode) return@withLock prev
        store.save(prev)
        val loaded = store.load(targetMode)
        // Nếu chế độ mới chưa khai báo máy nào, giữ sẵn danh sách máy LAN đã dò để khỏi phải nhập lại
        val next = if (loaded.devices.isEmpty() && prev.devices.isNotEmpty()) {
            loaded.copy(devices = prev.devices)
        } else {
            loaded
        }
        store.save(next)
        _configFlow.value = next
        next
    }

    override suspend fun saveConfig(config: AppConfig) = mutex.withLock {
        store.save(config)
        _configFlow.value = config
    }

    override suspend fun upsertSavedAccount(account: SavedAccount) = mutex.withLock {
        val current = _configFlow.value
        val updatedList = listOf(account) + current.savedAccounts
            .filterNot { it.username.equals(account.username, ignoreCase = true) }
            .take(9)
        val updated = current.copy(savedAccounts = updatedList)
        store.save(updated)
        _configFlow.value = updated
    }

    override suspend fun removeSavedAccount(username: String) = mutex.withLock {
        val current = _configFlow.value
        val updated = current.copy(
            savedAccounts = current.savedAccounts.filterNot { it.username.equals(username, ignoreCase = true) }
        )
        store.save(updated)
        _configFlow.value = updated
    }

    override suspend fun updateDevices(devices: List<DeviceConfig>) = mutex.withLock {
        val updated = _configFlow.value.copy(devices = devices)
        store.save(updated)
        _configFlow.value = updated
    }

    override suspend fun setAutoStartRunning(running: Boolean) = mutex.withLock {
        val updated = _configFlow.value.copy(autoStartRunning = running)
        store.save(updated)
        _configFlow.value = updated
    }

    override suspend fun clearActiveCredentials() = mutex.withLock {
        val updated = _configFlow.value.copy(
            accountId = "",
            orgName = "",
            username = "",
            password = "",
            token = "",
            autoStartRunning = false,
        )
        store.save(updated)
        _configFlow.value = updated
    }
}

class AuthRepositoryImpl(
    private val apiClient: AttendanceApiClient,
) : AuthRepository {
    override suspend fun login(apiDomain: String, username: String, password: String): LoginResult =
        apiClient.login(apiDomain, username, password)

    override suspend fun testServerConnection(apiDomain: String): String =
        apiClient.testConnection(apiDomain)
}

class AttendanceRepositoryImpl(
    private val apiClient: AttendanceApiClient,
) : AttendanceRepository {
    override suspend fun submitCheckInOut(
        apiDomain: String,
        accountId: String,
        studentCardId: String,
        device: DeviceConfig,
    ): ServerCheckInResult = apiClient.checkInOut(apiDomain, accountId, studentCardId, device)

    override suspend fun sendHeartbeat(
        apiDomain: String,
        accountId: String,
        primaryMachineNo: Int,
        running: Boolean,
        devices: List<DeviceHeartbeatItem>,
    ) {
        apiClient.sendHeartbeat(apiDomain, accountId, primaryMachineNo, running, devices)
    }

    override fun lastKnownServerTimeMillis(): Long? = apiClient.lastKnownServerTimeMillis()

    override fun resetNetworkConnections() = apiClient.evictStaleConnections()
}

class MemberRepositoryImpl(
    private val apiClient: AttendanceApiClient,
) : MemberRepository {
    override suspend fun getClasses(apiDomain: String, bearerToken: String): Result<List<ClassGroup>> =
        apiClient.getClasses(apiDomain, bearerToken)

    override suspend fun getStudents(apiDomain: String, bearerToken: String): Result<List<Member>> =
        apiClient.getStudents(apiDomain, bearerToken)
}

class LanDiscoveryRepositoryImpl(
    private val scanner: LanScannerImpl,
) : LanDiscoveryRepository {
    override suspend fun detectActiveSubnets(preferredIps: Set<String>): List<SubnetInfo> =
        scanner.detectActiveSubnets(preferredIps)

    override suspend fun detectActiveSubnet(): SubnetInfo? = scanner.detectActiveSubnet()

    override suspend fun detectRoutableExtraPrefixes(
        localSubnets: List<SubnetInfo>,
        knownIps: Set<String>,
    ): List<String> = scanner.detectRoutableExtraPrefixes(localSubnets, knownIps)

    override suspend fun scanSubnetByPing(
        prefix: String,
        localIp: String?,
        configuredIps: Set<String>,
        port: Int,
        onProgress: (scanned: Int, total: Int) -> Unit,
    ): List<LanHostInfo> = scanner.scanSubnetByPing(prefix, localIp, configuredIps, port, onProgress)

    override suspend fun scanLanHosts(
        configuredIps: Set<String>,
        port: Int,
        customPrefix: String?,
        onProgress: (scanned: Int, total: Int) -> Unit,
    ): List<LanHostInfo> = scanner.scanLanHosts(configuredIps, port, customPrefix, onProgress)

    override suspend fun suggestFreeIp(port: Int, excludeIps: Set<String>): IpSuggestion? =
        scanner.suggestFreeIp(port, excludeIps)
}

class ExportRepositoryImpl(
    private val exporter: WordDocExporter,
) : ExportRepository {
    override suspend fun exportMembersToWordDoc(
        title: String,
        orgName: String,
        groupLabel: String,
        personLabel: String,
        classes: List<ClassGroup>,
        members: List<Member>,
        abbreviatedNames: Map<Int, String>,
    ): Result<String> = exporter.export(title, orgName, groupLabel, personLabel, classes, members, abbreviatedNames)
}

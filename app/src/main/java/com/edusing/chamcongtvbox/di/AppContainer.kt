package com.edusing.chamcongtvbox.di

import android.content.Context
import com.edusing.chamcongtvbox.BuildConfig
import com.edusing.chamcongtvbox.data.hikvision.HikvisionControlApi
import com.edusing.chamcongtvbox.data.hikvision.HikvisionGatewayImpl
import com.edusing.chamcongtvbox.data.hikvision.HikvisionIsapiClient
import com.edusing.chamcongtvbox.data.hikvision.HikvisionSadpScanner
import com.edusing.chamcongtvbox.data.local.AlertConfigStore
import com.edusing.chamcongtvbox.data.local.HikvisionSyncWatermarkStore
import com.edusing.chamcongtvbox.data.local.PendingCheckInStoreImpl
import com.edusing.chamcongtvbox.data.local.SecureConfigStore
import com.edusing.chamcongtvbox.data.local.WordDocExporter
import com.edusing.chamcongtvbox.data.network.InternetProbeImpl
import com.edusing.chamcongtvbox.data.network.LanScannerImpl
import com.edusing.chamcongtvbox.data.protocol.FkDeviceGatewayImpl
import com.edusing.chamcongtvbox.data.protocol.HybridDeviceGatewayImpl
import com.edusing.chamcongtvbox.data.remote.AttendanceApiClient
import com.edusing.chamcongtvbox.data.repository.AppUpdateRepositoryImpl
import com.edusing.chamcongtvbox.data.repository.AttendanceRepositoryImpl
import com.edusing.chamcongtvbox.data.repository.AuthRepositoryImpl
import com.edusing.chamcongtvbox.data.repository.ConfigRepositoryImpl
import com.edusing.chamcongtvbox.data.repository.ExportRepositoryImpl
import com.edusing.chamcongtvbox.data.repository.LanDiscoveryRepositoryImpl
import com.edusing.chamcongtvbox.data.repository.MemberRepositoryImpl
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.domain.model.HikvisionWebhookStats
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.protocol.HikvisionControlGateway
import com.edusing.chamcongtvbox.domain.repository.AppUpdateRepository
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.AuthRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.ExportRepository
import com.edusing.chamcongtvbox.domain.repository.LanDiscoveryRepository
import com.edusing.chamcongtvbox.domain.repository.MemberRepository
import com.edusing.chamcongtvbox.domain.usecase.AbbreviateNamesUseCase
import com.edusing.chamcongtvbox.domain.usecase.AutoPushMembersUseCase
import com.edusing.chamcongtvbox.domain.usecase.BackfillHikvisionEventsUseCase
import com.edusing.chamcongtvbox.domain.usecase.ClearDeviceLogsUseCase
import com.edusing.chamcongtvbox.domain.usecase.ConnectDeviceUseCase
import com.edusing.chamcongtvbox.domain.usecase.DeleteDeviceUsersUseCase
import com.edusing.chamcongtvbox.domain.usecase.DiscoverDevicesUseCase
import com.edusing.chamcongtvbox.domain.usecase.ExportMemberListDocUseCase
import com.edusing.chamcongtvbox.domain.usecase.LoadMembersTreeUseCase
import com.edusing.chamcongtvbox.domain.usecase.LoginUseCase
import com.edusing.chamcongtvbox.domain.usecase.LogoutUseCase
import com.edusing.chamcongtvbox.domain.usecase.PollAttendanceUseCase
import com.edusing.chamcongtvbox.domain.usecase.PushMembersToDevicesUseCase
import com.edusing.chamcongtvbox.domain.usecase.ReadDeviceUsersUseCase
import com.edusing.chamcongtvbox.domain.usecase.ScanLanDevicesUseCase
import com.edusing.chamcongtvbox.domain.usecase.SendHeartbeatUseCase
import com.edusing.chamcongtvbox.domain.usecase.SuggestFreeIpUseCase
import com.edusing.chamcongtvbox.domain.usecase.ValidateCredentialsUseCase
import com.edusing.chamcongtvbox.service.AlertNotifier
import com.edusing.chamcongtvbox.service.AppUpdateCoordinator
import com.edusing.chamcongtvbox.service.AttendanceEngine
import com.edusing.chamcongtvbox.service.AttendanceVoiceAnnouncer
import com.edusing.chamcongtvbox.service.web.LocalWebConsoleServer
import kotlinx.coroutines.launch

/**
 * Gốc khởi tạo phụ thuộc (Composition Root) theo kiến trúc Clean Architecture.
 * Hợp nhất 3 chế độ (Điểm danh HS / PALUZZ Cơ quan / Đón trả HS) với cấu hình động theo chế độ đang bật.
 */
class AppContainer(appContext: Context) {

    private val defaultMode: AppMode = AppMode.fromCode(BuildConfig.DEFAULT_MODE)

    // Nguồn dữ liệu cấu hình động theo chế độ (tách riêng phiên & tài khoản cho từng chế độ)
    internal val configStore = SecureConfigStore(appContext, defaultMode = defaultMode)
    val configRepository: ConfigRepository = ConfigRepositoryImpl(configStore)
    internal val alertNotifier = AlertNotifier(AlertConfigStore(appContext))

    /** Trả về cấu hình thương hiệu hiện hành (tự động đổi ngay khi chuyển chế độ). */
    val brandingConfig: BrandingConfig get() = configRepository.getBranding()

    private val apiClient = AttendanceApiClient(
        brandingProvider = { configRepository.getBranding() },
        appVersion = BuildConfig.VERSION_NAME,
    )
    private val lanScanner = LanScannerImpl()
    val internetProbe = InternetProbeImpl(appContext)
    private val pendingCheckInStorage = PendingCheckInStoreImpl(appContext)
    private val wordDocExporter = WordDocExporter(appContext)
    private val hikWatermarkStore = HikvisionSyncWatermarkStore(appContext)
    internal val voiceAnnouncer = AttendanceVoiceAnnouncer(appContext)

    @Volatile
    var activeWebConsolePort: Int = 8080

    private val hikIsapiClient = HikvisionIsapiClient()
    val hikvisionControlGateway: HikvisionControlGateway = HikvisionControlApi(hikIsapiClient)

    private val hikvisionGateway = HikvisionGatewayImpl(
        client = hikIsapiClient,
        sadpScanner = HikvisionSadpScanner(appContext),
        webhookTargetProvider = {
            val lanIp = LocalWebConsoleServer.detectLanIpv4()
            if (lanIp.isNotBlank() && activeWebConsolePort > 0) lanIp to activeWebConsolePort else null
        },
        onDeviceSynced = hikWatermarkStore::recordSynced,
    )

    // Cổng đa giao thức: hỗ trợ đồng thời máy FK (cổng 5005 TCP/UDP) và Hikvision DS-K1T320MX (ISAPI cổng 80/443)
    val deviceGateway: FkDeviceGateway = HybridDeviceGatewayImpl(
        fkGateway = FkDeviceGatewayImpl(),
        hikGateway = hikvisionGateway,
    )

    fun handleHikvisionWebhook(remoteIp: String, rawBody: String): Boolean {
        if (rawBody.contains("antiDismantle", true) || rawBody.contains("tamper", true)) {
            val org = configRepository.getConfig().orgName
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                alertNotifier.notifyAlert(org, "Cảnh báo CẬY VỎ / XÂM NHẬP máy chấm công!", "Máy Hikvision ($remoteIp) vừa phát tín hiệu chống tháo gỡ!", remoteIp, "CRITICAL")
            }
        }
        val ok = hikvisionGateway.onWebhookEvent(remoteIp, rawBody)
        if (ok) attendanceEngine.wakePollLoopNow()
        return ok
    }

    fun hikvisionWebhookStats(): HikvisionWebhookStats = hikvisionGateway.webhookStats()

    /** Tự đồng bộ bù lượt chấm công Hikvision bị gián đoạn trong lúc mất điện / rớt mạng (>= 90 giây). */
    suspend fun autoBackfillHikvisionIfOutage(device: com.edusing.chamcongtvbox.domain.model.DeviceConfig) {
        if (!device.isHikvision) return
        val cfg = configRepository.getConfig()
        if (!cfg.isLoggedIn) return
        val now = attendanceEngine.currentServerTimeMillis()
        val (sinceMs, untilMs) = hikWatermarkStore.computeRecoveryWindow(device.key, now) ?: run {
            hikWatermarkStore.recordSynced(device.key, now); return
        }
        val domain = configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)
        attendanceEngine.withDeviceIoLock {
            backfillHikvisionEventsUseCase(device, domain, cfg.accountId, sinceMs, untilMs, cfg.dedupSeconds)
        }.onSuccess { s ->
            hikWatermarkStore.recordSynced(device.key, now)
            if (s.totalFound > 0) {
                attendanceEngine.appendLog(device.displayName, "-", "Tự đồng bộ bù sau mất kết nối: ${s.message}", com.edusing.chamcongtvbox.domain.model.EventSeverity.SUCCESS)
            }
        }
    }

    // Kho dữ liệu (Repositories)
    val authRepository: AuthRepository = AuthRepositoryImpl(apiClient)
    val attendanceRepository: AttendanceRepository = AttendanceRepositoryImpl(apiClient)
    val memberRepository: MemberRepository = MemberRepositoryImpl(apiClient)
    val lanDiscoveryRepository: LanDiscoveryRepository = LanDiscoveryRepositoryImpl(lanScanner)
    val exportRepository: ExportRepository = ExportRepositoryImpl(wordDocExporter)
    val appUpdateRepository: AppUpdateRepository = AppUpdateRepositoryImpl(appContext)

    // Nghiệp vụ (UseCases) — đọc thương hiệu động qua configRepository.getBranding()
    val abbreviateNamesUseCase = AbbreviateNamesUseCase()
    val loginUseCase = LoginUseCase(authRepository, configRepository)
    val logoutUseCase = LogoutUseCase(configRepository, deviceGateway)
    val validateCredentialsUseCase = ValidateCredentialsUseCase(authRepository, configRepository)
    val clearDeviceLogsUseCase = ClearDeviceLogsUseCase(deviceGateway)
    val connectDeviceUseCase = ConnectDeviceUseCase(deviceGateway, clearDeviceLogsUseCase)
    val pollAttendanceUseCase = PollAttendanceUseCase(deviceGateway, attendanceRepository, pendingStorage = pendingCheckInStorage)
    val sendHeartbeatUseCase = SendHeartbeatUseCase(attendanceRepository)
    val scanLanDevicesUseCase = ScanLanDevicesUseCase(lanDiscoveryRepository, deviceGateway)
    val suggestFreeIpUseCase = SuggestFreeIpUseCase(lanDiscoveryRepository)
    val discoverDevicesUseCase = DiscoverDevicesUseCase(lanDiscoveryRepository, deviceGateway, hikvisionControlGateway)
    val loadMembersTreeUseCase = LoadMembersTreeUseCase(
        configRepository = configRepository,
        memberRepository = memberRepository,
        validateCredentialsUseCase = validateCredentialsUseCase,
        deviceGateway = deviceGateway,
        hikControl = hikvisionControlGateway,
    )
    val pushMembersToDevicesUseCase = PushMembersToDevicesUseCase(deviceGateway, abbreviateNamesUseCase, hikvisionControlGateway)
    val autoPushMembersUseCase = AutoPushMembersUseCase(
        configRepository = configRepository,
        memberRepository = memberRepository,
        validateCredentialsUseCase = validateCredentialsUseCase,
        pushMembersToDevicesUseCase = pushMembersToDevicesUseCase,
    )
    val readDeviceUsersUseCase = ReadDeviceUsersUseCase(
        deviceGateway = deviceGateway,
        memberRepository = memberRepository,
        configRepository = configRepository,
        hikControl = hikvisionControlGateway,
    )
    val deleteDeviceUsersUseCase = DeleteDeviceUsersUseCase(deviceGateway, hikvisionControlGateway)
    val backfillHikvisionEventsUseCase = BackfillHikvisionEventsUseCase(hikvisionControlGateway, attendanceRepository)
    val exportMemberListDocUseCase = ExportMemberListDocUseCase(
        exportRepository = exportRepository,
        abbreviateNamesUseCase = abbreviateNamesUseCase,
        configRepository = configRepository,
    )

    // Bộ điều phối vận hành chấm công tự động 24/7 + giám sát Internet chủ động
    val attendanceEngine = AttendanceEngine(
        configRepository = configRepository,
        attendanceRepository = attendanceRepository,
        deviceGateway = deviceGateway,
        connectDeviceUseCase = connectDeviceUseCase,
        clearDeviceLogsUseCase = clearDeviceLogsUseCase,
        pollAttendanceUseCase = pollAttendanceUseCase,
        discoverDevicesUseCase = discoverDevicesUseCase,
        sendHeartbeatUseCase = sendHeartbeatUseCase,
        validateCredentialsUseCase = validateCredentialsUseCase,
        internetProbe = internetProbe,
        autoPushMembersUseCase = autoPushMembersUseCase,
        onNewDaySync = { devs ->
            devs.filter { it.isHikvision }.forEach { runCatching { hikvisionControlGateway.syncVietnamTime(it) } }
        },
    )

    // Bộ tự động kiểm tra & cập nhật OTA từ GitHub Releases (danhhoa20005/paluzz)
    val appUpdateCoordinator = AppUpdateCoordinator(
        configRepository = configRepository,
        updateRepository = appUpdateRepository,
        isInternetOnline = { attendanceEngine.state.value.isInternetOnline },
        onLogEvent = { title, msg, sev -> attendanceEngine.appendLog(title, "-", msg, sev) },
    )
}

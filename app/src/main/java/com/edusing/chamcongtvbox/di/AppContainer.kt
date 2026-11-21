package com.edusing.chamcongtvbox.di

import android.content.Context
import com.edusing.chamcongtvbox.BuildConfig
import com.edusing.chamcongtvbox.data.local.PendingCheckInStoreImpl
import com.edusing.chamcongtvbox.data.local.SecureConfigStore
import com.edusing.chamcongtvbox.data.local.WordDocExporter
import com.edusing.chamcongtvbox.data.network.InternetProbeImpl
import com.edusing.chamcongtvbox.data.network.LanScannerImpl
import com.edusing.chamcongtvbox.data.protocol.FkDeviceGatewayImpl
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
import com.edusing.chamcongtvbox.domain.protocol.FkDeviceGateway
import com.edusing.chamcongtvbox.domain.repository.AppUpdateRepository
import com.edusing.chamcongtvbox.domain.repository.AttendanceRepository
import com.edusing.chamcongtvbox.domain.repository.AuthRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.ExportRepository
import com.edusing.chamcongtvbox.domain.repository.LanDiscoveryRepository
import com.edusing.chamcongtvbox.domain.repository.MemberRepository
import com.edusing.chamcongtvbox.domain.usecase.AbbreviateNamesUseCase
import com.edusing.chamcongtvbox.domain.usecase.AutoPushMembersUseCase
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
import com.edusing.chamcongtvbox.service.AppUpdateCoordinator
import com.edusing.chamcongtvbox.service.AttendanceEngine

/**
 * Gốc khởi tạo phụ thuộc (Composition Root) theo kiến trúc Clean Architecture.
 * Hợp nhất 3 chế độ (Điểm danh HS / PALUZZ Cơ quan / Đón trả HS) với cấu hình động theo chế độ đang bật.
 */
class AppContainer(appContext: Context) {

    private val defaultMode: AppMode = AppMode.fromCode(BuildConfig.DEFAULT_MODE)

    // Nguồn dữ liệu cấu hình động theo chế độ (tách riêng phiên & tài khoản cho từng chế độ)
    private val configStore = SecureConfigStore(appContext, defaultMode = defaultMode)
    val configRepository: ConfigRepository = ConfigRepositoryImpl(configStore)

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

    // Cổng giao tiếp máy chấm công thuần Kotlin (TCP + UDP, thay thế 100% DLL Windows)
    val deviceGateway: FkDeviceGateway = FkDeviceGatewayImpl()

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
    val discoverDevicesUseCase = DiscoverDevicesUseCase(lanDiscoveryRepository, deviceGateway)
    val loadMembersTreeUseCase = LoadMembersTreeUseCase(
        configRepository = configRepository,
        memberRepository = memberRepository,
        validateCredentialsUseCase = validateCredentialsUseCase,
        deviceGateway = deviceGateway,
    )
    val pushMembersToDevicesUseCase = PushMembersToDevicesUseCase(deviceGateway, abbreviateNamesUseCase)
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
    )
    val deleteDeviceUsersUseCase = DeleteDeviceUsersUseCase(deviceGateway)
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
    )

    // Bộ tự động kiểm tra & cập nhật OTA từ GitHub Releases (danhhoa20005/paluzz)
    val appUpdateCoordinator = AppUpdateCoordinator(
        configRepository = configRepository,
        updateRepository = appUpdateRepository,
        isInternetOnline = { attendanceEngine.state.value.isInternetOnline },
        onLogEvent = { title, msg, sev -> attendanceEngine.appendLog(title, "-", msg, sev) },
    )
}

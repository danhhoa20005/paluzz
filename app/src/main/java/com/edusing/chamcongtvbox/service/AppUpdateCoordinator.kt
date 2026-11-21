package com.edusing.chamcongtvbox.service

import com.edusing.chamcongtvbox.domain.model.AppVersionComparator
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.model.UpdateStateSnapshot
import com.edusing.chamcongtvbox.domain.repository.AppUpdateRepository
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bộ điều phối tự động cập nhật OTA từ GitHub Releases (`danhhoa20005/paluzz`):
 * - Chạy nền nhẹ mỗi 6 giờ (chờ 90 giây sau khởi động để ưu tiên kết nối máy chấm công trước).
 * - Tải bản APK mới & kiểm tra SHA-256 + tên gói an toàn.
 * - Nếu TV Box có quyền **Device Owner** hoặc **Root (`su`)**: tự cài đè 100% âm thầm rồi `BootReceiver` tự bật lại app.
 * - Nếu là máy thường: tải sẵn vào bộ đệm và báo có bản mới để người dùng bấm cài 1 chạm (không bật popup bất ngờ).
 */
class AppUpdateCoordinator(
    private val configRepository: ConfigRepository,
    private val updateRepository: AppUpdateRepository,
    private val isInternetOnline: () -> Boolean,
    private val onLogEvent: (title: String, message: String, severity: EventSeverity) -> Unit = { _, _, _ -> },
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opMutex = Mutex()
    private var bgJob: Job? = null

    private val _state = MutableStateFlow(buildInitialState())
    val state: StateFlow<UpdateStateSnapshot> = _state.asStateFlow()

    init {
        scope.launch {
            configRepository.configFlow.collect { cfg ->
                _state.update {
                    it.copy(
                        repoSlug = cfg.safeUpdateRepoSlug,
                        autoUpdateEnabled = cfg.autoUpdateEnabled,
                        installMode = updateRepository.detectInstallMode(),
                    )
                }
            }
        }
    }

    fun startBackgroundLoop() {
        if (bgJob?.isActive == true) return
        bgJob = scope.launch {
            if (initialDelayMs > 0L) delay(initialDelayMs)
            while (isActive) {
                try {
                    val cfg = configRepository.getConfig()
                    if (cfg.autoUpdateEnabled && isInternetOnline()) {
                        checkAndPrepareUpdate(triggerSilentInstall = true, isManual = false)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Không để lỗi kiểm tra cập nhật ảnh hưởng vòng chấm công 24/7
                }
                delay(checkIntervalMs)
            }
        }
    }

    suspend fun checkNow(): UpdateStateSnapshot {
        checkAndPrepareUpdate(triggerSilentInstall = false, isManual = true)
        return _state.value
    }

    suspend fun downloadAndInstallNow(allowInteractiveFallback: Boolean = true): Pair<Boolean, String> = opMutex.withLock {
        val cfg = configRepository.getConfig()
        val mode = updateRepository.detectInstallMode()
        var release = _state.value.availableRelease

        if (release == null) {
            _state.update { it.copy(isChecking = true, errorMessage = null, statusMessage = "Đang kiểm tra bản mới từ GitHub...") }
            val fetched = updateRepository.fetchLatestRelease(cfg.safeUpdateRepoSlug).getOrElse { err ->
                val msg = "Không kiểm tra được GitHub: ${err.message ?: "lỗi mạng"}"
                _state.update { it.copy(isChecking = false, errorMessage = msg) }
                return@withLock false to msg
            }
            _state.update { it.copy(isChecking = false) }
            if (fetched == null || !isRemoteNewer(fetched.versionCode, fetched.versionName)) {
                val msg = "Đang dùng bản mới nhất (v${updateRepository.currentVersionName()})."
                _state.update { it.copy(availableRelease = null, statusMessage = msg, errorMessage = null) }
                return@withLock true to msg
            }
            release = fetched
            _state.update { it.copy(availableRelease = fetched) }
        }

        var apkPath = _state.value.downloadedApkPath
        if (apkPath.isNullOrBlank()) {
            _state.update { it.copy(isDownloading = true, errorMessage = null, statusMessage = "Đang tải v${release.versionName}...") }
            val dl = updateRepository.downloadAndVerifyApk(release).getOrElse { err ->
                val msg = "Tải bản cập nhật lỗi: ${err.message ?: "lỗi mạng"}"
                _state.update { it.copy(isDownloading = false, errorMessage = msg) }
                return@withLock false to msg
            }
            apkPath = dl
            _state.update { it.copy(isDownloading = false, downloadedApkPath = dl) }
        }

        _state.update { it.copy(isInstalling = true, installMode = mode, statusMessage = "Đang cài đặt v${release.versionName}...") }
        val outcome = updateRepository.installApk(apkPath, allowInteractiveFallback)
        _state.update {
            it.copy(
                isInstalling = false,
                statusMessage = if (outcome.success) outcome.message else it.statusMessage,
                errorMessage = if (outcome.success) null else outcome.message,
            )
        }
        if (outcome.success) onLogEvent("Cập nhật", "v${release.versionName}: ${outcome.message}", EventSeverity.SUCCESS)
        outcome.success to outcome.message
    }

    internal suspend fun checkAndPrepareUpdate(triggerSilentInstall: Boolean, isManual: Boolean) = opMutex.withLock {
        val cfg = configRepository.getConfig()
        val mode = updateRepository.detectInstallMode()
        _state.update {
            it.copy(
                isChecking = true,
                installMode = mode,
                repoSlug = cfg.safeUpdateRepoSlug,
                errorMessage = if (isManual) null else it.errorMessage,
                statusMessage = if (isManual) "Đang kiểm tra bản mới từ ${cfg.safeUpdateRepoSlug}..." else it.statusMessage,
            )
        }

        val fetched = updateRepository.fetchLatestRelease(cfg.safeUpdateRepoSlug).getOrElse { err ->
            _state.update {
                it.copy(
                    isChecking = false,
                    errorMessage = if (isManual) "Không kiểm tra được GitHub: ${err.message ?: "lỗi mạng"}" else null,
                )
            }
            return@withLock
        }

        if (fetched == null || !isRemoteNewer(fetched.versionCode, fetched.versionName)) {
            _state.update {
                it.copy(
                    isChecking = false,
                    availableRelease = null,
                    downloadedApkPath = null,
                    errorMessage = null,
                    statusMessage = "Đang dùng bản mới nhất (v${updateRepository.currentVersionName()}).",
                )
            }
            return@withLock
        }

        _state.update {
            it.copy(
                isChecking = false,
                availableRelease = fetched,
                isDownloading = true,
                statusMessage = "Phát hiện bản mới v${fetched.versionName} — đang tải APK...",
            )
        }

        val apkPath = updateRepository.downloadAndVerifyApk(fetched).getOrElse { err ->
            _state.update { it.copy(isDownloading = false, errorMessage = "Tải v${fetched.versionName} lỗi: ${err.message}") }
            return@withLock
        }

        _state.update {
            it.copy(
                isDownloading = false,
                downloadedApkPath = apkPath,
                errorMessage = null,
                statusMessage = "Đã tải sẵn v${fetched.versionName} (${fetched.source}).",
            )
        }
        onLogEvent("Cập nhật", "Đã tải sẵn bản mới v${fetched.versionName} từ GitHub.", EventSeverity.INFO)

        if (triggerSilentInstall && mode.isSilentCapable) {
            _state.update { it.copy(isInstalling = true, statusMessage = "Đang tự cài âm thầm v${fetched.versionName}...") }
            val outcome = updateRepository.installApk(apkPath, allowInteractiveFallback = false)
            _state.update {
                it.copy(
                    isInstalling = false,
                    statusMessage = outcome.message,
                    errorMessage = if (outcome.success) null else outcome.message,
                )
            }
            onLogEvent("Cập nhật", outcome.message, if (outcome.success) EventSeverity.SUCCESS else EventSeverity.WARNING)
        }
    }

    private fun isRemoteNewer(remoteCode: Int, remoteName: String): Boolean = AppVersionComparator.isNewer(
        remoteVersionCode = remoteCode,
        remoteVersionName = remoteName,
        currentVersionCode = updateRepository.currentVersionCode(),
        currentVersionName = updateRepository.currentVersionName(),
    )

    private fun buildInitialState(): UpdateStateSnapshot {
        val cfg = configRepository.getConfig()
        return UpdateStateSnapshot(
            currentVersionCode = updateRepository.currentVersionCode(),
            currentVersionName = updateRepository.currentVersionName(),
            repoSlug = cfg.safeUpdateRepoSlug,
            autoUpdateEnabled = cfg.autoUpdateEnabled,
            installMode = updateRepository.detectInstallMode(),
        )
    }

    companion object {
        private const val DEFAULT_INITIAL_DELAY_MS = 90_000L
        private const val DEFAULT_CHECK_INTERVAL_MS = 6L * 60 * 60_000L
    }
}

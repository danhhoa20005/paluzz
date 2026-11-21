package com.edusing.chamcongtvbox.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import com.edusing.chamcongtvbox.domain.repository.ConfigRepository
import com.edusing.chamcongtvbox.domain.repository.InternetProbe
import com.edusing.chamcongtvbox.domain.usecase.LoginUseCase
import com.edusing.chamcongtvbox.service.AttendanceEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Trạng thái UI màn hình Đăng nhập (hỗ trợ lưu 1 lần tài khoản + mật khẩu & tự đăng nhập khi mở app). */
data class LoginUiState(
    val activeMode: AppMode = AppMode.PALUZZ,
    val title: String = "",
    val orgLabel: String = "Đơn vị",
    val username: String = "",
    val password: String = "",
    val apiDomain: String = "",
    val rememberAccount: Boolean = true,
    val showAdvancedServer: Boolean = false,
    val savedAccounts: List<SavedAccount> = emptyList(),
    val isLoading: Boolean = false,
    val isAutoLoggingIn: Boolean = false,
    val isTestingServer: Boolean = false,
    val serverTestResult: String? = null,
    val errorMessage: String? = null,
    val noticeBanner: String? = null,
)

/** ViewModel xử lý đăng nhập, lưu tài khoản/mật khẩu 1 lần và tự chờ mạng → đăng nhập khi mở app / TV Box boot. */
class LoginViewModel(
    private val configRepository: ConfigRepository,
    private val attendanceEngine: AttendanceEngine,
    private val loginUseCase: LoginUseCase,
    private val internetProbe: InternetProbe,
) : ViewModel() {

    private val _uiState = MutableStateFlow(buildStateFromCurrentConfig())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _loginSuccessEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loginSuccessEvent: SharedFlow<Unit> = _loginSuccessEvent.asSharedFlow()

    private var autoLoginJob: Job? = null

    init {
        viewModelScope.launch {
            configRepository.configFlow.collect { cfg ->
                val branding = configRepository.getBranding()
                _uiState.update { s ->
                    val modeChanged = s.activeMode != cfg.mode
                    val fallbackSaved = cfg.savedAccounts.firstOrNull()
                    val defaultUser = cfg.username.ifBlank { fallbackSaved?.username.orEmpty() }
                    val defaultPass = cfg.password.ifBlank { fallbackSaved?.password.orEmpty() }
                    s.copy(
                        activeMode = cfg.mode,
                        title = branding.loginTitle,
                        orgLabel = branding.org,
                        apiDomain = if (modeChanged) branding.normalizeApiDomain(cfg.apiDomain) else s.apiDomain,
                        savedAccounts = cfg.savedAccounts,
                        username = if (modeChanged || (s.username.isBlank() && defaultUser.isNotBlank())) defaultUser else s.username,
                        password = if (modeChanged || (s.password.isBlank() && defaultPass.isNotBlank())) defaultPass else s.password,
                        rememberAccount = if (modeChanged) cfg.rememberAccount else s.rememberAccount,
                        errorMessage = if (modeChanged) null else s.errorMessage,
                        serverTestResult = if (modeChanged) null else s.serverTestResult,
                    )
                }
            }
        }
    }

    /**
     * Tự động đăng nhập bằng tài khoản + mật khẩu đã lưu 1 lần (khi mở app hoặc khi TV Box vừa có điện lại):
     * Chờ kết nối Internet → Đếm ngược 2 giây → Tự đăng nhập (tự thử lại nếu router đang khởi động).
     */
    fun startAutoLoginIfSaved() {
        val cfg = configRepository.getConfig()
        val saved = cfg.savedAccounts.firstOrNull()
        val user = cfg.username.ifBlank { saved?.username.orEmpty() }.trim()
        val pass = cfg.password.ifBlank { saved?.password.orEmpty() }
        if (!cfg.rememberAccount || user.isBlank() || pass.isBlank()) return
        if (autoLoginJob?.isActive == true || _uiState.value.isLoading) return

        _uiState.update { it.copy(username = user, password = pass, rememberAccount = true, isAutoLoggingIn = true, errorMessage = null) }
        autoLoginJob = viewModelScope.launch {
            val domain = configRepository.getBranding().normalizeApiDomain(_uiState.value.apiDomain)
            while (isActive) {
                if (!internetProbe.checkInternet(domain).online) {
                    _uiState.update { it.copy(noticeBanner = "Đang chờ kết nối Internet để tự đăng nhập ($user)...") }
                    runCatching { internetProbe.requestReconnectNetwork() }
                    delay(2_500L)
                    continue
                }
                for (sec in 2 downTo 1) {
                    if (!isActive) return@launch
                    _uiState.update { it.copy(noticeBanner = "Đã có mạng — tự đăng nhập tài khoản \"$user\" sau ${sec}s...") }
                    delay(1_000L)
                }
                if (!isActive) return@launch
                val res = submitLoginInternal(clearAutoFlag = false)
                if (res?.success == true) {
                    _uiState.update { it.copy(isAutoLoggingIn = false) }
                    return@launch
                }
                if (res?.isInvalidCredentials == true) {
                    _uiState.update { it.copy(isAutoLoggingIn = false, noticeBanner = null) }
                    return@launch
                }
                _uiState.update { it.copy(noticeBanner = "Chưa nối được máy chủ — tự thử đăng nhập lại sau 3s...") }
                delay(3_000L)
            }
        }
    }

    fun cancelAutoLogin() {
        autoLoginJob?.cancel()
        autoLoginJob = null
        if (_uiState.value.isAutoLoggingIn) {
            _uiState.update { it.copy(isAutoLoggingIn = false, noticeBanner = null) }
        }
    }

    private fun buildStateFromCurrentConfig(): LoginUiState {
        val cfg = configRepository.getConfig()
        val branding = configRepository.getBranding()
        val saved = cfg.savedAccounts.firstOrNull()
        return LoginUiState(
            activeMode = cfg.mode,
            title = branding.loginTitle,
            orgLabel = branding.org,
            username = cfg.username.ifBlank { saved?.username.orEmpty() },
            password = cfg.password.ifBlank { saved?.password.orEmpty() },
            apiDomain = branding.normalizeApiDomain(cfg.apiDomain),
            rememberAccount = cfg.rememberAccount,
            savedAccounts = cfg.savedAccounts,
        )
    }

    fun switchMode(targetMode: AppMode) {
        cancelAutoLogin()
        if (_uiState.value.isLoading || _uiState.value.activeMode == targetMode) return
        viewModelScope.launch {
            attendanceEngine.switchAppMode(targetMode)
            _uiState.value = buildStateFromCurrentConfig()
        }
    }

    fun setNoticeBanner(message: String?) = _uiState.update { it.copy(noticeBanner = message) }
    fun onUsernameChange(value: String) { cancelAutoLogin(); _uiState.update { it.copy(username = value, errorMessage = null) } }
    fun onPasswordChange(value: String) { cancelAutoLogin(); _uiState.update { it.copy(password = value, errorMessage = null) } }
    fun onApiDomainChange(value: String) { cancelAutoLogin(); _uiState.update { it.copy(apiDomain = value, errorMessage = null, serverTestResult = null) } }
    fun onRememberChange(checked: Boolean) = _uiState.update { it.copy(rememberAccount = checked) }
    fun toggleAdvancedServer() = _uiState.update { it.copy(showAdvancedServer = !it.showAdvancedServer) }

    fun selectSavedAccount(account: SavedAccount) {
        cancelAutoLogin()
        _uiState.update {
            it.copy(username = account.username, password = account.password, rememberAccount = true, errorMessage = null)
        }
    }

    fun deleteSavedAccount(username: String) {
        viewModelScope.launch { configRepository.removeSavedAccount(username) }
    }

    fun testServerConnection() {
        val state = _uiState.value
        if (state.isTestingServer || state.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingServer = true, serverTestResult = "Đang kiểm tra kết nối máy chủ...") }
            val msg = loginUseCase.testServerConnection(state.apiDomain)
            _uiState.update { it.copy(isTestingServer = false, serverTestResult = msg) }
        }
    }

    fun submitLogin() {
        cancelAutoLogin()
        viewModelScope.launch { submitLoginInternal(clearAutoFlag = true) }
    }

    private suspend fun submitLoginInternal(clearAutoFlag: Boolean) = run {
        val state = _uiState.value
        if (state.isLoading) return@run null
        _uiState.update { it.copy(isLoading = true, errorMessage = null, noticeBanner = if (clearAutoFlag) null else it.noticeBanner) }
        val result = loginUseCase(state.username, state.password, state.rememberAccount, state.apiDomain)
        if (result.success) {
            _uiState.update { it.copy(isLoading = false, isAutoLoggingIn = false, noticeBanner = null) }
            _loginSuccessEvent.tryEmit(Unit)
        } else {
            _uiState.update { it.copy(isLoading = false, errorMessage = result.error ?: "Đăng nhập thất bại.") }
        }
        result
    }
}

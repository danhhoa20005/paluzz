package com.edusing.chamcongtvbox.presentation

import android.Manifest
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edusing.chamcongtvbox.AttendanceTvApp
import com.edusing.chamcongtvbox.di.AppContainer
import com.edusing.chamcongtvbox.di.AppViewModelFactory
import com.edusing.chamcongtvbox.domain.model.StartupPermissionType
import com.edusing.chamcongtvbox.domain.model.StartupPermissionsSnapshot
import com.edusing.chamcongtvbox.presentation.adddevice.AddDeviceScreen
import com.edusing.chamcongtvbox.presentation.adddevice.AddDeviceViewModel
import com.edusing.chamcongtvbox.presentation.components.AutoUpdateCountdownDialog
import com.edusing.chamcongtvbox.presentation.login.LoginScreen
import com.edusing.chamcongtvbox.presentation.login.LoginViewModel
import com.edusing.chamcongtvbox.presentation.main.AppShellScaffold
import com.edusing.chamcongtvbox.presentation.permissions.StartupPermissionsScreen
import com.edusing.chamcongtvbox.presentation.theme.AppTvTheme
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassBackdrop
import com.edusing.chamcongtvbox.presentation.theme.detectIsTvDevice
import com.edusing.chamcongtvbox.service.AttendanceForegroundService
import com.edusing.chamcongtvbox.service.StartupPermissionsHelper
import com.edusing.chamcongtvbox.service.TvAutoBootHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Các màn hình cấp gốc của ứng dụng sau khi đã cấp đủ quyền bắt buộc lúc khởi động. */
private sealed interface AppScreen {
    data class Login(val notice: String? = null) : AppScreen
    data object Shell : AppScreen
    data class AddDevice(val session: Int) : AppScreen
}

/**
 * Activity gốc:
 * - Chặn tại cổng **Cấp quyền bắt buộc** ngay khi vừa mở app (Tự động bật app, Bỏ giới hạn pin, Thông báo, OTA)
 *   trước khi vào màn hình Đăng nhập / Trạm trực.
 * - Khởi chạy dịch vụ nền 24/7 (`AttendanceForegroundService`) và giữ màn hình sáng khi đang trực.
 */
class MainActivity : ComponentActivity() {

    private val permissionsState = MutableStateFlow<StartupPermissionsSnapshot?>(null)
    private var notificationRequestedOnce = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshStartupPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        refreshStartupPermissions()
        AttendanceForegroundService.start(applicationContext, fromBoot = false)

        val container = (application as AttendanceTvApp).container
        val isTv = detectIsTvDevice(this, resources.configuration)
        configureTvAlwaysAwake(isTv)
        observeKeepScreenOn(container, isTv)

        setContent {
            val perms by permissionsState.collectAsStateWithLifecycle()
            val eng by container.attendanceEngine.state.collectAsStateWithLifecycle()
            AppTvTheme {
                LiquidGlassBackdrop {
                    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding()) {
                        val snap = perms ?: StartupPermissionsHelper.evaluate(this@MainActivity)
                        if (!snap.allMandatoryGranted) {
                            StartupPermissionsScreen(
                                snapshot = snap,
                                webConsoleUrl = eng.webConsoleUrl,
                                showDefaultPinHint = !container.configRepository.getConfig().isLoggedIn,
                                onRequestPermission = ::requestMandatoryPermission,
                                onEnableTvHomeLauncher = { TvAutoBootHelper.enableTvHomeLauncherAndPrompt(this@MainActivity) },
                                onRecheckNow = ::refreshStartupPermissions,
                            )
                        } else {
                            AttendanceTvRoot(container) {
                                if (!TvAutoBootHelper.isDefaultHomeLauncher(this@MainActivity)) moveTaskToBack(true)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TvAutoBootHelper.setAppInForeground(true)
        refreshStartupPermissions()
        val c = (application as AttendanceTvApp).container
        c.attendanceEngine.onAppResumed()
        c.appUpdateCoordinator.triggerBackgroundCheckIfDue()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStartupPermissions()
    }

    override fun onPause() {
        TvAutoBootHelper.setAppInForeground(false)
        super.onPause()
    }

    private fun refreshStartupPermissions() {
        permissionsState.value = StartupPermissionsHelper.evaluate(this)
    }

    private fun requestMandatoryPermission(type: StartupPermissionType) {
        when (type) {
            StartupPermissionType.AUTO_LAUNCH_ON_BOOT -> StartupPermissionsHelper.openAutoLaunchSettings(this)
            StartupPermissionType.BATTERY_UNRESTRICTED -> StartupPermissionsHelper.openBatteryOptimizationSettings(this)
            StartupPermissionType.INSTALL_UPDATES -> StartupPermissionsHelper.openInstallUnknownAppsSettings(this)
            StartupPermissionType.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (StartupPermissionsHelper.shouldOpenNotificationSettingsDirectly(this, notificationRequestedOnce)) {
                        StartupPermissionsHelper.openNotificationSettings(this)
                    } else {
                        notificationRequestedOnce = true
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureTvAlwaysAwake(isTv: Boolean) {
        if (!isTv) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true); setShowWhenLocked(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    private fun observeKeepScreenOn(container: AppContainer, isTv: Boolean) {
        if (isTv) return
        lifecycleScope.launch {
            container.attendanceEngine.state.map { it.isRunning }.distinctUntilChanged().collect { running ->
                if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

@Composable
private fun AttendanceTvRoot(container: AppContainer, onMoveToBackground: () -> Unit) {
    val factory = remember(container) { AppViewModelFactory(container) }
    val uiScope = rememberCoroutineScope()
    val engineSnap by container.attendanceEngine.state.collectAsStateWithLifecycle()
    val updateSnap by container.appUpdateCoordinator.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<AppScreen>(if (container.configRepository.getConfig().isLoggedIn) AppScreen.Shell else AppScreen.Login()) }
    var suppressAutoLogin by remember { mutableStateOf(false) }
    var subScreenCounter by remember { mutableIntStateOf(1) }

    LaunchedEffect(container) {
        container.attendanceEngine.forceRelogin.collect { reason -> suppressAutoLogin = true; screen = AppScreen.Login(notice = reason) }
    }
    LaunchedEffect(container) {
        container.configRepository.configFlow.map { it.isLoggedIn }.distinctUntilChanged().collect { loggedIn ->
            if (loggedIn && screen is AppScreen.Login) { suppressAutoLogin = false; screen = AppScreen.Shell }
        }
    }

    BackHandler(enabled = screen is AppScreen.AddDevice) { screen = AppScreen.Shell }
    BackHandler(enabled = screen is AppScreen.Shell && engineSnap.isRunning) { onMoveToBackground() }

    when (val dest = screen) {
        is AppScreen.Login -> {
            val vm: LoginViewModel = viewModel(key = "login", factory = factory)
            LaunchedEffect(dest.notice) { dest.notice?.let(vm::setNoticeBanner) }
            LaunchedEffect(suppressAutoLogin) { if (!suppressAutoLogin) vm.startAutoLoginIfSaved() }
            LaunchedEffect(vm) {
                vm.loginSuccessEvent.collect { suppressAutoLogin = false; screen = AppScreen.Shell }
            }
            LoginScreen(viewModel = vm, webConsoleUrl = engineSnap.webConsoleUrl)
        }

        AppScreen.Shell -> {
            AppShellScaffold(
                container = container,
                factory = factory,
                onOpenAddDevice = { screen = AppScreen.AddDevice(++subScreenCounter) },
                onLoggedOut = { suppressAutoLogin = true; screen = AppScreen.Login() },
            )
        }

        is AppScreen.AddDevice -> {
            val vm: AddDeviceViewModel = viewModel(key = "add_device_${dest.session}", factory = factory)
            AddDeviceScreen(viewModel = vm, onBack = { screen = AppScreen.Shell })
        }
    }

    if (updateSnap.shouldAutoPromptNow) {
        val verCode = updateSnap.availableRelease?.versionCode ?: 0
        AutoUpdateCountdownDialog(
            updateState = updateSnap,
            onInstallNow = {
                container.appUpdateCoordinator.markAutoPrompted(verCode)
                uiScope.launch { container.appUpdateCoordinator.downloadAndInstallNow(allowInteractiveFallback = true) }
            },
            onSnooze = { container.appUpdateCoordinator.dismissUpdateBanner(verCode) },
        )
    }
}

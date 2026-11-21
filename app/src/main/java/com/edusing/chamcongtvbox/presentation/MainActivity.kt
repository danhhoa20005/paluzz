package com.edusing.chamcongtvbox.presentation

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edusing.chamcongtvbox.AttendanceTvApp
import com.edusing.chamcongtvbox.di.AppContainer
import com.edusing.chamcongtvbox.di.AppViewModelFactory
import com.edusing.chamcongtvbox.presentation.adddevice.AddDeviceScreen
import com.edusing.chamcongtvbox.presentation.adddevice.AddDeviceViewModel
import com.edusing.chamcongtvbox.presentation.components.AutoUpdateCountdownDialog
import com.edusing.chamcongtvbox.presentation.login.LoginScreen
import com.edusing.chamcongtvbox.presentation.login.LoginViewModel
import com.edusing.chamcongtvbox.presentation.main.AppShellScaffold
import com.edusing.chamcongtvbox.presentation.theme.AppTvTheme
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassBackdrop
import com.edusing.chamcongtvbox.presentation.theme.detectIsTvDevice
import com.edusing.chamcongtvbox.service.AttendanceForegroundService
import com.edusing.chamcongtvbox.service.TvAutoBootHelper
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Các màn hình cấp gốc của ứng dụng Tammi Clean 3D. */
private sealed interface AppScreen {
    data class Login(val notice: String? = null) : AppScreen
    data object Shell : AppScreen
    data class AddDevice(val session: Int) : AppScreen
}

/**
 * Activity gốc:
 * - Khởi chạy dịch vụ nền 24/7 (`AttendanceForegroundService`).
 * - Tự bật `FLAG_KEEP_SCREEN_ON` khi đang chấm công và nối lại nhẹ khi quay lại ứng dụng.
 * - Tương thích vùng an toàn tai thỏ / thanh trạng thái / điều hướng trên cả điện thoại và TV Box.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Sau khi hỏi quyền thông báo (Android 13+), tiếp tục kiểm tra quyền tự bật màn hình cho Android 10+
        promptOverlayPermissionIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        requestStartupPermissionsIfNeeded()
        AttendanceForegroundService.start(applicationContext, fromBoot = false)

        val container = (application as AttendanceTvApp).container
        val isTv = detectIsTvDevice(this, resources.configuration)
        configureTvAlwaysAwake(isTv)
        observeKeepScreenOn(container, isTv)

        setContent {
            AppTvTheme {
                // Nền quang học Liquid Glass tràn viền toàn màn hình (xuyên sau thanh trạng thái & cử chỉ)
                LiquidGlassBackdrop {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .displayCutoutPadding(),
                    ) {
                        AttendanceTvRoot(
                            container = container,
                            onMoveToBackground = {
                                if (!TvAutoBootHelper.isDefaultHomeLauncher(this@MainActivity)) {
                                    moveTaskToBack(true)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TvAutoBootHelper.setAppInForeground(true)
        val c = (application as AttendanceTvApp).container
        c.attendanceEngine.onAppResumed()
        c.appUpdateCoordinator.triggerBackgroundCheckIfDue()
    }

    override fun onPause() {
        TvAutoBootHelper.setAppInForeground(false)
        super.onPause()
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

    private fun requestStartupPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        promptOverlayPermissionIfNeeded()
    }

    private fun promptOverlayPermissionIfNeeded() =
        TvAutoBootHelper.promptOverlayPermissionOnLaunchIfNeeded(this, isTvDevice = detectIsTvDevice(this, resources.configuration))
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

    BackHandler(enabled = screen is AppScreen.AddDevice) {
        screen = AppScreen.Shell
    }
    BackHandler(enabled = screen is AppScreen.Shell && engineSnap.isRunning) {
        onMoveToBackground()
    }

    when (val dest = screen) {
        is AppScreen.Login -> {
            val vm: LoginViewModel = viewModel(key = "login", factory = factory)
            LaunchedEffect(dest.notice) { dest.notice?.let(vm::setNoticeBanner) }
            LaunchedEffect(suppressAutoLogin) { if (!suppressAutoLogin) vm.startAutoLoginIfSaved() }
            LaunchedEffect(vm) {
                vm.loginSuccessEvent.collect {
                    suppressAutoLogin = false
                    screen = AppScreen.Shell
                }
            }
            LoginScreen(viewModel = vm)
        }

        AppScreen.Shell -> {
            AppShellScaffold(
                container = container,
                factory = factory,
                onOpenAddDevice = { screen = AppScreen.AddDevice(++subScreenCounter) },
                onLoggedOut = {
                    suppressAutoLogin = true
                    screen = AppScreen.Login()
                },
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

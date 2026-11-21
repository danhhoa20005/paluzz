package com.edusing.chamcongtvbox.presentation.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edusing.chamcongtvbox.di.AppContainer
import com.edusing.chamcongtvbox.di.AppViewModelFactory
import com.edusing.chamcongtvbox.presentation.components.AppModePickerDialog
import com.edusing.chamcongtvbox.presentation.components.AutoUpdateReadyBanner
import com.edusing.chamcongtvbox.presentation.components.TammiEmptyStateView
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import kotlinx.coroutines.launch
import com.edusing.chamcongtvbox.presentation.push.PushMembersScreen
import com.edusing.chamcongtvbox.presentation.push.PushMembersViewModel
import com.edusing.chamcongtvbox.presentation.settings.SettingsScreen
import com.edusing.chamcongtvbox.presentation.settings.SettingsViewModel
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassScrollEdgeFade
import com.edusing.chamcongtvbox.presentation.theme.LocalFloatingNavBottomInset
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.rememberDeviceUiSpec
import com.edusing.chamcongtvbox.presentation.users.DeviceUsersScreen
import com.edusing.chamcongtvbox.presentation.users.DeviceUsersViewModel

/**
 * Khung điều hướng **Liquid Glass 3D 2 lớp** chuẩn Apple HIG:
 * - **Điện thoại**: Thanh tiêu đề thoáng + nội dung cuộn xuyên sáng phía sau viên thuốc kính nổi đáy.
 * - **Android TV Box / Tablet**: Sidebar Chrome Liquid Glass nổi toàn chiều cao bên trái + nội dung 16:9 bên phải.
 */
@Composable
fun AppShellScaffold(
    container: AppContainer,
    factory: AppViewModelFactory,
    onOpenAddDevice: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val mainVm: MainViewModel = viewModel(key = "main", factory = factory)
    val mainState by mainVm.uiState.collectAsStateWithLifecycle()
    val updateState by container.appUpdateCoordinator.state.collectAsStateWithLifecycle()
    val shellScope = rememberCoroutineScope()
    val ui = rememberDeviceUiSpec()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var isKioskMode by rememberSaveable { mutableStateOf(false) }

    if (isKioskMode) {
        Kiosk24x7Overlay(
            orgName = mainState.orgName.ifBlank { mainState.appTitle },
            brandSubtitle = "${mainState.activeMode.shortLabel} • 24/7",
            internetStatus = mainState.internetStatus,
            onlineCount = mainState.connectedCount,
            totalCount = mainState.totalDeviceCount,
            isRunning = mainState.isRunning,
            totalToday = mainState.totalCheckInsToday,
            latestEvent = mainState.events.firstOrNull(),
            onExitKiosk = { isKioskMode = false },
        )
        return
    }

    val memberTabLabel = if (mainState.isOffice) "Nhân sự" else "Học sinh"
    val tabs = listOf("Trạm trực", memberTabLabel, "Trên máy", "Cấu hình")

    val tabBody: @Composable () -> Unit = {
        when (selectedTab) {
            0 -> MainScreen(mainVm, onOpenAddDevice, { selectedTab = 1 }, { selectedTab = 2 }, { isKioskMode = true }, onLoggedOut)
            1 -> GuardedDeviceTab(mainState.isRunning, mainState.connectedCount, "Đẩy danh sách ${memberTabLabel.lowercase()}", { selectedTab = 0 }) {
                val pushVm: PushMembersViewModel = viewModel(key = "shell_push_${mainState.activeMode.code}", factory = factory)
                LaunchedEffect(mainState.connectedCount, mainState.activeMode) { pushVm.refreshTargetsAndLoad() }
                PushMembersScreen(viewModel = pushVm, onBack = null)
            }
            2 -> GuardedDeviceTab(mainState.isRunning, mainState.connectedCount, "Người trên máy", { selectedTab = 0 }) {
                val usersVm: DeviceUsersViewModel = viewModel(key = "shell_users_${mainState.activeMode.code}", factory = factory)
                LaunchedEffect(mainState.connectedCount, mainState.activeMode) { usersVm.refreshAndLoad() }
                DeviceUsersScreen(viewModel = usersVm, onBack = null)
            }
            else -> {
                val settingsVm: SettingsViewModel = viewModel(key = "shell_settings", factory = factory)
                SettingsScreen(
                    viewModel = settingsVm,
                    onBack = null,
                    onLogoutClick = null,
                    onModeSwitchRequireLogin = onLoggedOut,
                )
            }
        }
    }

    if (ui.isTwoPane) {
        Row(modifier = Modifier.fillMaxSize()) {
            TvSideNavigationRail(
                state = mainState,
                tabs = tabs,
                selectedIndex = selectedTab,
                ui = ui,
                onSelect = { selectedTab = it },
                onModeClick = mainVm::openModePicker,
                onLogoutClick = mainVm::requestLogout,
            )
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (mainState.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = AppColors.Primary)
                }
                if (updateState.shouldShowUpdateBanner) {
                    AutoUpdateReadyBanner(
                        updateState = updateState,
                        onInstallNow = { shellScope.launch { container.appUpdateCoordinator.downloadAndInstallNow(true) } },
                        onDismiss = { container.appUpdateCoordinator.dismissUpdateBanner(updateState.availableRelease?.versionCode ?: 0) },
                        modifier = Modifier.padding(horizontal = ui.horizontalPadding, vertical = 6.dp),
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { tabBody() }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            MainTopAppBar(mainState, ui, mainVm::openModePicker, mainVm::requestLogout)
            if (updateState.shouldShowUpdateBanner) {
                AutoUpdateReadyBanner(
                    updateState = updateState,
                    onInstallNow = { shellScope.launch { container.appUpdateCoordinator.downloadAndInstallNow(true) } },
                    onDismiss = { container.appUpdateCoordinator.dismissUpdateBanner(updateState.availableRelease?.versionCode ?: 0) },
                    modifier = Modifier.padding(horizontal = ui.horizontalPadding, vertical = 4.dp),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalFloatingNavBottomInset provides 78.dp) {
                    tabBody()
                }
                LiquidGlassScrollEdgeFade(
                    isTop = false,
                    height = 88.dp,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                PhoneBottomNavigationBar(
                    tabs = tabs,
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (mainState.showModePickerDialog) {
        AppModePickerDialog(
            activeMode = mainState.activeMode,
            isRunning = mainState.isRunning,
            onSelectMode = { mode -> mainVm.switchMode(mode, onRequireLogin = onLoggedOut) },
            onDismiss = mainVm::dismissModePicker,
        )
    }
}

@Composable
private fun GuardedDeviceTab(
    isRunning: Boolean,
    connectedCount: Int,
    featureTitle: String,
    onBackToDashboard: () -> Unit,
    content: @Composable () -> Unit,
) {
    val bottomInset = LocalFloatingNavBottomInset.current
    if (!isRunning && connectedCount > 0) { content(); return }
    Box(
        modifier = Modifier.fillMaxSize().padding(start = TammiSpacing.xl, end = TammiSpacing.xl, top = TammiSpacing.xl, bottom = TammiSpacing.xl + bottomInset),
        contentAlignment = Alignment.Center,
    ) {
        TammiGlassPanel {
            TammiEmptyStateView(
                accentColor = if (isRunning) AppColors.Amber else AppColors.Red,
                title = if (isRunning) "Đang chấm công 24/7" else "Chưa có máy Online",
                subtitle = if (isRunning) "Tạm dừng chấm công ở Trạm trực để mở \"$featureTitle\"."
                else "Về Trạm trực kết nối máy chấm công để mở \"$featureTitle\".",
                actionLabel = "Về Trạm trực",
                onAction = onBackToDashboard,
            )
        }
    }
}

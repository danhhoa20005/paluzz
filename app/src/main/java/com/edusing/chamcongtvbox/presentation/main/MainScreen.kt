package com.edusing.chamcongtvbox.presentation.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edusing.chamcongtvbox.presentation.components.RenameDeviceDialog
import com.edusing.chamcongtvbox.presentation.components.TammiQuickAction
import com.edusing.chamcongtvbox.presentation.components.TammiQuickActionDock
import com.edusing.chamcongtvbox.presentation.components.TvSafeConfirmDialog
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LocalFloatingNavBottomInset
import com.edusing.chamcongtvbox.presentation.theme.rememberDeviceUiSpec

/**
 * Màn hình Trạm Trực **Liquid Glass 3D** gọn sạch, không trùng lặp thao tác:
 * - **TV Box / Tablet (`isTwoPane`)**: Hero lệnh nhanh phía trên + 2 cột điều khiển 16:9.
 * - **Điện thoại**: Hero → 3 phím thao tác độc nhất (`Dò LAN`, `Thêm máy`, `Kiosk 24/7`) → Máy chấm công → Nhật ký.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenAddDevice: () -> Unit,
    onOpenPushMembers: () -> Unit,
    onOpenDeviceUsers: () -> Unit,
    onOpenKiosk: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ui = rememberDeviceUiSpec()

    LaunchedEffect(Unit) { viewModel.onEnterMainScreen() }

    // 3 phím tắt độc nhất trên Điện thoại (không lặp lại tab Nhân sự / Học sinh đã có ở thanh đáy)
    val phoneQuickActions = listOf(
        TammiQuickAction(
            label = if (state.isDiscovering) "Đang dò" else "Dò LAN",
            baseColor = AppColors.OrbCobalt,
            enabled = !state.isRunning && !state.isBusy,
            onClick = viewModel::onDiscoverDevicesClicked,
        ),
        TammiQuickAction(
            label = "Thêm máy",
            baseColor = AppColors.OrbTeal,
            enabled = !state.isRunning && !state.isBusy,
            onClick = onOpenAddDevice,
        ),
        TammiQuickAction(
            label = "Kiosk 24/7",
            baseColor = AppColors.OrbAmber,
            enabled = true,
            onClick = onOpenKiosk,
        ),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        if (ui.isTwoPane) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = ui.horizontalPadding, vertical = ui.verticalPadding),
                verticalArrangement = Arrangement.spacedBy(ui.cardSpacing),
            ) {
                GatewayHeroPanel(state, ui, onStartStop = viewModel::onStartOrStopClicked, onOpenKiosk = onOpenKiosk)
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ui.cardSpacing),
                ) {
                    if (state.isRunning) {
                        TvLiveStatusPane(
                            state = state,
                            modifier = Modifier.weight(0.68f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        )
                    } else {
                        DevicesPane(
                            devices = state.devices,
                            isRunning = false,
                            isBusy = state.isBusy,
                            isDiscovering = state.isDiscovering,
                            showQuickToolbar = true,
                            isTv = ui.isTvDevice,
                            onDiscover = viewModel::onDiscoverDevicesClicked,
                            onAddDevice = onOpenAddDevice,
                            onReconnectAll = viewModel::onConnectAllClicked,
                            onRename = viewModel::requestRenameDevice,
                            onReconnect = { viewModel.onReconnectDevice(it.key) },
                            onDelete = viewModel::requestDeleteDevice,
                            modifier = Modifier.weight(0.95f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        )
                    }
                    LiveActivityPane(
                        events = state.events,
                        lastPollTime = state.lastPollTimeText,
                        totalToday = state.totalCheckInsToday,
                        scrollable = true,
                        isTv = ui.isTvDevice,
                        onClearLogs = viewModel::onClearLogsClicked,
                        modifier = Modifier.weight(if (state.isRunning) 1.32f else 1.05f).fillMaxHeight(),
                    )
                }
            }
        } else if (state.isRunning) {
            val bottomInset = LocalFloatingNavBottomInset.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = ui.horizontalPadding, end = ui.horizontalPadding, top = ui.verticalPadding, bottom = ui.verticalPadding + bottomInset),
                verticalArrangement = Arrangement.spacedBy(ui.cardSpacing),
            ) {
                GatewayHeroPanel(state, ui, onStartStop = viewModel::onStartOrStopClicked, onOpenKiosk = onOpenKiosk)
                LiveActivityPane(
                    events = state.events,
                    lastPollTime = state.lastPollTimeText,
                    totalToday = state.totalCheckInsToday,
                    scrollable = true,
                    isTv = false,
                    onClearLogs = viewModel::onClearLogsClicked,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        } else {
            val bottomInset = LocalFloatingNavBottomInset.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ui.horizontalPadding, vertical = ui.verticalPadding),
                verticalArrangement = Arrangement.spacedBy(ui.cardSpacing),
            ) {
                GatewayHeroPanel(state, ui, onStartStop = viewModel::onStartOrStopClicked, onOpenKiosk = onOpenKiosk)
                TammiQuickActionDock(actions = phoneQuickActions, isTv = false)
                DevicesPane(
                    devices = state.devices,
                    isRunning = false,
                    isBusy = state.isBusy,
                    isDiscovering = state.isDiscovering,
                    onDiscover = viewModel::onDiscoverDevicesClicked,
                    onReconnectAll = viewModel::onConnectAllClicked,
                    onRename = viewModel::requestRenameDevice,
                    onReconnect = { viewModel.onReconnectDevice(it.key) },
                    onDelete = viewModel::requestDeleteDevice,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiveActivityPane(
                    events = state.events,
                    lastPollTime = state.lastPollTimeText,
                    totalToday = state.totalCheckInsToday,
                    scrollable = false,
                    isTv = false,
                    onClearLogs = viewModel::onClearLogsClicked,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (bottomInset.value > 0f) Spacer(Modifier.height(bottomInset))
            }
        }
    }

    if (state.showStopConfirmDialog) {
        TvSafeConfirmDialog(
            title = "Dừng chấm công?",
            message = "Đang chấm bình thường không cần dừng. Bạn vẫn muốn tạm dừng?",
            confirmText = "Dừng ngay",
            cancelText = "Tiếp tục chạy",
            confirmColor = AppColors.Red,
            onConfirm = viewModel::confirmStopAttendance,
            onDismiss = viewModel::dismissStopDialog,
        )
    }

    if (state.showLogoutConfirmDialog) {
        TvSafeConfirmDialog(
            title = "Đổi tài khoản?",
            message = "Ngắt kết nối máy chấm công và quay về màn hình đăng nhập?",
            confirmText = "Đăng xuất",
            cancelText = "Huỷ",
            confirmColor = AppColors.Red,
            onConfirm = { viewModel.confirmLogout(onLoggedOut) },
            onDismiss = viewModel::dismissLogoutDialog,
        )
    }

    state.renameTarget?.let { target ->
        RenameDeviceDialog(target.name, target.ip, viewModel::confirmRenameDevice, viewModel::dismissRenameDialog)
    }

    state.deleteTarget?.let { target ->
        TvSafeConfirmDialog(
            title = "Xoá máy khỏi danh sách?",
            message = "Xoá \"${target.displayName}\" (${target.ip}:${target.port})?\nDữ liệu trên máy chấm công vẫn được giữ nguyên.",
            confirmText = "Xoá",
            cancelText = "Không",
            confirmColor = AppColors.Red,
            onConfirm = viewModel::confirmDeleteDevice,
            onDismiss = viewModel::dismissDeleteDialog,
        )
    }
}

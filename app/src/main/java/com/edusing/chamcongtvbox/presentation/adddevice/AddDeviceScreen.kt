package com.edusing.chamcongtvbox.presentation.adddevice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edusing.chamcongtvbox.presentation.components.TvSegmentedTabs
import com.edusing.chamcongtvbox.presentation.components.TvSubScreenHeader
import com.edusing.chamcongtvbox.presentation.theme.rememberDeviceUiSpec

/**
 * Màn hình Thêm & Nhận máy chấm công **Liquid Glass 3D**:
 * - Ưu tiên **Tự tìm máy** trước (tự quét ngay khi mở, chạm 1 lần để nhận máy Hikvision / FK).
 * - Điện thoại: 2 tab gọn (`Tự tìm máy` mặc định | `Nhập thủ công`). TV Box: 2 cột song song (Trái: Tự tìm | Phải: Nhập tay).
 */
@Composable
fun AddDeviceScreen(
    viewModel: AddDeviceViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ui = rememberDeviceUiSpec()
    var compactTab by rememberSaveable { mutableIntStateOf(0) }
    val terminalCount = state.detectedTerminals.size

    LaunchedEffect(Unit) { viewModel.savedEvent.collect { onBack() } }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TvSubScreenHeader(
            title = "Thêm & Nhận máy",
            subtitle = "Tự tìm máy trong mạng — chạm 1 lần để nhận",
            onBack = onBack,
            backEnabled = !state.isSaving,
        )

        if (ui.isTwoPane) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = ui.horizontalPadding, vertical = ui.verticalPadding),
                horizontalArrangement = Arrangement.spacedBy(ui.cardSpacing),
            ) {
                AddDeviceLanPane(
                    state = state,
                    onScanPrefixChange = viewModel::onScanPrefixChange,
                    onStartScan = { viewModel.startLanScan() },
                    onQuickScanPrefix = { viewModel.startLanScan(it) },
                    onQuickAccept = viewModel::onQuickAcceptClicked,
                    onEditHost = viewModel::selectDiscoveredHost,
                    onToggleOtherHosts = viewModel::toggleOtherHosts,
                    modifier = Modifier.weight(0.55f).fillMaxHeight(),
                )
                AddDeviceFormPane(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.weight(0.45f).fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ui.horizontalPadding, vertical = ui.verticalPadding),
                verticalArrangement = Arrangement.spacedBy(ui.cardSpacing),
            ) {
                TvSegmentedTabs(
                    tabs = listOf(
                        if (terminalCount > 0) "Tự tìm máy ($terminalCount)" else "Tự tìm máy",
                        "Nhập thủ công",
                    ),
                    selectedIndex = compactTab,
                    onSelect = { compactTab = it },
                )
                if (compactTab == 0) {
                    AddDeviceLanPane(
                        state = state,
                        onScanPrefixChange = viewModel::onScanPrefixChange,
                        onStartScan = { viewModel.startLanScan() },
                        onQuickScanPrefix = { viewModel.startLanScan(it) },
                        onQuickAccept = viewModel::onQuickAcceptClicked,
                        onEditHost = {
                            viewModel.selectDiscoveredHost(it)
                            compactTab = 1
                        },
                        onToggleOtherHosts = viewModel::toggleOtherHosts,
                        onSwitchToManual = { compactTab = 1 },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    AddDeviceFormPane(state = state, viewModel = viewModel, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }

    state.quickAcceptHost?.let { target ->
        QuickAcceptDeviceDialog(
            host = target,
            username = state.quickAcceptUsername,
            password = state.quickAcceptPassword,
            error = state.quickAcceptError,
            isSaving = state.isSaving,
            onUsernameChange = viewModel::onQuickAcceptUsernameChange,
            onPasswordChange = viewModel::onQuickAcceptPasswordChange,
            onConfirm = viewModel::confirmQuickAcceptDialog,
            onDismiss = viewModel::dismissQuickAcceptDialog,
        )
    }
}

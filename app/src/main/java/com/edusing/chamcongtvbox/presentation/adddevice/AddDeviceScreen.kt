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
 * Màn hình Thêm máy chấm công **Liquid Glass 3D** — 2 cột cho TV Box / Tablet, 2 tab gọn cho Điện thoại.
 */
@Composable
fun AddDeviceScreen(
    viewModel: AddDeviceViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ui = rememberDeviceUiSpec()
    var compactTab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { viewModel.savedEvent.collect { onBack() } }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TvSubScreenHeader(
            title = "Thêm máy chấm công",
            subtitle = "",
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
                AddDeviceFormPane(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.weight(0.54f).fillMaxHeight(),
                )
                AddDeviceLanPane(
                    state = state,
                    onScanPrefixChange = viewModel::onScanPrefixChange,
                    onStartScan = { viewModel.startLanScan() },
                    onQuickScanPrefix = { viewModel.startLanScan(it) },
                    onSelectHost = viewModel::selectDiscoveredHost,
                    modifier = Modifier.weight(0.46f).fillMaxHeight(),
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
                    tabs = listOf("Nhập IP", "Quét LAN (${state.discoveredHosts.size})"),
                    selectedIndex = compactTab,
                    onSelect = { compactTab = it },
                )
                if (compactTab == 0) {
                    AddDeviceFormPane(state = state, viewModel = viewModel, modifier = Modifier.fillMaxWidth().weight(1f))
                } else {
                    AddDeviceLanPane(
                        state = state,
                        onScanPrefixChange = viewModel::onScanPrefixChange,
                        onStartScan = { viewModel.startLanScan() },
                        onQuickScanPrefix = { viewModel.startLanScan(it) },
                        onSelectHost = {
                            viewModel.selectDiscoveredHost(it)
                            compactTab = 0
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

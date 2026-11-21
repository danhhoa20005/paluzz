package com.edusing.chamcongtvbox.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edusing.chamcongtvbox.presentation.components.TammiAdaptiveBar
import com.edusing.chamcongtvbox.presentation.components.TammiErrorStateCard
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiModeSwitcherCard
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.components.TvSubScreenHeader
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LocalFloatingNavBottomInset
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.rememberDeviceUiSpec
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/**
 * Màn hình Cấu hình **Liquid Glass 3D** thoáng gọn (không chồng 2 thanh đáy, không trùng nút Đổi TK):
 * - Điện thoại: cuộn xuyên sáng liền mạch dưới thanh viên thuốc đáy, nút Lưu cấu hình nằm gọn cuối danh sách.
 * - TV Box / Tablet: 2 cột cân đối + thanh Lưu nổi kính căn thẳng hàng với Sidebar.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: (() -> Unit)? = null,
    onLogoutClick: (() -> Unit)? = null,
    onModeSwitchRequireLogin: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ui = rememberDeviceUiSpec()
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val bottomInset = LocalFloatingNavBottomInset.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (ui.isTwoPane && onBack == null) Modifier.padding(bottom = ui.verticalPadding) else Modifier)
            .imePadding(),
    ) {
        if (onBack != null) TvSubScreenHeader("Cấu hình", "", onBack = onBack)

        if (!state.errorMessage.isNullOrBlank() || !state.savedBanner.isNullOrBlank()) {
            Column(modifier = Modifier.padding(horizontal = ui.horizontalPadding, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!state.errorMessage.isNullOrBlank()) TammiErrorStateCard(message = state.errorMessage.orEmpty())
                if (!state.savedBanner.isNullOrBlank()) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.GreenSoft).border(1.dp, AppColors.Green.copy(alpha = 0.28f), RoundedCornerShape(12.dp)).padding(10.dp)) {
                        Text(state.savedBanner.orEmpty(), color = AppColors.GreenText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        if (ui.isTwoPane) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = ui.horizontalPadding, vertical = ui.verticalPadding), horizontalArrangement = Arrangement.spacedBy(ui.cardSpacing)) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ui.cardSpacing)) {
                    TammiModeSwitcherCard(activeMode = state.activeMode, onSelectMode = { viewModel.switchMode(it, onModeSwitchRequireLogin) })
                    DeviceHealthCard(webConsoleUrl = state.webConsoleUrl)
                    AppUpdateCard(state.updateState, viewModel::onAutoUpdateEnabledChange, viewModel::checkAppUpdateNow, viewModel::installAppUpdateNow)
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ui.cardSpacing)) {
                    AutoPushScheduleCard(state, viewModel)
                    EssentialRuntimeCard(state, viewModel)
                    AdvancedTechCard(state, viewModel, expanded = showAdvanced, onToggle = { showAdvanced = !showAdvanced })
                }
            }
            TammiAdaptiveBar(floating = true, horizontalPadding = ui.horizontalPadding) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (onLogoutClick != null) TammiOutlinedButton("Đổi TK", onLogoutClick, compact = false, contentColor = AppColors.Red, borderColor = AppColors.Red.copy(alpha = 0.34f))
                    TvActionButton("Lưu cấu hình", AppColors.Primary, onClick = viewModel::saveSettings, modifier = Modifier.weight(1f))
                }
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = ui.horizontalPadding, vertical = ui.verticalPadding),
                verticalArrangement = Arrangement.spacedBy(ui.cardSpacing),
            ) {
                TammiModeSwitcherCard(activeMode = state.activeMode, onSelectMode = { viewModel.switchMode(it, onModeSwitchRequireLogin) })
                AutoPushScheduleCard(state, viewModel)
                AppUpdateCard(state.updateState, viewModel::onAutoUpdateEnabledChange, viewModel::checkAppUpdateNow, viewModel::installAppUpdateNow)
                DeviceHealthCard(webConsoleUrl = state.webConsoleUrl)
                EssentialRuntimeCard(state, viewModel)
                AdvancedTechCard(state, viewModel, expanded = showAdvanced, onToggle = { showAdvanced = !showAdvanced })
                TvActionButton("Lưu cấu hình", AppColors.Primary, onClick = viewModel::saveSettings, modifier = Modifier.fillMaxWidth())
                if (bottomInset.value > 0f) Spacer(Modifier.height(bottomInset))
            }
        }
    }
}

@Composable
private fun EssentialRuntimeCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    TammiGlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(AppColors.OrbCobalt))
            Text("Chu kỳ chấm công", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Text)
        }
        Spacer(Modifier.size(TammiSpacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumField("Quét máy (giây)", state.pollIntervalSec, viewModel::onPollSecChange, Modifier.weight(1f))
            NumField("Chống trùng (giây)", state.dedupSeconds, viewModel::onDedupSecChange, Modifier.weight(1f))
        }
        Spacer(Modifier.size(TammiSpacing.sm))
        SettingsCheckboxRow("Tự chạy lại app khi bật nguồn", state.autoStartOnBoot, viewModel::onAutoStartOnBootChange)
        SettingsCheckboxRow("Đọc lời chào tiếng Việt qua loa khi chấm công", state.voiceGreetingEnabled, viewModel::onVoiceGreetingChange)
    }
}

@Composable
private fun AdvancedTechCard(state: SettingsUiState, viewModel: SettingsViewModel, expanded: Boolean, onToggle: () -> Unit) {
    var toggleFocused by remember { mutableStateOf(false) }
    TammiGlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .onFocusChanged { toggleFocused = it.isFocused }
                .tvFocusRing(toggleFocused, 10)
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(AppColors.OrbViolet))
            Column(modifier = Modifier.weight(1f)) {
                Text("Thông số kỹ thuật nâng cao", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Text)
                Text("TCP/UDP • Cổng ${state.defaultPort} • Báo sống ${state.heartbeatIntervalSec}s", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            Text(text = if (expanded) "Ẩn" else "Mở", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Primary)
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = TammiSpacing.md), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var tcpFocused by remember { mutableStateOf(false) }
                    var udpFocused by remember { mutableStateOf(false) }
                    Text("Giao thức:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Text)
                    FilterChip(
                        selected = !state.udpMode,
                        onClick = { viewModel.onUdpModeChange(false) },
                        label = { Text("TCP (Chuẩn)") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppColors.Primary, selectedLabelColor = Color.White),
                        modifier = Modifier.onFocusChanged { tcpFocused = it.isFocused }.tvFocusRing(tcpFocused, 8),
                    )
                    FilterChip(
                        selected = state.udpMode,
                        onClick = { viewModel.onUdpModeChange(true) },
                        label = { Text("UDP") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppColors.Primary, selectedLabelColor = Color.White),
                        modifier = Modifier.onFocusChanged { udpFocused = it.isFocused }.tvFocusRing(udpFocused, 8),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("Cổng (5005)", state.defaultPort, viewModel::onPortChange, Modifier.weight(1f))
                    NumField("Số máy (1)", state.defaultMachineNo, viewModel::onMachineNoChange, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("Mật khẩu (0)", state.defaultPassword, viewModel::onPasswordChange, Modifier.weight(1f))
                    NumField("License (5912)", state.defaultLicense, viewModel::onLicenseChange, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("Báo sống (giây)", state.heartbeatIntervalSec, viewModel::onHeartbeatSecChange, Modifier.weight(1f))
                    NumField("Tên máy (byte)", state.maxNameBytes, viewModel::onMaxNameBytesChange, Modifier.weight(1f))
                }
                NumField("Timeout kết nối (ms)", state.connectTimeoutMs, viewModel::onTimeoutChange, Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = state.apiDomain,
                    onValueChange = viewModel::onApiDomainChange,
                    label = { Text("Địa chỉ API") },
                    singleLine = true,
                    shape = TammiRadii.inputShape,
                    colors = tammiFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        shape = TammiRadii.inputShape,
        colors = tammiFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

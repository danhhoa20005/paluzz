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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edusing.chamcongtvbox.presentation.components.LiquidGlassChip
import com.edusing.chamcongtvbox.presentation.components.TammiErrorStateCard
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiModeSwitcherCard
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiSwitchRow
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
 * Màn hình Cấu hình **Liquid Glass 3D** tinh gọn theo chuẩn Progressive Disclosure:
 * - **Cơ bản (tự lưu tức thì)**: Chế độ hệ thống, 3 công tắc hằng ngày (Loa, Tự chạy, Tự đẩy), Cập nhật & Vận hành 24/7.
 * - **Nâng cao (mặc định thu gọn)**: Khung giờ đẩy thứ 4, chu kỳ quét/chống trùng, TV Launcher & thông số kỹ thuật FK/API.
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
            Column(modifier = Modifier.padding(horizontal = ui.horizontalPadding, vertical = TammiSpacing.sm), verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                if (!state.errorMessage.isNullOrBlank()) TammiErrorStateCard(message = state.errorMessage.orEmpty())
                if (!state.savedBanner.isNullOrBlank()) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.GreenSoft).border(1.dp, AppColors.Green.copy(alpha = 0.28f), RoundedCornerShape(12.dp)).padding(TammiSpacing.md)) {
                        Text(state.savedBanner.orEmpty(), color = AppColors.GreenText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        if (ui.isTwoPane) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = ui.horizontalPadding, vertical = ui.verticalPadding), horizontalArrangement = Arrangement.spacedBy(ui.cardSpacing)) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ui.cardSpacing)) {
                    TammiModeSwitcherCard(activeMode = state.activeMode, onSelectMode = { viewModel.switchMode(it, onModeSwitchRequireLogin) })
                    DailyEssentialsCard(state, viewModel)
                    if (onLogoutClick != null) {
                        TammiOutlinedButton("Đổi tài khoản", onLogoutClick, contentColor = AppColors.Red, borderColor = AppColors.Red.copy(alpha = 0.34f), modifier = Modifier.fillMaxWidth())
                    }
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ui.cardSpacing)) {
                    AppUpdateCard(state.updateState, viewModel::onAutoUpdateEnabledChange, viewModel::checkAppUpdateNow, viewModel::installAppUpdateNow)
                    DeviceHealthCard(webConsoleUrl = state.webConsoleUrl)
                    AdvancedSettingsAccordion(state, viewModel, expanded = showAdvanced, onToggle = { showAdvanced = !showAdvanced })
                }
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = ui.horizontalPadding, vertical = ui.verticalPadding),
                verticalArrangement = Arrangement.spacedBy(ui.cardSpacing),
            ) {
                TammiModeSwitcherCard(activeMode = state.activeMode, onSelectMode = { viewModel.switchMode(it, onModeSwitchRequireLogin) })
                DailyEssentialsCard(state, viewModel)
                AppUpdateCard(state.updateState, viewModel::onAutoUpdateEnabledChange, viewModel::checkAppUpdateNow, viewModel::installAppUpdateNow)
                DeviceHealthCard(webConsoleUrl = state.webConsoleUrl)
                AdvancedSettingsAccordion(state, viewModel, expanded = showAdvanced, onToggle = { showAdvanced = !showAdvanced })
                if (bottomInset.value > 0f) Spacer(Modifier.height(bottomInset))
            }
        }
    }
}

@Composable
private fun DailyEssentialsCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    TammiGlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tiện ích hằng ngày", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Text)
            Spacer(Modifier.height(4.dp))
            TammiSwitchRow(
                title = "Đọc lời chào qua loa",
                subtitle = "Phát tiếng Việt mỗi khi có người chấm công",
                checked = state.voiceGreetingEnabled,
                onCheckedChange = viewModel::onVoiceGreetingChange,
            )
            HorizontalDivider(color = AppColors.Border.copy(alpha = 0.42f))
            TammiSwitchRow(
                title = "Tự mở khi bật nguồn",
                subtitle = "Tự khởi chạy lại sau khi cúp điện có lại",
                checked = state.autoStartOnBoot,
                onCheckedChange = viewModel::onAutoStartOnBootChange,
            )
            HorizontalDivider(color = AppColors.Border.copy(alpha = 0.42f))
            TammiSwitchRow(
                title = "Tự đẩy ${state.personLower} (06:00 • 12:00 • 20:00)",
                subtitle = if (state.autoPushEnabled) "Lịch kế tiếp: ${state.nextAutoPushText} • Chỉ đẩy người mới" else "Đang tạm tắt lịch tự động",
                checked = state.autoPushEnabled,
                onCheckedChange = viewModel::onAutoPushEnabledChange,
            )
            if (state.connectedDeviceCount > 0) {
                Spacer(Modifier.height(TammiSpacing.sm))
                TammiOutlinedButton(
                    text = if (state.isAutoPushing) "Đang đồng bộ..." else "Đẩy ${state.personLower} ngay (${state.connectedDeviceCount} máy)",
                    enabled = !state.isAutoPushing,
                    compact = true,
                    contentColor = AppColors.Primary,
                    onClick = viewModel::triggerAutoPushNow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AdvancedSettingsAccordion(state: SettingsUiState, viewModel: SettingsViewModel, expanded: Boolean, onToggle: () -> Unit) {
    var toggleFocused by remember { mutableStateOf(false) }
    TammiGlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .onFocusChanged { toggleFocused = it.isFocused }.tvFocusRing(toggleFocused, 12)
                .clickable(onClick = onToggle).padding(vertical = TammiSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Cài đặt nâng cao", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Text)
                Text("Giờ đẩy thứ 4 • Chu kỳ quét • TV Launcher • Kỹ thuật FK", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            Text(text = if (expanded) "Thu gọn" else "Mở", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = AppColors.PrimaryText)
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = TammiSpacing.md), verticalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                AutoPushCustomSlotSection(state, viewModel)
                HorizontalDivider(color = AppColors.Border.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                    NumField("Quét máy (giây)", state.pollIntervalSec, viewModel::onPollSecChange, Modifier.weight(1f))
                    NumField("Chống trùng (giây)", state.dedupSeconds, viewModel::onDedupSecChange, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                    Text("Máy FK:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Text)
                    LiquidGlassChip("TCP", selected = !state.udpMode, onClick = { viewModel.onUdpModeChange(false) })
                    LiquidGlassChip("UDP", selected = state.udpMode, onClick = { viewModel.onUdpModeChange(true) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                    NumField("Cổng FK (5005)", state.defaultPort, viewModel::onPortChange, Modifier.weight(1f))
                    NumField("Số máy (1)", state.defaultMachineNo, viewModel::onMachineNoChange, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                    NumField("Mật khẩu (0)", state.defaultPassword, viewModel::onPasswordChange, Modifier.weight(1f))
                    NumField("License (5912)", state.defaultLicense, viewModel::onLicenseChange, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                    NumField("Báo sống (giây)", state.heartbeatIntervalSec, viewModel::onHeartbeatSecChange, Modifier.weight(1f))
                    NumField("Tên máy (byte)", state.maxNameBytes, viewModel::onMaxNameBytesChange, Modifier.weight(1f))
                }
                NumField("Timeout kết nối (ms)", state.connectTimeoutMs, viewModel::onTimeoutChange, Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = state.apiDomain, onValueChange = viewModel::onApiDomainChange, label = { Text("Địa chỉ API") },
                    singleLine = true, shape = TammiRadii.inputShape, colors = tammiFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                TvActionButton("Lưu thông số nâng cao", AppColors.Primary, onClick = viewModel::saveSettings, compact = true, modifier = Modifier.fillMaxWidth())
                TvHomeLauncherAdvancedSection()
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, maxLines = 1) }, singleLine = true,
        shape = TammiRadii.inputShape, colors = tammiFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier,
    )
}

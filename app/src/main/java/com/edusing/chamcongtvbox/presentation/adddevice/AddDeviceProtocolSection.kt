package com.edusing.chamcongtvbox.presentation.adddevice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.presentation.components.LiquidGlassChip
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Hai thẻ lớn chọn nhanh dòng máy chấm công (Hikvision / FK) — rõ ràng, chạm 1 lần. */
@Composable
internal fun ProtocolPickerCards(
    selected: DeviceProtocol,
    onSelect: (DeviceProtocol) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
        ProtocolOptionCard(
            title = "Hikvision",
            subtitle = "Mặt / Thẻ • Cổng 80",
            selected = selected == DeviceProtocol.HIKVISION_ISAPI,
            onClick = { onSelect(DeviceProtocol.HIKVISION_ISAPI) },
            modifier = Modifier.weight(1f),
        )
        ProtocolOptionCard(
            title = "Máy FK",
            subtitle = "Chuẩn LAN • Cổng 5005",
            selected = selected == DeviceProtocol.FK,
            onClick = { onSelect(DeviceProtocol.FK) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProtocolOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .clip(TammiRadii.inputShape)
            .background(if (selected) AppColors.PrimarySoft else AppColors.SurfaceSubtle)
            .border(if (selected) 1.6.dp else 1.dp, if (selected) AppColors.PrimaryText else AppColors.Border, TammiRadii.inputShape)
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, 16)
            .semantics { role = Role.Tab; this.selected = selected }
            .clickable(onClick = onClick)
            .padding(horizontal = TammiSpacing.md, vertical = TammiSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(TammiSpacing.xs),
    ) {
        Text(
            text = if (selected) "$title ✓" else title,
            fontWeight = FontWeight.Black,
            fontSize = 14.5.sp,
            color = if (selected) AppColors.PrimaryText else AppColors.Text,
        )
        Text(subtitle, fontSize = 12.sp, color = AppColors.TextSecondary)
    }
}

/** Khu vực tuỳ chọn kỹ thuật nâng cao (mặc định thu gọn để giữ biểu mẫu siêu đơn giản cho người dùng phổ thông). */
@Composable
internal fun AddDeviceAdvancedSection(
    state: AddDeviceUiState,
    viewModel: AddDeviceViewModel,
) {
    val fieldColors = tammiFieldColors()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TammiRadii.inputShape)
            .background(AppColors.SurfaceSubtle)
            .border(1.dp, AppColors.Border, TammiRadii.inputShape)
            .padding(TammiSpacing.md),
        verticalArrangement = Arrangement.spacedBy(TammiSpacing.md),
    ) {
        if (state.protocol == DeviceProtocol.HIKVISION_ISAPI) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                OutlinedTextField(
                    value = state.hikUsername,
                    onValueChange = viewModel::onHikUsernameChange,
                    label = { Text("Tài khoản (admin)") },
                    singleLine = true,
                    shape = TammiRadii.inputShape,
                    colors = fieldColors,
                    modifier = Modifier.weight(1.2f),
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChange,
                    label = { Text("Cổng") },
                    singleLine = true,
                    shape = TammiRadii.inputShape,
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                Text("Kênh:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Text)
                LiquidGlassChip("HTTP (80)", selected = !state.useHttps, onClick = { viewModel.onUseHttpsChange(false) })
                LiquidGlassChip("HTTPS (443)", selected = state.useHttps, onClick = { viewModel.onUseHttpsChange(true) })
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                OutlinedTextField(
                    value = state.port, onValueChange = viewModel::onPortChange, label = { Text("Cổng") },
                    singleLine = true, shape = TammiRadii.inputShape, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.machineNo, onValueChange = viewModel::onMachineNoChange, label = { Text("Số máy") },
                    singleLine = true, shape = TammiRadii.inputShape, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.9f),
                )
                OutlinedTextField(
                    value = state.commPassword, onValueChange = viewModel::onCommPasswordChange, label = { Text("MK (0)") },
                    singleLine = true, shape = TammiRadii.inputShape, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.9f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                Text("Truyền:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Text)
                LiquidGlassChip("TCP (Chuẩn)", selected = !state.useUdp, onClick = { viewModel.onUseUdpChange(false) })
                LiquidGlassChip("UDP", selected = state.useUdp, onClick = { viewModel.onUseUdpChange(true) })
            }
        }

        state.ipSuggestion?.let { sug ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TammiRadii.inputShape)
                    .background(AppColors.GreenSoft)
                    .border(1.dp, AppColors.Green.copy(alpha = 0.28f), TammiRadii.inputShape)
                    .padding(horizontal = TammiSpacing.md, vertical = TammiSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("IP tĩnh trống gợi ý: ${sug.suggestedIp}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = AppColors.GreenText)
                    Text("Mask ${sug.subnetMask} • GW ${sug.gateway}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AppColors.GreenText)
                }
                TammiOutlinedButton(
                    text = "Đổi", compact = true, enabled = !state.isLoadingSuggestion,
                    contentColor = AppColors.GreenText, onClick = viewModel::suggestNextFreeIp,
                )
            }
        }
    }
}

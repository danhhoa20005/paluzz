package com.edusing.chamcongtvbox.presentation.adddevice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.presentation.components.TammiErrorStateCard
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOfflineBanner
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Biểu mẫu thêm máy chấm công **Liquid Glass 3D** thuần màu & chữ chuẩn WCAG AAA. */
@Composable
internal fun AddDeviceFormPane(
    state: AddDeviceUiState,
    viewModel: AddDeviceViewModel,
    modifier: Modifier = Modifier,
) {
    val fieldColors = tammiFieldColors()
    TammiGlassPanel(modifier = modifier, padding = PaddingValues(TammiSpacing.lg)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AppColors.OrbCobalt))
                Text("Thông số kết nối", fontWeight = FontWeight.Black, fontSize = 16.sp, color = AppColors.Text)
            }

            if (!state.errorMessage.isNullOrBlank()) TammiErrorStateCard(message = state.errorMessage.orEmpty())
            if (!state.infoMessage.isNullOrBlank()) TammiOfflineBanner(message = state.infoMessage.orEmpty())

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.ip,
                    onValueChange = viewModel::onIpChange,
                    label = { Text("Địa chỉ IP *") },
                    placeholder = { Text("192.168.1.224") },
                    singleLine = true,
                    shape = TammiRadii.inputShape,
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1.45f),
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChange,
                    label = { Text("Cổng") },
                    singleLine = true,
                    shape = TammiRadii.inputShape,
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.55f),
                )
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Tên máy (VD: Cổng chính, Tầng 1)") },
                singleLine = true,
                shape = TammiRadii.inputShape,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.machineNo,
                    onValueChange = viewModel::onMachineNoChange,
                    label = { Text("Số máy (1)") },
                    singleLine = true,
                    shape = TammiRadii.inputShape,
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.commPassword,
                    onValueChange = viewModel::onCommPasswordChange,
                    label = { Text("Mật khẩu (0)") },
                    singleLine = true,
                    shape = TammiRadii.inputShape,
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var tcpFocused by remember { mutableStateOf(false) }
                var udpFocused by remember { mutableStateOf(false) }
                Text("Giao thức:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Text)
                FilterChip(
                    selected = !state.useUdp,
                    onClick = { viewModel.onUseUdpChange(false) },
                    label = { Text("TCP (Chuẩn)") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppColors.Primary, selectedLabelColor = Color.White),
                    modifier = Modifier.onFocusChanged { tcpFocused = it.isFocused }.tvFocusRing(tcpFocused, 8),
                )
                FilterChip(
                    selected = state.useUdp,
                    onClick = { viewModel.onUseUdpChange(true) },
                    label = { Text("UDP") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppColors.Primary, selectedLabelColor = Color.White),
                    modifier = Modifier.onFocusChanged { udpFocused = it.isFocused }.tvFocusRing(udpFocused, 8),
                )
            }

            state.ipSuggestion?.let { sug ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(TammiRadii.inputShape)
                        .background(AppColors.GreenSoft)
                        .border(1.dp, AppColors.Green.copy(alpha = 0.30f), TammiRadii.inputShape)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Gợi ý IP tĩnh trống", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = AppColors.GreenText)
                        TammiOutlinedButton(
                            text = "Đổi IP",
                            enabled = !state.isLoadingSuggestion,
                            compact = true,
                            contentColor = AppColors.GreenText,
                            borderColor = AppColors.Green.copy(alpha = 0.35f),
                            onClick = viewModel::suggestNextFreeIp,
                        )
                    }
                    Text("IP: ${sug.suggestedIp}  •  Cổng: ${sug.port}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.GreenText)
                    Text("Mask: ${sug.subnetMask}  •  GW: ${sug.gateway}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AppColors.GreenText)
                }
            }

            if (state.isSaving) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.8.dp, color = AppColors.Primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Đang nối thử máy chấm công...", fontWeight = FontWeight.Bold, color = AppColors.Primary)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvActionButton(
                        text = "Kết nối & Lưu",
                        containerColor = AppColors.Green,
                        onClick = { viewModel.saveDevice(verifyFirst = true) },
                        modifier = Modifier.weight(1.3f),
                    )
                    TammiOutlinedButton(
                        text = "Lưu luôn",
                        onClick = { viewModel.saveDevice(verifyFirst = false) },
                        modifier = Modifier.weight(0.75f),
                    )
                }
            }
        }
    }
}

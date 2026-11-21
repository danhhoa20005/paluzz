package com.edusing.chamcongtvbox.presentation.adddevice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Bộ chọn dòng máy (FK cổng 5005 / Hikvision DS-K1T320MX cổng 80) và thông số xác thực tương ứng. */
@Composable
internal fun AddDeviceProtocolSection(
    state: AddDeviceUiState,
    viewModel: AddDeviceViewModel,
) {
    val fieldColors = tammiFieldColors()
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = AppColors.Primary,
        selectedLabelColor = Color.White,
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var fkFocused by remember { mutableStateOf(false) }
            var hikFocused by remember { mutableStateOf(false) }
            Text("Dòng máy:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Text)
            FilterChip(
                selected = state.protocol == DeviceProtocol.FK,
                onClick = { viewModel.onProtocolChange(DeviceProtocol.FK) },
                label = { Text("FK (5005)") },
                colors = chipColors,
                modifier = Modifier.onFocusChanged { fkFocused = it.isFocused }.tvFocusRing(fkFocused, 8),
            )
            FilterChip(
                selected = state.protocol == DeviceProtocol.HIKVISION_ISAPI,
                onClick = { viewModel.onProtocolChange(DeviceProtocol.HIKVISION_ISAPI) },
                label = { Text("Hikvision (Mọi dòng)") },
                colors = chipColors,
                modifier = Modifier.onFocusChanged { hikFocused = it.isFocused }.tvFocusRing(hikFocused, 8),
            )
        }

        if (state.protocol == DeviceProtocol.HIKVISION_ISAPI) {
            var showPassword by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.hikUsername,
                    onValueChange = viewModel::onHikUsernameChange,
                    label = { Text("Tài khoản (admin)") },
                    singleLine = true,
                    shape = TammiRadii.inputShape,
                    colors = fieldColors,
                    modifier = Modifier.weight(0.85f),
                )
                OutlinedTextField(
                    value = state.hikPassword,
                    onValueChange = viewModel::onHikPasswordChange,
                    label = { Text("Mật khẩu thiết bị *") },
                    singleLine = true,
                    shape = TammiRadii.inputShape,
                    colors = fieldColors,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.weight(1.15f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var httpFocused by remember { mutableStateOf(false) }
                var httpsFocused by remember { mutableStateOf(false) }
                Text("Kênh ISAPI:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Text)
                FilterChip(
                    selected = !state.useHttps,
                    onClick = { viewModel.onUseHttpsChange(false) },
                    label = { Text("HTTP (80)") },
                    colors = chipColors,
                    modifier = Modifier.onFocusChanged { httpFocused = it.isFocused }.tvFocusRing(httpFocused, 8),
                )
                FilterChip(
                    selected = state.useHttps,
                    onClick = { viewModel.onUseHttpsChange(true) },
                    label = { Text("HTTPS (443)") },
                    colors = chipColors,
                    modifier = Modifier.onFocusChanged { httpsFocused = it.isFocused }.tvFocusRing(httpsFocused, 8),
                )
                TammiOutlinedButton(
                    text = if (showPassword) "Ẩn MK" else "Hiện MK",
                    compact = true,
                    onClick = { showPassword = !showPassword },
                )
            }
            Text(
                text = "Hỗ trợ mọi dòng Hikvision ISAPI (DS-K1T320/341/343/671/804...): quét SADP tự tìm mọi mã máy, đồng bộ giờ VN & đẩy tên tiếng Việt đầy đủ.",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TammiRadii.inputShape)
                    .background(AppColors.PrimarySoft)
                    .border(1.dp, AppColors.Primary.copy(alpha = 0.25f), TammiRadii.inputShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
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
                    colors = chipColors,
                    modifier = Modifier.onFocusChanged { tcpFocused = it.isFocused }.tvFocusRing(tcpFocused, 8),
                )
                FilterChip(
                    selected = state.useUdp,
                    onClick = { viewModel.onUseUdpChange(true) },
                    label = { Text("UDP") },
                    colors = chipColors,
                    modifier = Modifier.onFocusChanged { udpFocused = it.isFocused }.tvFocusRing(udpFocused, 8),
                )
            }
        }
    }
}

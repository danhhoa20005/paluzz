package com.edusing.chamcongtvbox.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii

/**
 * Hộp thoại sửa trực tiếp thông số máy chấm công đã lưu (Tên, IP, Cổng, Mật khẩu Hikvision / HTTPS hoặc Số máy / UDP)
 * mà không phải xoá máy rồi thêm lại từ đầu.
 */
@Composable
fun EditDeviceDialog(
    device: DeviceConfig,
    onConfirm: (DeviceConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(device.key) { mutableStateOf(device.name) }
    var ip by remember(device.key) { mutableStateOf(device.ip) }
    var portText by remember(device.key) { mutableStateOf(device.port.toString()) }
    var username by remember(device.key) { mutableStateOf(device.username) }
    var accessPassword by remember(device.key) { mutableStateOf(device.accessPassword) }
    var useHttps by remember(device.key) { mutableStateOf(device.useHttps) }
    var machineNo by remember(device.key) { mutableStateOf(device.machineNo.toString()) }
    var useUdp by remember(device.key) { mutableStateOf(device.useUdp) }
    var showPass by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val fieldColors = tammiFieldColors()
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = AppColors.Primary, selectedLabelColor = Color.White)

    Dialog(onDismissRequest = onDismiss) {
        TammiGlassPanel(modifier = Modifier.fillMaxWidth(), borderColor = AppColors.Primary.copy(alpha = 0.35f)) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (device.isHikvision) "Sửa máy Hikvision" else "Sửa máy chấm công FK",
                    fontSize = 17.sp, fontWeight = FontWeight.Black, color = AppColors.Text,
                )
                if (!error.isNullOrBlank()) TammiErrorStateCard(message = error.orEmpty())

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Tên máy (VD: Cổng chính)") }, singleLine = true,
                    shape = TammiRadii.inputShape, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it.trim(); error = null },
                        label = { Text("Địa chỉ IP *") }, singleLine = true,
                        shape = TammiRadii.inputShape, colors = fieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1.45f),
                    )
                    OutlinedTextField(
                        value = portText, onValueChange = { portText = it.filter(Char::isDigit); error = null },
                        label = { Text("Cổng") }, singleLine = true,
                        shape = TammiRadii.inputShape, colors = fieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.65f),
                    )
                }

                if (device.isHikvision) {
                    OutlinedTextField(
                        value = username, onValueChange = { username = it.trim() },
                        label = { Text("Tài khoản (admin)") }, singleLine = true,
                        shape = TammiRadii.inputShape, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = accessPassword, onValueChange = { accessPassword = it; error = null },
                            label = { Text("Mật khẩu thiết bị *") }, singleLine = true,
                            shape = TammiRadii.inputShape, colors = fieldColors,
                            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f),
                        )
                        TammiOutlinedButton(if (showPass) "Ẩn" else "Hiện", compact = true, onClick = { showPass = !showPass })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Kênh:", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppColors.Text)
                        FilterChip(selected = !useHttps, onClick = { useHttps = false; portText = "80" }, label = { Text("HTTP (80)") }, colors = chipColors)
                        FilterChip(selected = useHttps, onClick = { useHttps = true; portText = "443" }, label = { Text("HTTPS (443)") }, colors = chipColors)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = machineNo, onValueChange = { machineNo = it.filter(Char::isDigit) },
                            label = { Text("Số máy") }, singleLine = true,
                            shape = TammiRadii.inputShape, colors = fieldColors,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(selected = !useUdp, onClick = { useUdp = false }, label = { Text("TCP") }, colors = chipColors)
                        FilterChip(selected = useUdp, onClick = { useUdp = true }, label = { Text("UDP") }, colors = chipColors)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TammiOutlinedButton("Huỷ", onClick = onDismiss, modifier = Modifier.weight(0.75f))
                    TvActionButton(
                        text = "Lưu & Nối lại",
                        containerColor = AppColors.Green,
                        onClick = {
                            val parts = ip.trim().split('.')
                            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                            when {
                                parts.size != 4 || !parts.all { it.toIntOrNull() in 0..255 } -> error = "Địa chỉ IP không hợp lệ."
                                port == null -> error = "Cổng không hợp lệ (1–65535)."
                                device.isHikvision && accessPassword.isBlank() -> error = "Thiếu mật khẩu thiết bị Hikvision."
                                else -> onConfirm(
                                    device.copy(
                                        name = name.trim(), ip = ip.trim(), port = port,
                                        username = username.trim().ifBlank { DeviceConfig.DEFAULT_HIK_USERNAME },
                                        accessPassword = accessPassword, useHttps = useHttps,
                                        machineNo = machineNo.toIntOrNull()?.coerceAtLeast(1) ?: device.machineNo,
                                        useUdp = useUdp,
                                    )
                                )
                            }
                        },
                        modifier = Modifier.weight(1.25f),
                    )
                }
            }
        }
    }
}

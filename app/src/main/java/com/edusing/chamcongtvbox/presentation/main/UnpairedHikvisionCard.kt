package com.edusing.chamcongtvbox.presentation.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.HikvisionSadpDevice
import com.edusing.chamcongtvbox.presentation.components.TammiErrorStateCard
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing

/**
 * Thẻ tự dò máy Hikvision hiện ngay trên Trạm trực khi phát hiện máy mới qua SADP/TCP-80 mà chưa có mật khẩu thiết bị:
 * chỉ cần nhập mật khẩu 1 lần ngay tại đây để tự động nhận & kết nối đồng loạt toàn bộ máy Hikvision trong mạng.
 */
@Composable
internal fun UnpairedHikvisionCard(
    devices: List<HikvisionSadpDevice>,
    isPairing: Boolean,
    pairError: String?,
    onPairAll: (username: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (devices.isEmpty()) return
    var showDialog by remember { mutableStateOf(false) }

    TammiGlassPanel(
        modifier = modifier.fillMaxWidth(),
        padding = PaddingValues(TammiSpacing.md),
        borderColor = AppColors.Green.copy(alpha = 0.45f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AppColors.GreenBright))
                Text(
                    text = "Tự dò thấy ${devices.size} máy Hikvision trong mạng!",
                    fontWeight = FontWeight.Black,
                    fontSize = 14.5.sp,
                    color = AppColors.GreenText,
                )
            }
            devices.forEach { d ->
                Text(
                    text = "• ${d.model} — ${d.ip}:${d.httpPort}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.Text,
                )
            }
            Text(
                text = "Máy Hikvision cần mật khẩu thiết bị 1 lần đầu tiên (sau đó tự nối vĩnh viễn).",
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
            )
            if (!pairError.isNullOrBlank()) TammiErrorStateCard(message = pairError)
            TvActionButton(
                text = if (isPairing) "Đang kết nối..." else "Nhập mật khẩu 1 lần & Nhận tất cả",
                containerColor = AppColors.Green,
                enabled = !isPairing,
                compact = true,
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDialog) {
        PairHikvisionModal(
            devices = devices,
            isPairing = isPairing,
            error = pairError,
            onConfirm = { u, p -> onPairAll(u, p) },
            onDismiss = { if (!isPairing) showDialog = false },
        )
    }
}

@Composable
private fun PairHikvisionModal(
    devices: List<HikvisionSadpDevice>,
    isPairing: Boolean,
    error: String?,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember { mutableStateOf(DeviceConfig.DEFAULT_HIK_USERNAME) }
    var password by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var showUser by remember { mutableStateOf(false) }
    val colors = tammiFieldColors()

    Dialog(onDismissRequest = onDismiss) {
        TammiGlassPanel(modifier = Modifier.fillMaxWidth(), borderColor = AppColors.Green.copy(alpha = 0.42f), tier = LiquidGlassTier.CHROME) {
            Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                Text("Nhận ${devices.size} máy Hikvision tự động", fontSize = 17.sp, fontWeight = FontWeight.Black, color = AppColors.Text)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(TammiRadii.inputShape)
                        .background(AppColors.GreenSoft)
                        .border(1.dp, AppColors.Green.copy(alpha = 0.30f), TammiRadii.inputShape)
                        .padding(TammiSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(TammiSpacing.xs),
                ) {
                    devices.forEach { d ->
                        Text("${d.model} (${d.ip}:${d.httpPort})", fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = AppColors.GreenText)
                    }
                }

                if (!error.isNullOrBlank()) TammiErrorStateCard(message = error)

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mật khẩu thiết bị Hikvision *") },
                    singleLine = true,
                    enabled = !isPairing,
                    shape = TammiRadii.inputShape,
                    colors = colors,
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TammiOutlinedButton(if (showPass) "Ẩn MK" else "Hiện MK", compact = true, enabled = !isPairing, onClick = { showPass = !showPass })
                    TammiOutlinedButton(if (showUser) "Ẩn tài khoản" else "Đổi tài khoản ($username)", compact = true, enabled = !isPairing, onClick = { showUser = !showUser })
                }
                if (showUser) {
                    OutlinedTextField(
                        value = username, onValueChange = { username = it.trim() },
                        label = { Text("Tài khoản (admin)") }, singleLine = true, enabled = !isPairing,
                        shape = TammiRadii.inputShape, colors = colors, modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (isPairing) {
                    Row(Modifier.fillMaxWidth().padding(vertical = TammiSpacing.sm), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.4.dp, color = AppColors.Green)
                        Spacer(Modifier.width(8.dp))
                        Text("Đang kết nối & lưu máy...", fontWeight = FontWeight.Bold, color = AppColors.GreenText)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TammiOutlinedButton("Huỷ", onClick = onDismiss, modifier = Modifier.weight(0.75f))
                        TvActionButton(
                            text = "Kết nối & Nhận ngay",
                            containerColor = AppColors.Green,
                            enabled = password.isNotBlank(),
                            onClick = { onConfirm(username, password) },
                            modifier = Modifier.weight(1.25f),
                        )
                    }
                }
            }
        }
    }
}

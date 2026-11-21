package com.edusing.chamcongtvbox.presentation.adddevice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.presentation.components.TammiErrorStateCard
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing

/**
 * Hộp thoại gọn 1 bước khi nhận máy Hikvision lần đầu: chỉ cần nhập mật khẩu thiết bị 1 lần,
 * tự nhớ mã hoá AES-256 để mọi lần sau tự động nhận máy trong 1 chạm.
 */
@Composable
internal fun QuickAcceptDeviceDialog(
    host: LanHostInfo,
    username: String,
    password: String,
    error: String?,
    isSaving: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showPassword by remember { mutableStateOf(false) }
    var showUserField by remember { mutableStateOf(false) }
    val fieldColors = tammiFieldColors()

    Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
        TammiGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            borderColor = AppColors.Primary.copy(alpha = 0.35f),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Nhận máy chấm công Hikvision", fontSize = 17.sp, fontWeight = FontWeight.Black, color = AppColors.Text)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(TammiRadii.inputShape)
                        .background(AppColors.PrimarySoft)
                        .border(1.dp, AppColors.Primary.copy(alpha = 0.25f), TammiRadii.inputShape)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(host.modelHint.ifBlank { "Hikvision ISAPI" }, fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, color = AppColors.Primary)
                    Text("${host.ip}:${host.port} • Tài khoản: $username", fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = AppColors.TextSecondary)
                }

                if (!error.isNullOrBlank()) TammiErrorStateCard(message = error)

                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Mật khẩu thiết bị Hikvision *") },
                    singleLine = true,
                    enabled = !isSaving,
                    shape = TammiRadii.inputShape,
                    colors = fieldColors,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TammiOutlinedButton(
                        text = if (showPassword) "Ẩn MK" else "Hiện MK",
                        compact = true,
                        enabled = !isSaving,
                        onClick = { showPassword = !showPassword },
                    )
                    TammiOutlinedButton(
                        text = if (showUserField) "Ẩn tài khoản" else "Đổi tài khoản",
                        compact = true,
                        enabled = !isSaving,
                        onClick = { showUserField = !showUserField },
                    )
                }

                if (showUserField) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = onUsernameChange,
                        label = { Text("Tên đăng nhập (mặc định: admin)") },
                        singleLine = true,
                        enabled = !isSaving,
                        shape = TammiRadii.inputShape,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    text = "Chỉ cần nhập 1 lần — App sẽ tự nhớ an toàn để các lần sau tự động nhận máy.",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                )

                Spacer(Modifier.height(TammiSpacing.xs))

                if (isSaving) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.6.dp, color = AppColors.Green)
                        Spacer(Modifier.width(10.dp))
                        Text("Đang kết nối & nhận máy...", fontWeight = FontWeight.Bold, color = AppColors.GreenText)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TammiOutlinedButton(text = "Huỷ", onClick = onDismiss, modifier = Modifier.weight(0.75f))
                        TvActionButton(
                            text = "Kết nối & Nhận",
                            containerColor = AppColors.Green,
                            onClick = onConfirm,
                            modifier = Modifier.weight(1.25f),
                        )
                    }
                }
            }
        }
    }
}

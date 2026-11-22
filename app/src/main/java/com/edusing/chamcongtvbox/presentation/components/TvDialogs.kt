package com.edusing.chamcongtvbox.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing

/**
 * Hộp thoại xác nhận an toàn **Chrome Liquid Glass 3D** (không dùng `AlertDialog` phẳng mặc định,
 * đặt focus D-Pad mặc định vào nút Huỷ để bảo vệ thao tác phá huỷ).
 */
@Composable
fun TvSafeConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    cancelText: String = "Không",
    confirmColor: Color = AppColors.Red,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TammiGlassPanel(
            modifier = Modifier.fillMaxWidth(0.90f),
            padding = PaddingValues(TammiSpacing.xl),
            borderColor = confirmColor.copy(alpha = 0.36f),
            tier = LiquidGlassTier.CHROME,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                Text(text = title, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Text)
                Text(text = message, fontSize = 14.sp, color = AppColors.TextSecondary, lineHeight = 20.sp)
                Spacer(Modifier.height(TammiSpacing.xs))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                    TammiOutlinedButton(
                        text = cancelText,
                        onClick = onDismiss,
                        compact = true,
                        contentColor = AppColors.Text,
                        modifier = Modifier.weight(1f).focusRequester(cancelFocus),
                    )
                    TvActionButton(
                        text = confirmText,
                        containerColor = confirmColor,
                        onClick = onConfirm,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Hộp thoại đặt/sửa tên hiển thị cho máy chấm công dạng **Chrome Liquid Glass 3D**. */
@Composable
fun RenameDeviceDialog(
    currentName: String,
    deviceIp: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TammiGlassPanel(
            modifier = Modifier.fillMaxWidth(0.90f),
            padding = PaddingValues(TammiSpacing.xl),
            tier = LiquidGlassTier.CHROME,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                Text("Đổi tên máy chấm công", fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Text)
                Text("IP: $deviceIp", color = AppColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên hiển thị (VD: Cổng chính)") },
                    singleLine = true,
                    shape = TammiRadii.inputShape,
                    colors = tammiFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                    TammiOutlinedButton(text = "Huỷ", onClick = onDismiss, compact = true, contentColor = AppColors.Text, modifier = Modifier.weight(1f))
                    TvActionButton(text = "Lưu tên", containerColor = AppColors.Primary, onClick = { onConfirm(name) }, compact = true, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

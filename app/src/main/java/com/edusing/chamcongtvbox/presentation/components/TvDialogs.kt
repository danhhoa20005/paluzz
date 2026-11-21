package com.edusing.chamcongtvbox.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing

/**
 * Hộp thoại xác nhận an toàn Liquid Glass 3D (không icon thừa, đặt focus mặc định vào nút Huỷ).
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Card,
        shape = TammiRadii.panelShape,
        title = { Text(text = title, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Text) },
        text = { Text(text = message, fontSize = 14.sp, color = AppColors.TextSecondary, lineHeight = 20.sp) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                TammiOutlinedButton(
                    text = cancelText,
                    onClick = onDismiss,
                    compact = true,
                    contentColor = AppColors.Text,
                    modifier = Modifier.focusRequester(cancelFocus),
                )
                TvActionButton(text = confirmText, containerColor = confirmColor, onClick = onConfirm, compact = true)
            }
        },
    )
}

/** Hộp thoại đặt/sửa tên hiển thị cho máy chấm công (VD: Cổng chính, Tầng 1...). */
@Composable
fun RenameDeviceDialog(
    currentName: String,
    deviceIp: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Card,
        shape = TammiRadii.panelShape,
        title = { Text("Đổi tên máy chấm công", fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm), horizontalAlignment = Alignment.Start) {
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
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                TammiOutlinedButton(text = "Huỷ", onClick = onDismiss, compact = true, contentColor = AppColors.Text)
                TvActionButton(text = "Lưu tên", containerColor = AppColors.Primary, onClick = { onConfirm(name) }, compact = true)
            }
        },
    )
}

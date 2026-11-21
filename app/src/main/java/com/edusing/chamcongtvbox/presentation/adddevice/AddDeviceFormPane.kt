package com.edusing.chamcongtvbox.presentation.adddevice

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.DeviceProtocol
import com.edusing.chamcongtvbox.presentation.components.TammiErrorStateCard
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOfflineBanner
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing

/** Biểu mẫu nhập IP thủ công siêu gọn (chỉ 2–3 ô chính; mọi thông số kỹ thuật ẩn trong Tuỳ chọn nâng cao). */
@Composable
internal fun AddDeviceFormPane(
    state: AddDeviceUiState,
    viewModel: AddDeviceViewModel,
    modifier: Modifier = Modifier,
) {
    val fieldColors = tammiFieldColors()
    var showPassword by remember { mutableStateOf(false) }

    TammiGlassPanel(modifier = modifier, padding = PaddingValues(TammiSpacing.lg)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AppColors.OrbCobalt))
                Text("Nhập IP thủ công", fontWeight = FontWeight.Black, fontSize = 15.5.sp, color = AppColors.Text)
            }

            if (!state.errorMessage.isNullOrBlank()) TammiErrorStateCard(message = state.errorMessage.orEmpty())
            if (!state.infoMessage.isNullOrBlank()) TammiOfflineBanner(message = state.infoMessage.orEmpty())

            ProtocolPickerCards(selected = state.protocol, onSelect = viewModel::onProtocolChange)

            OutlinedTextField(
                value = state.ip,
                onValueChange = viewModel::onIpChange,
                label = { Text("Địa chỉ IP hoặc dán link web *") },
                placeholder = { Text("192.168.1.8") },
                singleLine = true,
                shape = TammiRadii.inputShape,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.protocol == DeviceProtocol.HIKVISION_ISAPI) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.hikPassword,
                        onValueChange = viewModel::onHikPasswordChange,
                        label = { Text("Mật khẩu thiết bị Hikvision *") },
                        singleLine = true,
                        shape = TammiRadii.inputShape,
                        colors = fieldColors,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                    TammiOutlinedButton(
                        text = if (showPassword) "Ẩn" else "Hiện",
                        compact = true,
                        onClick = { showPassword = !showPassword },
                    )
                }
                if (state.hasSavedHikPassword && state.hikPassword.isNotBlank()) {
                    Text(
                        text = "✓ Đã tự điền tài khoản Hikvision lưu sẵn (${state.hikUsername})",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.GreenText,
                    )
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Tên máy (tuỳ chọn — VD: Cổng chính)") },
                singleLine = true,
                shape = TammiRadii.inputShape,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            TammiOutlinedButton(
                text = if (state.showAdvanced) "Thu gọn tuỳ chọn nâng cao ▲"
                else "Tuỳ chọn nâng cao (Cổng ${state.port}, IP tĩnh...) ▾",
                compact = true,
                onClick = viewModel::toggleAdvanced,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.showAdvanced) {
                AddDeviceAdvancedSection(state = state, viewModel = viewModel)
            }

            if (state.isSaving) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.8.dp, color = AppColors.Primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Đang kết nối & nhận máy...", fontWeight = FontWeight.Bold, color = AppColors.Primary)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvActionButton(
                        text = "Kết nối & Nhận máy",
                        containerColor = AppColors.Green,
                        onClick = { viewModel.saveDevice(verifyFirst = true) },
                        modifier = Modifier.weight(1.35f),
                    )
                    TammiOutlinedButton(
                        text = "Lưu luôn",
                        onClick = { viewModel.saveDevice(verifyFirst = false) },
                        modifier = Modifier.weight(0.7f),
                    )
                }
            }
        }
    }
}

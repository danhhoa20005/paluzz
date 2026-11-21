package com.edusing.chamcongtvbox.presentation.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiErrorStateCard
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOfflineBanner
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.components.orbColor
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Thẻ đăng nhập **Liquid Glass 3D** gọn gàng, thuần màu sắc & chữ đạt chuẩn WCAG AAA. */
@Composable
internal fun LoginFormCard(
    state: LoginUiState,
    viewModel: LoginViewModel,
    isCompact: Boolean,
    modifier: Modifier = Modifier,
) {
    var showPassword by remember { mutableStateOf(false) }
    val fieldColors = tammiFieldColors()
    val accent = state.activeMode.orbColor()

    TammiGlassPanel(
        modifier = modifier,
        padding = PaddingValues(if (isCompact) TammiSpacing.lg else TammiSpacing.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(accent))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isCompact) state.activeMode.shortLabel else "Đăng nhập",
                    fontSize = if (isCompact) 17.sp else 19.sp,
                    fontWeight = FontWeight.Black,
                    color = AppColors.Text,
                )
                Text(
                    text = "Tài khoản quản trị ${state.orgLabel.lowercase()} trên web tổng",
                    fontSize = 12.5.sp,
                    color = AppColors.TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(TammiSpacing.md))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!state.noticeBanner.isNullOrBlank()) {
                TammiOfflineBanner(message = state.noticeBanner.orEmpty())
                if (state.isAutoLoggingIn) {
                    TammiOutlinedButton(
                        text = "Huỷ tự đăng nhập",
                        compact = true,
                        onClick = viewModel::cancelAutoLogin,
                        contentColor = AppColors.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (!state.errorMessage.isNullOrBlank()) TammiErrorStateCard(message = state.errorMessage.orEmpty(), onRetry = viewModel::submitLogin)
            if (!state.serverTestResult.isNullOrBlank()) {
                val ok = state.serverTestResult.startsWith("Kết nối OK")
                TammiStatusPill(
                    label = state.serverTestResult,
                    tone = if (ok) TammiBadgeTone.SUCCESS else TammiBadgeTone.WARNING,
                )
            }

            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("Tên đăng nhập") },
                singleLine = true,
                shape = TammiRadii.inputShape,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Mật khẩu") },
                singleLine = true,
                shape = TammiRadii.inputShape,
                colors = fieldColors,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    var pwToggleFocused by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { showPassword = !showPassword },
                        modifier = Modifier.onFocusChanged { pwToggleFocused = it.isFocused }.tvFocusRing(pwToggleFocused, 10),
                    ) {
                        Text(
                            text = if (showPassword) "Ẩn" else "Hiện",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = AppColors.Primary,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.submitLogin() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                var rememberFocused by remember { mutableStateOf(false) }
                var serverFocused by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .onFocusChanged { rememberFocused = it.isFocused }
                        .tvFocusRing(rememberFocused, 10)
                        .clickable { viewModel.onRememberChange(!state.rememberAccount) },
                ) {
                    Checkbox(checked = state.rememberAccount, onCheckedChange = viewModel::onRememberChange)
                    Text("Lưu tài khoản & mật khẩu (Tự đăng nhập)", fontSize = 12.5.sp, color = AppColors.Text, fontWeight = FontWeight.Medium)
                }
                TextButton(
                    onClick = viewModel::toggleAdvancedServer,
                    modifier = Modifier.onFocusChanged { serverFocused = it.isFocused }.tvFocusRing(serverFocused, 10),
                ) {
                    Text(
                        text = if (state.showAdvancedServer) "Ẩn máy chủ" else "Máy chủ",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = AppColors.Primary,
                    )
                }
            }

            if (state.showAdvancedServer || !state.errorMessage.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                    if (state.showAdvancedServer) {
                        OutlinedTextField(
                            value = state.apiDomain,
                            onValueChange = viewModel::onApiDomainChange,
                            label = { Text("Địa chỉ API") },
                            singleLine = true,
                            shape = TammiRadii.inputShape,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TammiOutlinedButton(
                        text = if (state.isTestingServer) "Đang kiểm tra..." else "Kiểm tra kết nối máy chủ",
                        enabled = !state.isTestingServer && !state.isLoading,
                        compact = true,
                        onClick = viewModel::testServerConnection,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (state.isLoading) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.8.dp, color = accent)
                    Spacer(Modifier.width(TammiSpacing.md))
                    Text("Đang đăng nhập...", fontWeight = FontWeight.Bold, color = accent)
                }
            } else {
                TvActionButton(
                    text = "Đăng nhập",
                    containerColor = accent,
                    onClick = viewModel::submitLogin,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

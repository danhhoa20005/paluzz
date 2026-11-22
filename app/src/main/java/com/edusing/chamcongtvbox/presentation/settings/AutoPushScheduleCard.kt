package com.edusing.chamcongtvbox.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiSwitchRow
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import java.util.Locale

/**
 * Khối cài đặt **khung giờ tự đẩy thứ 4 tuỳ chọn** (đặt gọn trong mục Nâng cao của màn hình Cấu hình
 * để màn hình chính giữ gọn nhẹ với 3 mốc cố định 06:00 • 12:00 • 20:00).
 */
@Composable
internal fun AutoPushCustomSlotSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val customText = String.format(Locale.US, "%02d:%02d", state.autoPushCustomHour, state.autoPushCustomMinute)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
        TammiSwitchRow(
            title = if (state.autoPushCustomEnabled) "Khung giờ đẩy thứ 4: $customText" else "Thêm khung giờ đẩy thứ 4",
            subtitle = "Ngoài 3 khung giờ mặc định 06:00 • 12:00 • 20:00",
            checked = state.autoPushCustomEnabled,
            enabled = state.autoPushEnabled,
            onCheckedChange = viewModel::onAutoPushCustomEnabledChange,
        )

        AnimatedVisibility(visible = state.autoPushEnabled && state.autoPushCustomEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                    TimeStepperPod(
                        title = "Giờ (00–23)",
                        valueText = String.format(Locale.US, "%02d", state.autoPushCustomHour),
                        onMinus = { viewModel.adjustCustomHour(-1) },
                        onPlus = { viewModel.adjustCustomHour(1) },
                        modifier = Modifier.weight(1f),
                    )
                    TimeStepperPod(
                        title = "Phút (00–59)",
                        valueText = String.format(Locale.US, "%02d", state.autoPushCustomMinute),
                        onMinus = { viewModel.adjustCustomMinute(-5) },
                        onPlus = { viewModel.adjustCustomMinute(5) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                    listOf(0, 15, 30, 45).forEach { m ->
                        val selected = state.autoPushCustomMinute == m
                        TammiOutlinedButton(
                            text = String.format(Locale.US, ":%02d", m),
                            onClick = { viewModel.setCustomMinute(m) },
                            compact = true,
                            contentColor = if (selected) AppColors.Primary else AppColors.TextSecondary,
                            borderColor = if (selected) AppColors.PrimaryText else AppColors.Border,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TammiOutlinedButton(
                        text = "+1p",
                        onClick = { viewModel.adjustCustomMinute(1) },
                        compact = true,
                        contentColor = AppColors.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeStepperPod(
    title: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.SurfaceSubtle)
            .border(1.dp, AppColors.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = TammiSpacing.md, vertical = TammiSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm),
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
            TammiOutlinedButton("−", onMinus, compact = true, contentColor = AppColors.Primary)
            Text(valueText, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = AppColors.Text)
            TammiOutlinedButton("+", onPlus, compact = true, contentColor = AppColors.Primary)
        }
    }
}

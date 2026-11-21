package com.edusing.chamcongtvbox.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing
import java.util.Locale

/**
 * Thẻ cấu hình **Tự động đẩy nhân sự xuống máy chấm công** chuẩn Liquid Glass 3D (0 icon, D-Pad TV Box):
 * - Cố định 3 mốc giờ hằng ngày: **06:00**, **12:00**, **20:00** (giờ Việt Nam đồng bộ máy chủ).
 * - Thêm **1 khung giờ tuỳ chọn** chỉnh nhanh bằng nút `−/+` và mốc phút nhanh (`:00`, `:15`, `:30`, `:45`).
 * - Chỉ đẩy người mới (Delta Fast-Path), tự khoá đồng bộ tránh đụng phiên quét mặt đang chạy.
 */
@Composable
internal fun AutoPushScheduleCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val customText = String.format(Locale.US, "%02d:%02d", state.autoPushCustomHour, state.autoPushCustomMinute)

    TammiGlassPanel(modifier = modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (state.autoPushEnabled) AppColors.Green else AppColors.OrbCobalt))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tự động đẩy ${state.personLower} xuống máy", fontWeight = FontWeight.Black, fontSize = 15.sp, color = AppColors.Text)
                    Text(
                        text = if (state.autoPushEnabled) "Lịch kế tiếp: ${state.nextAutoPushText} • Chỉ đẩy người mới" else "Đang tạm tắt lịch tự động",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                    )
                }
            }

            ToggleRow(
                checked = state.autoPushEnabled,
                label = "Bật tự động đẩy hằng ngày (3 khung giờ cố định)",
                onToggle = { viewModel.onAutoPushEnabledChange(!state.autoPushEnabled) },
            )

            // 3 mốc giờ cố định: 06:00 sáng, 12:00 trưa, 20:00 tối
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val fixedTone = if (state.autoPushEnabled) TammiBadgeTone.SUCCESS else TammiBadgeTone.NEUTRAL
                TammiStatusPill("06:00 Sáng", tone = fixedTone)
                TammiStatusPill("12:00 Trưa", tone = fixedTone)
                TammiStatusPill("20:00 Tối", tone = fixedTone)
            }

            AnimatedVisibility(visible = state.autoPushEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = AppColors.Line.copy(alpha = 0.5f))

                    ToggleRow(
                        checked = state.autoPushCustomEnabled,
                        label = if (state.autoPushCustomEnabled) "Khung giờ phụ thứ 4: $customText" else "Thêm 1 khung giờ tuỳ chọn khác",
                        onToggle = { viewModel.onAutoPushCustomEnabledChange(!state.autoPushCustomEnabled) },
                    )

                    AnimatedVisibility(visible = state.autoPushCustomEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            // Chọn nhanh phút tròn + tinh chỉnh 1 phút (dễ bấm bằng remote TV Box lẫn điện thoại)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0, 15, 30, 45).forEach { m ->
                                    val selected = state.autoPushCustomMinute == m
                                    TammiOutlinedButton(
                                        text = String.format(Locale.US, ":%02d", m),
                                        onClick = { viewModel.setCustomMinute(m) },
                                        compact = true,
                                        contentColor = if (selected) AppColors.Primary else AppColors.TextSecondary,
                                        borderColor = if (selected) AppColors.Primary else AppColors.Border,
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

            if (state.lastAutoPushText.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Lần đồng bộ gần nhất", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary)
                    TammiStatusPill(state.lastAutoPushText, tone = TammiBadgeTone.INFO)
                }
            }

            TvActionButton(
                text = if (state.isAutoPushing) "Đang đồng bộ ${state.personLower}..." else "Đẩy tự động ngay (${state.connectedDeviceCount} máy)",
                containerColor = AppColors.Primary,
                compact = true,
                enabled = !state.isAutoPushing && state.connectedDeviceCount > 0,
                onClick = viewModel::triggerAutoPushNow,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ToggleRow(checked: Boolean, label: String, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, 10)
            .clickable(onClick = onToggle),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Text)
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
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.SurfaceSubtle)
            .border(1.dp, AppColors.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = AppColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TammiOutlinedButton("−", onMinus, compact = true, contentColor = AppColors.Primary)
            Text(valueText, fontSize = 19.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = AppColors.Text)
            TammiOutlinedButton("+", onPlus, compact = true, contentColor = AppColors.Primary)
        }
    }
}

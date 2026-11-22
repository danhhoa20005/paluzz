package com.edusing.chamcongtvbox.presentation.main

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.repository.InternetStatus
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiModeBadgeButton
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.DeviceUiSpec
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.liquidGlassLayer
import com.edusing.chamcongtvbox.presentation.theme.rememberReducedMotion

/** Thanh tiêu đề thoáng trong suốt (không viền chia cắt cứng — nền sáng chảy liền mạch từ mép trên). */
@Composable
fun MainTopAppBar(
    state: MainUiState,
    ui: DeviceUiSpec,
    onModeClick: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ui.horizontalPadding, vertical = TammiSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TammiSpacing.xs)) {
                Text(
                    text = state.orgName.uppercase().ifBlank { state.activeMode.shortLabel.uppercase() },
                    color = AppColors.Text,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.5.sp,
                    letterSpacing = (-0.2).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TammiModeBadgeButton(activeMode = state.activeMode, onClick = onModeClick)
            }
            Spacer(Modifier.width(TammiSpacing.sm))
            TammiStatusPill("${state.connectedCount}/${state.totalDeviceCount}", tone = if (state.connectedCount > 0) TammiBadgeTone.SUCCESS else TammiBadgeTone.NEUTRAL)
            if (!state.isRunning) {
                Spacer(Modifier.width(TammiSpacing.sm))
                TammiOutlinedButton("Đổi TK", onLogoutClick, compact = true, contentColor = AppColors.TextSecondary)
            }
        }
        if (state.isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = AppColors.Primary)
    }
}

/**
 * Thẻ Hero **Clear Liquid Glass Sáng Tối Giản**:
 * kính pha lê trắng trong suốt thanh lịch (không mảng màu đậm sặc sỡ), chấm nhịp sống tinh tế & nút than chì AAA.
 */
@Composable
internal fun GatewayHeroPanel(
    state: MainUiState,
    ui: DeviceUiSpec,
    onStartStop: () -> Unit,
    onOpenKiosk: () -> Unit,
) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    val isWifiOff = state.wifiHardwareDisabled
    val isLanLost = !state.hasLanOrWifi && !isWifiOff
    val isNetOffline = state.internetStatus == InternetStatus.OFFLINE
    val isNetRecovering = state.internetStatus == InternetStatus.RECOVERING || state.isDiscovering
    val headline = when {
        isWifiOff -> "Wi-Fi đang tắt"
        isLanLost -> "Mất kết nối Wi-Fi / LAN"
        isNetOffline -> "Mất mạng Internet"
        isNetRecovering -> "Đang tự dò máy chấm công..."
        state.isRunning -> "Đang chờ quét 24/7"
        state.connectedCount > 0 -> "Sẵn sàng chấm công"
        else -> "Đang tìm máy chấm công"
    }
    val pollShort = state.lastPollTimeText.substringBefore(" ")
    val statsLine = when {
        isWifiOff -> "Bật Wi-Fi để tự nối lại mạng & quét máy chấm công"
        isLanLost -> "Đang tự kiểm tra & kết nối lại Wi-Fi mỗi 3.5 giây..."
        isNetOffline -> "${state.internetDetail.ifBlank { "Đang kiểm tra mạng" }} • Tự nối lại & dò máy khi có mạng"
        isNetRecovering -> state.statusHint.ifBlank { "Đã có Internet — tự kết nối máy quét mặt..." }
        else -> buildString {
            append("${state.totalCheckInsToday} lượt hôm nay • ${state.connectedCount}/${state.totalDeviceCount} Online")
            if (state.internetStatus == InternetStatus.ONLINE) append(" • Internet OK")
            if (pollShort.isNotBlank()) append(" • $pollShort")
        }
    }
    val ctaText = when {
        isWifiOff -> "Bật Wi-Fi ngay"
        state.isRunning -> "Dừng chấm công"
        else -> "Bắt đầu chấm công"
    }
    val ctaBgColor = when {
        isWifiOff -> AppColors.Amber
        state.isRunning -> AppColors.Red
        else -> AppColors.Primary
    }
    val ctaAction: () -> Unit = if (isWifiOff) ({ openWifiQuickPanel(context) }) else onStartStop
    val vis = when {
        isWifiOff || isLanLost || isNetOffline -> HeroVisual(AppColors.AmberBright, AppColors.AmberBright, "Cảnh báo", TammiBadgeTone.WARNING)
        state.isRunning -> HeroVisual(AppColors.GreenBright, AppColors.GreenBright, "Đang trực", TammiBadgeTone.SUCCESS)
        state.connectedCount > 0 -> HeroVisual(AppColors.Primary, AppColors.Primary, "Sẵn sàng", TammiBadgeTone.PRIMARY)
        else -> HeroVisual(AppColors.AmberBright, AppColors.Primary, "Đang dò", TammiBadgeTone.INFO)
    }
    val borderAccent = vis.barColor.copy(alpha = 0.38f)
    val pulseLive = state.isRunning && !isWifiOff && !isLanLost && !isNetOffline && !reducedMotion

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassLayer(
                shape = TammiRadii.panelShape,
                tier = LiquidGlassTier.CHROME,
                borderOverride = borderAccent,
                shadowElevation = 14.dp,
            ),
    ) {
        // Dải chỉ báo trạng thái mảnh 4dp trên đỉnh giúp nhận diện trạng thái trong 0.1 giây mà vẫn giữ toàn khung sáng sạch
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(vis.barColor))
        Box(modifier = Modifier.padding(horizontal = if (ui.isCompact) TammiSpacing.lg else TammiSpacing.xxl, vertical = TammiSpacing.lg)) {
            if (ui.isTwoPane) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TammiSpacing.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                            HeroStatusDot(vis.dotColor, sizeDp = 10, pulse = pulseLive)
                            Text(headline, fontSize = if (ui.isTvDevice) 21.sp else 19.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp, color = AppColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TammiStatusPill(vis.badgeLabel, tone = vis.badgeTone)
                        }
                        Text(state.statusHint.ifBlank { statsLine }, fontSize = if (ui.isTvDevice) 14.sp else 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TvActionButton(ctaText, ctaBgColor, ctaAction, enabled = !state.isBusy || isWifiOff)
                    TammiOutlinedButton("Kiosk 24/7", onOpenKiosk, contentColor = AppColors.Primary, borderColor = AppColors.Primary.copy(alpha = 0.42f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.lg)) {
                    Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.xs)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                            HeroStatusDot(vis.dotColor, sizeDp = 10, pulse = pulseLive)
                            Text(headline, fontSize = 18.5.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp, color = AppColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TammiStatusPill(vis.badgeLabel, tone = vis.badgeTone)
                        }
                        Text(state.statusHint.ifBlank { statsLine }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        TvActionButton(ctaText, ctaBgColor, ctaAction, Modifier.weight(1f), enabled = !state.isBusy || isWifiOff)
                        TammiOutlinedButton("Kiosk", onOpenKiosk, contentColor = AppColors.Primary, borderColor = AppColors.Primary.copy(alpha = 0.42f))
                    }
                }
            }
        }
    }
}

private data class HeroVisual(val dotColor: Color, val barColor: Color, val badgeLabel: String, val badgeTone: TammiBadgeTone)

private fun openWifiQuickPanel(context: Context) {
    val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); true }.getOrDefault(false)
    } else false
    if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
}

@Composable
private fun HeroStatusDot(color: Color, sizeDp: Int, pulse: Boolean) {
    val haloScale = if (!pulse) 1f else {
        val t = rememberInfiniteTransition(label = "heroLivePulse")
        val anim by t.animateFloat(1f, 1.38f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "haloScale")
        anim
    }
    Box(modifier = Modifier.size((sizeDp + 8).dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size((sizeDp + 6).dp).scale(haloScale).clip(CircleShape).background(color.copy(alpha = 0.24f)))
        Box(modifier = Modifier.size(sizeDp.dp).clip(CircleShape).background(color).border(1.5.dp, Color.White, CircleShape))
    }
}

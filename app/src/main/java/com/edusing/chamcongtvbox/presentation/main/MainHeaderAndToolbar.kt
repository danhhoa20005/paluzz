package com.edusing.chamcongtvbox.presentation.main

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import com.edusing.chamcongtvbox.presentation.theme.DaylightLiquidPalette
import com.edusing.chamcongtvbox.presentation.theme.DeviceUiSpec
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.rememberReducedMotion
import com.edusing.chamcongtvbox.presentation.theme.stainedLiquidGlass

/** Thanh tiêu đề thoáng trong suốt (không viền chia cắt cứng — nền Liquid Glass chảy liền mạch từ mép trên). */
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
        if (state.isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = AppColors.PrimaryText)
    }
}

/** Thẻ Hero **Stained Liquid Glass 3D** biến sắc quang học mượt (Cobalt ↔ Lục Bảo) + chấm nhịp sống & nút pha lê trắng AAA. */
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
    val heroBaseColor by animateColorAsState(
        targetValue = when {
            isWifiOff || isLanLost || isNetOffline -> AppColors.Amber
            state.isRunning -> AppColors.HeroRunningColor
            else -> AppColors.HeroIdleColor
        },
        animationSpec = if (reducedMotion) snap() else spring(stiffness = 260f),
        label = "heroStainedColor",
    )
    val ctaText = when {
        isWifiOff -> "Bật Wi-Fi ngay"
        state.isRunning -> "Dừng chấm công"
        else -> "Bắt đầu chấm công"
    }
    // Trên nút pha lê trắng luôn dùng mực đậm Daylight (>= 8.1:1 AAA cả Sáng lẫn Tối)
    val ctaTextColor = when {
        isWifiOff -> DaylightLiquidPalette.amber
        state.isRunning -> DaylightLiquidPalette.red
        else -> DaylightLiquidPalette.green
    }
    val ctaAction: () -> Unit = if (isWifiOff) ({ openWifiQuickPanel(context) }) else onStartStop
    val dotColor = if (isWifiOff || isLanLost || isNetOffline || (!state.isRunning && state.connectedCount == 0)) AppColors.AmberBright else AppColors.GreenBright
    val pulseLive = state.isRunning && !isWifiOff && !isLanLost && !isNetOffline && !reducedMotion

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .stainedLiquidGlass(shape = TammiRadii.panelShape, baseColor = heroBaseColor, shadowElevation = 16.dp),
    ) {
        Box(modifier = Modifier.padding(horizontal = if (ui.isCompact) TammiSpacing.lg else TammiSpacing.xxl, vertical = TammiSpacing.lg)) {
            if (ui.isTwoPane) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TammiSpacing.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                            HeroStatusDot(dotColor, sizeDp = 10, pulse = pulseLive)
                            Text(headline, fontSize = if (ui.isTvDevice) 21.sp else 19.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(state.statusHint.ifBlank { statsLine }, fontSize = if (ui.isTvDevice) 14.sp else 13.sp, fontWeight = FontWeight.Medium, color = AppColors.HeroSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TvActionButton(ctaText, Color.White, ctaAction, enabled = !state.isBusy || isWifiOff, contentColor = ctaTextColor, onDarkSurface = true)
                    TammiOutlinedButton("Kiosk 24/7", onOpenKiosk, contentColor = AppColors.Primary, onDarkSurface = true)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.lg)) {
                    Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                            HeroStatusDot(dotColor, sizeDp = 10, pulse = pulseLive)
                            Text(headline, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(state.statusHint.ifBlank { statsLine }, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColors.HeroSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        TvActionButton(ctaText, Color.White, ctaAction, Modifier.weight(1f), enabled = !state.isBusy || isWifiOff, contentColor = ctaTextColor, onDarkSurface = true)
                        TammiOutlinedButton("Kiosk", onOpenKiosk, contentColor = AppColors.Primary, onDarkSurface = true)
                    }
                }
            }
        }
    }
}

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
        Box(modifier = Modifier.size((sizeDp + 6).dp).scale(haloScale).clip(CircleShape).background(color.copy(alpha = 0.30f)))
        Box(modifier = Modifier.size(sizeDp.dp).clip(CircleShape).background(color).border(1.5.dp, Color.White, CircleShape))
    }
}

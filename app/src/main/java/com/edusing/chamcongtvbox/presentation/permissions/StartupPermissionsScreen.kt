package com.edusing.chamcongtvbox.presentation.permissions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.StartupPermissionItem
import com.edusing.chamcongtvbox.domain.model.StartupPermissionType
import com.edusing.chamcongtvbox.domain.model.StartupPermissionsSnapshot
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.liquidGlassLayer
import com.edusing.chamcongtvbox.presentation.theme.rememberDeviceUiSpec

/**
 * Cổng **Cấp quyền bắt buộc ngay khi mới mở ứng dụng** (chặn trước cả Đăng nhập & Trạm trực):
 * không cho bỏ qua hoặc nhấn Quay lại khi còn quyền bắt buộc chưa cấp; tự mở thẳng vào app khi đủ 100%.
 */
@Composable
fun StartupPermissionsScreen(
    snapshot: StartupPermissionsSnapshot,
    webConsoleUrl: String = "",
    showDefaultPinHint: Boolean = false,
    onRequestPermission: (StartupPermissionType) -> Unit,
    onEnableTvHomeLauncher: () -> Unit,
    onRecheckNow: () -> Unit,
) {
    // Bắt buộc cấp đủ quyền trước khi vào app — chặn phím Back lách qua
    BackHandler(enabled = !snapshot.allMandatoryGranted) { onRecheckNow() }

    val ui = rememberDeviceUiSpec()
    val primaryFocusRequester = remember { FocusRequester() }
    val nextPending = snapshot.nextPending

    LaunchedEffect(ui.isTvDevice, nextPending?.type) {
        if (ui.isTvDevice) runCatching { primaryFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = ui.horizontalPadding, vertical = ui.verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (ui.isTwoPane) 820.dp else 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TammiSpacing.md),
        ) {
            PermissionsHeaderBanner(snapshot = snapshot, isTv = ui.isTvDevice)

            snapshot.items.forEachIndexed { idx, item ->
                PermissionRequirementCard(
                    stepNumber = idx + 1,
                    item = item,
                    isDefaultHome = snapshot.isDefaultHomeLauncher,
                    showHomeShortcut = item.type == StartupPermissionType.AUTO_LAUNCH_ON_BOOT && snapshot.canSetHomeLauncher,
                    onRequest = { onRequestPermission(item.type) },
                    onEnableTvHome = onEnableTvHomeLauncher,
                )
            }

            TammiGlassPanel(Modifier.fillMaxWidth(), PaddingValues(TammiSpacing.lg), tier = LiquidGlassTier.CHROME) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (nextPending != null) {
                        TvActionButton(
                            text = "Cấp quyền tiếp theo: ${nextPending.actionLabel}",
                            containerColor = AppColors.Primary,
                            onClick = { onRequestPermission(nextPending.type) },
                            modifier = Modifier.weight(1f).focusRequester(primaryFocusRequester),
                        )
                    }
                    TammiOutlinedButton("Kiểm tra lại", onRecheckNow, contentColor = AppColors.Primary)
                }
                Spacer(Modifier.height(TammiSpacing.xs))
                Text(
                    text = "Ứng dụng sẽ tự động vào màn hình Đăng nhập ngay sau khi tất cả quyền bắt buộc được cấp đủ.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextSecondary,
                )
                if (webConsoleUrl.isNotBlank()) {
                    Spacer(Modifier.height(TammiSpacing.sm))
                    val pinText = if (showDefaultPinHint) " • Mã mặc định: 1234" else ""
                    Text(
                        text = "Web quản trị LAN đang mở: $webConsoleUrl$pinText",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.PrimaryText,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsHeaderBanner(snapshot: StartupPermissionsSnapshot, isTv: Boolean) {
    val total = snapshot.totalMandatoryCount.coerceAtLeast(1)
    val done = snapshot.grantedMandatoryCount.coerceAtMost(total)
    val progress = done.toFloat() / total.toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassLayer(
                shape = TammiRadii.panelShape,
                tier = LiquidGlassTier.CHROME,
                borderOverride = AppColors.Primary.copy(alpha = 0.36f),
                shadowElevation = 14.dp,
            ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(AppColors.Primary))
        Column(modifier = Modifier.padding(TammiSpacing.lg), verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Cấp quyền bắt buộc để chạy 24/7",
                    fontSize = if (isTv) 20.sp else 18.sp,
                    fontWeight = FontWeight.Black,
                    color = AppColors.Text,
                    modifier = Modifier.weight(1f),
                )
                TammiStatusPill("Đã cấp $done/$total", tone = if (done >= total) TammiBadgeTone.SUCCESS else TammiBadgeTone.WARNING)
            }
            Text(
                text = "Cấp đủ các quyền bên dưới ngay bây giờ để ứng dụng tự bật khi có điện và trực chấm công 24/7 mà không cần vào Cài đặt lại.",
                fontSize = if (isTv) 13.5.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
                lineHeight = 19.sp,
            )
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = if (done >= total) AppColors.Green else AppColors.Primary,
                trackColor = AppColors.SurfaceMuted,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
private fun PermissionRequirementCard(
    stepNumber: Int,
    item: StartupPermissionItem,
    isDefaultHome: Boolean,
    showHomeShortcut: Boolean,
    onRequest: () -> Unit,
    onEnableTvHome: () -> Unit,
) {
    val border = when {
        item.granted -> AppColors.Green.copy(alpha = 0.38f)
        item.required -> AppColors.Primary.copy(alpha = 0.34f)
        else -> AppColors.Border
    }
    TammiGlassPanel(Modifier.fillMaxWidth(), PaddingValues(TammiSpacing.lg), borderColor = border) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (item.granted) AppColors.GreenBright else AppColors.AmberBright))
            Text(
                text = "$stepNumber. ${item.title}",
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = AppColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TammiStatusPill(
                label = if (item.granted) "Đã cấp ✓" else if (item.required) "Bắt buộc" else "Tuỳ chọn",
                tone = if (item.granted) TammiBadgeTone.SUCCESS else if (item.required) TammiBadgeTone.DANGER else TammiBadgeTone.NEUTRAL,
            )
        }
        Spacer(Modifier.height(TammiSpacing.xs))
        Text(item.description, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = AppColors.TextSecondary, lineHeight = 18.sp)
        if (!item.granted || (showHomeShortcut && !isDefaultHome)) {
            Spacer(Modifier.height(TammiSpacing.md))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                if (!item.granted) {
                    TvActionButton(item.actionLabel, AppColors.Primary, onRequest, Modifier.weight(1f), compact = true)
                }
                if (showHomeShortcut && !isDefaultHome) {
                    TammiOutlinedButton(
                        text = "Đặt Màn hình chính TV",
                        onClick = onEnableTvHome,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        contentColor = AppColors.Primary,
                        borderColor = AppColors.Primary.copy(alpha = 0.42f),
                    )
                }
            }
        }
    }
}

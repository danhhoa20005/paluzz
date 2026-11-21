package com.edusing.chamcongtvbox.presentation.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.readDeviceHealthInfo
import com.edusing.chamcongtvbox.service.TvAutoBootHelper
import java.util.Locale

/**
 * Thẻ sức khoẻ vận hành 24/7 & tự khởi động TV Box **Liquid Glass 3D** thuần màu & chữ:
 * - Hiển thị thiết bị, IP LAN, pin, thông báo và trạng thái tự mở app sau khi mất điện có điện lại.
 * - Hỗ trợ bật 1 chạm cả 2 lớp tự khởi động TV Box (Màn hình chính TV Box & Quyền tự mở màn hình Android 10+).
 */
@Composable
internal fun DeviceHealthCard(
    webConsoleUrl: String = "",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var health by remember { mutableStateOf(readDeviceHealthInfo(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) health = readDeviceHealthInfo(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    TammiGlassPanel(
        modifier = modifier.fillMaxWidth(),
        padding = PaddingValues(TammiSpacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AppColors.OrbTeal))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tối ưu 24/7 & Tự mở khi có điện", fontWeight = FontWeight.Black, fontSize = 15.sp, color = AppColors.Text)
                    Text(
                        text = "${health.networkLabel} • ${health.localIp}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TammiOutlinedButton(
                    text = "Làm mới",
                    onClick = { health = readDeviceHealthInfo(context) },
                    compact = true,
                    contentColor = AppColors.Primary,
                )
            }

            Text("${health.formFactorLabel} • ${health.modelAndOs}", fontSize = 12.5.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Medium)

            HealthBadgeRow(
                label = "Không giới hạn pin",
                statusText = if (health.ignoringBatteryOptimizations) "Đã bật" else "Đang hạn chế",
                tone = if (health.ignoringBatteryOptimizations) TammiBadgeTone.SUCCESS else TammiBadgeTone.WARNING,
            )
            HealthBadgeRow(
                label = "Thông báo nền",
                statusText = if (health.notificationGranted) "Đã bật" else "Chưa bật",
                tone = if (health.notificationGranted) TammiBadgeTone.SUCCESS else TammiBadgeTone.WARNING,
            )
            HealthBadgeRow(
                label = "Tự bật màn hình khi có điện",
                statusText = when {
                    health.isDefaultHomeLauncher -> "Màn hình chính TV"
                    health.canAutoOpenUiOnBoot -> "Sẵn sàng"
                    else -> "Cần cấp quyền"
                },
                tone = if (health.canAutoOpenUiOnBoot) TammiBadgeTone.SUCCESS else TammiBadgeTone.WARNING,
            )
            HealthBadgeRow(
                label = "Chế độ Màn hình chính TV Box",
                statusText = when {
                    health.isDefaultHomeLauncher -> "Đang bật (100%)"
                    health.isTvHomeLauncherEnabled -> "Chưa chọn mặc định"
                    else -> "Chưa bật"
                },
                tone = when {
                    health.isDefaultHomeLauncher -> TammiBadgeTone.SUCCESS
                    health.isTvHomeLauncherEnabled -> TammiBadgeTone.WARNING
                    else -> TammiBadgeTone.NEUTRAL
                },
            )
            HealthBadgeRow(
                label = "Web quản trị từ xa (LAN)",
                statusText = webConsoleUrl.removePrefix("http://").ifBlank { "Đang chờ IP LAN" },
                tone = if (webConsoleUrl.isNotBlank()) TammiBadgeTone.INFO else TammiBadgeTone.NEUTRAL,
            )

            if (!health.ignoringBatteryOptimizations) {
                TvActionButton(
                    text = "Bỏ giới hạn pin ngay",
                    containerColor = AppColors.Amber,
                    compact = true,
                    onClick = { openBatteryOptimizationWhitelist(context) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!health.canAutoOpenUiOnBoot) {
                TvActionButton(
                    text = "Cấp quyền tự mở khi có điện",
                    containerColor = AppColors.Primary,
                    compact = true,
                    onClick = { TvAutoBootHelper.openOverlayPermissionSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(color = AppColors.Line.copy(alpha = 0.55f))

            Text(
                text = "TV Box chuyên dụng: đặt làm Màn hình chính để mất điện có lại vào thẳng app (100% mọi Android 7–15).",
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
                lineHeight = 17.sp,
            )

            if (!health.isDefaultHomeLauncher) {
                TvActionButton(
                    text = if (health.isTvHomeLauncherEnabled) "Chọn làm Màn hình chính mặc định" else "Đặt làm Màn hình chính TV Box",
                    containerColor = AppColors.Green,
                    compact = true,
                    onClick = {
                        TvAutoBootHelper.enableTvHomeLauncherAndPrompt(context)
                        health = readDeviceHealthInfo(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (health.isTvHomeLauncherEnabled) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TammiOutlinedButton(
                        text = "Tắt màn hình chính",
                        onClick = {
                            TvAutoBootHelper.disableTvHomeLauncher(context)
                            health = readDeviceHealthInfo(context)
                        },
                        compact = true,
                        contentColor = AppColors.Red,
                        borderColor = AppColors.Red.copy(alpha = 0.34f),
                        modifier = Modifier.weight(1f),
                    )
                    TammiOutlinedButton(
                        text = "Cài đặt Android",
                        onClick = { TvAutoBootHelper.openSystemSettings(context) },
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
private fun HealthBadgeRow(label: String, statusText: String, tone: TammiBadgeTone) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Text, modifier = Modifier.weight(1f))
        TammiStatusPill(label = statusText, tone = tone)
    }
}

@SuppressLint("BatteryLife")
private fun openBatteryOptimizationWhitelist(context: Context) {
    val mfg = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
    if (mfg.contains("oppo") || mfg.contains("realme") || mfg.contains("oneplus")) {
        val opened = runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            true
        }.getOrDefault(false)
        if (opened) return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val direct = runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            true
        }.getOrDefault(false)
        if (direct) return
    }
    runCatching {
        val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        context.startActivity(fallback)
    }
}

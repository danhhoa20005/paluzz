package com.edusing.chamcongtvbox.presentation.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Thẻ **Vận hành 24/7 & Web quản trị LAN** tinh gọn:
 * chỉ hiện nút hành động khi thiếu quyền quan trọng; mọi thiết lập TV Launcher chuyên sâu tách sang mục Nâng cao.
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

    val allReady = health.ignoringBatteryOptimizations && health.canAutoOpenUiOnBoot

    TammiGlassPanel(modifier = modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Vận hành 24/7", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Text)
                    Text(
                        text = "${health.networkLabel} • ${health.localIp}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TammiStatusPill(
                    label = if (allReady) "Sẵn sàng" else "Cần cấp quyền",
                    tone = if (allReady) TammiBadgeTone.SUCCESS else TammiBadgeTone.WARNING,
                )
            }

            if (webConsoleUrl.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Web quản trị LAN", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary)
                    Text(
                        text = webConsoleUrl.removePrefix("http://"),
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.PrimaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!health.ignoringBatteryOptimizations) {
                TvActionButton(
                    text = "Bỏ giới hạn pin để chạy nền 24/7",
                    containerColor = AppColors.Amber,
                    compact = true,
                    onClick = { openBatteryOptimizationWhitelist(context) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!health.canAutoOpenUiOnBoot) {
                TammiOutlinedButton(
                    text = "Cấp quyền tự mở màn hình khi có điện",
                    contentColor = AppColors.Primary,
                    compact = true,
                    onClick = { TvAutoBootHelper.openOverlayPermissionSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Khối cấu hình Màn hình chính TV Box (đặt gọn trong mục Nâng cao để người dùng phổ thông không bị rối). */
@Composable
internal fun TvHomeLauncherAdvancedSection() {
    val context = LocalContext.current
    var health by remember { mutableStateOf(readDeviceHealthInfo(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Màn hình chính TV Box", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = AppColors.Text)
                Text("Mất điện có lại tự mở thẳng ứng dụng", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            TammiStatusPill(
                label = if (health.isDefaultHomeLauncher) "Đang bật" else "Chưa bật",
                tone = if (health.isDefaultHomeLauncher) TammiBadgeTone.SUCCESS else TammiBadgeTone.NEUTRAL,
            )
        }
        if (!health.isDefaultHomeLauncher) {
            TammiOutlinedButton(
                text = if (health.isTvHomeLauncherEnabled) "Chọn làm Màn hình chính mặc định" else "Bật chế độ Màn hình chính TV Box",
                contentColor = AppColors.Green,
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

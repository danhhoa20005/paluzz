package com.edusing.chamcongtvbox.presentation.components

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.edusing.chamcongtvbox.domain.model.UpdateStateSnapshot
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import kotlinx.coroutines.delay

/**
 * Thanh thông báo bản cập nhật nổi gọn trên đầu màn hình chính (nhìn thấy ở mọi tab, bấm 1 chạm để cài ngay).
 */
@Composable
fun AutoUpdateReadyBanner(
    updateState: UpdateStateSnapshot,
    onInstallNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rel = updateState.availableRelease ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(TammiRadii.inputShape)
            .background(AppColors.GreenSoft)
            .border(1.2.dp, AppColors.Green.copy(alpha = 0.42f), TammiRadii.inputShape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(AppColors.GreenBright))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Bản mới v${rel.versionName} đã tự tải sẵn",
                fontWeight = FontWeight.Black,
                fontSize = 13.5.sp,
                color = AppColors.GreenText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (rel.notes.isNotBlank()) {
                Text(
                    text = rel.notes,
                    fontSize = 11.5.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TvActionButton(
            text = if (updateState.isInstalling) "Đang cài..." else "Cài ngay",
            containerColor = AppColors.Green,
            enabled = !updateState.isInstalling,
            compact = true,
            onClick = onInstallNow,
        )
        TammiOutlinedButton(
            text = "Ẩn",
            compact = true,
            enabled = !updateState.isInstalling,
            contentColor = AppColors.TextSecondary,
            onClick = onDismiss,
        )
    }
}

/**
 * Hộp thoại tự động kích hoạt cài bản mới sau khi đã tải ngầm xong (đếm ngược 4 giây rồi tự mở trình cài đặt APK):
 * người dùng không cần vào Cấu hình hay bấm kiểm tra bản mới.
 */
@Composable
fun AutoUpdateCountdownDialog(
    updateState: UpdateStateSnapshot,
    onInstallNow: () -> Unit,
    onSnooze: () -> Unit,
) {
    val rel = updateState.availableRelease ?: return
    var secondsLeft by remember(rel.versionCode) { mutableIntStateOf(4) }

    LaunchedEffect(rel.versionCode) {
        while (secondsLeft > 0) {
            delay(1_000L)
            secondsLeft--
        }
        onInstallNow()
    }

    Dialog(onDismissRequest = onSnooze) {
        TammiGlassPanel(
            modifier = Modifier.fillMaxWidth(),
            borderColor = AppColors.Green.copy(alpha = 0.45f),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AppColors.GreenBright))
                    Text("Tự động cập nhật v${rel.versionName}", fontSize = 17.sp, fontWeight = FontWeight.Black, color = AppColors.Text)
                }

                Text(
                    text = "Ứng dụng đã tự kiểm tra & tải sẵn bản mới từ GitHub (v${updateState.currentVersionName} → v${rel.versionName}). Toàn bộ tài khoản và máy chấm công được giữ nguyên.",
                    fontSize = 13.sp,
                    color = AppColors.TextSecondary,
                    lineHeight = 19.sp,
                )

                if (rel.notes.isNotBlank()) {
                    Text(
                        text = rel.notes,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.Text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(TammiRadii.inputShape)
                            .background(AppColors.SurfaceSubtle)
                            .border(1.dp, AppColors.Border, TammiRadii.inputShape)
                            .padding(10.dp),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.2.dp, color = AppColors.Green)
                    Text(
                        text = if (secondsLeft > 0) "Tự mở trình cài đặt sau ${secondsLeft}s..." else "Đang mở trình cài đặt...",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.GreenText,
                    )
                }

                Spacer(Modifier.height(TammiSpacing.xs))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TammiOutlinedButton(text = "Để sau", onClick = onSnooze, modifier = Modifier.weight(0.75f))
                    TvActionButton(
                        text = "Cài ngay",
                        containerColor = AppColors.Green,
                        onClick = onInstallNow,
                        modifier = Modifier.weight(1.25f),
                    )
                }
            }
        }
    }
}

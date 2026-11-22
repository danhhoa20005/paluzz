package com.edusing.chamcongtvbox.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.UpdateStateSnapshot
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TammiSwitchRow
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing

/**
 * Thẻ **Cập nhật ứng dụng** tối giản chuẩn Apple HIG / Liquid Glass:
 * hiển thị phiên bản, công tắc tự cập nhật và 1 nút kiểm tra / cài bản mới rõ ràng.
 */
@Composable
internal fun AppUpdateCard(
    updateState: UpdateStateSnapshot,
    onToggleAutoUpdate: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onInstallNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TammiGlassPanel(modifier = modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cập nhật ứng dụng", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppColors.Text)
                    Text(
                        text = "Phiên bản hiện tại v${updateState.currentVersionName}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TammiStatusPill(
                    label = if (updateState.hasUpdateAvailable) "Có bản mới" else "Mới nhất",
                    tone = if (updateState.hasUpdateAvailable) TammiBadgeTone.SUCCESS else TammiBadgeTone.NEUTRAL,
                )
            }

            TammiSwitchRow(
                title = "Tự động cập nhật",
                subtitle = "Tự kiểm tra và tải bản mới mỗi 30 phút",
                checked = updateState.autoUpdateEnabled,
                onCheckedChange = onToggleAutoUpdate,
            )

            if (updateState.statusMessage.isNotBlank()) {
                Text(updateState.statusMessage, fontSize = 12.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Medium)
            }
            if (!updateState.errorMessage.isNullOrBlank()) {
                Text(updateState.errorMessage, fontSize = 12.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TammiOutlinedButton(
                    text = if (updateState.isChecking) "Đang kiểm tra..." else "Kiểm tra bản mới",
                    onClick = onCheckNow,
                    enabled = !updateState.isBusy,
                    compact = true,
                    contentColor = AppColors.Primary,
                    modifier = Modifier.weight(1f),
                )
                if (updateState.hasUpdateAvailable) {
                    val ver = updateState.availableRelease?.versionName.orEmpty()
                    TvActionButton(
                        text = if (updateState.isInstalling || updateState.isDownloading) "Đang cập nhật..." else "Cài v$ver",
                        containerColor = AppColors.Green,
                        compact = true,
                        enabled = !updateState.isBusy,
                        onClick = onInstallNow,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

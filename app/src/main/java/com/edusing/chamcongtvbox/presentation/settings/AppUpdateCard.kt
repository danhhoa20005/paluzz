package com.edusing.chamcongtvbox.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.UpdateStateSnapshot
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/**
 * Thẻ **Tự động cập nhật OTA từ GitHub** (`danhhoa20005/paluzz`) chuẩn Liquid Glass 3D (0 icon, D-Pad TV Box):
 * hiển thị phiên bản hiện tại, chế độ tự cài âm thầm (Device Owner / Root) hoặc cài 1 chạm, kiểm tra & cài ngay.
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (updateState.hasUpdateAvailable) AppColors.Green else AppColors.OrbViolet))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tự động cập nhật từ GitHub", fontWeight = FontWeight.Black, fontSize = 15.sp, color = AppColors.Text)
                    Text(
                        text = "v${updateState.currentVersionName} • ${updateState.repoSlug}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Chế độ cài đặt", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Text, modifier = Modifier.weight(1f))
                TammiStatusPill(
                    label = updateState.installMode.label,
                    tone = if (updateState.installMode.isSilentCapable) TammiBadgeTone.SUCCESS else TammiBadgeTone.INFO,
                )
            }

            var autoFocused by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .onFocusChanged { autoFocused = it.isFocused }
                    .tvFocusRing(autoFocused, 10)
                    .clickable { onToggleAutoUpdate(!updateState.autoUpdateEnabled) },
            ) {
                Checkbox(checked = updateState.autoUpdateEnabled, onCheckedChange = onToggleAutoUpdate)
                Spacer(Modifier.width(4.dp))
                Text("Tự kiểm tra & tải bản mới mỗi 6 giờ", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Text)
            }

            if (updateState.statusMessage.isNotBlank()) {
                Text(updateState.statusMessage, fontSize = 12.5.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Medium)
            }
            if (!updateState.errorMessage.isNullOrBlank()) {
                Text(updateState.errorMessage, fontSize = 12.5.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
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

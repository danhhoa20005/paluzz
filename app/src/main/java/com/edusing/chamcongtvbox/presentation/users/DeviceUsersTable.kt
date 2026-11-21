package com.edusing.chamcongtvbox.presentation.users

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.DeviceUser
import com.edusing.chamcongtvbox.presentation.components.TammiEmptyStateView
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Bảng danh sách người dùng trên máy **Liquid Glass 3D** thuần chữ & màu chuẩn AAA. */
@Composable
internal fun DeviceUsersTableCard(
    state: DeviceUsersUiState,
    isCompact: Boolean,
    onToggleSelect: (String) -> Unit,
    onDeleteSingle: (DeviceUser) -> Unit,
    modifier: Modifier = Modifier,
) {
    TammiGlassPanel(modifier = modifier, padding = PaddingValues(0.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isCompact) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(AppColors.SurfaceSubtle).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(46.dp))
                    Text("Mã thẻ", fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp, color = AppColors.TextSecondary, modifier = Modifier.width(126.dp))
                    Text("Tên máy", fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp, color = AppColors.TextSecondary, modifier = Modifier.width(116.dp))
                    Text("Họ tên (Web)", fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
                    Text(state.groupLabel, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp, color = AppColors.TextSecondary, modifier = Modifier.width(130.dp))
                    Text("Thao tác", fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp, color = AppColors.TextSecondary, modifier = Modifier.width(66.dp))
                }
                HorizontalDivider(color = AppColors.Border)
            }

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.8.dp, color = AppColors.Primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Đang đọc bộ nhớ máy...", fontWeight = FontWeight.Bold, color = AppColors.Primary)
                    }
                }
                state.filteredUsers.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TammiEmptyStateView(
                        title = if (state.users.isEmpty()) "Chưa có người trên máy" else "Không tìm thấy kết quả",
                        subtitle = if (state.users.isEmpty()) "Hãy sang mục Đẩy danh sách để nạp thẻ xuống máy." else "Không khớp với \"${state.searchQuery}\".",
                        accentColor = AppColors.OrbViolet,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.filteredUsers, key = { it.uniqueKey }) { user ->
                        DeviceUserRow(
                            user = user,
                            isCompact = isCompact,
                            isSelected = user.uniqueKey in state.selectedKeys,
                            enabled = !state.isDeleting,
                            onToggleSelect = { onToggleSelect(user.uniqueKey) },
                            onDeleteSingle = { onDeleteSingle(user) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceUserRow(
    user: DeviceUser,
    isCompact: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
    onToggleSelect: () -> Unit,
    onDeleteSingle: () -> Unit,
) {
    var rowFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) AppColors.RedSoft else AppColors.SurfaceSubtle)
            .border(1.dp, if (isSelected) AppColors.Red else AppColors.Border.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .onFocusChanged { rowFocused = it.isFocused }
            .tvFocusRing(rowFocused, 10)
            .clickable(enabled = enabled, onClick = onToggleSelect)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isSelected, enabled = enabled, onCheckedChange = { if (enabled) onToggleSelect() })

        if (isCompact) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("#${user.displayCardId}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Primary)
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(AppColors.PrimarySoft).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text(user.deviceName.ifBlank { "(Trống)" }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppColors.PrimaryDark)
                    }
                    if (user.faceCount >= 0) {
                        val hasFace = user.faceCount > 0
                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (hasFace) AppColors.GreenSoft else AppColors.AmberSoft).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(if (hasFace) "Mặt:${user.faceCount}" else "Chưa mặt", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (hasFace) AppColors.GreenText else AppColors.AmberText)
                        }
                    }
                }
                Text(
                    text = user.serverFullName ?: "Chưa có trên web",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (user.serverFullName != null) AppColors.Text else AppColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!user.className.isNullOrBlank()) {
                    Text(user.className, fontSize = 11.5.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Text(user.displayCardId, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = AppColors.Primary, modifier = Modifier.width(126.dp))
            Text(user.deviceName.ifBlank { "(Trống)" }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(116.dp))
            val bioSuffix = if (user.faceCount >= 0) (if (user.faceCount > 0) " • Có mặt (${user.faceCount})" else " • Chưa mặt") else ""
            Text((user.serverFullName ?: "—") + bioSuffix, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = if (user.faceCount == 0) AppColors.AmberText else if (user.serverFullName != null) AppColors.Text else AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(user.className ?: "-", fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(130.dp))
        }

        Spacer(Modifier.width(6.dp))
        TammiOutlinedButton(
            text = "Xoá",
            onClick = onDeleteSingle,
            enabled = enabled,
            compact = true,
            contentColor = AppColors.Red,
            borderColor = AppColors.Red.copy(alpha = 0.36f),
        )
    }
}

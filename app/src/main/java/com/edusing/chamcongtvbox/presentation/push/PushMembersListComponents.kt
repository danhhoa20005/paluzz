package com.edusing.chamcongtvbox.presentation.push

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.PushResultSummary
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Dòng nhóm Lớp/Phòng ban chuẩn AAA có checkbox chọn nhanh cả nhóm (thuần chữ & màu). */
@Composable
internal fun PushGroupHeaderRow(
    section: MemberGroupSection,
    onToggleGroupSelect: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.PrimarySoft)
            .border(1.dp, AppColors.Primary.copy(alpha = 0.24f), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, 12)
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(checked = section.allPushableSelected, enabled = section.pushableCount > 0, onCheckedChange = { onToggleGroupSelect() })
        Text(
            text = "${section.groupName} (${section.rows.size} • chọn ${section.selectedCount})",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 13.5.sp,
            color = AppColors.PrimaryDark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (section.isExpanded) "Thu gọn" else "Mở",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 12.sp,
            color = AppColors.Primary,
        )
    }
}

/** Dòng thành viên chuẩn AAA: 2 dòng gọn trên Điện thoại, 1 hàng căn cột trên TV Box. */
@Composable
internal fun PushMemberRowItem(
    row: MemberDisplayRow,
    isCompact: Boolean,
    onToggle: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val pushable = row.member.isPushable
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isCompact) 6.dp else 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (row.isSelected) AppColors.GreenSoft else AppColors.SurfaceSubtle)
            .border(1.dp, if (row.isSelected) AppColors.Green else AppColors.Border.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, 10)
            .clickable(enabled = pushable, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.isSelected, enabled = pushable, onCheckedChange = { if (pushable) onToggle() })

        if (isCompact) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = row.member.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    color = if (pushable) AppColors.Text else AppColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pushable) {
                        MemberBadge("#${row.member.cardId}", AppColors.PrimarySoft, AppColors.Primary)
                        MemberBadge(row.previewShortName.ifBlank { "-" }, AppColors.InfoSoft, AppColors.Info)
                    } else {
                        MemberBadge("Mã lỗi (${row.member.rawCard.ifBlank { "trống" }})", AppColors.RedSoft, AppColors.Red)
                    }
                }
            }
        } else {
            Text(
                text = row.member.name,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (pushable) AppColors.Text else AppColors.Muted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pushable) {
                Text(
                    text = "#${row.member.cardId}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = AppColors.Primary,
                    modifier = Modifier.width(140.dp),
                )
                MemberBadge(row.previewShortName.ifBlank { "-" }, AppColors.PrimarySoft, AppColors.Primary)
            } else {
                MemberBadge("Mã lỗi (${row.member.rawCard.ifBlank { "trống" }})", AppColors.RedSoft, AppColors.Red)
            }
        }
    }
}

@Composable
private fun MemberBadge(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text = text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = fg, maxLines = 1)
    }
}

/** Hộp thoại báo cáo kết quả đẩy danh sách chuẩn Liquid Glass 3D + AAA (thuần chữ & màu). */
@Composable
internal fun PushSummaryDialog(
    summary: PushResultSummary,
    onDismiss: () -> Unit,
) {
    val accent = if (summary.failed == 0) AppColors.Green else AppColors.Amber
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Card,
        shape = TammiRadii.panelShape,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(11.dp).clip(CircleShape).background(accent))
                Text("Kết quả đẩy danh sách", fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Text)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Mới: ${summary.succeeded}  •  Đã có: ${summary.skippedExisting}  •  Lỗi: ${summary.failed}",
                    fontWeight = FontWeight.Bold,
                    color = if (summary.failed == 0) AppColors.GreenText else AppColors.AmberText,
                )
                HorizontalDivider(color = AppColors.Border)
                summary.perDeviceResults.forEach { dev ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.SurfaceSubtle).border(1.dp, AppColors.Border, RoundedCornerShape(10.dp)).padding(10.dp)) {
                        Text("${dev.deviceName} (${dev.deviceIp})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Text)
                        Text(
                            text = "Mới: ${dev.succeeded} • Đã có: ${dev.skippedExisting} • Lỗi: ${dev.failed} • Flash: ${if (dev.savedOk) "OK" else "Lỗi"}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (dev.failed == 0 && dev.savedOk) AppColors.GreenText else AppColors.Red,
                        )
                        if (!dev.error.isNullOrBlank()) Text(dev.error, fontSize = 12.sp, color = AppColors.Red)
                    }
                }
            }
        },
        confirmButton = {
            TvActionButton(text = "Đóng", containerColor = AppColors.Primary, onClick = onDismiss, compact = true)
        },
    )
}

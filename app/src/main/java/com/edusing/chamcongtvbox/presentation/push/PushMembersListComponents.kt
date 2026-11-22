package com.edusing.chamcongtvbox.presentation.push

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import com.edusing.chamcongtvbox.domain.model.PushResultSummary
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
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
            .border(1.dp, AppColors.Primary.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, 12)
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = TammiSpacing.md, vertical = TammiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm),
    ) {
        Checkbox(checked = section.allPushableSelected, enabled = section.pushableCount > 0, onCheckedChange = { onToggleGroupSelect() })
        Text(
            text = "${section.groupName} (${section.rows.size} • chọn ${section.selectedCount})",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 13.5.sp,
            color = AppColors.PrimaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (section.isExpanded) "Thu gọn" else "Mở",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 12.sp,
            color = AppColors.PrimaryText,
        )
    }
}

/** Dòng thành viên phẳng sạch chuẩn Apple HIG (không lồng khung trong khung): 2 dòng gọn trên Điện thoại, 1 hàng căn cột trên TV Box. */
@Composable
internal fun PushMemberRowItem(
    row: MemberDisplayRow,
    isCompact: Boolean,
    onToggle: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val pushable = row.isPushableOnTarget
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (isCompact) TammiSpacing.xs else TammiSpacing.md)
                .clip(RoundedCornerShape(12.dp))
                .background(if (row.isSelected) AppColors.GreenSoft.copy(alpha = 0.72f) else Color.Transparent)
                .onFocusChanged { focused = it.isFocused }
                .tvFocusRing(focused, 12)
                .clickable(enabled = pushable, onClick = onToggle)
                .padding(horizontal = TammiSpacing.sm, vertical = TammiSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = row.isSelected, enabled = pushable, onCheckedChange = { if (pushable) onToggle() })

            if (isCompact) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TammiSpacing.xs)) {
                    Text(
                        text = row.member.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (pushable) AppColors.Text else AppColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (pushable) {
                        val preview = row.previewShortName.takeIf { it.isNotBlank() && it != row.member.name }
                        val meta = if (preview != null) "#${row.pushKey} • Tên máy: $preview" else "#${row.pushKey}"
                        Text(meta, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text("Mã thẻ chưa hợp lệ (${row.member.rawCard.ifBlank { "trống" }})", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.RedText)
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
                    Text("#${row.pushKey}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.PrimaryText, modifier = Modifier.width(140.dp))
                    MemberBadge(row.previewShortName.ifBlank { "-" }, AppColors.PrimarySoft, AppColors.PrimaryText)
                } else {
                    MemberBadge("Mã lỗi (${row.member.rawCard.ifBlank { "trống" }})", AppColors.RedSoft, AppColors.RedText)
                }
            }
        }
        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.30f), modifier = Modifier.padding(start = 48.dp))
    }
}

@Composable
private fun MemberBadge(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = TammiSpacing.sm, vertical = TammiSpacing.xs)) {
        Text(text = text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = fg, maxLines = 1)
    }
}

/** Hộp thoại báo cáo kết quả đẩy danh sách **Chrome Liquid Glass 3D** + AAA (thuần chữ & màu). */
@Composable
internal fun PushSummaryDialog(
    summary: PushResultSummary,
    onDismiss: () -> Unit,
) {
    val accent = if (summary.failed == 0) AppColors.GreenBright else AppColors.AmberBright
    Dialog(onDismissRequest = onDismiss) {
        TammiGlassPanel(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            padding = PaddingValues(TammiSpacing.xl),
            borderColor = accent.copy(alpha = 0.38f),
            tier = LiquidGlassTier.CHROME,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TammiSpacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(accent))
                    Text("Kết quả đẩy danh sách", fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Text)
                }
                Text(
                    text = "Mới: ${summary.succeeded}  •  Đã có: ${summary.skippedExisting}  •  Lỗi: ${summary.failed}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (summary.failed == 0) AppColors.GreenText else AppColors.AmberText,
                )
                HorizontalDivider(color = AppColors.Border.copy(alpha = 0.55f))
                summary.perDeviceResults.forEach { dev ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.SurfaceSubtle)
                            .border(1.dp, AppColors.Border, RoundedCornerShape(12.dp)).padding(TammiSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(TammiSpacing.xs),
                    ) {
                        Text("${dev.deviceName} (${dev.deviceIp})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Text)
                        Text(
                            text = "Mới: ${dev.succeeded} • Đã có: ${dev.skippedExisting} • Lỗi: ${dev.failed} • Flash: ${if (dev.savedOk) "OK" else "Lỗi"}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (dev.failed == 0 && dev.savedOk) AppColors.GreenText else AppColors.RedText,
                        )
                        if (!dev.error.isNullOrBlank()) Text(dev.error, fontSize = 12.sp, color = AppColors.RedText)
                    }
                }
                TvActionButton(text = "Đóng", containerColor = AppColors.Primary, onClick = onDismiss, compact = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

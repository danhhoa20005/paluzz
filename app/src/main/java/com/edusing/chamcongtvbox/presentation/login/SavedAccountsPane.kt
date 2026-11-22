package com.edusing.chamcongtvbox.presentation.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.SavedAccount
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Bảng chọn nhanh tài khoản đã lưu phong cách **Liquid Glass 3D** thuần màu & chữ. */
@Composable
internal fun SavedAccountsPane(
    accounts: List<SavedAccount>,
    onSelect: (SavedAccount) -> Unit,
    onDelete: (String) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    TammiGlassPanel(modifier = modifier, padding = PaddingValues(TammiSpacing.lg)) {
        Text("Tài khoản đã lưu (${accounts.size})", fontWeight = FontWeight.Black, fontSize = 15.sp, color = AppColors.Text)
        Text("Chạm để điền nhanh tài khoản và đăng nhập", fontSize = 12.sp, color = AppColors.TextSecondary)
        Spacer(Modifier.height(TammiSpacing.md))

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                accounts.forEach { acc -> SavedAccountRow(acc, { onSelect(acc) }, { onDelete(acc.username) }) }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm),
                modifier = Modifier.heightIn(max = 260.dp),
            ) {
                items(accounts, key = { it.username }) { acc ->
                    SavedAccountRow(acc, { onSelect(acc) }, { onDelete(acc.username) })
                }
            }
        }
    }
}

@Composable
private fun SavedAccountRow(
    acc: SavedAccount,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TammiRadii.inputShape)
            .background(AppColors.SurfaceSubtle)
            .border(1.dp, AppColors.Border, TammiRadii.inputShape)
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, 16)
            .clickable(onClick = onSelect)
            .padding(horizontal = TammiSpacing.md, vertical = TammiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AppColors.Primary))
        Spacer(Modifier.width(TammiSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(acc.orgName.ifBlank { acc.username }, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = AppColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(acc.username, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(TammiSpacing.sm))
        TammiOutlinedButton(
            text = "Xoá",
            onClick = onDelete,
            compact = true,
            contentColor = AppColors.Red,
            borderColor = AppColors.Red.copy(alpha = 0.34f),
        )
    }
}

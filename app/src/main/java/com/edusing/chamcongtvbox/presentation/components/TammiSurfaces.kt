package com.edusing.chamcongtvbox.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.liquidGlassLayer

/** Bảng màu ô nhập liệu kính sáng thanh thoát chuẩn WCAG AAA dùng chung toàn ứng dụng. */
@Composable
fun tammiFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = AppColors.SurfaceSubtle,
    focusedBorderColor = AppColors.Primary,
    unfocusedBorderColor = AppColors.Border.copy(alpha = 0.72f),
    focusedTextColor = AppColors.Text,
    unfocusedTextColor = AppColors.Text,
    focusedLabelColor = AppColors.Primary,
    unfocusedLabelColor = AppColors.TextSecondary,
    focusedPlaceholderColor = AppColors.Muted,
    unfocusedPlaceholderColor = AppColors.Muted,
)

/**
 * Thẻ **Liquid Glass 3D** dùng vật liệu quang học chuẩn Apple HIG (`CONTENT` cho thẻ dữ liệu, `CHROME` cho thanh nổi).
 */
@Composable
fun TammiGlassPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(TammiSpacing.lg),
    backgroundColor: Color = AppColors.Card,
    borderColor: Color? = null,
    tier: LiquidGlassTier = LiquidGlassTier.CONTENT,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tintOverride = if (backgroundColor == AppColors.Card) null else backgroundColor
    Box(
        modifier = modifier.liquidGlassLayer(
            shape = TammiRadii.cardShape,
            tier = tier,
            tintOverride = tintOverride,
            borderOverride = borderColor,
        ),
    ) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

enum class TammiBadgeTone { PRIMARY, SUCCESS, WARNING, DANGER, INFO, NEUTRAL }

/** Nhãn trạng thái Liquid Glass tối giản (chấm phát sáng 2 lớp + chữ chuẩn AAA, không icon). */
@Composable
fun TammiStatusPill(
    label: String,
    modifier: Modifier = Modifier,
    tone: TammiBadgeTone = TammiBadgeTone.PRIMARY,
) {
    val (fg, bg, dot) = when (tone) {
        TammiBadgeTone.PRIMARY -> Triple(AppColors.Primary, AppColors.PrimarySoft, AppColors.Primary)
        TammiBadgeTone.SUCCESS -> Triple(AppColors.Green, AppColors.GreenSoft, AppColors.GreenBright)
        TammiBadgeTone.WARNING -> Triple(AppColors.Amber, AppColors.AmberSoft, AppColors.Amber)
        TammiBadgeTone.DANGER -> Triple(AppColors.Red, AppColors.RedSoft, AppColors.Red)
        TammiBadgeTone.INFO -> Triple(AppColors.Info, AppColors.InfoSoft, AppColors.Info)
        TammiBadgeTone.NEUTRAL -> Triple(AppColors.TextSecondary, AppColors.SurfaceMuted, AppColors.Muted)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(TammiRadii.badgeShape)
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.26f), TammiRadii.badgeShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier.size(11.dp).clip(CircleShape).background(dot.copy(alpha = 0.24f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(6.5.dp).clip(CircleShape).background(dot))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Khung trạng thái rỗng tối giản (dải màu nhấn 3D + chữ rõ ràng + 1 nút chính, không icon/ảnh). */
@Composable
fun TammiEmptyStateView(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accentColor: Color = AppColors.Primary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(TammiSpacing.xl).widthIn(max = 380.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(5.dp)
                .shadow(4.dp, CircleShape, ambientColor = accentColor.copy(alpha = 0.40f), spotColor = accentColor.copy(alpha = 0.40f))
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(accentColor, accentColor.copy(alpha = 0.55f)))),
        )
        Spacer(Modifier.height(TammiSpacing.md))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(TammiSpacing.xs))
        Text(subtitle, fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center, lineHeight = 19.sp)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(TammiSpacing.md))
            TvActionButton(text = actionLabel, containerColor = accentColor, onClick = onAction)
        }
    }
}

/** Thanh cảnh báo Liquid Glass chuẩn AAA. */
@Composable
fun TammiOfflineBanner(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(TammiRadii.inputShape)
            .background(AppColors.AmberSoft)
            .border(1.1.dp, AppColors.Amber.copy(alpha = 0.30f), TammiRadii.inputShape)
            .padding(horizontal = TammiSpacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AppColors.Amber))
        Text(message, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.AmberText, modifier = Modifier.weight(1f))
        if (onRetry != null) TammiOutlinedButton("Thử lại", onRetry, compact = true, contentColor = AppColors.AmberText, borderColor = AppColors.Amber.copy(alpha = 0.34f))
    }
}

/** Khung báo lỗi Liquid Glass chuẩn AAA. */
@Composable
fun TammiErrorStateCard(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    TammiGlassPanel(modifier.fillMaxWidth(), PaddingValues(horizontal = TammiSpacing.md, vertical = 10.dp), AppColors.RedSoft, AppColors.Red.copy(alpha = 0.34f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AppColors.Red))
            Text(message, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Red, modifier = Modifier.weight(1f))
            if (onRetry != null) TammiOutlinedButton("Thử lại", onRetry, compact = true, contentColor = AppColors.Red, borderColor = AppColors.Red.copy(alpha = 0.34f))
        }
    }
}

/**
 * Thanh công cụ/thao tác nổi **Chrome Liquid Glass** đồng bộ cả Điện thoại & TV Box:
 * luôn là thẻ kính bo tròn nổi thoáng thay vì dải chữ nhật phẳng đè chồng lên thanh viên thuốc đáy.
 */
@Composable
fun TammiAdaptiveBar(
    floating: Boolean,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
    topDivider: Boolean = false,
    bottomDivider: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    TammiGlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = if (floating) 0.dp else 4.dp),
        padding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        tier = LiquidGlassTier.CHROME,
        content = content,
    )
}

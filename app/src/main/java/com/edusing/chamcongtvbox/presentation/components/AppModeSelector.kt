package com.edusing.chamcongtvbox.presentation.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.domain.model.BrandingConfig
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.liquidGlassLayer
import com.edusing.chamcongtvbox.presentation.theme.rememberReducedMotion
import com.edusing.chamcongtvbox.presentation.theme.stainedLiquidGlass
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Tông nhấn tiết chế phân biệt 3 chế độ (rõ ràng, không chói màu). */
@Composable
@ReadOnlyComposable
fun AppMode.orbColor(): Color = when (this) {
    AppMode.DIEM_DANH -> AppColors.OrbCobalt
    AppMode.PALUZZ -> AppColors.OrbTeal
    AppMode.DON_TRA -> AppColors.OrbAmber
}

private fun AppMode.compactTabLabel(): String = when (this) {
    AppMode.DIEM_DANH -> "Điểm danh"
    AppMode.PALUZZ -> "PALUZZ"
    AppMode.DON_TRA -> "Đón trả"
}

/** Thanh chuyển 3 chế độ **Light Liquid Glass AAAAA** (viên Xanh Apple sắc nét, đọc tức thì). */
@Composable
fun TammiModeSwitcherCard(
    activeMode: AppMode,
    enabled: Boolean = true,
    onSelectMode: (AppMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    val domainShort = BrandingConfig.forMode(activeMode).defaultApiDomain.removePrefix("https://")
    val modes = AppMode.entries
    val activeIdx = modes.indexOf(activeMode).coerceAtLeast(0)

    TammiGlassPanel(modifier = modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.md)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Chế độ hệ thống", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = AppColors.Text)
            Text(domainShort, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.PrimaryText)
        }
        Spacer(Modifier.height(TammiSpacing.sm))

        val trackShape = RoundedCornerShape(16.dp)
        val thumbShape = RoundedCornerShape(12.dp)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassLayer(shape = trackShape, tier = LiquidGlassTier.CHROME, tintOverride = AppColors.SurfaceMuted, shadowElevation = 4.dp)
                .padding(4.dp),
        ) {
            val segWidth = maxWidth / modes.size
            val thumbOffset by animateDpAsState(
                targetValue = segWidth * activeIdx,
                animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.76f, stiffness = 420f),
                label = "modeThumbX",
            )
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .width(segWidth)
                    .height(42.dp)
                    .stainedLiquidGlass(shape = thumbShape, baseColor = AppColors.Primary, shadowElevation = 6.dp),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                modes.forEach { mode ->
                    val selected = mode == activeMode
                    var focused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(thumbShape)
                            .onFocusChanged { focused = it.isFocused }
                            .tvFocusRing(focused, cornerRadiusDp = 12, onDark = selected)
                            .clickable(enabled = enabled) { if (!selected) onSelectMode(mode) }
                            .padding(horizontal = TammiSpacing.sm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = mode.compactTabLabel(),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (selected) Color.White else AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Viên thuốc chế độ **Light Liquid Glass AAAAA** (chấm Xanh Apple rõ nét + viền sắc nét, 1 chạm đổi ngay). */
@Composable
fun TammiModeBadgeButton(
    activeMode: AppMode,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .liquidGlassLayer(
                shape = CircleShape,
                tier = LiquidGlassTier.CHROME,
                tintOverride = AppColors.PrimarySoft,
                borderOverride = AppColors.Primary.copy(alpha = 0.40f),
                shadowElevation = 4.dp,
            )
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, cornerRadiusDp = 24)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = TammiSpacing.md, vertical = TammiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm),
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(AppColors.Primary))
        Text(activeMode.compactTabLabel(), color = AppColors.PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Hộp thoại chọn chuyển 3 chế độ **Chrome Liquid Glass Sáng** tối giản. */
@Composable
fun AppModePickerDialog(
    activeMode: AppMode,
    isRunning: Boolean,
    onSelectMode: (AppMode) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TammiGlassPanel(
            modifier = Modifier.fillMaxWidth(0.90f),
            padding = PaddingValues(TammiSpacing.xl),
            tier = LiquidGlassTier.CHROME,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                Text("Chuyển chế độ", fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Text)
                if (isRunning) {
                    Text("Phiên chấm công hiện tại sẽ tạm dừng khi đổi chế độ.", fontSize = 12.5.sp, color = AppColors.AmberText, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(TammiSpacing.xs))
                AppMode.entries.forEach { mode ->
                    val selected = mode == activeMode
                    val dotAccent = mode.orbColor()
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(TammiRadii.inputShape)
                            .background(if (selected) AppColors.PrimarySoft else AppColors.SurfaceSubtle)
                            .border(if (selected) 1.5.dp else 1.dp, if (selected) AppColors.Primary else AppColors.Border, TammiRadii.inputShape)
                            .onFocusChanged { focused = it.isFocused }
                            .tvFocusRing(focused, cornerRadiusDp = 16)
                            .clickable { onDismiss(); if (!selected) onSelectMode(mode) }
                            .padding(horizontal = TammiSpacing.lg, vertical = TammiSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotAccent))
                        Spacer(Modifier.width(TammiSpacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.shortLabel, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = AppColors.Text)
                            Text(mode.subtitle, fontSize = 12.sp, color = AppColors.TextSecondary)
                        }
                        if (selected) {
                            Text("Đang bật", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = AppColors.Text)
                        }
                    }
                }
                Spacer(Modifier.height(TammiSpacing.xs))
                TammiOutlinedButton(text = "Đóng", onClick = onDismiss, compact = true, contentColor = AppColors.Text, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

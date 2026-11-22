package com.edusing.chamcongtvbox.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.liquidGlassLayer
import com.edusing.chamcongtvbox.presentation.theme.rememberReducedMotion
import com.edusing.chamcongtvbox.presentation.theme.stainedLiquidGlass
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/**
 * Nút chính **Stained Liquid Glass 3D** thuần chữ chuẩn WCAG 2.2 AAA + TalkBack + TV D-Pad:
 * đàn hồi spring mượt khi nhấn & thích ứng tự động Sáng/Tối.
 */
@Composable
fun TvActionButton(
    text: String,
    containerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    contentColor: Color = Color.White,
    onDarkSurface: Boolean = false,
) {
    val reducedMotion = rememberReducedMotion()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }

    val targetScale = when {
        !enabled || reducedMotion -> 1f
        pressed -> 0.965f
        focused -> 1.03f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "btnSpringScale",
    )

    val surfaceMod = when {
        !enabled -> Modifier.clip(TammiRadii.buttonShape).background(AppColors.SurfaceMuted)
        containerColor == Color.White -> Modifier.liquidGlassLayer(
            shape = TammiRadii.buttonShape,
            tier = LiquidGlassTier.CHROME,
            tintOverride = Color.White.copy(alpha = 0.95f),
            shadowElevation = if (compact) 8.dp else 12.dp,
        )
        else -> Modifier.stainedLiquidGlass(
            shape = TammiRadii.buttonShape,
            baseColor = containerColor,
            shadowElevation = if (compact) 8.dp else 12.dp,
        )
    }

    Box(
        modifier = modifier
            .heightIn(min = if (compact) 44.dp else TammiSpacing.touchMin)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.56f)
            .then(surfaceMod)
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, cornerRadiusDp = 16, onDark = onDarkSurface || containerColor != Color.White)
            .semantics { role = Role.Button }
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) TammiSpacing.md else TammiSpacing.xl, vertical = if (compact) TammiSpacing.sm else TammiSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else AppColors.Muted,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (compact) 13.sp else 14.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Nút phụ **Chrome Liquid Glass 3D** (kính trong mờ khúc xạ 45°, tự nâng sáng chữ trên Dark Mode chuẩn AAA). */
@Composable
fun TammiOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    contentColor: Color = AppColors.Primary,
    borderColor: Color? = null,
    onDarkSurface: Boolean = false,
) {
    val reducedMotion = rememberReducedMotion()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }

    val targetScale = when {
        !enabled || reducedMotion -> 1f
        pressed -> 0.965f
        focused -> 1.03f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "outBtnSpringScale",
    )

    val effectiveTextColor = when {
        !enabled -> AppColors.Muted
        onDarkSurface -> Color.White
        else -> AppColors.readableAccent(contentColor)
    }
    val glassMod = when {
        !enabled -> Modifier.clip(TammiRadii.buttonShape).background(AppColors.SurfaceMuted)
        onDarkSurface -> Modifier.liquidGlassLayer(
            shape = TammiRadii.buttonShape,
            tier = LiquidGlassTier.CHROME,
            tintOverride = Color.White.copy(alpha = 0.18f),
            borderOverride = Color.White.copy(alpha = 0.42f),
            shadowElevation = 4.dp,
        )
        else -> Modifier.liquidGlassLayer(
            shape = TammiRadii.buttonShape,
            tier = LiquidGlassTier.CHROME,
            tintOverride = Color.White.copy(alpha = 0.94f),
            borderOverride = borderColor ?: AppColors.Border,
            shadowElevation = if (compact) 4.dp else 8.dp,
        )
    }

    Box(
        modifier = modifier
            .heightIn(min = if (compact) 44.dp else TammiSpacing.touchMin)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.56f)
            .then(glassMod)
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, cornerRadiusDp = 16, onDark = onDarkSurface)
            .semantics { role = Role.Button }
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) TammiSpacing.md else TammiSpacing.lg, vertical = TammiSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = effectiveTextColor,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 13.sp else 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Chip lọc viên thuốc **Liquid Glass 3D** thay thế hoàn toàn `FilterChip` mặc định (đồng bộ Daylight & Midnight). */
@Composable
fun LiquidGlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selectedColor: Color = AppColors.Primary,
    unselectedLabelColor: Color = AppColors.Text,
) {
    val reducedMotion = rememberReducedMotion()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (!enabled || reducedMotion) 1f else if (pressed) 0.96f else if (focused) 1.03f else 1f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.74f, stiffness = 520f),
        label = "chipScale",
    )
    val surfaceMod = if (selected) {
        Modifier.stainedLiquidGlass(shape = CircleShape, baseColor = selectedColor, shadowElevation = 8.dp)
    } else {
        Modifier.liquidGlassLayer(
            shape = CircleShape,
            tier = LiquidGlassTier.CHROME,
            tintOverride = Color.White.copy(alpha = 0.94f),
            borderOverride = AppColors.Border,
            shadowElevation = 4.dp,
        )
    }

    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.55f)
            .then(surfaceMod)
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, cornerRadiusDp = 24, onDark = selected)
            .semantics { role = Role.Tab; this.selected = selected }
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = TammiSpacing.md, vertical = TammiSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else AppColors.readableAccent(unselectedLabelColor),
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

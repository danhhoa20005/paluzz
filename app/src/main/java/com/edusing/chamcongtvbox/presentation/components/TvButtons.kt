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
 * Nút chính **Stained Liquid Glass 3D** thuần chữ (không icon):
 * đàn hồi spring mượt khi nhấn (80–120ms) & nâng nhẹ khi focus D-Pad TV Box, tôn trọng Reduced Motion.
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
            tintOverride = Color.White.copy(alpha = 0.94f),
            shadowElevation = if (compact) 6.dp else 12.dp,
        )
        else -> Modifier.stainedLiquidGlass(
            shape = TammiRadii.buttonShape,
            baseColor = containerColor,
            shadowElevation = if (compact) 6.dp else 11.dp,
        )
    }

    Box(
        modifier = modifier
            .heightIn(min = if (compact) 44.dp else TammiSpacing.touchMin)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.62f)
            .then(surfaceMod)
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, cornerRadiusDp = 16, onDark = onDarkSurface)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 9.dp else 12.dp),
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

/** Nút phụ **Chrome Liquid Glass 3D** (kính trong mờ khúc xạ 45°, đàn hồi spring khi nhấn, chữ đậm chuẩn AAA). */
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

    val glassMod = if (enabled) {
        Modifier.liquidGlassLayer(
            shape = TammiRadii.buttonShape,
            tier = LiquidGlassTier.CHROME,
            borderOverride = borderColor,
            shadowElevation = if (compact) 5.dp else 8.dp,
        )
    } else {
        Modifier.clip(TammiRadii.buttonShape).background(AppColors.SurfaceMuted)
    }

    Box(
        modifier = modifier
            .heightIn(min = if (compact) 42.dp else TammiSpacing.touchMin)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.58f)
            .then(glassMod)
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, cornerRadiusDp = 16, onDark = onDarkSurface)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else AppColors.Muted,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 13.sp else 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

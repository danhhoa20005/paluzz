package com.edusing.chamcongtvbox.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.liquidGlassLayer
import com.edusing.chamcongtvbox.presentation.theme.rememberReducedMotion
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Dữ liệu 1 ô thao tác nhanh Chrome Liquid Glass 3D (trực quan bằng màu sắc + chữ, không icon). */
data class TammiQuickAction(
    val label: String,
    val baseColor: Color,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Hàng 4 phím tắt **Chrome Liquid Glass 3D** trong suốt pha lê:
 * viên kính lồi khúc xạ nền đa sắc + dải màu nhấn 3D phía trên + phản hồi spring khi chạm.
 */
@Composable
fun TammiQuickActionDock(
    actions: List<TammiQuickAction>,
    modifier: Modifier = Modifier,
    isTv: Boolean = false,
) {
    if (actions.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { item ->
            LiquidGlassQuickTile(item = item, isTv = isTv, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LiquidGlassQuickTile(
    item: TammiQuickAction,
    isTv: Boolean,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)

    val targetScale = when {
        !item.enabled || reducedMotion -> 1f
        pressed -> 0.96f
        focused -> 1.03f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 500f),
        label = "quickTileScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .alpha(if (item.enabled) 1f else 0.45f)
            .heightIn(min = if (isTv) 62.dp else 56.dp)
            .liquidGlassLayer(
                shape = shape,
                tier = LiquidGlassTier.CHROME,
                shadowElevation = 10.dp,
                shadowTint = item.baseColor.copy(alpha = 0.24f),
            )
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, cornerRadiusDp = 18)
            .clickable(interactionSource = interactionSource, indication = null, enabled = item.enabled, onClick = item.onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.46f)
                    .height(5.dp)
                    .shadow(4.dp, CircleShape, ambientColor = item.baseColor.copy(alpha = 0.45f), spotColor = item.baseColor.copy(alpha = 0.45f))
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(item.baseColor, item.baseColor.copy(alpha = 0.62f)))),
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = item.label,
                fontSize = if (isTv) 13.5.sp else 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = item.baseColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

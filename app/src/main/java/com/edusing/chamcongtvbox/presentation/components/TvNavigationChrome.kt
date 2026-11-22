package com.edusing.chamcongtvbox.presentation.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.liquidGlassLayer
import com.edusing.chamcongtvbox.presentation.theme.rememberDeviceUiSpec
import com.edusing.chamcongtvbox.presentation.theme.rememberReducedMotion
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Thanh phân đoạn **Liquid Glass trượt lò xo mượt** (viên kính sáng/tối lướt tự nhiên giữa các tab). */
@Composable
fun TvSegmentedTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val reducedMotion = rememberReducedMotion()
    val trackShape = RoundedCornerShape(16.dp)
    val thumbShape = RoundedCornerShape(12.dp)
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassLayer(shape = trackShape, tier = LiquidGlassTier.CHROME, tintOverride = AppColors.SurfaceMuted, shadowElevation = 4.dp)
            .padding(TammiSpacing.xs),
    ) {
        val segWidth = maxWidth / tabs.size
        val thumbOffset by animateDpAsState(
            targetValue = segWidth * safeIndex,
            animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.78f, stiffness = 440f),
            label = "segThumbX",
        )
        // Viên kính trắng sắc nét viền Xanh Apple lướt mượt phía sau tab đang chọn
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(segWidth)
                .height(44.dp)
                .liquidGlassLayer(
                    shape = thumbShape,
                    tier = LiquidGlassTier.CHROME,
                    tintOverride = AppColors.SegThumbTint,
                    borderOverride = AppColors.Primary.copy(alpha = 0.45f),
                    shadowElevation = 8.dp,
                    shadowTint = AppColors.Primary.copy(alpha = 0.22f),
                ),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, label ->
                val selected = index == safeIndex
                var focused by remember { mutableStateOf(false) }
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(thumbShape)
                        .onFocusChanged { focused = it.isFocused }
                        .tvFocusRing(focused, 12)
                        .semantics { role = Role.Tab; this.selected = selected }
                        .clickable(interactionSource = interaction, indication = null) { onSelect(index) }
                        .padding(horizontal = TammiSpacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = if (selected) AppColors.PrimaryText else AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Thanh tiêu đề màn phụ thoáng nhẹ trong suốt (đồng bộ nền Liquid Glass tràn viền). */
@Composable
fun TvSubScreenHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val ui = rememberDeviceUiSpec()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ui.horizontalPadding, vertical = if (ui.isCompact) TammiSpacing.sm else TammiSpacing.md),
        verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                TammiOutlinedButton(text = "Quay lại", onClick = onBack, enabled = backEnabled, compact = true, contentColor = AppColors.Text)
                Spacer(Modifier.width(TammiSpacing.md))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = AppColors.Text, fontWeight = FontWeight.Black, fontSize = if (ui.isCompact) 16.5.sp else 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = AppColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!ui.isCompact && actions != null) {
                Spacer(Modifier.width(TammiSpacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
        if (ui.isCompact && actions != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

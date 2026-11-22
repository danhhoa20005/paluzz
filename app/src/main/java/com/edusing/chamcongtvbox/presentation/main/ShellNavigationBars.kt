package com.edusing.chamcongtvbox.presentation.main

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.edusing.chamcongtvbox.domain.repository.InternetStatus
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiModeBadgeButton
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.DeviceUiSpec
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.liquidGlassLayer
import com.edusing.chamcongtvbox.presentation.theme.rememberReducedMotion
import com.edusing.chamcongtvbox.presentation.theme.stainedLiquidGlass
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Thanh điều hướng đáy nổi viên thuốc **Chrome Liquid Glass 3D** chuẩn iOS 26 (thấu kính Stained lướt lò xo). */
@Composable
internal fun PhoneBottomNavigationBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val reducedMotion = rememberReducedMotion()
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = TammiSpacing.lg, vertical = TammiSpacing.md)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassLayer(shape = CircleShape, tier = LiquidGlassTier.CHROME, shadowElevation = 16.dp)
                .padding(TammiSpacing.xs),
        ) {
            val tabWidth = maxWidth / tabs.size
            val lensOffset by animateDpAsState(
                targetValue = tabWidth * safeIndex,
                animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.76f, stiffness = 420f),
                label = "phoneNavLensX",
            )
            Box(
                modifier = Modifier
                    .offset(x = lensOffset)
                    .width(tabWidth)
                    .height(44.dp)
                    .stainedLiquidGlass(shape = CircleShape, baseColor = AppColors.Primary, shadowElevation = 8.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { idx, label ->
                    val isSel = idx == safeIndex
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(CircleShape)
                            .semantics { role = Role.Tab; selected = isSel }
                            .clickable(interactionSource = interaction, indication = null) { onSelect(idx) }
                            .padding(horizontal = TammiSpacing.sm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isSel) Color.White else AppColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Thanh điều hướng nổi bên trái **Chrome Liquid Glass 3D** cho **Android TV Box & Tablet** (D-Pad AAA). */
@Composable
internal fun TvSideNavigationRail(
    state: MainUiState,
    tabs: List<String>,
    selectedIndex: Int,
    ui: DeviceUiSpec,
    onSelect: (Int) -> Unit,
    onModeClick: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    val safeIndex = selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val itemHeight = if (ui.isTvDevice) 48.dp else 44.dp
    val itemGap = TammiSpacing.sm
    val itemShape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .width(ui.sidebarWidth)
            .fillMaxHeight()
            .padding(start = ui.horizontalPadding, top = ui.verticalPadding, bottom = ui.verticalPadding),
    ) {
        TammiGlassPanel(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            padding = PaddingValues(TammiSpacing.lg),
            tier = LiquidGlassTier.CHROME,
        ) {
            Text(
                text = state.orgName.uppercase().ifBlank { state.activeMode.shortLabel.uppercase() },
                color = AppColors.Text,
                fontWeight = FontWeight.Black,
                fontSize = if (ui.isTvDevice) 14.5.sp else 13.5.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TammiSpacing.sm))
            TammiModeBadgeButton(activeMode = state.activeMode, onClick = onModeClick)
            Spacer(Modifier.height(TammiSpacing.md))
            HorizontalDivider(color = AppColors.Border.copy(alpha = 0.65f))
            Spacer(Modifier.height(TammiSpacing.md))

            val lensOffsetY by animateDpAsState(
                targetValue = (itemHeight + itemGap) * safeIndex,
                animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.78f, stiffness = 420f),
                label = "tvNavLensY",
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .offset(y = lensOffsetY)
                        .fillMaxWidth()
                        .height(itemHeight)
                        .stainedLiquidGlass(shape = itemShape, baseColor = AppColors.Primary, shadowElevation = 8.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(itemGap), modifier = Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { idx, label ->
                        val isSel = idx == safeIndex
                        var focused by remember { mutableStateOf(false) }
                        val scale by animateFloatAsState(
                            targetValue = if (focused && !reducedMotion) 1.03f else 1f,
                            animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.74f, stiffness = 480f),
                            label = "tvTabScale",
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                .scale(scale)
                                .clip(itemShape)
                                .then(if (!isSel) Modifier.background(AppColors.SurfaceSubtle.copy(alpha = 0.45f)) else Modifier)
                                .onFocusChanged { focused = it.isFocused }
                                .tvFocusRing(focused, cornerRadiusDp = 16, onDark = isSel)
                                .semantics { role = Role.Tab; selected = isSel }
                                .clickable { onSelect(idx) }
                                .padding(horizontal = TammiSpacing.md),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = label,
                                fontSize = if (ui.isTvDevice) 14.5.sp else 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isSel) Color.White else AppColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Column(verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                val (netLabel, netTone) = when (state.internetStatus) {
                    InternetStatus.ONLINE -> "Internet OK" to TammiBadgeTone.SUCCESS
                    InternetStatus.OFFLINE -> "Mất Internet" to TammiBadgeTone.DANGER
                    InternetStatus.RECOVERING -> "Đang nối lại..." to TammiBadgeTone.WARNING
                    InternetStatus.CHECKING -> "Kiểm tra mạng" to TammiBadgeTone.INFO
                }
                TammiStatusPill(netLabel, Modifier.fillMaxWidth(), netTone)
                TammiStatusPill(
                    "${state.connectedCount}/${state.totalDeviceCount} máy Online",
                    Modifier.fillMaxWidth(),
                    if (state.connectedCount > 0) TammiBadgeTone.SUCCESS else TammiBadgeTone.NEUTRAL,
                )
                if (!state.isRunning) {
                    TammiOutlinedButton("Đổi tài khoản", onLogoutClick, Modifier.fillMaxWidth(), compact = true, contentColor = AppColors.TextSecondary)
                }
                if (ui.isTvDevice) {
                    Text("D-Pad: Di chuyển • OK: Chọn", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

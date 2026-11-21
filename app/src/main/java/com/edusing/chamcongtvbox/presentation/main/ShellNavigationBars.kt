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
import com.edusing.chamcongtvbox.presentation.theme.liquidGlassLayer
import com.edusing.chamcongtvbox.presentation.theme.rememberReducedMotion
import com.edusing.chamcongtvbox.presentation.theme.stainedLiquidGlass
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/**
 * Thanh điều hướng đáy nổi dạng viên thuốc **Chrome Liquid Glass 3D** chuẩn iOS 26 cho Điện thoại:
 * - Lớp ngoài pha lê trong suốt 65–80% cho nội dung cuộn xuyên sáng bên dưới.
 * - Viên **Stained Liquid Glass** xanh Cobalt lướt lò xo mượt (`spring`) theo tab đang chọn, không giật cục.
 */
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

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassLayer(shape = CircleShape, tier = LiquidGlassTier.CHROME, shadowElevation = 18.dp)
                .padding(5.dp),
        ) {
            val tabWidth = maxWidth / tabs.size
            val lensOffset by animateDpAsState(
                targetValue = tabWidth * safeIndex,
                animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.76f, stiffness = 420f),
                label = "phoneNavLensX",
            )
            // Thấu kính Stained Liquid Glass lướt mượt dưới chữ tab
            Box(
                modifier = Modifier
                    .offset(x = lensOffset)
                    .width(tabWidth)
                    .height(44.dp)
                    .stainedLiquidGlass(shape = CircleShape, baseColor = AppColors.Primary, shadowElevation = 9.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { idx, label ->
                    val selected = idx == safeIndex
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(CircleShape)
                            .clickable(interactionSource = interaction, indication = null) { onSelect(idx) }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (selected) Color.White else AppColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Thanh điều hướng nổi bên trái **Chrome Liquid Glass 3D** chuyên biệt cho **Android TV Box & Tablet**:
 * - Chiếm trọn chiều cao 16:9 với lề overscan an toàn, kính pha lê trong suốt khúc xạ nền đa sắc.
 * - Viên **Stained Liquid Glass** trượt dọc theo lò xo khi di chuyển bằng D-Pad, kèm viền focus AAA.
 */
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
    val itemGap = 8.dp
    val itemShape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .width(ui.sidebarWidth)
            .fillMaxHeight()
            .padding(start = ui.horizontalPadding, top = ui.verticalPadding, bottom = ui.verticalPadding),
    ) {
        TammiGlassPanel(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            padding = PaddingValues(14.dp),
            tier = LiquidGlassTier.CHROME,
        ) {
            // 1. Đầu bảng: Tên đơn vị + nút chuyển 3 chế độ 1 bấm
            Text(
                text = state.orgName.uppercase().ifBlank { state.activeMode.shortLabel.uppercase() },
                color = AppColors.Text,
                fontWeight = FontWeight.Black,
                fontSize = if (ui.isTvDevice) 14.5.sp else 13.5.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            TammiModeBadgeButton(activeMode = state.activeMode, onClick = onModeClick)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = AppColors.Border.copy(alpha = 0.65f))
            Spacer(Modifier.height(10.dp))

            // 2. 4 tab điều hướng lớn với thấu kính Stained Liquid Glass lướt dọc mượt
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
                        .stainedLiquidGlass(shape = itemShape, baseColor = AppColors.Primary, shadowElevation = 9.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(itemGap), modifier = Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { idx, label ->
                        val selected = idx == safeIndex
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
                                .then(if (!selected) Modifier.background(AppColors.SurfaceSubtle.copy(alpha = 0.45f)) else Modifier)
                                .onFocusChanged { focused = it.isFocused }
                                .tvFocusRing(focused, cornerRadiusDp = 14, onDark = selected)
                                .clickable { onSelect(idx) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = label,
                                fontSize = if (ui.isTvDevice) 14.5.sp else 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (selected) Color.White else AppColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // 3. Đuôi bảng: Tình trạng Internet + Máy Online + Đổi tài khoản + gợi ý TV
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                    Text("D-Pad: Di chuyển • OK: Chọn", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

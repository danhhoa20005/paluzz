package com.edusing.chamcongtvbox.presentation.login

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.presentation.components.TammiModeSwitcherCard
import com.edusing.chamcongtvbox.presentation.components.orbColor
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.rememberDeviceUiSpec
import com.edusing.chamcongtvbox.presentation.theme.rememberReducedMotion
import com.edusing.chamcongtvbox.presentation.theme.stainedLiquidGlass

/**
 * Màn hình Đăng nhập **Liquid Glass 3D** thuần màu sắc & chữ (không icon, không ảnh thừa):
 * - **TV Box / Tablet**: 2 cột cân đối (Trái: Chọn 3 chế độ + Tài khoản lưu / Giới thiệu | Phải: Form đăng nhập).
 * - **Điện thoại**: Thẻ tiêu đề 3D theo màu chế độ → Thanh chuyển 3 chế độ → Form đăng nhập → Tài khoản đã lưu.
 */
@Composable
fun LoginScreen(viewModel: LoginViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ui = rememberDeviceUiSpec()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = ui.horizontalPadding, vertical = ui.verticalPadding),
        contentAlignment = if (ui.isTwoPane) Alignment.Center else Alignment.TopCenter,
    ) {
        if (ui.isTwoPane) {
            Row(
                modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TammiSpacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(0.95f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(ui.cardSpacing),
                ) {
                    TammiModeSwitcherCard(activeMode = state.activeMode, enabled = !state.isLoading, onSelectMode = viewModel::switchMode)
                    if (state.savedAccounts.isNotEmpty()) {
                        SavedAccountsPane(
                            accounts = state.savedAccounts,
                            onSelect = viewModel::selectSavedAccount,
                            onDelete = viewModel::deleteSavedAccount,
                            compact = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LoginHeroPanel(mode = state.activeMode, title = state.title, orgLabel = state.orgLabel)
                    }
                }
                LoginFormCard(
                    state = state,
                    viewModel = viewModel,
                    isCompact = false,
                    modifier = Modifier.weight(1.05f).verticalScroll(rememberScrollState()),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ui.cardSpacing),
            ) {
                CompactLiquidBrandBanner(mode = state.activeMode, title = state.title, orgLabel = state.orgLabel)
                TammiModeSwitcherCard(activeMode = state.activeMode, enabled = !state.isLoading, onSelectMode = viewModel::switchMode)
                LoginFormCard(state = state, viewModel = viewModel, isCompact = true, modifier = Modifier.fillMaxWidth())
                if (state.savedAccounts.isNotEmpty()) {
                    SavedAccountsPane(
                        accounts = state.savedAccounts,
                        onSelect = viewModel::selectSavedAccount,
                        onDelete = viewModel::deleteSavedAccount,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Thẻ thương hiệu Stained Liquid Glass 3D chuyển sắc mượt theo 3 chế độ. */
@Composable
private fun CompactLiquidBrandBanner(mode: AppMode, title: String, orgLabel: String) {
    val reducedMotion = rememberReducedMotion()
    val accent by animateColorAsState(
        targetValue = mode.orbColor(),
        animationSpec = if (reducedMotion) snap() else spring(stiffness = 320f),
        label = "loginBannerTint",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .stainedLiquidGlass(shape = TammiRadii.panelShape, baseColor = accent, shadowElevation = 14.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(AppColors.GreenBright).border(1.5.dp, Color.White, CircleShape))
                Text(title, fontSize = 17.5.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
            Text("Trạm cầu nối 3-trong-1 • Đồng bộ web $orgLabel 24/7", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.HeroSubtitle)
        }
    }
}

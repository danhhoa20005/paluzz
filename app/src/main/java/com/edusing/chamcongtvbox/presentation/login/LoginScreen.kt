package com.edusing.chamcongtvbox.presentation.login

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edusing.chamcongtvbox.domain.model.AppMode
import com.edusing.chamcongtvbox.presentation.components.TammiModeSwitcherCard
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.liquidGlassLayer
import com.edusing.chamcongtvbox.presentation.theme.rememberDeviceUiSpec

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
        contentAlignment = Alignment.Center,
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

/** Thẻ tiêu đề **Clear Liquid Glass Sáng** tinh giản (không khối màu đậm). */
@Composable
private fun CompactLiquidBrandBanner(mode: AppMode, title: String, orgLabel: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassLayer(shape = TammiRadii.panelShape, tier = LiquidGlassTier.CHROME, shadowElevation = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = TammiSpacing.lg, vertical = TammiSpacing.md), verticalArrangement = Arrangement.spacedBy(TammiSpacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AppColors.GreenBright))
                Text(title, fontSize = 17.5.sp, fontWeight = FontWeight.Black, color = AppColors.Text)
            }
            Text("Đăng nhập 1 lần — tự động kết nối máy chấm công 24/7", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = AppColors.TextSecondary)
        }
    }
}

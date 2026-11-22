package com.edusing.chamcongtvbox.presentation.push

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.presentation.components.LiquidGlassChip
import com.edusing.chamcongtvbox.presentation.components.TammiAdaptiveBar
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TvSegmentedTabs
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Thanh chọn máy đích, lọc tab Chưa có/Đã có và tìm kiếm **Liquid Glass 3D** thuần chữ & màu sắc. */
@Composable
internal fun PushTargetAndFilterBars(
    state: PushMembersUiState,
    isCompact: Boolean,
    floating: Boolean,
    horizontalPadding: Dp,
    viewModel: PushMembersViewModel,
) {
    TammiAdaptiveBar(floating = floating, horizontalPadding = horizontalPadding, bottomDivider = true) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
            // Hàng 1: Chọn máy đích bằng LiquidGlassChip + nút Tải lại thuần chữ
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm),
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.connectedDevices.forEach { target ->
                        LiquidGlassChip(
                            label = target.device.displayName,
                            selected = state.selectedTargetKey == target.device.key,
                            onClick = { viewModel.selectTarget(target.device.key) },
                        )
                    }
                    if (state.connectedDevices.size > 1) {
                        LiquidGlassChip(
                            label = "Tất cả (${state.connectedDevices.size})",
                            selected = state.isTargetAll,
                            selectedColor = AppColors.Red,
                            unselectedLabelColor = AppColors.RedText,
                            onClick = { viewModel.selectTarget(PushMembersUiState.TARGET_ALL_KEY) },
                        )
                    }
                }
                TammiOutlinedButton(
                    text = "Tải lại",
                    enabled = !state.isLoading && !state.isPushing,
                    compact = true,
                    contentColor = AppColors.Primary,
                    onClick = viewModel::refreshTargetsAndLoad,
                )
            }

            // Hàng 2: Tab phân đoạn + Tìm kiếm + Chọn hết/Bỏ chọn
            if (isCompact) {
                TvSegmentedTabs(
                    tabs = listOf("Chưa có (${state.notOnDeviceCount})", "Đã có (${state.onDeviceCount})"),
                    selectedIndex = if (state.activeTab == PushListTab.NOT_ON_DEVICE) 0 else 1,
                    onSelect = { viewModel.selectTab(if (it == 0) PushListTab.NOT_ON_DEVICE else PushListTab.ON_DEVICE) },
                )
                SearchWithSelectionRow(
                    query = state.searchQuery,
                    placeholder = "Tìm tên, mã thẻ, ${state.groupLabel.lowercase()}...",
                    onQueryChange = viewModel::onSearchQueryChange,
                    canSelectAll = !state.isPushing && state.visibleSections.isNotEmpty(),
                    canClear = state.selectedKeys.isNotEmpty() && !state.isPushing,
                    onSelectAll = viewModel::selectAllVisible,
                    onClear = viewModel::clearSelection,
                    isCompact = true,
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    TvSegmentedTabs(
                        tabs = listOf("Chưa có (${state.notOnDeviceCount})", "Đã có (${state.onDeviceCount})"),
                        selectedIndex = if (state.activeTab == PushListTab.NOT_ON_DEVICE) 0 else 1,
                        onSelect = { viewModel.selectTab(if (it == 0) PushListTab.NOT_ON_DEVICE else PushListTab.ON_DEVICE) },
                        modifier = Modifier.weight(0.95f),
                    )
                    SearchWithSelectionRow(
                        query = state.searchQuery,
                        placeholder = "Tìm tên, mã thẻ, ${state.groupLabel.lowercase()}...",
                        onQueryChange = viewModel::onSearchQueryChange,
                        canSelectAll = !state.isPushing && state.visibleSections.isNotEmpty(),
                        canClear = state.selectedKeys.isNotEmpty() && !state.isPushing,
                        onSelectAll = viewModel::selectAllVisible,
                        onClear = viewModel::clearSelection,
                        isCompact = false,
                        modifier = Modifier.weight(1.35f),
                    )
                }
            }
        }
    }
}

/** Thanh tìm kiếm kèm nút Chọn hết / Bỏ chọn thuần chữ chuẩn Liquid Glass AAA. */
@Composable
internal fun SearchWithSelectionRow(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    canSelectAll: Boolean,
    canClear: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(placeholder, fontSize = 13.sp, maxLines = 1) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    var clearFocused by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.onFocusChanged { clearFocused = it.isFocused }.tvFocusRing(clearFocused, 12),
                    ) {
                        Text("Xoá", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.TextSecondary)
                    }
                }
            } else null,
            singleLine = true,
            shape = TammiRadii.inputShape,
            colors = tammiFieldColors(),
            modifier = Modifier.weight(1f),
        )
        TammiOutlinedButton(
            text = "Chọn hết",
            enabled = canSelectAll,
            compact = true,
            contentColor = AppColors.Primary,
            onClick = onSelectAll,
        )
        if (!isCompact || canClear) {
            TammiOutlinedButton(
                text = "Bỏ chọn",
                enabled = canClear,
                compact = true,
                contentColor = AppColors.TextSecondary,
                onClick = onClear,
            )
        }
    }
}

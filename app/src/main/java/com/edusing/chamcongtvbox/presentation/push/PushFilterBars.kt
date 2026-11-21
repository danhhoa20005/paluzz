package com.edusing.chamcongtvbox.presentation.push

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.presentation.components.TammiAdaptiveBar
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TvSegmentedTabs
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Thanh chọn máy đích, lọc tab Chưa có/Đã có và tìm kiếm **Liquid Glass** thuần chữ & màu sắc. */
@Composable
internal fun PushTargetAndFilterBars(
    state: PushMembersUiState,
    isCompact: Boolean,
    floating: Boolean,
    horizontalPadding: Dp,
    viewModel: PushMembersViewModel,
) {
    TammiAdaptiveBar(floating = floating, horizontalPadding = horizontalPadding, bottomDivider = true) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Hàng 1: Chọn máy đích + nút Tải lại thuần chữ
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.connectedDevices.forEach { target ->
                        val selected = state.selectedTargetKey == target.device.key
                        var focused by remember { mutableStateOf(false) }
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.selectTarget(target.device.key) },
                            label = { Text(target.device.displayName, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = AppColors.SurfaceSubtle,
                                labelColor = AppColors.Text,
                                selectedContainerColor = AppColors.Primary,
                                selectedLabelColor = Color.White,
                            ),
                            modifier = Modifier.onFocusChanged { focused = it.isFocused }.tvFocusRing(focused, 8),
                        )
                    }
                    if (state.connectedDevices.size > 1) {
                        var focused by remember { mutableStateOf(false) }
                        FilterChip(
                            selected = state.isTargetAll,
                            onClick = { viewModel.selectTarget(PushMembersUiState.TARGET_ALL_KEY) },
                            label = { Text("Tất cả (${state.connectedDevices.size})", fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = AppColors.SurfaceSubtle,
                                labelColor = AppColors.Red,
                                selectedContainerColor = AppColors.Red,
                                selectedLabelColor = Color.White,
                            ),
                            modifier = Modifier.onFocusChanged { focused = it.isFocused }.tvFocusRing(focused, 8),
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
                    canClear = state.selectedCardIds.isNotEmpty() && !state.isPushing,
                    onSelectAll = viewModel::selectAllVisible,
                    onClear = viewModel::clearSelection,
                    isCompact = true,
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        canClear = state.selectedCardIds.isNotEmpty() && !state.isPushing,
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
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(placeholder, fontSize = 13.sp, maxLines = 1) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    var clearFocused by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.onFocusChanged { clearFocused = it.isFocused }.tvFocusRing(clearFocused, 10),
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

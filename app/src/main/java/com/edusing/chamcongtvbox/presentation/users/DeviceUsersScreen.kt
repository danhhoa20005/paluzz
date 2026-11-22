package com.edusing.chamcongtvbox.presentation.users

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import com.edusing.chamcongtvbox.presentation.components.LiquidGlassChip
import com.edusing.chamcongtvbox.presentation.components.TammiAdaptiveBar
import com.edusing.chamcongtvbox.presentation.components.TammiErrorStateCard
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.components.TvSafeConfirmDialog
import com.edusing.chamcongtvbox.presentation.components.TvSubScreenHeader
import com.edusing.chamcongtvbox.presentation.push.SearchWithSelectionRow
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LocalFloatingNavBottomInset
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.rememberDeviceUiSpec

/** Màn hình Người trên máy chấm công — **Liquid Glass 3D** thuần chữ & màu, thanh xoá nổi theo ngữ cảnh. */
@Composable
fun DeviceUsersScreen(
    viewModel: DeviceUsersViewModel,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ui = rememberDeviceUiSpec()
    val bottomInset = LocalFloatingNavBottomInset.current
    val rootMod = if (ui.isTwoPane && onBack == null) {
        Modifier.fillMaxSize().padding(top = ui.verticalPadding, bottom = ui.verticalPadding)
    } else {
        Modifier.fillMaxSize().padding(top = TammiSpacing.xs, bottom = bottomInset)
    }

    Column(modifier = rootMod) {
        if (onBack != null) {
            TvSubScreenHeader("Người trên máy (${state.users.size})", "", onBack = onBack, backEnabled = !state.isDeleting)
        }

        val isFloatingTv = ui.isTwoPane && onBack == null
        // Thanh chọn máy bằng LiquidGlassChip + đọc lại + tìm kiếm Liquid Glass
        TammiAdaptiveBar(floating = isFloatingTv, horizontalPadding = ui.horizontalPadding, bottomDivider = true) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
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
                        state.connectedDevices.forEach { dev ->
                            val selected = state.selectedDeviceKey == dev.device.key
                            LiquidGlassChip(
                                label = "${dev.device.displayName} (${if (selected) "${state.users.size}" else dev.device.ip})",
                                selected = selected,
                                onClick = { viewModel.selectDevice(dev.device.key) },
                            )
                        }
                    }
                    if (state.isHikvisionSelected) {
                        TammiOutlinedButton(
                            text = if (state.onlyMissingFace) "Tất cả" else "Chưa mặt",
                            enabled = !state.isLoading && !state.isDeleting,
                            compact = true,
                            contentColor = if (state.onlyMissingFace) AppColors.AmberText else AppColors.Text,
                            onClick = viewModel::toggleOnlyMissingFace,
                        )
                    }
                    TammiOutlinedButton(
                        text = "Đọc lại",
                        enabled = !state.isLoading && !state.isDeleting,
                        compact = true,
                        contentColor = AppColors.Primary,
                        onClick = viewModel::refreshAndLoad,
                    )
                }
                SearchWithSelectionRow(
                    query = state.searchQuery,
                    placeholder = "Tìm mã thẻ, tên máy, họ tên, ${state.groupLabel.lowercase()}...",
                    onQueryChange = viewModel::onSearchQueryChange,
                    canSelectAll = !state.isLoading && !state.isDeleting && state.filteredUsers.isNotEmpty(),
                    canClear = state.selectedKeys.isNotEmpty() && !state.isDeleting,
                    onSelectAll = viewModel::selectAllFiltered,
                    onClear = viewModel::clearSelection,
                    isCompact = ui.isCompact,
                )
            }
        }

        if (!state.errorMessage.isNullOrBlank() || !state.statusMessage.isNullOrBlank() || state.progress != null) {
            Column(modifier = Modifier.padding(horizontal = ui.horizontalPadding, vertical = TammiSpacing.sm), verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
                if (!state.errorMessage.isNullOrBlank()) TammiErrorStateCard(message = state.errorMessage.orEmpty())
                if (!state.statusMessage.isNullOrBlank()) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.GreenSoft).border(1.dp, AppColors.Green.copy(alpha = 0.28f), RoundedCornerShape(12.dp)).padding(TammiSpacing.md)) {
                        Text(state.statusMessage.orEmpty(), color = AppColors.GreenText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                state.progress?.let { prog ->
                    val pct = if (prog.total > 0) prog.completed.toFloat() / prog.total else 0f
                    TammiGlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.md), borderColor = AppColors.Red.copy(alpha = 0.45f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(prog.currentLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.RedText)
                            Text("${prog.completed}/${prog.total}", fontWeight = FontWeight.Black, fontSize = 13.sp, color = AppColors.RedText)
                        }
                        Spacer(Modifier.height(TammiSpacing.sm))
                        LinearProgressIndicator(progress = { pct.coerceIn(0f, 1f) }, color = AppColors.Red, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                    }
                }
            }
        }

        DeviceUsersTableCard(
            state = state,
            isCompact = ui.isCompact,
            onToggleSelect = viewModel::toggleUserSelected,
            onDeleteSingle = viewModel::requestDeleteSingle,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = ui.horizontalPadding, vertical = 8.dp),
        )

        // Thanh xoá theo ngữ cảnh Liquid Glass (chỉ hiện khi có người được chọn)
        AnimatedVisibility(visible = state.selectedKeys.isNotEmpty()) {
            TammiAdaptiveBar(floating = isFloatingTv, horizontalPadding = ui.horizontalPadding, topDivider = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TammiOutlinedButton(
                        text = "Bỏ chọn",
                        enabled = !state.isDeleting,
                        compact = true,
                        contentColor = AppColors.Text,
                        onClick = viewModel::clearSelection,
                    )
                    TvActionButton(
                        text = "Xoá đã chọn (${state.selectedKeys.size})",
                        containerColor = AppColors.Red,
                        enabled = !state.isLoading && !state.isDeleting,
                        onClick = viewModel::requestDeleteSelected,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (state.pendingDeleteUsers.isNotEmpty() && !state.pendingDeleteSummary.isNullOrBlank()) {
        TvSafeConfirmDialog(
            title = "Xoá người dùng trên máy?",
            message = state.pendingDeleteSummary.orEmpty(),
            confirmText = "Xoá vĩnh viễn",
            cancelText = "Không",
            confirmColor = AppColors.Red,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDeleteDialog,
        )
    }
}

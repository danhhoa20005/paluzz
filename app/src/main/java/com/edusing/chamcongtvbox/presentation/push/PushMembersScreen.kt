package com.edusing.chamcongtvbox.presentation.push

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edusing.chamcongtvbox.presentation.components.TammiAdaptiveBar
import com.edusing.chamcongtvbox.presentation.components.TammiEmptyStateView
import com.edusing.chamcongtvbox.presentation.components.TammiErrorStateCard
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.components.TvSafeConfirmDialog
import com.edusing.chamcongtvbox.presentation.components.TvSubScreenHeader
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LocalFloatingNavBottomInset
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.rememberDeviceUiSpec
import java.io.File

/** Màn hình Đẩy danh sách xuống máy chấm công — **Liquid Glass 3D** thuần màu sắc & chữ, thanh thao tác nổi. */
@Composable
fun PushMembersScreen(
    viewModel: PushMembersViewModel,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ui = rememberDeviceUiSpec()
    val bottomInset = LocalFloatingNavBottomInset.current
    val rootMod = if (ui.isTwoPane && onBack == null) {
        Modifier.fillMaxSize().padding(top = ui.verticalPadding, bottom = ui.verticalPadding)
    } else {
        Modifier.fillMaxSize().padding(top = 4.dp, bottom = bottomInset)
    }

    Column(modifier = rootMod) {
        if (onBack != null) {
            TvSubScreenHeader("Đẩy danh sách ${state.personLabel.lowercase()}", "", onBack = onBack, backEnabled = !state.isPushing)
        }

        val isFloatingTv = ui.isTwoPane && onBack == null
        PushTargetAndFilterBars(state = state, isCompact = ui.isCompact, floating = isFloatingTv, horizontalPadding = ui.horizontalPadding, viewModel = viewModel)

        if (!state.errorMessage.isNullOrBlank() || !state.exportSuccessPath.isNullOrBlank() || state.progress != null) {
            Column(modifier = Modifier.padding(horizontal = ui.horizontalPadding, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!state.errorMessage.isNullOrBlank()) TammiErrorStateCard(message = state.errorMessage.orEmpty())
                if (!state.exportSuccessPath.isNullOrBlank()) {
                    val path = state.exportSuccessPath.orEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppColors.GreenSoft)
                            .border(1.dp, AppColors.Green.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Đã xuất Word: ${File(path).name}", color = AppColors.GreenText, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        TvActionButton("Chia sẻ", AppColors.Green, compact = true, onClick = {
                            runCatching {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/msword"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Chia sẻ danh sách Word"))
                            }
                        })
                    }
                }
                state.progress?.let { prog ->
                    val pct = if (prog.total > 0) prog.completed.toFloat() / prog.total else 0f
                    TammiGlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(12.dp), borderColor = AppColors.Primary.copy(alpha = 0.45f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(prog.currentLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.PrimaryDark, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${prog.completed}/${prog.total}", fontWeight = FontWeight.Black, fontSize = 13.sp, color = AppColors.Primary)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { pct.coerceIn(0f, 1f) }, color = AppColors.Primary, modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)))
                    }
                }
            }
        }

        // Danh sách thành viên trên tấm kính Liquid Glass 3D
        TammiGlassPanel(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = ui.horizontalPadding, vertical = 8.dp),
            padding = PaddingValues(0.dp),
        ) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.8.dp, color = AppColors.Primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Đang tải danh sách...", fontWeight = FontWeight.Bold, color = AppColors.Primary)
                    }
                }
                state.visibleSections.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TammiEmptyStateView(
                        title = "Không có ${state.personLabel.lowercase()}",
                        subtitle = if (state.searchQuery.isNotBlank()) "Không tìm thấy kết quả khớp \"${state.searchQuery}\"." else "Danh sách trong mục này đang trống.",
                        accentColor = AppColors.OrbTeal,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.visibleSections.forEach { section ->
                        item(key = "grp_${section.classId}") {
                            PushGroupHeaderRow(section, { viewModel.toggleGroupSelection(section) }, { viewModel.toggleGroupExpanded(section.classId) })
                        }
                        if (section.isExpanded) {
                            items(section.rows, key = { r -> "m_${section.classId}_${r.pushKey}_${r.member.name}" }) { row ->
                                PushMemberRowItem(row, isCompact = ui.isCompact) { if (row.isPushableOnTarget) viewModel.toggleMemberSelected(row.pushKey) }
                            }
                        }
                    }
                }
            }
        }

        // Thanh hành động đáy Liquid Glass (Thumb Zone / Floating TV Dock): Xuất Word + Đẩy xuống máy
        TammiAdaptiveBar(floating = ui.isTwoPane && onBack == null, horizontalPadding = ui.horizontalPadding, topDivider = true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TammiOutlinedButton(
                    text = if (state.isExporting) "Đang xuất..." else "Xuất Word",
                    enabled = !state.isLoading && !state.isPushing && !state.isExporting && state.allMembers.isNotEmpty(),
                    compact = ui.isCompact,
                    onClick = viewModel::exportWordDocument,
                )
                TvActionButton(
                    text = state.pushButtonLabel,
                    containerColor = if (state.isTargetAll && state.targetHandles.size > 1) AppColors.Red else AppColors.Green,
                    enabled = !state.isLoading && !state.isPushing && state.selectedKeys.isNotEmpty() && state.targetHandles.isNotEmpty(),
                    compact = false,
                    onClick = viewModel::requestPushSelected,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (state.showPushAllWarningDialog) {
        val names = state.targetHandles.joinToString("\n• ") { "${it.device.displayName} (${it.device.ip})" }
        TvSafeConfirmDialog(
            title = "Đẩy xuống TẤT CẢ máy?",
            message = "Sẽ đẩy ${state.selectedKeys.size} ${state.personLabel.lowercase()} xuống ${state.targetHandles.size} máy:\n• $names",
            confirmText = "Đẩy tất cả",
            cancelText = "Huỷ",
            confirmColor = AppColors.Red,
            onConfirm = viewModel::confirmPushAll,
            onDismiss = viewModel::dismissPushAllWarning,
        )
    }

    state.pushSummary?.let { PushSummaryDialog(it, viewModel::dismissPushSummary) }
}

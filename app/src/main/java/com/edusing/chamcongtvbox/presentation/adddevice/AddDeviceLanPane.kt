package com.edusing.chamcongtvbox.presentation.adddevice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.presentation.components.TammiEmptyStateView
import com.edusing.chamcongtvbox.presentation.components.TammiErrorStateCard
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing

/**
 * Bảng tự động tìm & nhận máy chấm công trong mạng LAN (SADP Hikvision ~1.5s + FK 5005):
 * - Chỉ nổi bật các máy chấm công thật với nút lớn **Nhận máy ngay** (1 chạm).
 * - Các thiết bị mạng khác (router, PC...) và đổi dải IP xếp gọn vào mục mở rộng bên dưới.
 */
@Composable
internal fun AddDeviceLanPane(
    state: AddDeviceUiState,
    onScanPrefixChange: (String) -> Unit,
    onStartScan: () -> Unit,
    onQuickScanPrefix: (String) -> Unit,
    onQuickAccept: (LanHostInfo) -> Unit,
    onEditHost: (LanHostInfo) -> Unit,
    onToggleOtherHosts: () -> Unit,
    onSwitchToManual: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val terminals = state.detectedTerminals
    val others = state.otherLanHosts

    TammiGlassPanel(modifier = modifier, padding = PaddingValues(TammiSpacing.lg)) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (terminals.isNotEmpty()) AppColors.GreenBright else AppColors.OrbTeal))
                    Column {
                        Text(
                            text = if (terminals.isNotEmpty()) "Tìm thấy ${terminals.size} máy chấm công" else "Tự tìm máy trong LAN",
                            fontWeight = FontWeight.Black, fontSize = 15.5.sp, color = AppColors.Text,
                        )
                        Text(
                            text = state.subnetInfo?.let { "Mạng ${it.prefix}.x • Tự quét Hikvision & FK" } ?: "Kết nối Wi-Fi/LAN cùng máy chấm công",
                            fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TvActionButton(
                    text = if (state.isScanning) "Đang quét" else "Quét lại",
                    containerColor = AppColors.Primary,
                    enabled = !state.isScanning,
                    compact = true,
                    onClick = onStartScan,
                )
            }

            if (!state.errorMessage.isNullOrBlank()) TammiErrorStateCard(message = state.errorMessage.orEmpty())

            if (state.isScanning) {
                val pct = if (state.scanTotal > 0) state.scanCompleted.toFloat() / state.scanTotal else 0f
                LinearProgressIndicator(progress = { pct.coerceIn(0f, 1f) }, color = AppColors.Primary, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
                Text(
                    text = if (terminals.isEmpty()) "Đang quét nhanh SADP & LAN (${state.scanCompleted}/${state.scanTotal})..."
                    else "Đã thấy ${terminals.size} máy — đang dò thêm máy FK (${state.scanCompleted}/${state.scanTotal})...",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Primary,
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (terminals.isEmpty() && !state.isScanning) {
                    item(key = "empty_terminals") {
                        TammiEmptyStateView(
                            title = "Chưa thấy máy chấm công",
                            subtitle = "Kiểm tra máy chấm công đã bật nguồn, chung Wi-Fi/LAN rồi bấm Quét lại hoặc nhập IP thủ công.",
                            accentColor = AppColors.OrbTeal,
                            actionLabel = if (onSwitchToManual != null) "Nhập IP thủ công" else "Quét lại ngay",
                            onAction = onSwitchToManual ?: onStartScan,
                        )
                    }
                }

                items(terminals, key = { "term_${it.ip}" }) { host ->
                    DetectedTerminalCard(
                        host = host,
                        isConnecting = state.savingHostIp == host.ip,
                        busyAny = state.isSaving,
                        hasSavedHikPassword = state.hasSavedHikPassword,
                        onQuickAccept = { onQuickAccept(host) },
                        onEdit = { onEditHost(host) },
                    )
                }

                item(key = "other_toggle") {
                    HorizontalDivider(color = AppColors.Border.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp))
                    TammiOutlinedButton(
                        text = if (state.showOtherHosts) "Thu gọn dải IP & thiết bị khác ▲"
                        else "Đổi dải IP / Xem ${others.size} thiết bị mạng khác ▾",
                        compact = true,
                        onClick = onToggleOtherHosts,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }

                if (state.showOtherHosts) {
                    item(key = "subnet_controls") { SubnetQuickControls(state, onScanPrefixChange, onQuickScanPrefix) }
                    items(others, key = { "other_${it.ip}" }) { host -> OtherLanHostRow(host = host, onClick = { onEditHost(host) }) }
                }
            }
        }
    }
}

package com.edusing.chamcongtvbox.presentation.adddevice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiEmptyStateView
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Bảng quét thiết bị mạng LAN **Liquid Glass 3D** thuần màu & chữ (đánh dấu màu xanh lục cho máy FK). */
@Composable
internal fun AddDeviceLanPane(
    state: AddDeviceUiState,
    onScanPrefixChange: (String) -> Unit,
    onStartScan: () -> Unit,
    onQuickScanPrefix: (String) -> Unit,
    onSelectHost: (LanHostInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    TammiGlassPanel(modifier = modifier, padding = PaddingValues(TammiSpacing.lg)) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AppColors.OrbTeal))
                    Column {
                        Text("Quét mạng LAN", fontWeight = FontWeight.Black, fontSize = 15.5.sp, color = AppColors.Text)
                        Text(
                            text = state.subnetInfo?.let { "${it.localIp} (${it.interfaceName})" } ?: "Chưa có LAN/Wi-Fi",
                            fontSize = 12.sp,
                            color = AppColors.TextSecondary,
                        )
                    }
                }
                TvActionButton(
                    text = if (state.isScanning) "Đang quét..." else "Quét",
                    containerColor = AppColors.Primary,
                    enabled = !state.isScanning,
                    compact = true,
                    onClick = onStartScan,
                )
            }

            OutlinedTextField(
                value = state.scanPrefix,
                onValueChange = onScanPrefixChange,
                label = { Text("Dải IP (VD: 192.168.1)") },
                singleLine = true,
                enabled = !state.isScanning,
                shape = TammiRadii.inputShape,
                colors = tammiFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.quickPrefixes.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.quickPrefixes.forEach { prefix ->
                        val active = state.scanPrefix == prefix
                        val isLocal = state.subnetInfo?.prefix == prefix
                        var focused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) AppColors.PrimarySoft else AppColors.SurfaceSubtle)
                                .border(1.dp, if (active) AppColors.Primary else AppColors.Border, RoundedCornerShape(10.dp))
                                .onFocusChanged { focused = it.isFocused }
                                .tvFocusRing(focused, 10)
                                .clickable(enabled = !state.isScanning) { onQuickScanPrefix(prefix) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (isLocal) "$prefix.x (Hiện tại)" else "$prefix.x (LAN chính)",
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                                color = if (active) AppColors.Primary else AppColors.Text,
                            )
                        }
                    }
                }
            }

            if (state.isScanning) {
                val pct = if (state.scanTotal > 0) state.scanCompleted.toFloat() / state.scanTotal else 0f
                LinearProgressIndicator(progress = { pct.coerceIn(0f, 1f) }, color = AppColors.Primary, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                Text("Đã quét ${state.scanCompleted}/${state.scanTotal} IP...", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Primary)
            }

            HorizontalDivider(color = AppColors.Border)

            if (state.discoveredHosts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TammiEmptyStateView(
                        title = if (state.isScanning) "Đang tìm thiết bị..." else "Chưa có kết quả quét",
                        subtitle = if (state.isScanning) "Vui lòng chờ vài giây." else "Chọn dải IP rồi bấm Quét để tìm máy chấm công.",
                        accentColor = AppColors.OrbTeal,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.discoveredHosts, key = { it.ip }) { host ->
                        LanHostRowItem(host, selected = state.ip == host.ip, onClick = { onSelectHost(host) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LanHostRowItem(host: LanHostInfo, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val isTerminal = host.isDetectedTerminal
    val bg = when {
        selected -> AppColors.PrimarySoft
        isTerminal || host.alreadyConfigured -> AppColors.GreenSoft
        else -> AppColors.SurfaceSubtle
    }
    val borderColor = when {
        selected -> AppColors.Primary
        isTerminal || host.alreadyConfigured -> AppColors.Green
        else -> AppColors.Border
    }
    val dotColor = if (isTerminal || host.alreadyConfigured) AppColors.GreenBright else AppColors.Primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(TammiRadii.inputShape)
            .background(bg)
            .border(1.dp, borderColor, TammiRadii.inputShape)
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, 16)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(host.ip, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = AppColors.Text)
            Text(host.note, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (isTerminal || host.alreadyConfigured) AppColors.GreenText else AppColors.TextSecondary)
        }
        if (isTerminal || host.alreadyConfigured) {
            Spacer(Modifier.width(8.dp))
            val badgeLabel = when {
                host.alreadyConfigured -> "Đã thêm"
                host.isHikvisionTerminal -> "Hikvision"
                else -> "Máy FK"
            }
            TammiStatusPill(
                label = badgeLabel,
                tone = if (host.alreadyConfigured) TammiBadgeTone.PRIMARY else TammiBadgeTone.SUCCESS,
            )
        }
    }
}

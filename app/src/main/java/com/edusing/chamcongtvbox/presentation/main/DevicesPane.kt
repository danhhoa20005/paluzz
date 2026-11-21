package com.edusing.chamcongtvbox.presentation.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.DeviceConnectionState
import com.edusing.chamcongtvbox.domain.model.DeviceRuntimeStatus
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiEmptyStateView
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing

/** Khu vực Máy chấm công **Liquid Glass 3D** cân xứng, không trùng lặp thao tác, tối ưu cả TV Box & Điện thoại. */
@Composable
internal fun DevicesPane(
    devices: List<DeviceRuntimeStatus>,
    isRunning: Boolean,
    isBusy: Boolean,
    isDiscovering: Boolean,
    onDiscover: () -> Unit,
    onReconnectAll: () -> Unit,
    onRename: (DeviceConfig) -> Unit,
    onReconnect: (DeviceConfig) -> Unit,
    onDelete: (DeviceConfig) -> Unit,
    modifier: Modifier = Modifier,
    showQuickToolbar: Boolean = false,
    isTv: Boolean = false,
    onAddDevice: (() -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Máy chấm công (${devices.size})",
                fontWeight = FontWeight.Bold,
                fontSize = if (isTv) 17.sp else 15.sp,
                color = AppColors.Text,
                modifier = Modifier.weight(1f),
            )
            if (showQuickToolbar && !isRunning) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TammiOutlinedButton(
                        text = if (isDiscovering) "Đang dò..." else "Dò LAN",
                        enabled = !isBusy,
                        compact = true,
                        contentColor = AppColors.OrbCobalt,
                        onClick = onDiscover,
                    )
                    if (onAddDevice != null) {
                        TvActionButton(
                            text = "Thêm máy",
                            containerColor = AppColors.OrbTeal,
                            enabled = !isBusy,
                            compact = true,
                            onClick = onAddDevice,
                        )
                    }
                    if (devices.size > 1) {
                        TammiOutlinedButton(text = "Nối lại hết", enabled = !isBusy, compact = true, onClick = onReconnectAll)
                    }
                }
            } else if (devices.size > 1 && !isRunning) {
                TammiOutlinedButton(text = "Nối lại hết", enabled = !isBusy, compact = true, onClick = onReconnectAll)
            }
        }

        if (devices.isEmpty()) {
            TammiGlassPanel(modifier = Modifier.fillMaxWidth()) {
                TammiEmptyStateView(
                    accentColor = AppColors.OrbTeal,
                    title = "Chưa có máy chấm công",
                    subtitle = "Bấm Dò LAN để tự tìm máy trong mạng hoặc bấm Thêm máy để nhập IP.",
                    actionLabel = if (isDiscovering) null else "Dò LAN ngay",
                    onAction = if (isDiscovering) null else onDiscover,
                )
            }
        } else {
            devices.forEach { item ->
                TerminalCard(
                    item = item,
                    isRunning = isRunning,
                    isBusy = isBusy,
                    isTv = isTv,
                    onRename = { onRename(item.config) },
                    onReconnect = { onReconnect(item.config) },
                    onDelete = { onDelete(item.config) },
                )
            }
        }
    }
}

@Composable
private fun TerminalCard(
    item: DeviceRuntimeStatus,
    isRunning: Boolean,
    isBusy: Boolean,
    isTv: Boolean,
    onRename: () -> Unit,
    onReconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    val (accentColor, pillLabel, pillTone) = when (item.state) {
        DeviceConnectionState.CONNECTED -> Triple(AppColors.GreenBright, "Online", TammiBadgeTone.SUCCESS)
        DeviceConnectionState.CONNECTING -> Triple(AppColors.Amber, "Đang nối", TammiBadgeTone.WARNING)
        DeviceConnectionState.ERROR -> Triple(AppColors.Red, "Offline", TammiBadgeTone.DANGER)
        DeviceConnectionState.DISCONNECTED -> Triple(AppColors.Gray, "Chưa nối", TammiBadgeTone.NEUTRAL)
    }

    TammiGlassPanel(
        modifier = Modifier.fillMaxWidth(),
        padding = PaddingValues(if (isTv) TammiSpacing.lg else TammiSpacing.md),
        borderColor = if (item.isConnected) AppColors.Green.copy(alpha = 0.30f) else null,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(16.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(accentColor))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.config.displayName, fontSize = if (isTv) 16.sp else 15.sp, fontWeight = FontWeight.Bold, color = AppColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val protoHint = item.firmwareProfile.ifBlank { if (item.config.isHikvision) "Hikvision ISAPI" else "" }
                val fw = if (protoHint.isNotBlank()) " • $protoHint" else ""
                Text("${item.config.ip}:${item.config.port}$fw", fontFamily = FontFamily.Monospace, fontSize = if (isTv) 13.sp else 12.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(TammiSpacing.sm))
            TammiStatusPill(label = pillLabel, tone = pillTone)
        }

        if (!item.lastError.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(item.lastError, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = AppColors.Red, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(12.dp))

        // 3 nút thao tác cân xứng đều nhau (không trùng tab Trên máy đã có ở thanh điều hướng)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TammiOutlinedButton(text = "Nối lại", enabled = !isBusy, compact = true, onClick = onReconnect, modifier = Modifier.weight(1f))
            if (!isRunning) {
                TammiOutlinedButton(text = "Đổi tên", enabled = !isBusy, compact = true, contentColor = AppColors.Text, onClick = onRename, modifier = Modifier.weight(1f))
                TammiOutlinedButton(text = "Xoá", enabled = !isBusy, compact = true, contentColor = AppColors.Red, borderColor = AppColors.Red.copy(alpha = 0.30f), onClick = onDelete, modifier = Modifier.weight(0.85f))
            }
        }
    }
}

/** Bảng thông số trực 24/7 tầm nhìn xa 3m dành riêng cho TV Box (không lồng khung trong khung). */
@Composable
internal fun TvLiveStatusPane(
    state: MainUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TammiSpacing.md)) {
        TammiGlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.lg), borderColor = AppColors.Green.copy(alpha = 0.34f)) {
            Text("Lượt chấm hôm nay", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${state.totalCheckInsToday}",
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = (-1).sp,
                color = AppColors.Green,
            )
            val pollShort = state.lastPollTimeText.substringBefore(" ")
            Text(
                text = if (pollShort.isNotBlank()) "Quét gần nhất: $pollShort" else "Đang lắng nghe máy chấm công...",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
            )
        }

        TammiGlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(TammiSpacing.lg)) {
            Text("Thiết bị đang trực (${state.connectedCount}/${state.totalDeviceCount})", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = AppColors.Text)
            Spacer(Modifier.height(10.dp))
            state.devices.forEachIndexed { idx, dev ->
                if (idx > 0) HorizontalDivider(color = AppColors.Border.copy(alpha = 0.42f))
                val dot = if (dev.isConnected) AppColors.GreenBright else AppColors.Red
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dev.config.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(dev.config.ip, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextSecondary)
                    }
                    Text(
                        text = if (dev.isConnected) "Online" else "Offline",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (dev.isConnected) AppColors.Green else AppColors.Red,
                    )
                }
            }
        }
    }
}

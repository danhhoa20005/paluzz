package com.edusing.chamcongtvbox.presentation.adddevice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.LanHostInfo
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.components.tammiFieldColors
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing

/** Thẻ lớn cho từng máy chấm công tìm thấy (Hikvision / FK) với nút **Nhận máy ngay** 1 chạm. */
@Composable
internal fun DetectedTerminalCard(
    host: LanHostInfo,
    isConnecting: Boolean,
    busyAny: Boolean,
    hasSavedHikPassword: Boolean,
    onQuickAccept: () -> Unit,
    onEdit: () -> Unit,
) {
    val isHik = host.isHikvisionTerminal
    val title = if (isHik) host.modelHint.ifBlank { "Hikvision ISAPI" } else "Máy chấm công FK"
    val borderColor = if (host.alreadyConfigured) AppColors.Primary.copy(alpha = 0.32f) else AppColors.Green.copy(alpha = 0.45f)
    val bg = if (host.alreadyConfigured) AppColors.PrimarySoft else AppColors.GreenSoft

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TammiRadii.inputShape)
            .background(bg)
            .border(1.2.dp, borderColor, TammiRadii.inputShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(11.dp).clip(CircleShape).background(if (isHik) AppColors.OrbTeal else AppColors.OrbCobalt))
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Black, fontSize = 15.sp, color = AppColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${host.ip}:${host.port}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = AppColors.TextSecondary)
            }
            TammiStatusPill(
                label = when {
                    host.alreadyConfigured -> "Đã nhận ✓"
                    isHik -> "Hikvision"
                    else -> "FK 5005"
                },
                tone = if (host.alreadyConfigured) TammiBadgeTone.PRIMARY else TammiBadgeTone.SUCCESS,
            )
        }

        if (!host.alreadyConfigured) {
            if (isConnecting) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.4.dp, color = AppColors.Green)
                    Spacer(Modifier.width(8.dp))
                    Text("Đang kết nối & nhận máy...", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppColors.GreenText)
                }
            } else {
                val btnLabel = if (isHik && !hasSavedHikPassword) "Nhận máy (nhập MK)" else "Nhận máy ngay"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvActionButton(
                        text = btnLabel,
                        containerColor = AppColors.Green,
                        enabled = !busyAny,
                        compact = true,
                        onClick = onQuickAccept,
                        modifier = Modifier.weight(1.35f),
                    )
                    TammiOutlinedButton(
                        text = "Sửa trước",
                        enabled = !busyAny,
                        compact = true,
                        onClick = onEdit,
                        modifier = Modifier.weight(0.75f),
                    )
                }
            }
        }
    }
}

/** Bộ đổi dải mạng LAN nhanh (ẩn gọn trong mục mở rộng). */
@Composable
internal fun SubnetQuickControls(
    state: AddDeviceUiState,
    onScanPrefixChange: (String) -> Unit,
    onQuickScanPrefix: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.scanPrefix,
            onValueChange = onScanPrefixChange,
            label = { Text("Dải IP cần quét (VD: 192.168.1)") },
            singleLine = true,
            enabled = !state.isScanning,
            shape = TammiRadii.inputShape,
            colors = tammiFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.quickPrefixes.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.quickPrefixes.forEach { prefix ->
                    var focused by remember { mutableStateOf(false) }
                    val active = state.scanPrefix == prefix
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) AppColors.PrimarySoft else AppColors.SurfaceSubtle)
                            .border(1.dp, if (active) AppColors.Primary else AppColors.Border, RoundedCornerShape(10.dp))
                            .onFocusChanged { focused = it.isFocused }
                            .tvFocusRing(focused, 10)
                            .clickable(enabled = !state.isScanning) { onQuickScanPrefix(prefix) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$prefix.x", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (active) AppColors.Primary else AppColors.Text)
                    }
                }
            }
        }
    }
}

/** Dòng gọn cho các thiết bị mạng LAN khác (router, PC...) để chọn tay khi cần. */
@Composable
internal fun OtherLanHostRow(host: LanHostInfo, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 46.dp)
            .clip(TammiRadii.inputShape)
            .background(AppColors.SurfaceSubtle)
            .border(1.dp, AppColors.Border, TammiRadii.inputShape)
            .onFocusChanged { focused = it.isFocused }
            .tvFocusRing(focused, 14)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(host.ip, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = AppColors.Text, modifier = Modifier.weight(1f))
        Text("Chọn →", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Primary)
    }
}

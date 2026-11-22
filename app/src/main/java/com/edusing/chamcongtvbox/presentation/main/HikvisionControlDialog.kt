package com.edusing.chamcongtvbox.presentation.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.edusing.chamcongtvbox.di.AppContainer
import com.edusing.chamcongtvbox.domain.model.BatchProgress
import com.edusing.chamcongtvbox.domain.model.DeviceConfig
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.model.HikvisionDoorCommand
import com.edusing.chamcongtvbox.domain.model.HikvisionHealthStatus
import com.edusing.chamcongtvbox.domain.model.HikvisionWebhookConfig
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TvActionButton
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.LiquidGlassTier
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.service.web.LocalWebConsoleServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/**
 * Trung tâm quản trị phần cứng chuyên sâu cho máy Hikvision (`DS-K1T320MX` / `MFWX`) ngay trên App TV Box & Điện thoại:
 * sức khoẻ bộ nhớ/mạng/cửa, gắn Webhook 1 chạm, đồng bộ giờ VN, khoá IP tĩnh, khởi động lại & nhân bản khuôn mặt sang máy khác.
 */
@Composable
internal fun HikvisionControlDialog(
    device: DeviceConfig,
    otherOnlineHikDevices: List<DeviceConfig>,
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctrl = container.hikvisionControlGateway
    var health by remember(device.key) { mutableStateOf<HikvisionHealthStatus?>(null) }
    var webhook by remember(device.key) { mutableStateOf<HikvisionWebhookConfig?>(null) }
    var busy by remember { mutableStateOf(true) }
    var confirmReboot by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var faceProgress by remember { mutableStateOf<BatchProgress?>(null) }
    val stats = container.hikvisionWebhookStats()
    val localIp = remember { LocalWebConsoleServer.detectLanIpv4() }
    val localPort = container.activeWebConsolePort.takeIf { it > 0 } ?: 8080

    fun reloadAll() {
        scope.launch(Dispatchers.IO) {
            busy = true
            health = ctrl.fetchHealth(device).getOrElse { err ->
                notice = (err.message ?: "Không đọc được thông số máy") to false; null
            }
            webhook = ctrl.fetchWebhookConfig(device).getOrNull()
            busy = false
        }
    }

    fun runAction(block: suspend () -> Result<String>) {
        scope.launch(Dispatchers.IO) {
            busy = true; notice = null; confirmReboot = false
            block().fold(
                onSuccess = { msg ->
                    notice = msg to true
                    container.attendanceEngine.appendLog(device.displayName, "-", msg, EventSeverity.SUCCESS)
                    reloadAll()
                },
                onFailure = { err ->
                    val m = err.message ?: "Thao tác thất bại"
                    notice = m to false
                    container.attendanceEngine.appendLog(device.displayName, "-", m, EventSeverity.ERROR)
                    busy = false
                },
            )
        }
    }

    LaunchedEffect(device.key) { reloadAll() }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TammiGlassPanel(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 680.dp),
            borderColor = AppColors.Primary.copy(alpha = 0.35f),
            tier = LiquidGlassTier.CHROME,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TammiSpacing.md),
            ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quản trị Hikvision • ${device.displayName}", fontSize = 17.sp, fontWeight = FontWeight.Black, color = AppColors.Text)
                    val meta = health?.let { "${it.model} • ${it.firmwareVersion.ifBlank { "ISAPI" }}" } ?: "Đang đọc thông số phần cứng..."
                    Text("${device.ip}:${device.port} • $meta", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextSecondary)
                }
                TammiOutlinedButton("Đọc lại", enabled = !busy, compact = true, contentColor = AppColors.Primary, onClick = { reloadAll() })
            }

            if (busy && faceProgress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = AppColors.PrimaryText)

            health?.let { h ->
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.SurfaceSubtle)
                        .border(1.dp, AppColors.Border.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(TammiSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(TammiSpacing.sm),
                ) {
                    InfoLine("Bộ nhớ", "${h.userCount} người • ${h.faceCount}/${h.maxFaceCapacity} mặt • ${h.cardCount} thẻ")
                    InfoLine("Mạng IP", if (h.isDhcp) "${h.ipAddress} (DHCP — nên khoá tĩnh)" else "${h.ipAddress} (IP tĩnh OK)", if (h.isDhcp) AppColors.AmberText else AppColors.GreenText)
                    InfoLine("Giờ máy", h.deviceTimeText.ifBlank { "—" })
                    InfoLine("Chống cạy vỏ", if (h.antiDismantleAlert) "CẢNH BÁO ĐANG BỊ MỞ VỎ!" else "An toàn • Cửa ${if (h.doorLocked) "đang khoá" else "đang mở"}", if (h.antiDismantleAlert) AppColors.RedText else AppColors.Text)
                }
            }

            HorizontalDivider(color = AppColors.Line.copy(alpha = 0.5f))
            val wh = webhook
            val pointsHere = wh != null && wh.enabled && wh.hostOrIp.equals(localIp, true) && wh.port == localPort
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Webhook tức thì (< 0.5s)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.Text)
                TammiStatusPill(
                    label = when {
                        pointsHere -> "Đang trỏ về máy này"
                        wh?.enabled == true -> "Trỏ máy chủ khác"
                        else -> "Chưa bật"
                    },
                    tone = if (pointsHere) TammiBadgeTone.SUCCESS else if (wh?.enabled == true) TammiBadgeTone.INFO else TammiBadgeTone.WARNING,
                )
            }
            Text("Đích trên máy: ${wh?.summaryUrl ?: "—"} • Nhận: ${stats.eventCount} lượt / ${stats.heartbeatCount} nhịp", fontSize = 12.sp, color = AppColors.TextSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvActionButton(
                    text = "Gắn Webhook về máy này", containerColor = AppColors.Green, enabled = !busy && localIp.isNotBlank(), compact = true, modifier = Modifier.weight(1.3f),
                    onClick = { runAction { ctrl.configureWebhook(device, HikvisionWebhookConfig(true, "HTTP", "ipaddress", localIp, localPort, "/hikvision/event")) } },
                )
                TammiOutlinedButton(
                    text = "Tắt Webhook", enabled = !busy, compact = true, contentColor = AppColors.Red, borderColor = AppColors.Red.copy(alpha = 0.35f), modifier = Modifier.weight(0.9f),
                    onClick = { runAction { ctrl.clearWebhook(device) } },
                )
            }

            HorizontalDivider(color = AppColors.Line.copy(alpha = 0.5f))
            Text("Thao tác nhanh", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.Text)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvActionButton("Mở cửa", AppColors.Green, { runAction { ctrl.controlDoor(device, HikvisionDoorCommand.OPEN) } }, Modifier.weight(1f), !busy, compact = true)
                TvActionButton("Bù hôm nay", AppColors.OrbCobalt, {
                    runAction {
                        val cfg = container.configRepository.getConfig()
                        val now = container.attendanceEngine.currentServerTimeMillis()
                        val start = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh")).apply {
                            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        val domain = container.configRepository.getBranding().normalizeApiDomain(cfg.apiDomain)
                        container.attendanceEngine.withDeviceIoLock { container.backfillHikvisionEventsUseCase(device, domain, cfg.accountId, start, now + 60_000L, cfg.dedupSeconds) }.map { it.message }
                    }
                }, Modifier.weight(1f), !busy, compact = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TammiOutlinedButton("Đồng bộ giờ VN", { runAction { ctrl.syncVietnamTime(device) } }, Modifier.weight(1f), !busy, compact = true, contentColor = AppColors.Primary)
                TammiOutlinedButton("Khoá IP tĩnh", { runAction { ctrl.lockStaticIp(device) } }, Modifier.weight(1f), !busy, compact = true, contentColor = AppColors.OrbCobalt)
            }
            if (confirmReboot) {
                TvActionButton("Xác nhận khởi động lại máy", AppColors.Red, { runAction { ctrl.rebootDevice(device) } }, Modifier.fillMaxWidth(), !busy, compact = true)
            } else {
                TammiOutlinedButton("Khởi động lại máy chấm công", { confirmReboot = true }, Modifier.fillMaxWidth(), !busy, compact = true, contentColor = AppColors.Red, borderColor = AppColors.Red.copy(alpha = 0.35f))
            }

            if (otherOnlineHikDevices.isNotEmpty()) {
                HorizontalDivider(color = AppColors.Line.copy(alpha = 0.5f))
                Text("Nhân bản khuôn mặt sang máy khác", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.Text)
                Text("Sao chép ảnh khuôn mặt AI + thẻ từ ${device.displayName} sang ${otherOnlineHikDevices.size} máy Hikvision đang online (chỉ chép người còn thiếu).", fontSize = 12.sp, color = AppColors.TextSecondary)
                faceProgress?.let { p ->
                    val frac = if (p.total > 0) p.completed.toFloat() / p.total else 0f
                    Text("${p.currentLabel} (${p.completed}/${p.total})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.PrimaryText)
                    LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = AppColors.PrimaryText)
                }
                TvActionButton(
                    text = "Đồng bộ mặt sang ${otherOnlineHikDevices.size} máy Hikvision khác",
                    containerColor = AppColors.OrbCobalt, enabled = !busy, compact = true, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            busy = true; notice = null
                            val res = container.attendanceEngine.withDeviceIoLock {
                                container.syncHikvisionFacesUseCase(device, otherOnlineHikDevices, skipExistingFace = true) { faceProgress = it }
                            }
                            faceProgress = null
                            res.fold(
                                onSuccess = { s ->
                                    notice = s.message to (s.failed == 0)
                                    container.attendanceEngine.appendLog(device.displayName, "-", "Đồng bộ mặt: ${s.message}", if (s.failed == 0) EventSeverity.SUCCESS else EventSeverity.WARNING)
                                },
                                onFailure = { e -> notice = (e.message ?: "Đồng bộ khuôn mặt lỗi") to false },
                            )
                            busy = false
                        }
                    },
                )
            }

            notice?.let { (msg, ok) ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (ok) AppColors.GreenSoft else AppColors.RedSoft).padding(TammiSpacing.md)) {
                    Text(msg, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (ok) AppColors.GreenText else AppColors.RedText)
                }
            }

            Spacer(Modifier.height(TammiSpacing.xs))
            TammiOutlinedButton("Đóng", enabled = !busy, compact = true, contentColor = AppColors.Text, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = AppColors.Text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.5.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 12.5.sp, color = valueColor, fontWeight = FontWeight.Bold)
    }
}

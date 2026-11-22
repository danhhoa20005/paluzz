package com.edusing.chamcongtvbox.presentation.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.model.LiveEventEntry
import com.edusing.chamcongtvbox.domain.repository.InternetStatus
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiRadii
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing
import com.edusing.chamcongtvbox.presentation.theme.rememberDeviceUiSpec
import com.edusing.chamcongtvbox.presentation.theme.tvFocusRing
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn hình Trực 24/7 (Kiosk) tối màu chống lưu ảnh OLED/TV — thuần màu sắc & chữ AAA:
 * - Đồng hồ lớn tầm nhìn 3–5m, tự dịch chuyển nhẹ mỗi phút chống burn-in.
 * - Hiển thị **5 lượt chấm công thành công gần nhất** (lọc bỏ log hệ thống) + cảnh báo Chờ đẩy bù khi mất mạng.
 */
@Composable
fun Kiosk24x7Overlay(
    orgName: String,
    brandSubtitle: String,
    internetStatus: InternetStatus = InternetStatus.ONLINE,
    onlineCount: Int,
    totalCount: Int,
    isRunning: Boolean,
    totalToday: Int,
    pendingCount: Int = 0,
    recentEvents: List<LiveEventEntry> = emptyList(),
    onExitKiosk: () -> Unit,
) {
    BackHandler(onBack = onExitKiosk)

    val ui = rememberDeviceUiSpec()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var shiftStep by remember { mutableIntStateOf(0) }
    var exitFocused by remember { mutableStateOf(false) }
    val exitScale by animateFloatAsState(targetValue = if (exitFocused) 1.04f else 1f, label = "kioskExitScale")
    val exitFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (ui.isTvDevice) runCatching { exitFocusRequester.requestFocus() }
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
            if ((nowMs / 1_000L) % 60L == 0L) shiftStep = (shiftStep + 1) % 4
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.US) }
    val timeText = timeFmt.format(Date(nowMs))
    val dateText = "Ngày ${dateFmt.format(Date(nowMs))} • $brandSubtitle"
    val dx = if (shiftStep % 2 == 0) 6.dp else (-6).dp
    val dy = if (shiftStep < 2) 6.dp else (-6).dp
    val recentCheckIns = remember(recentEvents) {
        recentEvents.filter { it.cardId.isNotBlank() && it.cardId != "-" && it.severity == EventSeverity.SUCCESS }.take(5)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.KioskBg)
            .padding(horizontal = ui.horizontalPadding, vertical = ui.verticalPadding),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val (netLabel, netTone) = when (internetStatus) {
                    InternetStatus.ONLINE -> "Internet OK" to TammiBadgeTone.SUCCESS
                    InternetStatus.OFFLINE -> "Mất mạng" to TammiBadgeTone.DANGER
                    InternetStatus.RECOVERING -> "Đang nối mạng" to TammiBadgeTone.WARNING
                    InternetStatus.CHECKING -> "Kiểm tra mạng" to TammiBadgeTone.INFO
                }
                TammiStatusPill(label = netLabel, tone = netTone)
                TammiStatusPill(
                    label = "$onlineCount/$totalCount máy" + if (isRunning) " • Chờ quét" else " • Tạm dừng",
                    tone = if (onlineCount > 0 && isRunning) TammiBadgeTone.SUCCESS else TammiBadgeTone.WARNING,
                )
                if (pendingCount > 0) {
                    TammiStatusPill(label = "Chờ bù: $pendingCount", tone = TammiBadgeTone.WARNING)
                }
            }
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .scale(exitScale)
                    .clip(TammiRadii.buttonShape)
                    .background(AppColors.KioskCard)
                    .border(1.2.dp, AppColors.KioskBorder, TammiRadii.buttonShape)
                    .focusRequester(exitFocusRequester)
                    .onFocusChanged { exitFocused = it.isFocused }
                    .tvFocusRing(exitFocused, cornerRadiusDp = 16, onDark = true)
                    .clickable(onClick = onExitKiosk)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Thoát Kiosk", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = if (ui.isTvDevice) 14.5.sp else 13.sp)
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.offset(x = dx, y = dy).widthIn(max = if (ui.isTvDevice) 780.dp else 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(orgName.uppercase(), color = AppColors.KioskTextSecondary, fontSize = if (ui.isTvDevice) 19.sp else 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(TammiSpacing.sm))
                Text(
                    text = timeText,
                    color = Color.White,
                    fontSize = if (ui.isTvDevice) 72.sp else 52.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 3.sp,
                )
                Text(dateText, color = AppColors.KioskTextMuted, fontSize = if (ui.isTvDevice) 15.sp else 13.5.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(TammiSpacing.lg))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(TammiRadii.cardShape)
                        .background(AppColors.KioskCard)
                        .border(1.2.dp, AppColors.KioskBorder, TammiRadii.cardShape)
                        .padding(if (ui.isTvDevice) TammiSpacing.xl else TammiSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Chấm công gần nhất", color = AppColors.KioskTextSecondary, fontSize = if (ui.isTvDevice) 15.sp else 13.sp, fontWeight = FontWeight.Bold)
                        Text("Hôm nay: $totalToday lượt", color = AppColors.KioskSuccess, fontSize = if (ui.isTvDevice) 16.sp else 13.5.sp, fontWeight = FontWeight.Black)
                    }
                    if (recentCheckIns.isEmpty()) {
                        Text(
                            text = "Sẵn sàng chờ nhân viên / học sinh quét mặt hoặc quẹt thẻ...",
                            color = AppColors.KioskTextMuted,
                            fontSize = if (ui.isTvDevice) 15.sp else 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    } else {
                        recentCheckIns.forEachIndexed { idx, ev ->
                            if (idx > 0) HorizontalDivider(color = AppColors.KioskBorder.copy(alpha = 0.55f))
                            val who = ev.userName.ifBlank { "Mã #${ev.cardId}" }
                            val isLatest = idx == 0
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(if (isLatest) 10.dp else 7.dp).clip(CircleShape).background(if (isLatest) AppColors.KioskSuccess else AppColors.KioskTextMuted))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = who,
                                    color = Color.White,
                                    fontSize = if (isLatest) (if (ui.isTvDevice) 20.sp else 16.sp) else (if (ui.isTvDevice) 15.5.sp else 13.5.sp),
                                    fontWeight = if (isLatest) FontWeight.Black else FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${ev.timeText.substringBefore(" ")} • ${ev.deviceName}",
                                    color = if (isLatest) AppColors.KioskSuccess else AppColors.KioskTextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = if (ui.isTvDevice) 14.sp else 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }

                if (ui.isTvDevice) {
                    Spacer(Modifier.height(TammiSpacing.sm))
                    Text("Nhấn phím Back trên điều khiển TV để thoát chế độ Kiosk", color = AppColors.KioskTextMuted, fontSize = 12.5.sp)
                }
            }
        }
    }
}

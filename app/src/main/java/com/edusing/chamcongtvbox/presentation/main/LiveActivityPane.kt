package com.edusing.chamcongtvbox.presentation.main

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edusing.chamcongtvbox.domain.model.EventSeverity
import com.edusing.chamcongtvbox.domain.model.LiveEventEntry
import com.edusing.chamcongtvbox.presentation.components.TammiBadgeTone
import com.edusing.chamcongtvbox.presentation.components.TammiGlassPanel
import com.edusing.chamcongtvbox.presentation.components.TammiOutlinedButton
import com.edusing.chamcongtvbox.presentation.components.TammiStatusPill
import com.edusing.chamcongtvbox.presentation.components.TvSegmentedTabs
import com.edusing.chamcongtvbox.presentation.theme.AppColors
import com.edusing.chamcongtvbox.presentation.theme.TammiSpacing

/**
 * Khung Nhật ký trực tiếp **Liquid Glass 3D** thanh thoát:
 * mặc định ưu tiên hiển thị lượt chấm công thực & lỗi quan trọng (bỏ nhiễu log hệ thống), có chuyển nhanh sang `Tất cả`.
 */
@Composable
internal fun LiveActivityPane(
    events: List<LiveEventEntry>,
    lastPollTime: String,
    totalToday: Int,
    scrollable: Boolean,
    isTv: Boolean = false,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var onlyCheckIns by rememberSaveable { mutableStateOf(true) }
    val checkInEvents = remember(events) {
        events.filter { it.cardId != "-" || it.severity == EventSeverity.ERROR }
    }
    val displayed = if (onlyCheckIns) checkInEvents else events

    TammiGlassPanel(
        modifier = modifier,
        padding = PaddingValues(if (isTv) TammiSpacing.lg else TammiSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Nhật ký trực tiếp",
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isTv) 17.sp else 15.sp,
                    color = AppColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val pollShort = lastPollTime.substringBefore(" ")
                val meta = if (pollShort.isNotBlank()) "Hôm nay: $totalToday lượt • Quét $pollShort" else "Hôm nay: $totalToday lượt"
                Text(meta, fontSize = if (isTv) 13.sp else 12.sp, fontWeight = FontWeight.Medium, color = AppColors.Primary)
            }
            if (events.isNotEmpty()) {
                TammiOutlinedButton(
                    text = "Xoá",
                    onClick = onClearLogs,
                    compact = true,
                    contentColor = AppColors.TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(TammiSpacing.sm))
        TvSegmentedTabs(
            tabs = listOf("Chấm công (${checkInEvents.size})", "Hệ thống (${events.size})"),
            selectedIndex = if (onlyCheckIns) 0 else 1,
            onSelect = { onlyCheckIns = (it == 0) },
        )
        Spacer(Modifier.height(TammiSpacing.sm))
        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.42f))

        if (displayed.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = TammiSpacing.xl), contentAlignment = Alignment.Center) {
                Text(
                    text = if (onlyCheckIns && events.isNotEmpty()) "Chưa có lượt chấm công nào.\nChạm tab Hệ thống để xem log kết nối."
                    else "Chưa có lượt chấm công nào.",
                    color = AppColors.TextSecondary,
                    fontSize = if (isTv) 14.5.sp else 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                )
            }
        } else if (scrollable) {
            val listState = rememberLazyListState()
            val topId = displayed.firstOrNull()?.id
            LaunchedEffect(topId) {
                if (topId != null && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(displayed, key = { _, it -> it.id }) { idx, ev ->
                    if (idx > 0) HorizontalDivider(color = AppColors.Border.copy(alpha = 0.34f))
                    LiveEventItemRow(ev, isTv)
                }
            }
        } else {
            Column {
                displayed.take(25).forEachIndexed { idx, ev ->
                    if (idx > 0) HorizontalDivider(color = AppColors.Border.copy(alpha = 0.34f))
                    LiveEventItemRow(ev, isTv)
                }
            }
        }
    }
}

@Composable
private fun LiveEventItemRow(ev: LiveEventEntry, isTv: Boolean) {
    val tone = when (ev.severity) {
        EventSeverity.SUCCESS -> TammiBadgeTone.SUCCESS
        EventSeverity.ERROR -> TammiBadgeTone.DANGER
        EventSeverity.WARNING -> TammiBadgeTone.WARNING
        EventSeverity.INFO -> TammiBadgeTone.INFO
    }
    val who = if (ev.cardId == "-") ev.deviceName
    else if (ev.userName.isNotBlank()) "${ev.userName} (#${ev.cardId})"
    else "Thẻ #${ev.cardId}"
    val timeOnly = ev.timeText.substringBefore(" ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (isTv) 12.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TammiSpacing.sm)) {
            TammiStatusPill(label = timeOnly, tone = tone)
            Text(who, fontSize = if (isTv) 15.5.sp else 14.sp, fontWeight = FontWeight.Bold, color = AppColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Text(
            text = ev.message,
            fontSize = if (isTv) 14.sp else 13.sp,
            fontWeight = FontWeight.Normal,
            color = if (ev.severity == EventSeverity.ERROR) AppColors.Red else AppColors.TextSecondary,
        )
        if (ev.cardId != "-") {
            Text("${ev.deviceName} • ${ev.timeText}", fontSize = if (isTv) 12.sp else 11.5.sp, fontWeight = FontWeight.Medium, color = AppColors.Muted)
        }
    }
}

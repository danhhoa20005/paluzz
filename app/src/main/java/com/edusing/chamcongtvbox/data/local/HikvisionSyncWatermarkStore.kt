package com.edusing.chamcongtvbox.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Lưu mốc thời gian đồng bộ gần nhất (`lastSyncedMs`) cho từng máy chấm công Hikvision:
 * giúp tự động đồng bộ bù chính xác các lượt quét diễn ra trong khoảng thời gian TV Box bị mất điện hoặc rớt mạng
 * mà tuyệt đối không gửi trùng dữ liệu cũ khi vừa cài mới app.
 */
internal class HikvisionSyncWatermarkStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordSynced(deviceKey: String, epochMs: Long) {
        if (deviceKey.isBlank() || epochMs <= 0L) return
        runCatching { prefs.edit().putLong(keyOf(deviceKey), epochMs).apply() }
    }

    fun getLastSynced(deviceKey: String): Long =
        runCatching { prefs.getLong(keyOf(deviceKey), 0L) }.getOrDefault(0L)

    fun computeRecoveryWindow(deviceKey: String, nowMs: Long): Pair<Long, Long>? =
        computeWindow(getLastSynced(deviceKey), nowMs)

    private fun keyOf(deviceKey: String): String = "wm_${deviceKey.trim()}"

    companion object {
        private const val PREFS_NAME = "chamcong_hik_watermark_v1"
        private const val MIN_OUTAGE_GAP_MS = 90_000L
        private const val MAX_BACKFILL_WINDOW_MS = 12L * 3_600_000L
        private const val OVERLAP_MS = 15_000L

        /** Chỉ trả khung giờ cần đồng bộ bù khi đã có mốc trước đó và gián đoạn thật sự từ 90 giây đến 12 giờ. */
        fun computeWindow(lastSyncedMs: Long, nowMs: Long): Pair<Long, Long>? {
            if (lastSyncedMs <= 0L || nowMs <= lastSyncedMs) return null
            val gap = nowMs - lastSyncedMs
            if (gap < MIN_OUTAGE_GAP_MS) return null
            val since = maxOf(lastSyncedMs - OVERLAP_MS, nowMs - MAX_BACKFILL_WINDOW_MS)
            return since to (nowMs + 60_000L)
        }
    }
}
